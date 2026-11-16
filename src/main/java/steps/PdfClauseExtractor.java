package steps;

import ai.Router;
import ai.StepAi;
import config.AppConfig;
import pdf.PdfDoc;
import util.ClauseNumber;
import util.Images;
import util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic replacement for {@link PdfClauseCropper} + {@link ClauseOcr}.
 *
 * <p>Old approach: ask a vision LLM to READ pixel/ruler positions of every clause on a page. Fragile, because
 * general vision LLMs approximate geometry - small ruler-reading errors + a narrow ink-snap tolerance meant
 * crops regularly cut through text or bled into the next clause, which then produced wrong OCR and wrong diffs.
 *
 * <p>New approach, mirroring {@link DocxClauseExtractor}'s proven pattern for DOCX ("the LLM only picks WHICH
 * units belong to a clause - it never invents geometry or text"):
 * <ol>
 *   <li>OCR each page ONCE with a block-aware OCR engine (Mistral OCR 4+, {@code include_blocks=true}) that
 *       returns real, already-accurate bounding boxes + text per block, in reading order.</li>
 *   <li>One text-only LLM call decides which block ids belong to which clause number - no vision, no pixel
 *       guessing, exactly like DocxClauseExtractor does for DOCX blocks.</li>
 *   <li>A clause's text is the concatenation of its blocks' OCR'd text (already known - no second OCR pass).</li>
 *   <li>A clause's crop image is the deterministic union of its blocks' real bounding boxes, per page, stacked
 *       vertically for clauses that span pages.</li>
 * </ol>
 *
 * <p>Output shape matches the old clauseImageCropPDFSignedDocument + ocrContentClauseImages pair combined, so
 * nothing downstream (DocAnalysis, AnalyzeMerger, ReportBuilder) needs to change:
 * {@code {"title":"...", "clauses": {"1.1": {"signed_content":"..","signed_image":"<base64>","bboxes":[...]}}}}
 */
public final class PdfClauseExtractor {
    private static final Logger log = LogManager.getLogger();

    private static final String SYSTEM = "You segment scanned contract text into numbered clauses. You only output JSON.";

    /** OCR block types that never belong to clause text. */
    private static final Set<String> SKIP_TYPES = Set.of("header", "footer");

    /**
     * Minimum character length of the text following a parenthesized marker —
     * "(a)", "(1)", "(i)" etc. — for it to be treated as a real sub-clause heading
     * rather than a definition-list item.
     *
     * <p>Definition-list items (e.g. "(a) Amerika Birleşik Devletleri;") are typically
     * very short (< 50 chars after the token). Real sub-clauses carry a full sentence.
     * Matching threshold in {@link extraction.ClauseTree} is identical.
     * Overridable via config key {@code pipeline.pdfMinParenTailChars} (default 100).
     */
    private static final int MIN_PAREN_TAIL_CHARS = 100;

    /**
     * Maximum number of parenthesized sub-clauses accepted under one parent clause.
     * Beyond this limit the items are treated as body text (overflow guard).
     * Matching threshold in {@link extraction.ClauseTree} is identical.
     * Overridable via config key {@code pipeline.pdfMaxParenPerParent} (default 6).
     */
    private static final int MAX_PAREN_PER_PARENT = 6;

    private final AppConfig cfg;
    private final Router router;

    public PdfClauseExtractor(AppConfig cfg, Router router) {
        this.cfg = cfg;
        this.router = router;
    }

    // ------------------------------------------------------------------ block model

    /** One OCR block: page + bounding box normalized to 0..1000 (same convention used elsewhere in this codebase,
     *  see PdfClauseCropper's ruler and AnalyzeMerger's bboxes). */
    private static final class Block {
        final int id;          // global id, sequential across the whole document (all pages)
        final int page;        // 0-based
        final String type;
        final String text;
        final double x0, y0, x1, y1;   // 0..1000, relative to this block's own page
        /**
         * Column index for bilingual side-by-side PDFs:
         *  0 = left column (primary language)
         *  1 = right column (secondary language)
         * -1 = single-column layout (no splitting applied)
         */
        final int columnIndex;

        Block(int id, int page, String type, String text, double x0, double y0, double x1, double y1) {
            this(id, page, type, text, x0, y0, x1, y1, -1);
        }

        Block(int id, int page, String type, String text, double x0, double y0, double x1, double y1, int columnIndex) {
            this.id = id;
            this.page = page;
            this.type = type;
            this.text = text;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.columnIndex = columnIndex;
        }
    }

    // ------------------------------------------------------------------ public entry point

    public JSONObject extract(File pdf, String lang) throws Exception {
        List<BufferedImage> pages = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        boolean readable = readBlocks(pdf, lang, pages, blocks, null);

        if (blocks.isEmpty()) {
            log.warn("Signed PDF: no usable blocks found for language {}", lang);
            return new JSONObject().put("title", "").put("clauses", new JSONObject());
        }
        int colIndex = resolveColumnIndex(blocks, lang);
        List<Block> langBlocks = filterByColumn(blocks, lang, colIndex);
        return readable ? segmentNative(langBlocks, pages, lang) : segmentAndCrop(langBlocks, pages, lang);
    }

    public JSONObject extractWithOcrPages(File pdf, String lang, List<JSONObject> ocrPagesOut) throws Exception {
        return extractWithOcrPages(pdf, lang, ocrPagesOut, 0, null);
    }

    /**
     * Full extraction with page-limit support.
     *
     * @param maxContentPages  process only the first N pages for clause extraction (0 = no limit).
     *                         Pages beyond this limit are rendered and stored in {@code extraPagesOut}
     *                         for the report (signature pages, annexes, etc.).
     * @param extraPagesOut    if non-null, rendered images of pages beyond {@code maxContentPages}
     *                         are appended here (one BufferedImage per extra page, in order).
     */
    public JSONObject extractWithOcrPages(File pdf, String lang, List<JSONObject> ocrPagesOut,
                                          int maxContentPages, List<BufferedImage> extraPagesOut) throws Exception {
        List<BufferedImage> pages = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        boolean readable = readBlocks(pdf, lang, pages, blocks, ocrPagesOut, maxContentPages, extraPagesOut);

        if (blocks.isEmpty()) {
            log.warn("Signed PDF: no usable blocks found for language {}", lang);
            return new JSONObject().put("title", "").put("clauses", new JSONObject());
        }
        int colIndex = resolveColumnIndex(blocks, lang);
        List<Block> langBlocks = filterByColumn(blocks, lang, colIndex);
        return readable ? segmentNative(langBlocks, pages, lang) : segmentAndCrop(langBlocks, pages, lang);
    }

    // ------------------------------------------------------------------ 1. block OCR (deterministic geometry)

    /**
     * Decides whether the PDF has enough embedded (native) text to skip OCR entirely.
     *
     * <p>A page is considered "readable" when its extracted text has at least
     * {@code pdfNativeTextMinCharsPerPage} non-whitespace characters on average.
     * Config key: {@code pipeline.pdfNativeTextMinCharsPerPage}, default 100.
     *
     * <p>We sample the first {@code samplePages} pages (default 5) to handle edge
     * cases where the last few pages are blank or image-only (e.g. signature scan
     * appended to an otherwise digital PDF).
     */
    private boolean isReadablePdf(List<String> pageTexts) {
        int threshold = cfg.pipelineInt("pdfNativeTextMinCharsPerPage", 100);
        int samplePages = Math.min(pageTexts.size(), cfg.pipelineInt("pdfNativeTextSamplePages", 5));
        if (samplePages == 0) return false;
        long total = 0;
        for (int p = 0; p < samplePages; p++) {
            String t = pageTexts.get(p);
            for (int i = 0; i < t.length(); i++) {
                if (!Character.isWhitespace(t.charAt(i))) total++;
            }
        }
        return (total / samplePages) >= threshold;
    }

    /**
     * @param ocrPagesOut if non-null, the raw {@code {width,height,blocks[]}} response for every page is
     *                    appended here (one entry per page, in order).  These raw responses still carry ALL
     *                    block types (including {@code signature}, {@code handwriting}, etc.) that
     *                    {@link PdfClauseExtractor} would otherwise discard — which is exactly what
     *                    {@link SignatureDetector} needs.
     *                    In readable (native-text) mode this list is left empty; {@link SignatureDetector}
     *                    handles that gracefully by falling back to tail-page heuristics only.
     * @return true when the PDF was readable (native text used), false when OCR was run
     */
    private boolean readBlocks(File pdf, String lang, List<BufferedImage> pagesOut, List<Block> blocksOut,
                                List<JSONObject> ocrPagesOut) throws Exception {
        return readBlocks(pdf, lang, pagesOut, blocksOut, ocrPagesOut, 0, null);
    }

    /**
     * @param maxContentPages  process only the first N pages for clause extraction (0 = no limit).
     *                         Pages beyond this limit are rendered and appended to {@code extraPagesOut}.
     * @param extraPagesOut    if non-null, rendered images of pages beyond {@code maxContentPages}
     *                         are appended here.
     * @return true when the PDF was readable (native text used), false when OCR was run
     */
    private boolean readBlocks(File pdf, String lang, List<BufferedImage> pagesOut, List<Block> blocksOut,
                                List<JSONObject> ocrPagesOut, int maxContentPages,
                                List<BufferedImage> extraPagesOut) throws Exception {
        try (PdfDoc doc = PdfDoc.open(pdf)) {
            // Determine how many pages to process for clause extraction
            int totalPages = doc.pageCount();
            // contentPages: pages to include in clause extraction
            // extraPages: remaining pages (signature, annex) → rendered for report only
            int contentPages = (maxContentPages > 0 && maxContentPages < totalPages)
                    ? maxContentPages : totalPages;
            int numExtra = totalPages - contentPages;

            if (maxContentPages > 0 && numExtra > 0) {
                log.info("Signed PDF [{}]: page limit {} — processing {} content page(s), {} extra page(s) skipped for OCR",
                        lang, maxContentPages, contentPages, numExtra);
            }

            List<String> pageTexts = doc.pageTexts(contentPages);

            if (isReadablePdf(pageTexts)) {
                // ── NATIVE TEXT MODE ──────────────────────────────────────────
                // The PDF has embedded text. Use PDFBox's TextPosition API to get
                // real per-line bounding boxes — no OCR, no AI needed at all.
                log.info("Signed PDF [{}]: readable mode — using native text + bbox extraction, OCR skipped ({} pages)",
                        lang, pageTexts.size());

                float dpi = cfg.pipelineInt("renderDpi", 150);
                log.info("Signed PDF [{}]: native render DPI={}", lang, (int) dpi);
                double headerEdge = cfg.pipelineDouble("pdfNativeHeaderMarginPct", 5.0) * 10.0;
                double footerEdge = 1000.0 - cfg.pipelineDouble("pdfNativeFooterMarginPct", 6.0) * 10.0;

                // Bilingual column split threshold (0..1000).
                // When > 0 the page is treated as two-column: lines whose x0 < threshold
                // are assigned columnIndex=0 (left / primary), lines whose x0 >= threshold
                // are assigned columnIndex=1 (right / secondary).
                // Config key: pipeline.pdfColumnSplitX  (default 0 = auto-detect)
                // Auto-detect: look at the first content-rich page, compute the median gap
                // in x0 values across all lines, pick the mid-point as the split boundary.
                // If no clear gap is found (single-column PDF), columnIndex stays -1 for all.
                double columnSplitX = cfg.pipelineDouble("pdfColumnSplitX", 0.0);

                // Collect all lines first so we can auto-detect the column boundary
                List<List<PdfDoc.LineBbox>> allLines = new ArrayList<>();
                for (int p = 0; p < contentPages; p++) {
                    pagesOut.add(doc.render(p, dpi));
                    allLines.add(doc.pageLineBboxes(p));
                }

                if (columnSplitX <= 0) {
                    columnSplitX = detectColumnSplit(allLines, headerEdge, footerEdge);
                }
                boolean bilingualPdf = columnSplitX > 0;
                log.info("Signed PDF [{}]: columnSplitX={} ({})",
                        lang, columnSplitX, bilingualPdf ? "bilingual" : "single-column");

                int id = 0;
                for (int p = 0; p < doc.pageCount(); p++) {
                    List<PdfDoc.LineBbox> lines = allLines.get(p);
                    int skipped = 0;
                    for (PdfDoc.LineBbox lb : lines) {
                        if (lb.text.isEmpty()) continue;
                        if (lb.y1 <= headerEdge || lb.y0 >= footerEdge) {
                            skipped++;
                            continue;
                        }
                        int colIdx = -1;
                        if (bilingualPdf) {
                            // Column assignment uses the line's horizontal centre (x0+x1)/2
                            // — same measure used in detectColumnSplit — to correctly classify
                            // lines whose x0 overlaps the opposite column's starting zone.
                            // Full-width lines (spanning both columns) go to col=0 so both
                            // language extractions receive shared content (title, preamble).
                            boolean fullWidth = (lb.x1 - lb.x0) > 800;
                            double centre = (lb.x0 + lb.x1) / 2.0;
                            colIdx = fullWidth ? 0 : (centre < columnSplitX ? 0 : 1);
                        }
                        blocksOut.add(new Block(id++, p, "text", lb.text,
                                lb.x0, lb.y0, lb.x1, lb.y1, colIdx));
                    }
                    log.debug("Signed PDF [{}] page {}/{}: {} native lines ({} header/footer skipped)",
                            lang, p + 1, contentPages, lines.size() - skipped, skipped);
                }
                // ocrPagesOut intentionally left empty — SignatureDetector falls back to tail heuristic
                // Render extra pages (signature/annex) for the report
                renderExtraPages(doc, contentPages, totalPages, dpi, extraPagesOut);
                return true;  // readable
            }

            // ── OCR MODE ─────────────────────────────────────────────────────
            // The PDF is a scan (image-only pages): render + OCR each page.
            log.info("Signed PDF [{}]: OCR mode — no native text detected, rendering {} content page(s) of {}",
                    lang, contentPages, totalPages);
            StepAi ocrAi = router.get("pdfPageBlockOcr");

            // ── Azure CV: whole-document PDF path ──────────────────────────
            if (ocrAi.supportsPdfOcr()) {
                return readBlocksViaPdfOcr(pdf, doc, ocrAi, lang, pagesOut, blocksOut, ocrPagesOut,
                        contentPages, extraPagesOut);
            }

            // ── Per-page OCR path (Mistral OCR, etc.) ─────────────────────
            // ocrRenderDpi: OCR için ayrı DPI (default 300 — native crop'tan bağımsız)
            int ocrRenderDpiCfg = cfg.pipelineInt("ocrRenderDpi", -1);
            int renderDpiCfg    = cfg.pipelineInt("renderDpi", 150);
            float dpi = ocrRenderDpiCfg > 0 ? ocrRenderDpiCfg : renderDpiCfg;
            log.info("Signed PDF [{}]: OCR render DPI={} (ocrRenderDpi={}, renderDpi={})",
                    lang, (int) dpi,
                    ocrRenderDpiCfg > 0 ? ocrRenderDpiCfg : "not-set",
                    renderDpiCfg);
            int id = 0;
            int pagesFailed = 0;
            for (int p = 0; p < contentPages; p++) {
                BufferedImage page = doc.render(p, dpi);
                pagesOut.add(page);

                JSONObject res;
                try {
                    res = ocrAi.ocrBlocks(Images.toBase64Png(page));
                } catch (IOException e) {
                    // Per-page hata toleransı: tek sayfa hatası tüm extraction'ı çökertmesin
                    log.error("Signed PDF [{}] page {}/{}: OCR failed ({}), skipping page",
                            lang, p + 1, doc.pageCount(), e.getMessage());
                    pagesFailed++;
                    continue;
                }
                // raw response captured BEFORE filtering — SignatureDetector needs all block types
                if (ocrPagesOut != null) {
                    ocrPagesOut.add(res);
                }
                double pw = res.optDouble("width", page.getWidth());
                double ph = res.optDouble("height", page.getHeight());
                JSONArray arr = res.optJSONArray("blocks");
                int kept = 0;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject b = arr.optJSONObject(i);
                        if (b == null) {
                            continue;
                        }
                        String type = b.optString("type", "text").toLowerCase();
                        if (SKIP_TYPES.contains(type)) {
                            continue;
                        }
                        String text = b.optString("content", "").trim();
                        if (text.isEmpty() && !"table".equals(type)) {
                            continue;
                        }
                        double x0 = pw > 0 ? b.optDouble("x0", 0) / pw * 1000.0 : 0;
                        double y0 = ph > 0 ? b.optDouble("y0", 0) / ph * 1000.0 : 0;
                        double x1 = pw > 0 ? b.optDouble("x1", pw) / pw * 1000.0 : 1000;
                        double y1 = ph > 0 ? b.optDouble("y1", ph) / ph * 1000.0 : 1000;
                        blocksOut.add(new Block(id++, p, type, text, x0, y0, x1, y1));
                        kept++;
                    }
                }
                log.info("Signed PDF [{}] page {}/{}: {} OCR blocks kept", lang, p + 1, contentPages, kept);
            }
            if (pagesFailed > 0) {
                log.warn("Signed PDF [{}]: {} of {} content page(s) failed OCR — extraction may be incomplete",
                        lang, pagesFailed, contentPages);
            }
            // Render extra pages (signature/annex) for the report
            renderExtraPages(doc, contentPages, totalPages, cfg.pipelineInt("renderDpi", 150), extraPagesOut);
            return false;  // OCR
        }
    }

    /**
     * Whole-document PDF OCR path using a provider that supports {@link ai.AiClient#ocrPdfBlocks}
     * (e.g. Azure Computer Vision Read API).
     *
     * <p>The PDF bytes are sent to the provider in one request; the response already contains
     * per-page block data with real bounding boxes and text.  No local rendering is needed for
     * OCR — pages are still rendered locally at {@code renderDpi} but only for the crop images
     * that appear in the final report (same use as the per-page path).</p>
     */
    private boolean readBlocksViaPdfOcr(File pdf, PdfDoc doc, StepAi ocrAi,
                                         String lang,
                                         List<BufferedImage> pagesOut,
                                         List<Block> blocksOut,
                                         List<JSONObject> ocrPagesOut,
                                         int maxContentPages,
                                         List<BufferedImage> extraPagesOut) throws Exception {
        int totalPages   = doc.pageCount();
        // contentPages: pages whose blocks go into clause extraction
        int contentPages = (maxContentPages > 0 && maxContentPages < totalPages)
                ? maxContentPages : totalPages;
        int numExtra = totalPages - contentPages;

        log.info("Signed PDF [{}]: Azure CV PDF-OCR mode — submitting full PDF ({} pages); clause extraction limited to first {} page(s), {} extra page(s)",
                lang, totalPages, contentPages, numExtra);

        // Render pages for crop images (report only — not sent to OCR)
        float dpi = cfg.pipelineInt("renderDpi", 150);
        for (int p = 0; p < totalPages; p++) {
            pagesOut.add(doc.render(p, dpi));
        }

        // Read raw PDF bytes and send to Azure
        byte[] pdfBytes = Files.readAllBytes(pdf.toPath());
        JSONArray pageResults;
        try {
            // Pass the document language to Azure CV so it can optimise for language-specific
            // character sets (Turkish İ/ı/Ş/Ğ/Ç/Ö/Ü, German ü/ö/ä …).
            // Azure accepts BCP-47 codes ("tr", "en", "de", "fr", "ar" …); null = auto-detect.
            String ocrLang = toLowerBcp47(lang);
            pageResults = ocrAi.ocrPdfBlocks(pdfBytes, ocrLang);
        } catch (IOException e) {
            throw new IOException(
                    "Signed PDF [" + lang + "]: Azure CV PDF-OCR failed — " + e.getMessage(), e);
        }

        // Build ocrPagesOut cache (same contract used by SignatureDetector + guided pass)
        // and populate blocksOut — but only for content pages (pageIndex < contentPages).
        // Extra pages (signature/annex) are excluded from clause extraction just like in
        // the per-page Mistral path; their rendered images were already added to pagesOut above.
        int id = 0;
        for (int pi = 0; pi < pageResults.length(); pi++) {
            JSONObject pageData = pageResults.optJSONObject(pi);
            if (pageData == null) continue;

            double pw = pageData.optDouble("width",  1000);
            double ph = pageData.optDouble("height", 1000);
            if (pw <= 0) pw = 1000;
            if (ph <= 0) ph = 1000;

            int pageIndex = pageData.optInt("page", pi);  // 0-based

            // Build the per-page cache entry in the same shape as the per-page Mistral path
            // so SignatureDetector and the guided pass can consume it without changes.
            // ocrPagesOut receives ALL pages (signature detector needs the full set).
            JSONObject cacheEntry = new JSONObject()
                    .put("width",  pw)
                    .put("height", ph)
                    .put("blocks", pageData.optJSONArray("blocks"));
            if (ocrPagesOut != null) {
                // Ensure the list is long enough to hold this page index
                while (ocrPagesOut.size() <= pageIndex) {
                    ocrPagesOut.add(new JSONObject().put("width", pw).put("height", ph)
                            .put("blocks", new JSONArray()));
                }
                ocrPagesOut.set(pageIndex, cacheEntry);
            }

            // Skip extra pages for clause-extraction block list
            if (pageIndex >= contentPages) {
                log.debug("Signed PDF [{}] page {}/{}: extra page (beyond content limit {}), blocks excluded from extraction",
                        lang, pageIndex + 1, totalPages, contentPages);
                continue;
            }

            JSONArray arr = pageData.optJSONArray("blocks");
            int kept = 0;
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    String type = b.optString("type", "text").toLowerCase();
                    if (SKIP_TYPES.contains(type)) continue;
                    String text = b.optString("content", "").trim();
                    if (text.isEmpty() && !"table".equals(type)) continue;
                    double x0 = b.optDouble("x0", 0) / pw * 1000.0;
                    double y0 = b.optDouble("y0", 0) / ph * 1000.0;
                    double x1 = b.optDouble("x1", pw) / pw * 1000.0;
                    double y1 = b.optDouble("y1", ph) / ph * 1000.0;
                    blocksOut.add(new Block(id++, pageIndex, type, text, x0, y0, x1, y1));
                    kept++;
                }
            }
            log.info("Signed PDF [{}] page {}/{}: {} Azure CV blocks kept",
                    lang, pageIndex + 1, totalPages, kept);
        }
        // Extra pages for report (consistent with Mistral path's renderExtraPages call)
        if (extraPagesOut != null && numExtra > 0) {
            for (int p = contentPages; p < totalPages; p++) {
                extraPagesOut.add(pagesOut.get(p));
            }
            log.info("Signed PDF [{}]: {} extra page image(s) forwarded for report", lang, numExtra);
        }

        log.info("Signed PDF [{}]: Azure CV PDF-OCR complete — {} clause-extraction blocks from {} content page(s) (skipped {} extra page(s))",
                lang, blocksOut.size(), contentPages, numExtra);

        // Warn if Azure returned fewer pages than the PDF — silent data loss otherwise
        if (pageResults.length() < doc.pageCount()) {
            log.warn("Signed PDF [{}]: Azure returned {} page(s) but PDF has {} — {} page(s) missing, " +
                    "extraction may be incomplete",
                    lang, pageResults.length(), doc.pageCount(),
                    doc.pageCount() - pageResults.length());
        }
        return false; // OCR mode
    }

    /**
     * Auto-detects the x-coordinate (0..1000) that divides a two-column page layout.
     *
     * <p>Uses bimodal peak detection on the x0 (line-start) histogram:
     * <ol>
     *   <li>Build a 50-bucket histogram of line-start positions.</li>
     *   <li>Find the global peak (tallest column start cluster).</li>
     *   <li>Find a second peak that is at least 150 units away from the first.</li>
     *   <li>The split is the valley (minimum) between the two peaks.</li>
     *   <li>Require valley ≤ 30% of the shorter peak → confirms a real gap.</li>
     * </ol>
     */
    private static double detectColumnSplit(List<List<PdfDoc.LineBbox>> allLines,
                                            double headerEdge, double footerEdge) {
        final int BUCKETS = 50;
        final double BUCKET_SIZE = 1000.0 / BUCKETS;
        int[] hist = new int[BUCKETS];

        int total = 0;
        for (List<PdfDoc.LineBbox> pageLines : allLines) {
            for (PdfDoc.LineBbox lb : pageLines) {
                if (lb.text.isBlank()) continue;
                if (lb.y1 <= headerEdge || lb.y0 >= footerEdge) continue;
                if ((lb.x1 - lb.x0) > 800) continue; // skip full-width lines
                // Use line centre (x0+x1)/2 instead of x0 alone:
                // x0 can overlap between columns (TR lines start at 120, EN lines start at 487
                // but TR lines extend to 470 — near EN's start), whereas the centre clearly
                // separates the two columns (TR centre ~280, EN centre ~650).
                double centre = (lb.x0 + lb.x1) / 2.0;
                int bucket = Math.min(BUCKETS - 1, (int) (centre / BUCKET_SIZE));
                hist[bucket]++;
                total++;
            }
        }
        if (total < 10) return 0;

        // --- Smooth histogram with a 3-bucket window to reduce noise ---
        int[] smooth = new int[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            smooth[i] = hist[i];
            if (i > 0) smooth[i] += hist[i - 1];
            if (i < BUCKETS - 1) smooth[i] += hist[i + 1];
        }

        // --- Find global peak ---
        int peak1 = maxBucket(smooth, 0, BUCKETS - 1);
        if (peak1 < 0 || smooth[peak1] < 5) return 0;

        // --- Find second peak at least 7 buckets (~140 units) away ---
        int minDist = 7;
        int peak2 = -1;
        int peak2Val = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (Math.abs(i - peak1) < minDist) continue;
            if (smooth[i] > peak2Val) { peak2Val = smooth[i]; peak2 = i; }
        }
        if (peak2 < 0 || smooth[peak2] < 3) return 0;

        // Ensure peak1 < peak2 (left < right)
        if (peak1 > peak2) { int tmp = peak1; peak1 = peak2; peak2 = tmp; }

        // Both peaks must be reasonably prominent (≥ 20% of the dominant peak)
        int dominant = Math.max(smooth[peak1], smooth[peak2]);
        if (smooth[peak1] < dominant * 0.20 || smooth[peak2] < dominant * 0.20) return 0;

        // --- Valley between peaks ---
        int valleyBucket = minBucket(smooth, peak1 + 1, peak2 - 1);
        if (valleyBucket < 0) return 0;

        // Valley must be ≤ 30% of the shorter peak
        int shorterPeak = Math.min(smooth[peak1], smooth[peak2]);
        if (smooth[valleyBucket] > shorterPeak * 0.30) return 0;

        double split = (valleyBucket + 0.5) * BUCKET_SIZE;
        log.info("PDF column split auto-detected: peak1=bucket{} peak2=bucket{} valley=bucket{} split={}",
                peak1, peak2, valleyBucket, split);
        return split;
    }

    private static int maxBucket(int[] hist, int from, int to) {
        int max = -1, idx = -1;
        for (int i = from; i <= to; i++) {
            if (hist[i] > max) { max = hist[i]; idx = i; }
        }
        return idx;
    }

    private static int minBucket(int[] hist, int from, int to) {
        if (from > to) return -1;
        int min = Integer.MAX_VALUE, idx = -1;
        for (int i = from; i <= to; i++) {
            if (hist[i] < min) { min = hist[i]; idx = i; }
        }
        return idx;
    }

    private static double avgBuckets(int[] hist, int from, int to) {
        if (from > to) return 0;
        long sum = 0;
        for (int i = from; i <= to; i++) sum += hist[i];
        return (double) sum / (to - from + 1);
    }

    /**
     * Returns the blocks that belong to the given language in a bilingual block list.
     *
     * <p>When columnIndex is set (>= 0), only blocks of the target column are kept.
     * Full-width blocks (columnIndex == 0 for both languages, see readBlocks) are
     * always included so titles and preambles appear in both language extractions.
     *
     * <p>When no bilingual split was detected (all columnIndex == -1), the full
     * list is returned unchanged — this preserves backward compatibility with
     * single-column PDFs.
     *
     * @param blocks   the full block list (all columns)
     * @param lang     target language ISO-639-1 code (used only for logging)
     * @param colIndex the column that belongs to this language (0 or 1), or -1 if unknown
     */
    private static List<Block> filterByColumn(List<Block> blocks, String lang, int colIndex) {
        boolean hasBilingual = blocks.stream().anyMatch(b -> b.columnIndex >= 0);
        if (!hasBilingual || colIndex < 0) {
            return blocks; // single-column or column not determined
        }
        List<Block> filtered = new ArrayList<>();
        for (Block b : blocks) {
            // Keep: target column, or full-width (columnIndex==0 for both by convention in readBlocks)
            if (b.columnIndex < 0 || b.columnIndex == colIndex) {
                filtered.add(b);
            }
        }
        log.debug("Signed PDF [{}]: column filter col={} → {}/{} blocks kept",
                lang, colIndex, filtered.size(), blocks.size());
        return filtered;
    }

    /**
     * Determines which column (0 or 1) belongs to the target language.
     *
     * <p>Each column's concatenated text is classified with Lingua. The column
     * whose dominant language matches {@code lang} is chosen. If neither column
     * matches (e.g. language not in Lingua's supported set, or single-column PDF),
     * -1 is returned and no filtering is applied.
     */
    private int resolveColumnIndex(List<Block> blocks, String lang) {
        boolean hasBilingual = blocks.stream().anyMatch(b -> b.columnIndex >= 0);
        if (!hasBilingual) return -1;

        // Build per-column text
        Map<Integer, StringBuilder> colTexts = new LinkedHashMap<>();
        for (Block b : blocks) {
            if (b.columnIndex < 0 || b.text == null || b.text.isBlank()) continue;
            colTexts.computeIfAbsent(b.columnIndex, k -> new StringBuilder())
                    .append(b.text).append('\n');
        }
        if (colTexts.isEmpty()) return -1;

        try {
            com.github.pemistahl.lingua.api.LanguageDetector d =
                    com.github.pemistahl.lingua.api.LanguageDetectorBuilder.fromLanguages(
                            com.github.pemistahl.lingua.api.Language.TURKISH,
                            com.github.pemistahl.lingua.api.Language.ENGLISH,
                            com.github.pemistahl.lingua.api.Language.GERMAN,
                            com.github.pemistahl.lingua.api.Language.FRENCH,
                            com.github.pemistahl.lingua.api.Language.ARABIC,
                            com.github.pemistahl.lingua.api.Language.RUSSIAN,
                            com.github.pemistahl.lingua.api.Language.SPANISH,
                            com.github.pemistahl.lingua.api.Language.ITALIAN
                    ).build();

            for (Map.Entry<Integer, StringBuilder> entry : colTexts.entrySet()) {
                com.github.pemistahl.lingua.api.Language detected =
                        d.detectLanguageOf(entry.getValue().toString());
                String code = langCode(detected);
                log.info("Signed PDF column {} → detected language: {}", entry.getKey(), code);
                if (lang.equalsIgnoreCase(code)) {
                    return entry.getKey();
                }
            }
        } catch (Exception ex) {
            log.debug("resolveColumnIndex: Lingua failed ({}), no column filter applied", ex.getMessage());
        }
        // Fallback: if lang is the first language alphabetically or "TR", assume column 0
        return -1;
    }

    private static String langCode(com.github.pemistahl.lingua.api.Language lang) {
        if (lang == null) return null;
        switch (lang) {
            case TURKISH:  return "TR";
            case ENGLISH:  return "EN";
            case GERMAN:   return "DE";
            case FRENCH:   return "FR";
            case ARABIC:   return "AR";
            case RUSSIAN:  return "RU";
            case SPANISH:  return "ES";
            case ITALIAN:  return "IT";
            default:       return null;
        }
    }

    /**
     * Clause heading patterns (Turkish + English).
     *
     * A line is treated as a clause/sub-clause heading when it matches ONE of:
     *
     *   (A) Pure dotted number on the line, optionally followed by a title:
     *       "1."  "1.1"  "1.1."  "1.1.2"  "10.3.1"
     *
     *   (B) Keyword prefix:
     *       "Madde 5" / "MADDE 5" / "Article 5" / "§ 5" / "Section 2" / "Clause 3"
     *
     *   (C) Bilingual mixed line — two clause numbers on the same line separated by
     *       spaces/pipe, e.g. "1. SÖZLEŞMENİN TARAFLARI|  1. PARTIES TO THE AGREEMENT"
     *       In this case only the FIRST number is extracted (left column wins); the
     *       column filter in readBlocks() ensures only the target language's blocks
     *       are visible, so this case should be rare after filtering.
     *
     *   (A3) Bare integer followed by an uppercase letter or end-of-line.
     *       Catches OCR output where the trailing dot is dropped: "10 DEVREDİLEMEZLİK",
     *       "12 BAĞIMSIZLIK". Guard: next char must be uppercase (\\p{Lu}) or digit so
     *       lowercase body-text references ("see clause 3 for details") are not matched.
     *
     *   (D) Parenthesized sub-clause markers common in English/international contracts:
     *       "(1)", "(2)", "(a)", "(b)", "(i)", "(ii)", "(iii)"
     *       These are handled specially in {@link #segmentNative}: the matched token is
     *       mapped to a synthesized dotted sub-clause number under the current parent
     *       (e.g. inside clause "4", "(a)" → "4.1", "(b)" → "4.2").
     *
     * Config key {@code pipeline.pdfClauseHeadingPattern} can override the regex.
     *
     * Group 1 → multi-level dotted number  (alternative A1)
     * Group 2 → single-level dotted number (alternative A2)
     * Group 3 → keyword-prefixed number    (alternative B)
     * Group 4 → bare integer + uppercase   (alternative A3)
     * Group 5 → parenthesized decimal      (alternative D1)  e.g. "(1)"
     * Group 6 → parenthesized letter       (alternative D2)  e.g. "(a)"
     * Group 7 → parenthesized roman        (alternative D3)  e.g. "(i)"
     */
    private static final java.util.regex.Pattern DEFAULT_CLAUSE_HEADING =
            java.util.regex.Pattern.compile(
                    // (A1) Multi-level: "1.1", "1.1.2", "10.3.1" — dot presence is the guard
                    // Group 1
                    "^(\\d{1,4}(?:\\.\\d{1,4})+\\.?)(?:\\s|$)"
                    // (A2) Single-level trailing dot: "1." or "2."
                    // Group 2
                    + "|^(\\d{1,4}\\.)(?:\\s|$)"
                    // (B) Keyword prefix — Turkish: madde/MADDE; English: article/section/clause; §
                    // Group 3
                    + "|^(?:MADDE|madde|article|section|clause|§)\\s*(\\d[\\d.]*)"
                    // (A3) Bare integer followed by an uppercase letter or end-of-line.
                    // Catches "10 DEVREDİLEMEZLİK", "12 BAĞIMSIZLIK" etc. where OCR drops the dot.
                    // Guard: next char must be uppercase (\\p{Lu}) or digit or EOL — prevents matching
                    // body references like "see clause 3 for details" (lowercase follows).
                    // Group 4
                    + "|^(\\d{1,4})(?=\\s+[\\p{Lu}\\p{N}]|$)"
                    // (D1) Parenthesized decimal sub-clause: "(1)", "(2)", ... "(99)"
                    // Group 5
                    + "|^\\((\\d{1,2})\\)(?:\\s|$)"
                    // (D2) Parenthesized single letter: "(a)", "(b)", ... "(z)"
                    // Group 6
                    + "|^\\(([a-z])\\)(?:\\s|$)"
                    // (D3) Parenthesized roman numeral: "(i)", "(ii)", "(iii)", ... "(viii)"
                    // Group 7
                    + "|^\\((i{1,3}|iv|vi{0,3}|ix|x{1,3}|xi{1,3}|xiv|xv|xvi{0,3})\\)(?:\\s|$)",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Deterministic clause segmentation for readable (native-text) PDFs.
     * No AI call is made. Clause boundaries are detected by a regex on the
     * first token of each line block.
     *
     * Algorithm:
     * 1. Walk blocks in order. A block whose text starts with a clause-number
     *    pattern opens a new clause (or sub-clause).
     * 2. All subsequent blocks, until the next clause heading, belong to that clause.
     * 3. Content, bboxes and crop images are assembled with the same deterministic
     *    helpers used in the OCR path — no duplication.
     *
     * Page-break continuity: {@code currentClause} is never reset between pages.
     * A block at the top of page N+1 that does not match the heading pattern is
     * assigned to whatever clause was active at the bottom of page N — correct for
     * clauses that span pages.
     *
     * Parenthesized sub-clauses (group D): "(1)", "(a)", "(i)" etc. are synthesized as
     * dotted sub-clause numbers under the current parent clause. E.g. inside clause "4",
     * the sequence "(a)" → "4.1", "(b)" → "4.2". The parent is reset whenever a
     * non-parenthesized heading is encountered. A new parenthesized sequence resets the
     * per-parent counter so "(1)" always maps to parent.1 regardless of prior sequences.
     */
    private JSONObject segmentNative(List<Block> blocks, List<BufferedImage> pages, String lang) throws IOException {
        java.util.regex.Pattern headingPat = buildClauseHeadingPattern();
        // Config overrides for parenthesized sub-clause guards
        boolean parenEnabled   = cfg.pipelineBoolean("pdfParenSubclauses", false);
        int minParenTail       = cfg.pipelineInt("pdfMinParenTailChars", MIN_PAREN_TAIL_CHARS);
        int maxParenPerParent  = cfg.pipelineInt("pdfMaxParenPerParent", MAX_PAREN_PER_PARENT);
        if (!parenEnabled) {
            log.info("Signed PDF [{}]: parenthesized sub-clause synthesis disabled (pdfParenSubclauses=false)", lang);
        }

        Map<String, TreeSet<Integer>> found = new LinkedHashMap<>();
        // headingTexts: for heading blocks that also carry a title after the number,
        // store the stripped text (without the clause number prefix) so the assembly
        // step can use it instead of the full raw line.
        // Key = block id, Value = text after stripping the clause-number prefix.
        Map<Integer, String> headingTexts = new LinkedHashMap<>();
        String title = "";
        String currentClause = null;
        // currentParent: last clause opened via a dotted/keyword heading (groups 1-3).
        // Used to synthesize sub-clause numbers for parenthesized markers (groups 4-6).
        String currentParent = null;
        // parenthesizedCounter: tracks the ordinal for the current parenthesized sequence.
        // Reset each time a new non-parenthesized heading is opened so that "(1)" always
        // maps to parent.1 at the start of each clause's sub-list.
        int parenthesizedCounter = 0;

        for (Block b : blocks) {
            String trimmed = extraction.ClauseNumbering.stripArtifactPrefix(b.text.trim());
            java.util.regex.Matcher m = headingPat.matcher(trimmed);
            if (m.find()) {
                // Group 1: multi-level "1.1[.]"
                // Group 2: single-level "1."
                // Group 3: keyword-prefixed number "Madde N" / "Section N" / "Clause N" / "Article N"
                // Group 4: bare integer + uppercase "10 DEVREDİLEMEZLİK"
                // Group 5: parenthesized decimal "(1)"
                // Group 6: parenthesized letter  "(a)"
                // Group 7: parenthesized roman   "(i)"
                String rawNo = m.group(1) != null ? m.group(1)
                             : m.group(2) != null ? m.group(2)
                             : m.group(3) != null ? m.group(3)
                             : m.group(4);

                if (rawNo != null) {
                    // ---- Non-parenthesized heading (groups 1-3) ----
                    String no = ClauseNumber.normalize(rawNo);
                    if (no != null) {
                        // İsLegalReference kontrolü — yasal atıfları madde sanma
                        if (extraction.ClauseNumbering.isLegalReference(trimmed)) {
                            if (currentClause != null) found.get(currentClause).add(b.id);
                            continue;
                        }
                        extraction.ClauseNumbering.Relation rel =
                                extraction.ClauseNumbering.classify(currentClause, no);
                        if (rel == extraction.ClauseNumbering.Relation.VALID
                                || rel == extraction.ClauseNumbering.Relation.DUPLICATE) {
                            currentClause = no;
                            currentParent = no;
                            parenthesizedCounter = 0;
                            found.computeIfAbsent(no, k -> new TreeSet<>());
                            String afterNo = trimmed.substring(m.end()).trim();
                            if (!afterNo.isEmpty()) {
                                headingTexts.put(b.id, afterNo);
                                found.get(currentClause).add(b.id);
                            }
                            continue;
                        } else if (rel == extraction.ClauseNumbering.Relation.RESET
                                || rel == extraction.ClauseNumbering.Relation.BACKWARD) {
                            // EK / Schedule bölümü: yeni bölüm başlıyor (1'e geri dönüş).
                            // Bu bölümdeki maddeler S#-prefixed key olarak eklenir;
                            // AnalyzeMerger bunları son normal maddeye absorbe eder.
                            // Bu şekilde ek bölümlerindeki değişiklikler raporlanır.
                            currentClause = no;
                            currentParent = no;
                            parenthesizedCounter = 0;
                            found.computeIfAbsent(no, k -> new TreeSet<>());
                            String afterNo = trimmed.substring(m.end()).trim();
                            if (!afterNo.isEmpty()) {
                                headingTexts.put(b.id, afterNo);
                                found.get(currentClause).add(b.id);
                            }
                            log.debug("Signed PDF [{}] segmentNative: {} ({}) — EK/schedule segment started at block {}",
                                    lang, no, rel, b.id);
                            continue;
                        }
                        // INVALID — body block
                    }
                } else {
                    // ---- Parenthesized heading (groups 5-7): synthesize sub-clause ----
                    // Only synthesize when we are inside a known parent clause.
                    // The matched token itself (e.g. "(1)") is used only to anchor the
                    // ordinal; the actual sub-clause id is derived sequentially so that
                    // mixed sequences like (1)/(a)/(i) in the same clause are all mapped
                    // to consecutive parent.N numbers.
                    //
                    // Guard: short tail → definition-list item, not a real sub-clause.
                    // e.g. "(a) Amerika Birleşik Devletleri;" has a tail of ~32 chars.
                    // Real sub-clauses (e.g. "(a) ALICI, SATICI'dan Ekipman...") are 50+ chars.
                    // Guard: overflow → more than maxParenPerParent items under one parent.
                    if (parenEnabled && currentParent != null) {
                        String afterToken = trimmed.substring(m.end()).trim();
                        boolean shortTail       = afterToken.length() < minParenTail;
                        boolean counterOverflow = parenthesizedCounter >= maxParenPerParent;
                        if (!shortTail && !counterOverflow) {
                            parenthesizedCounter++;
                            String no = currentParent + "." + parenthesizedCounter;
                            // Validate against the last accepted clause (avoids spurious splits
                            // inside long paragraphs that happen to start with "(1)").
                            if (ClauseNumber.isValidNextClause(currentClause, no)) {
                                currentClause = no;
                                found.computeIfAbsent(no, k -> new TreeSet<>());
                                if (!afterToken.isEmpty()) {
                                    headingTexts.put(b.id, afterToken);
                                    found.get(currentClause).add(b.id);
                                }
                                continue;
                            } else {
                                // Validation failed — treat as body text, undo counter increment
                                parenthesizedCounter--;
                            }
                        }
                        // Falls through to body-block handling below
                    }
                }
            }
            // Body block: assign to current clause (if any)
            if (currentClause != null) {
                found.get(currentClause).add(b.id);
            } else if (title.isEmpty()) {
                // Text before first clause heading → document title candidate
                title = trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
            }
        }

        Map<Integer, Block> byId = new LinkedHashMap<>();
        for (Block b : blocks) byId.put(b.id, b);

        // Build resolved block lists first (needed for both content and crops)
        Map<String, List<Block>> clauseBlockMap = new LinkedHashMap<>();
        for (Map.Entry<String, TreeSet<Integer>> e : found.entrySet()) {
            List<Block> own = new ArrayList<>();
            for (int id : e.getValue()) {
                Block b = byId.get(id);
                if (b == null) continue;
                // If this block is a heading block with a stripped title, replace its text
                // so the clause number prefix ("4.") does not appear in the content.
                String override = headingTexts.get(id);
                own.add(override != null
                        ? new Block(b.id, b.page, b.type, override, b.x0, b.y0, b.x1, b.y1)
                        : b);
            }
            if (!own.isEmpty()) clauseBlockMap.put(e.getKey(), own);
        }

        // Crop all clauses page-by-page, releasing each page from memory as soon as its
        // crops are done — avoids holding all rendered pages alive simultaneously.
        int pad = cfg.pipelineInt("cropPaddingPx", 8);
        Map<String, BufferedImage> crops = cropAllClauses(clauseBlockMap, pages, pad);
        // pages list is now fully nulled by cropAllClauses

        JSONObject clauses = new JSONObject();
        for (Map.Entry<String, List<Block>> e : clauseBlockMap.entrySet()) {
            List<Block> own = e.getValue();
            JSONObject c = new JSONObject();
            c.put("signed_content", content(own, true));  // plain text from PDFBox — escape Markdown special chars
            BufferedImage img = crops.get(e.getKey());
            if (img != null) c.put("signed_image", Images.toBase64Png(img));
            c.put("bboxes", bboxes(own));
            clauses.put(e.getKey(), c);
        }
        log.info("Signed PDF [{}]: {} clauses found deterministically from {} native blocks",
                lang, clauses.length(), blocks.size());
        return new JSONObject().put("title", title).put("clauses", clauses);
    }

    /** Returns the clause-heading regex: config override or the compiled default. */
    private java.util.regex.Pattern buildClauseHeadingPattern() {
        String override = cfg.pipelineString("pdfClauseHeadingPattern", "");
        if (!override.isEmpty()) {
            return java.util.regex.Pattern.compile(override, java.util.regex.Pattern.CASE_INSENSITIVE);
        }
        return DEFAULT_CLAUSE_HEADING;
    }

    // ------------------------------------------------------------------ 2b. segmentation — OCR (AI-assisted)

    private JSONObject segmentAndCrop(List<Block> blocks, List<BufferedImage> pages, String lang) throws IOException {
        StepAi segAi = router.get("clauseSegmentationPDFSignedDocument");
        int chunkChars = cfg.pipelineInt("pdfChunkChars", cfg.pipelineInt("docxChunkChars", 24000));
        boolean parenEnabled = cfg.pipelineBoolean("pdfParenSubclauses", false);
        if (!parenEnabled) {
            log.info("Signed PDF [{}] segmentation: parenthesized sub-clause synthesis disabled (pdfParenSubclauses=false)", lang);
        }

        Map<String, TreeSet<Integer>> found = new LinkedHashMap<>();
        String title = "";
        String prev = "";

        int chunksTotal = 0;
        int chunksFailed = 0;
        for (List<Block> chunk : chunk(blocks, chunkChars)) {
            chunksTotal++;
            String resp;
            try {
                resp = segAi.chat(SYSTEM, prompt(lang, chunk, prev, parenEnabled), List.of());
            } catch (IOException e) {
                // A single chunk failure (e.g. content_filter, length, transient error) must not
                // abort the whole document — log and continue with the next chunk so that the
                // remaining clauses can still be extracted.
                log.warn("Signed PDF [{}] segmentation: chunk starting at block {} failed ({}), skipping chunk",
                        lang, chunk.isEmpty() ? "?" : chunk.get(0).id, e.getMessage());
                chunksFailed++;
                continue;
            }
            log.debug("Signed PDF [{}] segmentation raw response: {}", lang, resp);
            JSONObject j = Json.parseLoose(resp);
            String t = j.optString("title", "").trim();
            if (title.isEmpty() && !t.isEmpty()) {
                title = t;
            }
            JSONArray arr = j.optJSONArray("clauses");
            if (arr == null) {
                continue;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                String no = ClauseNumber.normalize(c.optString("number", ""));
                if (no == null) {
                    continue;
                }
                // pdfParenSubclauses=false → LLM'den gelen parantez-sentezi numaraları reddet.
                // Parantez sentezi: LLM "(a)/(b)" öğelerini parent.1/parent.2 olarak döndürür.
                // Filtre yalnızca sentezlenmiş numaralara uygulanır — OCR metninde açıkça
                // "9.1.1." yazan gerçek madde numaraları reddedilmez.
                // Sentez testi: bu chunk'taki block'lardan hiçbiri "no" ile başlamıyorsa sentez.
                if (!parenEnabled && no.contains(".")) {
                    long depth = no.chars().filter(ch -> ch == '.').count() + 1;
                    long prevDepth = prev.isEmpty() ? 0 : prev.chars().filter(ch -> ch == '.').count() + 1;
                    if (depth == prevDepth + 1 && prevDepth >= 2) {
                        // Explicit metin kontrolü: OCR bloklarından biri bu numarayla başlıyor mu?
                        boolean hasExplicitText = false;
                        List<Integer> candidateIds = DocxClauseExtractor.parseIds(c.opt("blocks"), blocks.size());
                        for (int bid : candidateIds) {
                            if (bid >= 0 && bid < blocks.size()) {
                                String bt = extraction.ClauseNumbering.stripMarkdownPrefix(
                                        blocks.get(bid).text.trim());
                                extraction.ClauseNumbering.ParseResult bpr =
                                        extraction.ClauseNumbering.parse(bt);
                                if (bpr != null && bpr.normalized.equals(no)) {
                                    hasExplicitText = true;
                                    break;
                                }
                            }
                        }
                        if (!hasExplicitText) {
                            log.debug("Signed PDF [{}] clause {} skipped — pdfParenSubclauses=false, synthesized (no explicit text in OCR), depth={} after prev='{}'",
                                    lang, no, depth, prev);
                            continue;
                        }
                        log.debug("Signed PDF [{}] clause {} kept — pdfParenSubclauses=false but explicit OCR text found, depth={} after prev='{}'",
                                lang, no, depth, prev);
                    }
                }
                // Sadece INVALID reddedilir — VALID, DUPLICATE, RESET, BACKWARD kabul edilir.
                // (DocxClauseExtractor ile aynı strateji: AI atıfları filtrelemeli,
                //  Java over-validate ederek geçerli maddeleri yutmamalı.)
                extraction.ClauseNumbering.Relation rel =
                        extraction.ClauseNumbering.classify(prev.isEmpty() ? null : prev, no);
                if (rel == extraction.ClauseNumbering.Relation.INVALID) {
                    log.debug("Signed PDF [{}] clause {} rejected ({}) — prev='{}'", lang, no, rel, prev);
                    continue;
                }
                // Reuses DocxClauseExtractor's id/range parser ("3-5,8" | [3,4,5] | ["3-5"]) - same package.
                List<Integer> ids = DocxClauseExtractor.parseIds(c.opt("blocks"), blocks.size());
                if (ids.isEmpty()) {
                    log.warn("Signed PDF [{}] clause {}: no valid block ids in AI response (blocks={}), skipping",
                            lang, no, c.opt("blocks"));
                    continue;
                }
                found.computeIfAbsent(no, k -> new TreeSet<>()).addAll(ids);
                prev = no;
            }
        }

        // All chunks failed → throw so Checkpoint does not cache the empty result
        // and the caller can surface a clear error instead of silently skipping the language.
        if (chunksTotal > 0 && chunksFailed == chunksTotal) {
            throw new IOException("Signed PDF [" + lang + "] segmentation: all " + chunksTotal
                    + " chunk(s) failed — see earlier warnings for details");
        }

        // ── Deterministic supplement: LLM'in kaçırabileceği maddeleri yakala ──
        // OCR bloklarını ClauseBlock'a çevir, deterministik motoru çalıştır,
        // LLM'in bulmadığı maddeleri ekle (LLM pass-1 wins).
        int detSupplementCount = 0;
        try {
            List<extraction.ClauseBlock> detBlocks = new ArrayList<>();
            for (Block b : blocks) {
                detBlocks.add(new extraction.ClauseBlock(b.id, b.page, b.text,
                        extraction.ClauseBlock.Source.OCR_BLOCK, -1,
                        b.x0, b.y0, b.x1, b.y1));
            }
            extraction.ClauseExtraction.ExtractionResult detResult =
                    extraction.ClauseExtraction.fromOcrBlocks(detBlocks, lang);
            for (String no : detResult.clauses.keySet()) {
                if (!found.containsKey(no)) {
                    // Deterministik motor bu maddeyi buldu ama LLM bulamadı
                    List<Integer> detBlockIds = detResult.blocksByClause.get(no);
                    if (detBlockIds != null && !detBlockIds.isEmpty()) {
                        found.put(no, new TreeSet<>(detBlockIds));
                        detSupplementCount++;
                        log.info("Signed PDF [{}] deterministik supplement: madde {} bulundu (LLM kaçırmış)",
                                lang, no);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Signed PDF [{}] deterministik supplement hatası: {}", lang, e.getMessage());
        }
        if (detSupplementCount > 0) {
            log.info("Signed PDF [{}]: {} ek madde deterministik motor ile bulundu", lang, detSupplementCount);
        }

        Map<Integer, Block> byId = new LinkedHashMap<>();
        for (Block b : blocks) {
            byId.put(b.id, b);
        }

        // Build resolved block lists first (needed for both content and crops)
        Map<String, List<Block>> clauseBlockMap = new LinkedHashMap<>();
        for (Map.Entry<String, TreeSet<Integer>> e : found.entrySet()) {
            List<Block> own = new ArrayList<>();
            for (int id : e.getValue()) {
                Block b = byId.get(id);
                if (b != null) own.add(b);
            }
            if (!own.isEmpty()) clauseBlockMap.put(e.getKey(), own);
        }

        // Crop all clauses page-by-page, releasing each page from memory as soon as its
        // crops are done — avoids holding all rendered pages alive simultaneously.
        int pad = cfg.pipelineInt("cropPaddingPx", 8);
        Map<String, BufferedImage> crops = cropAllClauses(clauseBlockMap, pages, pad);
        // pages list is now fully nulled by cropAllClauses

        JSONObject clauses = new JSONObject();
        for (Map.Entry<String, List<Block>> e : clauseBlockMap.entrySet()) {
            List<Block> own = e.getValue();
            JSONObject c = new JSONObject();
            c.put("signed_content", content(own, false));  // OCR blocks already Markdown (Mistral)
            BufferedImage img = crops.get(e.getKey());
            if (img != null) {
                c.put("signed_image", Images.toBase64Png(img));
            }
            c.put("bboxes", bboxes(own));
            clauses.put(e.getKey(), c);
        }
        log.info("Signed PDF [{}]: {} clauses segmented from {} OCR blocks", lang, clauses.length(), blocks.size());
        return new JSONObject().put("title", title).put("clauses", clauses);
    }

    // ------------------------------------------------------------------ clause assembly (deterministic - no AI)

    /**
     * Escapes Markdown special characters in a plain-text string.
     *
     * Used for text that comes from sources without Markdown formatting (readable PDF
     * native text via LineBboxStripper). DOCX and Mistral OCR blocks already produce
     * valid Markdown and must NOT be escaped again.
     */
    private static String escapeMd(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("*",  "\\*")
                .replace("_",  "\\_")
                .replace("`",  "\\`")
                .replace("[",  "\\[")
                .replace("]",  "\\]")
                .replace("#",  "\\#");
    }

    /**
     * Assembles clause content from its blocks.
     *
     * Blocks on the same page are joined with a blank line ({@code \n\n}).
     * Blocks that cross a page boundary are joined with a CommonMark thematic
     * break ({@code \n\n---\n\n}), which renders as {@code <hr>} in HTML without
     * requiring inline HTML and without being affected by {@code escapeHtml(true)}.
     *
     * @param escapePlainText when {@code true} each block's text is Markdown-escaped
     *                        before assembly (use for readable-PDF native text that
     *                        has no Markdown formatting). When {@code false} the text
     *                        is trusted to already be valid Markdown (DOCX, Mistral OCR).
     */
    private static String content(List<Block> blocks, boolean escapePlainText) {
        StringBuilder sb = new StringBuilder();
        int lastPage = -1;
        for (Block b : blocks) {
            if (sb.length() > 0) {
                sb.append(lastPage >= 0 && b.page != lastPage ? "\n\n---\n\n" : "\n\n");
            }
            String text = escapePlainText ? escapeMd(b.text) : normalizeBullets(b.text);
            sb.append(text);
            lastPage = b.page;
        }
        return sb.toString().trim();
    }

    /**
     * Converts Unicode bullet characters emitted by Mistral OCR (e.g. {@code •}, {@code ◦}, {@code ▪})
     * to standard CommonMark list markers ({@code - }) so that {@link report.ReportBuilder#md} renders
     * them as proper {@code <ul><li>} elements rather than plain {@code <p>} text.
     *
     * <p>Only line-leading bullets are converted; a bullet mid-sentence is left untouched.
     */
    private static final java.util.regex.Pattern UNICODE_BULLET_LINE =
            java.util.regex.Pattern.compile(
                    "(?m)^(\\s*)[\\u2022\\u25E6\\u25AA\\u25AB\\u25B8\\u2023\\u2043\\u204C\\u204D]\\s+");

    private static String normalizeBullets(String text) {
        if (text == null) return "";
        return UNICODE_BULLET_LINE.matcher(text).replaceAll("$1- ");
    }

    /** Union bbox per page, in 0..1000 ruler units. */
    private static Map<Integer, double[]> boxesByPage(List<Block> blocks) {
        Map<Integer, double[]> byPage = new LinkedHashMap<>();
        for (Block b : blocks) {
            double[] box = byPage.computeIfAbsent(b.page, k -> new double[]{1000, 1000, 0, 0});
            box[0] = Math.min(box[0], b.x0);
            box[1] = Math.min(box[1], b.y0);
            box[2] = Math.max(box[2], b.x1);
            box[3] = Math.max(box[3], b.y1);
        }
        return byPage;
    }

    private BufferedImage cropClause(List<Block> blocks, List<BufferedImage> pages) {
        int pad = cfg.pipelineInt("cropPaddingPx", 8);
        List<BufferedImage> parts = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : new TreeMap<>(boxesByPage(blocks)).entrySet()) {
            BufferedImage page = pages.get(e.getKey());
            if (page == null) continue;  // already released
            double[] box = e.getValue();
            BufferedImage crop = Images.crop(page, box[0], box[1], box[2], box[3], pad);
            if (crop != null) {
                parts.add(crop);
            }
        }
        return Images.stackVertically(parts);
    }

    /**
     * Renders pages from {@code startPage} (inclusive) to {@code endPage} (exclusive)
     * and appends them to {@code extraPagesOut}.
     * Called after clause extraction to collect signature/annex pages for the report.
     */
    private static void renderExtraPages(PdfDoc doc, int startPage, int endPage,
                                          float dpi, List<BufferedImage> extraPagesOut) {
        if (extraPagesOut == null || startPage >= endPage) return;
        for (int p = startPage; p < endPage; p++) {
            try {
                extraPagesOut.add(doc.render(p, dpi));
                log.debug("Rendered extra page {}/{} for report", p + 1, endPage);
            } catch (IOException e) {
                log.warn("Could not render extra page {}: {}", p + 1, e.getMessage());
            }
        }
        if (endPage > startPage) {
            log.info("Rendered {} extra page(s) (pages {}-{}) for report",
                    endPage - startPage, startPage + 1, endPage);
        }
    }

    /**
     * Releases all page BufferedImages held in the list so the GC can reclaim
     * the memory immediately after all clause crops have been produced.
     * The list entries are nulled rather than the list being cleared so that
     * any code that still holds an index reference gets a clear NPE rather than
     * an ArrayIndexOutOfBoundsException.
     */
    private static void releasePages(List<BufferedImage> pages) {
        for (int i = 0; i < pages.size(); i++) {
            pages.set(i, null);
        }
    }

    /**
     * Produces one cropped image per clause in a memory-efficient way.
     *
     * <p>Instead of holding all rendered page images alive until the full assembly
     * loop is done, this method processes pages in ascending order and nulls each
     * page entry immediately after all crops that touch that page have been
     * produced. For a 50-page PDF at 300 DPI this can reduce peak heap usage by
     * hundreds of megabytes.</p>
     *
     * @param clauseBlocks map from clause number to the blocks belonging to that clause
     * @param pages        rendered page images (entries are nulled in-place as they are consumed)
     * @param pad          crop padding in pixels
     * @return map from clause number to the stacked-crop image (null values omitted)
     */
    private static Map<String, BufferedImage> cropAllClauses(
            Map<String, List<Block>> clauseBlocks,
            List<BufferedImage> pages,
            int pad) {

        // Build an index: pageIndex → set of clause numbers that touch that page
        Map<Integer, Set<String>> pageToClauseNos = new TreeMap<>();
        for (Map.Entry<String, List<Block>> e : clauseBlocks.entrySet()) {
            for (Block b : e.getValue()) {
                pageToClauseNos.computeIfAbsent(b.page, k -> new java.util.LinkedHashSet<>()).add(e.getKey());
            }
        }

        // For each clause, accumulate per-page crop parts in page order
        Map<String, List<BufferedImage>> parts = new LinkedHashMap<>();

        // Process pages in ascending order; null each page after all its crops are done
        for (Map.Entry<Integer, Set<String>> pe : pageToClauseNos.entrySet()) {
            int pageIdx = pe.getKey();
            if (pageIdx < 0 || pageIdx >= pages.size()) continue;
            BufferedImage page = pages.get(pageIdx);
            if (page == null) continue;

            for (String no : pe.getValue()) {
                List<Block> cblocks = clauseBlocks.get(no);
                if (cblocks == null) continue;
                // Compute the bounding box of blocks on this specific page
                double bx0 = 1000, by0 = 1000, bx1 = 0, by1 = 0;
                boolean any = false;
                for (Block b : cblocks) {
                    if (b.page != pageIdx) continue;
                    bx0 = Math.min(bx0, b.x0); by0 = Math.min(by0, b.y0);
                    bx1 = Math.max(bx1, b.x1); by1 = Math.max(by1, b.y1);
                    any = true;
                }
                if (!any) continue;
                BufferedImage crop = Images.crop(page, bx0, by0, bx1, by1, pad);
                if (crop != null) {
                    parts.computeIfAbsent(no, k -> new ArrayList<>()).add(crop);
                }
            }

            // Release this page's memory immediately — all crops that needed it are done
            pages.set(pageIdx, null);
        }

        // Stack per-clause parts into one image
        Map<String, BufferedImage> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<BufferedImage>> e : parts.entrySet()) {
            BufferedImage stacked = Images.stackVertically(e.getValue());
            if (stacked != null) {
                result.put(e.getKey(), stacked);
            }
        }
        return result;
    }

    private static JSONArray bboxes(List<Block> blocks) {
        JSONArray out = new JSONArray();
        for (Map.Entry<Integer, double[]> e : new TreeMap<>(boxesByPage(blocks)).entrySet()) {
            double[] box = e.getValue();
            out.put(new JSONArray()
                    .put(e.getKey() + 1)            // 1-based page number, same convention as PdfClauseCropper
                    .put(Math.round(box[0]))
                    .put(Math.round(box[1]))
                    .put(Math.round(box[2]))
                    .put(Math.round(box[3])));
        }
        return out;
    }

    // ------------------------------------------------------------------ prompt (adapted from DocxClauseExtractor)

    private static List<List<Block>> chunk(List<Block> blocks, int maxChars) {
        List<List<Block>> chunks = new ArrayList<>();
        List<Block> cur = new ArrayList<>();
        int size = 0;
        for (Block b : blocks) {
            int len = line(b).length();
            if (!cur.isEmpty() && size + len > maxChars) {
                chunks.add(cur);
                cur = new ArrayList<>();
                size = 0;
            }
            cur.add(b);
            size += len;
        }
        if (!cur.isEmpty()) {
            chunks.add(cur);
        }
        return chunks;
    }

    private static String line(Block b) {
        return "[" + b.id + "|p" + (b.page + 1) + "|" + b.type + "] " + Json.abbreviate(b.text, 1200) + "\n";
    }

    private static String prompt(String lang, List<Block> chunk, String prev, boolean parenEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("TARGET LANGUAGE: ").append(lang).append(". The document may contain several languages; ignore ")
                .append("blocks that are not written in the target language.\n")
                .append("Below, the document is given as OCR blocks in reading order: [id|page|type] text. Table ")
                .append("blocks are already markdown.\n\n")
                .append("Segment the blocks into numbered clauses and sub-clauses (e.g. 1, 1.1, 1.2, 2.3.4).\n")
                .append("IMPORTANT: Every sub-clause (e.g. \"1.1\", \"1.2\") MUST be returned as a SEPARATE entry ")
                .append("with its own \"number\" and \"blocks\". Do NOT merge sub-clauses into their parent clause.\n");
        if (parenEnabled) {
            sb.append("PARENTHESIZED ITEMS: \"(a)\", \"(b)\", \"(1)\", \"(2)\" etc. are sub-clauses ONLY when ALL of the following are true:\n")
              .append("  1. The item contains a full independent obligation, condition, or right (not a name, country, or short phrase).\n")
              .append("  2. The text after the marker is 100+ characters long.\n")
              .append("  3. The parent clause has 6 or fewer such items (long definition lists are NOT sub-clauses).\n")
              .append("  4. The document structure clearly treats them as numbered sub-clauses (not as bullet points or enumerated lists).\n")
              .append("When in doubt, do NOT treat parenthesized items as sub-clauses — assign them to the parent clause body.\n")
              .append("Examples of items that are NOT sub-clauses:\n")
              .append("  - \"(a) Amerika Birleşik Devletleri;\", \"(b) United Kingdom;\", \"(c) Germany;\" → country/name lists, body of parent.\n")
              .append("  - Any series of 7+ items like (a)(b)(c)...(g) or (1)(2)(3)...(8) → enumeration list, body of parent.\n");
        } else {
            sb.append("PARENTHESIZED ITEMS: \"(a)\", \"(b)\", \"(1)\", \"(2)\", \"(i)\", \"(ii)\" etc. are NEVER sub-clauses in this document.\n")
              .append("  Assign ALL parenthesized items to the body of their enclosing parent clause.\n")
              .append("  Do NOT create numbered entries for (a), (b), (c), (1), (2) etc. — they are list items, not clause headings.\n");
        }
        sb.append("For each clause return:\n")
                .append(" - number: digits and dots only, exactly as printed (e.g. \"1\", \"1.1\", \"1.2\", \"2.3.4\")\n")
                .append(" - blocks: ids of ALL blocks that belong to that clause's OWN text, as a compact string of ")
                .append("ids and ranges, e.g. \"12-14,17\" (include tables that belong to it).\n")
                .append("CRITICAL SCOPING RULE: Once a sub-clause (e.g. \"1.1\") has started, ALL subsequent blocks ")
                .append("belong to that sub-clause (or the next sub-clause/clause that begins later) — they do NOT ")
                .append("revert back to the parent clause (\"1\"). A parent clause (\"1\") contains ONLY its own ")
                .append("heading/introductory text that appears BEFORE the first sub-clause (\"1.1\") starts. ")
                .append("Any body text appearing AFTER the last sub-clause heading belongs to THAT last sub-clause, ")
                .append("not to the parent. For example: if clause 1 has sub-clauses 1.1 and 1.2, and after 1.2's ")
                .append("address block there are two more paragraphs (party definitions, notice rules), those ")
                .append("paragraphs belong to 1.2 — NOT back to clause 1.\n")
                .append("WHAT IS NOT A CLAUSE HEADING — CRITICAL:\n")
                .append("  A block is a clause heading ONLY when ALL of these are true:\n")
                .append("    1. The block is visually distinct (numbered heading, bold, or alone on its line).\n")
                .append("    2. The number appears at the VERY START of the block (not mid-sentence, not after a comma).\n")
                .append("    3. The number advances the document outline by exactly one logical step from the previous clause.\n")
                .append("  A block is NOT a clause heading when:\n")
                .append("  - A number appears inside running body text (e.g. '...Madde 8.2 uyarinca...', '...clause 5.3 of the agreement...').\n")
                .append("  - The text is a continuation paragraph after a heading that was already captured.\n")
                .append("  - The block is a list item, footnote, or definition entry.\n")
                .append("  - The number would require jumping MORE than 3 levels from the current clause (likely a legal reference).\n")
                .append("Example: if the document has clause 1 (intro), clause 1.1 and clause 1.2, return THREE separate ")
                .append("entries: {\"number\":\"1\",...}, {\"number\":\"1.1\",...}, {\"number\":\"1.2\",...}.\n")
                .append("Blocks that belong to no numbered clause (document title/header, pre-contractual recitals before clause 1, signature blocks at the end) are omitted; ")
                .append("return the document title in the target language in \"title\" if it is in this part, else \"\".\n")
                .append("CRITICAL: Address lines, party names, company details, PO boxes, and registration numbers that appear ")
                .append("AFTER a sub-clause heading (e.g. after \"1.1.\") are PART OF THAT SUB-CLAUSE — assign them to it. ")
                .append("Do NOT treat them as 'parties/preamble' and omit them.\n")
                .append("ANNEX/EXHIBIT LIST RULE: A block that lists document annexes/exhibits (e.g. \"EKLER:\", \"EK - 1 ...\", \"Annex 1 ...\", ")
                .append("\"ATTACHMENTS:\") is NOT a new clause — assign it to the LAST numbered clause in this chunk. ")
                .append("Do NOT invent a new clause number for it (e.g. do not assign it clause 19 if the last real clause was 18).\n")
                .append("The previous chunk ended inside clause \"").append(prev).append("\": if the first blocks ")
                .append("continue it, return that same number again.\n")
                .append("IMPORTANT — clause numbering must continue logically from \"").append(prev).append("\":\n")
                .append("  - The next top-level clause after N is N+1 (small increment, typically 1).\n")
                .append("  - A sub-clause of N is N.1; a sub-clause of N.K is N.K.1.\n")
                .append("  - Numbers like \"193\" appearing in body text (e.g. \"HMK'nın 193. maddesi\") ")
                .append("are legal references, NOT new clause headings — do NOT assign them a clause number.\n")
                .append("  - Only assign a new clause number when the block is a structural heading that ")
                .append("advances the document outline by one logical step.\n")
                .append("Return JSON only: {\"title\":\"\",\"clauses\":[{\"number\":\"1\",\"blocks\":\"3-5\"},{\"number\":\"1.1\",\"blocks\":\"6-8\"},{\"number\":\"1.2\",\"blocks\":\"9-11\"}]}\n\nBLOCKS:\n");
        for (Block b : chunk) {
            sb.append(line(b));
        }
        return sb.toString();
    }

    // ================================================================== GUIDED PASS (2nd turn)

    /**
     * Second-pass extraction guided by the clause numbers known from original-1.
     *
     * <p>This pass is only called for clauses that were NOT found in the first (normal) pass.
     * It reuses the already-rendered pages and OCR blocks that are cached inside the
     * {@code firstPassResult}; no new OCR or rendering is performed.
     *
     * <p>The caller (Pipeline) is responsible for merging the returned clauses into the
     * first-pass result: for each clause number returned here that was absent from the
     * first pass, the guided result's content/image is inserted.
     *
     * @param pdf             the signed PDF file (needed only if a fresh re-render is required;
     *                        not re-opened when {@code firstPassResult} already carries images)
     * @param lang            target language
     * @param ocrPagesOut     shared OCR pages list already populated by the first pass (may be empty
     *                        for native PDFs — that is fine, segmentNativeGuided does not need it)
     * @param firstPassResult the JSONObject returned by the first extract call;
     *                        used to recover the block list via re-reading the PDF (blocks are not
     *                        cached between passes — we re-read them, which is cheap for native PDFs
     *                        because readBlocks() uses embedded text, and for OCR PDFs because the
     *                        per-page OCR JSON is already in ocrPagesOut).
     * @param missingNos      clause numbers present in original-1 but absent from the first pass
     * @return a JSONObject with shape {@code {"clauses": {"N": {"signed_content":...,"signed_image":...}}}}
     *         containing ONLY the newly found clauses (may be empty if none could be located)
     */
    public JSONObject extractGuided(File pdf, String lang,
                                    List<JSONObject> ocrPagesOut,
                                    JSONObject firstPassResult,
                                    java.util.Set<String> missingNos) throws Exception {
        if (missingNos == null || missingNos.isEmpty()) {
            return new JSONObject().put("clauses", new JSONObject());
        }

        List<BufferedImage> pages = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();

        // Re-read blocks. For native PDFs this is fast (no OCR). For OCR PDFs the blocks are
        // reconstructed from the cached ocrPagesOut so no new Mistral call is made.
        boolean readable;
        if (!ocrPagesOut.isEmpty()) {
            // OCR path: reconstruct blocks from the already-captured per-page OCR responses
            readable = false;
            try (PdfDoc doc = PdfDoc.open(pdf)) {
                float dpi = cfg.pipelineInt("renderDpi", 150);
                for (int p = 0; p < doc.pageCount(); p++) {
                    pages.add(doc.render(p, dpi));
                }
            }
            int id = 0;
            for (int p = 0; p < ocrPagesOut.size(); p++) {
                JSONObject res = ocrPagesOut.get(p);
                double pw = res.optDouble("width", 1000);
                double ph = res.optDouble("height", 1000);
                JSONArray arr = res.optJSONArray("blocks");
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    String type = b.optString("type", "text").toLowerCase();
                    if (SKIP_TYPES.contains(type)) continue;
                    String text = b.optString("content", "").trim();
                    if (text.isEmpty() && !"table".equals(type)) continue;
                    double x0 = pw > 0 ? b.optDouble("x0", 0) / pw * 1000.0 : 0;
                    double y0 = ph > 0 ? b.optDouble("y0", 0) / ph * 1000.0 : 0;
                    double x1 = pw > 0 ? b.optDouble("x1", pw) / pw * 1000.0 : 1000;
                    double y1 = ph > 0 ? b.optDouble("y1", ph) / ph * 1000.0 : 1000;
                    blocks.add(new Block(id++, p, type, text, x0, y0, x1, y1));
                }
            }
        } else {
            // Native text path: re-read blocks (fast — no OCR)
            readable = readBlocks(pdf, lang, pages, blocks, null);
        }

        if (blocks.isEmpty()) {
            return new JSONObject().put("clauses", new JSONObject());
        }

        int colIndex = resolveColumnIndex(blocks, lang);
        List<Block> langBlocks = filterByColumn(blocks, lang, colIndex);

        // Collect clauses already found in pass-1 so guided pass does not overwrite them
        java.util.Set<String> alreadyFound = new java.util.HashSet<>();
        JSONObject existingClauses = firstPassResult.optJSONObject("clauses");
        if (existingClauses != null) {
            alreadyFound.addAll(existingClauses.keySet());
        }

        // Only search for genuinely missing numbers
        java.util.Set<String> targets = new java.util.LinkedHashSet<>(missingNos);
        targets.removeAll(alreadyFound);
        if (targets.isEmpty()) {
            return new JSONObject().put("clauses", new JSONObject());
        }

        log.info("Signed PDF [{}] guided pass — {} missing clause(s): {}", lang, targets.size(), targets);

        JSONObject guided = readable
                ? segmentNativeGuided(langBlocks, pages, lang, targets)
                : segmentAndCropGuided(langBlocks, pages, lang, targets);

        releasePages(pages);
        return guided;
    }

    // ------------------------------------------------------------------ guided native path

    /**
     * Guided segmentation for readable (native-text) PDFs.
     *
     * <p>Strategy for each missing clause number N:
     * <ol>
     *   <li>Scan all blocks for one whose text starts with N (exact prefix match: "N ", "N.", "N\t").</li>
     *   <li>If found, open clause N at that block and collect subsequent blocks until the next
     *       known clause heading or another expected clause start.</li>
     *   <li>If not found by exact match, fall back to the normal {@link #segmentNative} logic
     *       but with {@code isValidNextClause} relaxed — a candidate number is accepted if it
     *       appears in {@code expectedNos} even when it would otherwise fail the succession check.</li>
     *   <li>Parenthesized sub-clauses ("(1)", "(a)", "(i)" etc.) are matched using the same
     *       parent-tracking + sequential-counter logic as {@link #segmentNative}: when we enter a
     *       parent clause the counter is reset, and each parenthesized token advances it by one,
     *       yielding {@code parent.N} — accepted only when that synthesized number is in
     *       {@code expectedNos}.</li>
     * </ol>
     */
    private JSONObject segmentNativeGuided(List<Block> blocks, List<BufferedImage> pages,
                                            String lang, java.util.Set<String> expectedNos) throws IOException {
        // Sort expected numbers so we can determine "next expected" boundaries
        List<String> sortedExpected = new ArrayList<>(expectedNos);
        sortedExpected.sort(ClauseNumber.COMPARATOR);

        // Config overrides for parenthesized sub-clause guards (same as segmentNative)
        boolean parenEnabled  = cfg.pipelineBoolean("pdfParenSubclauses", false);
        int minParenTail      = cfg.pipelineInt("pdfMinParenTailChars", MIN_PAREN_TAIL_CHARS);
        int maxParenPerParent = cfg.pipelineInt("pdfMaxParenPerParent", MAX_PAREN_PER_PARENT);

        Map<String, TreeSet<Integer>> found = new LinkedHashMap<>();
        Map<Integer, String> headingTexts = new LinkedHashMap<>();
        String currentClause = null;
        // currentParent: last clause opened via a non-parenthesized heading —
        // used to synthesize parent.N sub-clause numbers for groups 4/5/6.
        String currentParent = null;
        int parenthesizedCounter = 0;

        java.util.regex.Pattern headingPat = buildClauseHeadingPattern();

        for (Block b : blocks) {
            String trimmed = extraction.ClauseNumbering.stripArtifactPrefix(b.text.trim());

            // 1. Check exact-prefix match against any expected clause number (dotted notation)
            String exactMatch = matchExpectedPrefix(trimmed, expectedNos);
            if (exactMatch != null) {
                currentClause = exactMatch;
                currentParent = exactMatch;
                parenthesizedCounter = 0;
                found.computeIfAbsent(exactMatch, k -> new TreeSet<>());
                String afterNo = stripClausePrefix(trimmed, exactMatch).trim();
                if (!afterNo.isEmpty()) {
                    headingTexts.put(b.id, afterNo);
                    found.get(currentClause).add(b.id);
                }
                continue;
            }

            // 2. Regex match
            java.util.regex.Matcher m = headingPat.matcher(trimmed);
            if (m.find()) {
                String rawNo = m.group(1) != null ? m.group(1)
                             : m.group(2) != null ? m.group(2)
                             : m.group(3) != null ? m.group(3)
                             : m.group(4);

                if (rawNo != null) {
                    // ---- Non-parenthesized heading (groups 1-4) ----
                    String no = ClauseNumber.normalize(rawNo);
                    if (no != null) {
                        boolean validSuccessor = ClauseNumber.isValidNextClause(currentClause, no);
                        boolean isExpected = expectedNos.contains(no);
                        if (validSuccessor || isExpected) {
                            currentClause = no;
                            currentParent = no;
                            parenthesizedCounter = 0;
                            found.computeIfAbsent(no, k -> new TreeSet<>());
                            String afterNo = trimmed.substring(m.end()).trim();
                            if (!afterNo.isEmpty()) {
                                headingTexts.put(b.id, afterNo);
                                found.get(currentClause).add(b.id);
                            }
                            continue;
                        }
                    }
                } else {
                    // ---- Parenthesized heading (groups 5-7) ----
                    // Synthesize parent.N and check if it is in the expected set.
                    // Guard: short tail or counter overflow → definition-list item, skip.
                    if (parenEnabled && currentParent != null) {
                        String afterToken = trimmed.substring(m.end()).trim();
                        boolean shortTail       = afterToken.length() < minParenTail;
                        boolean counterOverflow = parenthesizedCounter >= maxParenPerParent;
                        if (!shortTail && !counterOverflow) {
                            int tentativeCounter = parenthesizedCounter + 1;
                            String no = currentParent + "." + tentativeCounter;
                            boolean validSuccessor = ClauseNumber.isValidNextClause(currentClause, no);
                            boolean isExpected = expectedNos.contains(no);
                            if (validSuccessor || isExpected) {
                                parenthesizedCounter = tentativeCounter;
                                currentClause = no;
                                found.computeIfAbsent(no, k -> new TreeSet<>());
                                if (!afterToken.isEmpty()) {
                                    headingTexts.put(b.id, afterToken);
                                    found.get(currentClause).add(b.id);
                                }
                                continue;
                            }
                        }
                        // Falls through to body-block handling below
                    }
                }
            }

            // 3. Body block
            if (currentClause != null) {
                found.get(currentClause).add(b.id);
            }
        }

        // Keep only clauses that were in the expected set
        found.keySet().retainAll(expectedNos);

        Map<Integer, Block> byId = new LinkedHashMap<>();
        for (Block b : blocks) byId.put(b.id, b);

        // Build resolved block lists
        Map<String, List<Block>> clauseBlockMap = new LinkedHashMap<>();
        for (Map.Entry<String, TreeSet<Integer>> e : found.entrySet()) {
            List<Block> own = new ArrayList<>();
            for (int id : e.getValue()) {
                Block b = byId.get(id);
                if (b == null) continue;
                String override = headingTexts.get(id);
                own.add(override != null
                        ? new Block(b.id, b.page, b.type, override, b.x0, b.y0, b.x1, b.y1)
                        : b);
            }
            if (!own.isEmpty()) clauseBlockMap.put(e.getKey(), own);
        }

        // Crop page-by-page, releasing each page immediately after its crops are done
        int pad = cfg.pipelineInt("cropPaddingPx", 8);
        Map<String, BufferedImage> crops = cropAllClauses(clauseBlockMap, pages, pad);

        JSONObject clauses = new JSONObject();
        for (Map.Entry<String, List<Block>> e : clauseBlockMap.entrySet()) {
            List<Block> own = e.getValue();
            JSONObject c = new JSONObject();
            c.put("signed_content", content(own, true));
            BufferedImage img = crops.get(e.getKey());
            if (img != null) c.put("signed_image", Images.toBase64Png(img));
            c.put("bboxes", bboxes(own));
            clauses.put(e.getKey(), c);
        }
        log.info("Signed PDF [{}] guided native pass — {}/{} missing clause(s) recovered",
                lang, clauses.length(), expectedNos.size());
        return new JSONObject().put("clauses", clauses);
    }

    /**
     * Checks whether {@code line} starts with an expected clause number prefix.
     *
     * <p>Accepted endings after the number: space, dot, colon, tab, or end-of-string.
     * E.g. "10.1 DELİL" and "10.1." and "10.1:" all match expected "10.1".
     */
    private static String matchExpectedPrefix(String line, java.util.Set<String> expectedNos) {
        for (String no : expectedNos) {
            if (line.startsWith(no)) {
                int after = no.length();
                if (after >= line.length()) return no;          // exact match (whole line)
                char ch = line.charAt(after);
                if (ch == ' ' || ch == '.' || ch == ':' || ch == '\t' || ch == ')') return no;
            }
        }
        return null;
    }

    /** Strips the clause number prefix (and any immediately trailing punctuation) from a line. */
    private static String stripClausePrefix(String line, String no) {
        String rest = line.substring(no.length());
        // strip leading dot/colon/paren/space
        int i = 0;
        while (i < rest.length() && (rest.charAt(i) == '.' || rest.charAt(i) == ':'
                || rest.charAt(i) == ')' || rest.charAt(i) == ' ' || rest.charAt(i) == '\t')) {
            i++;
        }
        return rest.substring(i);
    }

    // ------------------------------------------------------------------ guided OCR path

    /**
     * Guided segmentation for OCR PDFs: sends missing clause numbers as hints to the AI.
     *
     * <p>The prompt explicitly lists the expected-but-missing numbers and instructs the model
     * to look for them in the block text. The model's response is filtered to only accept
     * numbers from the expected set or those that are valid successors.
     */
    private JSONObject segmentAndCropGuided(List<Block> blocks, List<BufferedImage> pages,
                                             String lang, java.util.Set<String> expectedNos)
            throws IOException {
        Map<String, TreeSet<Integer>> found = new LinkedHashMap<>();

        // ── Deterministic exact-prefix pre-pass: LLM çağrısı olmadan açık eşleşmeleri bul ──
        // Her bloğun metninin beklenen madde numaralarından biriyle başlayıp başlamadığını kontrol et.
        // Örn. blok "10.1. ..." ile başlıyorsa ve "10.1" expectedNos içinde varsa → direkt eşleştir.
        for (Block b : blocks) {
            String trimmed = b.text.trim();
            String match = matchExpectedPrefix(trimmed, expectedNos);
            if (match != null) {
                found.computeIfAbsent(match, k -> new TreeSet<>()).add(b.id);
            }
        }
        int detFound = found.size();
        if (detFound > 0) {
            log.info("Signed PDF [{}] guided OCR pre-pass: {} clause(s) found deterministically (no LLM call needed)",
                    lang, detFound);
        }

        // LLM pass for remaining missing clauses
        java.util.Set<String> stillMissing = new java.util.LinkedHashSet<>(expectedNos);
        stillMissing.removeAll(found.keySet());
        if (stillMissing.isEmpty()) {
            // All found deterministically — skip LLM
            return assembleGuided(found, blocks, pages, lang, expectedNos.size());
        }

        StepAi segAi = router.get("clauseSegmentationPDFSignedDocument");
        int chunkChars = cfg.pipelineInt("pdfChunkChars", cfg.pipelineInt("docxChunkChars", 24000));

        List<String> sortedMissing = new ArrayList<>(stillMissing);
        sortedMissing.sort(ClauseNumber.COMPARATOR);
        String missingList = String.join(", ", sortedMissing);

        String title = "";
        String prev = "";

        for (List<Block> chunk : chunk(blocks, chunkChars)) {
            String resp;
            try {
                resp = segAi.chat(SYSTEM, promptGuided(lang, chunk, prev, missingList), List.of());
            } catch (IOException e) {
                log.warn("Signed PDF [{}] guided segmentation: chunk failed ({}), skipping", lang, e.getMessage());
                continue;
            }
            JSONObject j = Json.parseLoose(resp);
            String t = j.optString("title", "").trim();
            if (title.isEmpty() && !t.isEmpty()) title = t;
            JSONArray arr = j.optJSONArray("clauses");
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                String no = ClauseNumber.normalize(c.optString("number", ""));
                if (no == null) continue;
                // Accept only numbers from the expected set (the whole point of guided pass)
                if (!expectedNos.contains(no)) {
                    log.debug("Signed PDF [{}] guided: ignoring {} — not in expected set", lang, no);
                    continue;
                }
                if (found.containsKey(no)) continue;   // deterministic pre-pass wins
                List<Integer> ids = DocxClauseExtractor.parseIds(c.opt("blocks"), blocks.size());
                if (ids.isEmpty()) continue;
                found.computeIfAbsent(no, k -> new TreeSet<>()).addAll(ids);
                prev = no;
            }
        }

        return assembleGuided(found, blocks, pages, lang, expectedNos.size());
    }

    /** Assembly helper for guided pass — shared by deterministic pre-pass and LLM pass. */
    private JSONObject assembleGuided(Map<String, TreeSet<Integer>> found, List<Block> blocks,
                                       List<BufferedImage> pages, String lang, int expectedCount) throws IOException {
        Map<Integer, Block> byId = new LinkedHashMap<>();
        for (Block b : blocks) byId.put(b.id, b);

        // Build resolved block lists
        Map<String, List<Block>> clauseBlockMap = new LinkedHashMap<>();
        for (Map.Entry<String, TreeSet<Integer>> e : found.entrySet()) {
            List<Block> own = new ArrayList<>();
            for (int id : e.getValue()) {
                Block b = byId.get(id);
                if (b != null) own.add(b);
            }
            if (!own.isEmpty()) clauseBlockMap.put(e.getKey(), own);
        }

        // Crop page-by-page, releasing each page immediately after its crops are done
        int pad = cfg.pipelineInt("cropPaddingPx", 8);
        Map<String, BufferedImage> crops = cropAllClauses(clauseBlockMap, pages, pad);

        JSONObject clauses = new JSONObject();
        for (Map.Entry<String, List<Block>> e : clauseBlockMap.entrySet()) {
            List<Block> own = e.getValue();
            JSONObject c = new JSONObject();
            c.put("signed_content", content(own, false));
            BufferedImage img = crops.get(e.getKey());
            if (img != null) c.put("signed_image", Images.toBase64Png(img));
            c.put("bboxes", bboxes(own));
            clauses.put(e.getKey(), c);
        }
        log.info("Signed PDF [{}] guided OCR pass — {}/{} missing clause(s) recovered",
                lang, clauses.length(), expectedCount);
        return new JSONObject().put("clauses", clauses);
    }

    /** Prompt for guided OCR pass — focuses the model on a specific set of missing clause numbers. */
    private static String promptGuided(String lang, List<Block> chunk, String prev, String missingList) {
        StringBuilder sb = new StringBuilder();
        sb.append("TARGET LANGUAGE: ").append(lang).append(".\n")
          .append("This is a SECOND PASS over a signed contract PDF. The first pass already extracted most clauses.\n")
          .append("The following clause numbers were found in the original document but are MISSING from the signed copy so far:\n")
          .append("  MISSING: ").append(missingList).append("\n\n")
          .append("Your task: scan the blocks below and assign block IDs ONLY to the missing clause numbers above.\n")
          .append("Rules:\n")
          .append("  - Only return clause numbers from the MISSING list. Do not invent new numbers.\n")
          .append("  - A block belongs to clause N if its text is clearly the body of clause N.\n")
          .append("  - A clause heading block may start with the number (e.g. '10.1 ...') or be implied by context.\n")
          .append("  - Numbers like '193' inside body text (e.g. 'HMK\u2019n\u0131n 193. maddesi') are legal references, NOT headings.\n")
          .append("  - If a missing clause is genuinely absent from these blocks, omit it from the response.\n")
          .append("The previous chunk ended at clause \"").append(prev).append("\".\n")
          .append("Return JSON only: {\"clauses\":[{\"number\":\"10.1\",\"blocks\":\"3-5\"},{\"number\":\"10.2\",\"blocks\":\"6-8\"}]}\n\nBLOCKS:\n");
        for (Block b : chunk) {
            sb.append(line(b));
        }
        return sb.toString();
    }

    /**
     * Converts an internal language code (e.g. "TR", "EN") to a lowercase BCP-47 code
     * suitable for language-aware OCR APIs (Azure CV Read, etc.).
     * Returns {@code null} for unrecognised / empty input so the provider falls back to
     * auto-detection.
     */
    private static String toLowerBcp47(String lang) {
        if (lang == null || lang.isBlank()) return null;
        // Internal codes are uppercase ISO-639-1; Azure and most OCR APIs want lowercase BCP-47.
        return lang.trim().toLowerCase();
    }
}
