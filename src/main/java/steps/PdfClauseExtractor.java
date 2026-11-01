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
        List<BufferedImage> pages = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        boolean readable = readBlocks(pdf, lang, pages, blocks, ocrPagesOut);

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
        try (PdfDoc doc = PdfDoc.open(pdf)) {
            List<String> pageTexts = doc.pageTexts();

            if (isReadablePdf(pageTexts)) {
                // ── NATIVE TEXT MODE ──────────────────────────────────────────
                // The PDF has embedded text. Use PDFBox's TextPosition API to get
                // real per-line bounding boxes — no OCR, no AI needed at all.
                log.info("Signed PDF [{}]: readable mode — using native text + bbox extraction, OCR skipped ({} pages)",
                        lang, pageTexts.size());

                float dpi = cfg.pipelineInt("renderDpi", 150);
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
                for (int p = 0; p < doc.pageCount(); p++) {
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
                            lang, p + 1, doc.pageCount(), lines.size() - skipped, skipped);
                }
                // ocrPagesOut intentionally left empty — SignatureDetector falls back to tail heuristic
                return true;  // readable
            }

            // ── OCR MODE ─────────────────────────────────────────────────────
            // The PDF is a scan (image-only pages): render + OCR each page.
            log.info("Signed PDF [{}]: OCR mode — no native text detected, rendering {} page(s)",
                    lang, doc.pageCount());
            StepAi ocrAi = router.get("pdfPageBlockOcr");
            float dpi = cfg.pipelineInt("renderDpi", 150);
            int id = 0;
            for (int p = 0; p < doc.pageCount(); p++) {
                BufferedImage page = doc.render(p, dpi);
                pagesOut.add(page);

                JSONObject res = ocrAi.ocrBlocks(Images.toBase64Png(page));
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
                log.info("Signed PDF [{}] page {}/{}: {} OCR blocks kept", lang, p + 1, doc.pageCount(), kept);
            }
            return false;  // OCR
        }
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
     * Group 4 → parenthesized decimal      (alternative D1)  e.g. "(1)"
     * Group 5 → parenthesized letter       (alternative D2)  e.g. "(a)"
     * Group 6 → parenthesized roman        (alternative D3)  e.g. "(i)"
     */
    private static final java.util.regex.Pattern DEFAULT_CLAUSE_HEADING =
            java.util.regex.Pattern.compile(
                    // (A1) Multi-level: "1.1", "1.1.2", "10.3.1" — dot presence is the guard
                    "^(\\d{1,4}(?:\\.\\d{1,4})+\\.?)(?:\\s|$)"
                    // (A2) Single-level: "1." or "2." — trailing dot required, bare "1 word" rejected
                    + "|^(\\d{1,4}\\.)(?:\\s|$)"
                    // (B) Keyword prefix — Turkish: madde; English: article/section/clause; symbols: §
                    + "|^(?:madde|article|section|clause|§)\\s+(\\d[\\d.]*)"
                    // (D1) Parenthesized decimal sub-clause: "(1)", "(2)", ... "(99)"
                    + "|^\\((\\d{1,2})\\)(?:\\s|$)"
                    // (D2) Parenthesized single letter: "(a)", "(b)", ... "(z)"
                    + "|^\\(([a-z])\\)(?:\\s|$)"
                    // (D3) Parenthesized roman numeral: "(i)", "(ii)", "(iii)", ... "(viii)"
                    + "|^\\((i{1,3}|iv|vi{0,3}|ix|x{1,3}|xi{1,3}|xiv|xv|xvi{0,3})\\)(?:\\s|$)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

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
            String trimmed = b.text.trim();
            java.util.regex.Matcher m = headingPat.matcher(trimmed);
            if (m.find()) {
                // Group 1: multi-level "1.1[.]"
                // Group 2: single-level "1."
                // Group 3: "Madde N" / "Section N" / "Clause N" / "Article N"
                // Group 4: parenthesized decimal "(1)"
                // Group 5: parenthesized letter  "(a)"
                // Group 6: parenthesized roman   "(i)"
                String rawNo = m.group(1) != null ? m.group(1)
                             : m.group(2) != null ? m.group(2)
                             : m.group(3);

                if (rawNo != null) {
                    // ---- Non-parenthesized heading (groups 1-3) ----
                    String no = ClauseNumber.normalize(rawNo);
                    if (no != null && ClauseNumber.isValidNextClause(currentClause, no)) {
                        currentClause = no;
                        currentParent = no;
                        parenthesizedCounter = 0;
                        found.computeIfAbsent(no, k -> new TreeSet<>());
                        String afterNo = trimmed.substring(m.end()).trim();
                        if (!afterNo.isEmpty()) {
                            headingTexts.put(b.id, afterNo);
                            found.get(currentClause).add(b.id);
                        }
                        // Bare number line ("4." alone) — open clause, no content block added.
                        continue;
                    }
                } else {
                    // ---- Parenthesized heading (groups 4-6): synthesize sub-clause ----
                    // Only synthesize when we are inside a known parent clause.
                    // The matched token itself (e.g. "(1)") is used only to anchor the
                    // ordinal; the actual sub-clause id is derived sequentially so that
                    // mixed sequences like (1)/(a)/(i) in the same clause are all mapped
                    // to consecutive parent.N numbers.
                    if (currentParent != null) {
                        parenthesizedCounter++;
                        String no = currentParent + "." + parenthesizedCounter;
                        // Validate against the last accepted clause (avoids spurious splits
                        // inside long paragraphs that happen to start with "(1)").
                        if (ClauseNumber.isValidNextClause(currentClause, no)) {
                            currentClause = no;
                            found.computeIfAbsent(no, k -> new TreeSet<>());
                            String afterNo = trimmed.substring(m.end()).trim();
                            if (!afterNo.isEmpty()) {
                                headingTexts.put(b.id, afterNo);
                                found.get(currentClause).add(b.id);
                            }
                            continue;
                        } else {
                            // Validation failed — treat as body text, undo counter increment
                            parenthesizedCounter--;
                        }
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

        JSONObject clauses = new JSONObject();
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
            if (own.isEmpty()) continue;

            JSONObject c = new JSONObject();
            c.put("signed_content", content(own, true));  // plain text from PDFBox — escape Markdown special chars
            BufferedImage img = cropClause(own, pages);
            if (img != null) c.put("signed_image", Images.toBase64Png(img));
            c.put("bboxes", bboxes(own));
            clauses.put(e.getKey(), c);
        }
        log.info("Signed PDF [{}]: {} clauses found deterministically from {} native blocks",
                lang, clauses.length(), blocks.size());
        releasePages(pages);
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
        int chunkChars = cfg.pipelineInt("docxChunkChars", 24000);

        Map<String, TreeSet<Integer>> found = new LinkedHashMap<>();
        String title = "";
        String prev = "";

        int chunksTotal = 0;
        int chunksFailed = 0;
        for (List<Block> chunk : chunk(blocks, chunkChars)) {
            chunksTotal++;
            String resp;
            try {
                resp = segAi.chat(SYSTEM, prompt(lang, chunk, prev), List.of());
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
                if (!ClauseNumber.isValidNextClause(prev.isEmpty() ? null : prev, no)) {
                    log.debug("Signed PDF [{}] clause {} rejected — not a valid successor of '{}'", lang, no, prev);
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

        Map<Integer, Block> byId = new LinkedHashMap<>();
        for (Block b : blocks) {
            byId.put(b.id, b);
        }

        JSONObject clauses = new JSONObject();
        for (Map.Entry<String, TreeSet<Integer>> e : found.entrySet()) {
            List<Block> own = new ArrayList<>();
            for (int id : e.getValue()) {
                Block b = byId.get(id);
                if (b != null) {
                    own.add(b);
                }
            }
            if (own.isEmpty()) {
                continue;
            }
            JSONObject c = new JSONObject();
            c.put("signed_content", content(own, false));  // OCR blocks already Markdown (Mistral)
            BufferedImage img = cropClause(own, pages);
            if (img != null) {
                c.put("signed_image", Images.toBase64Png(img));
            }
            c.put("bboxes", bboxes(own));
            clauses.put(e.getKey(), c);
        }
        log.info("Signed PDF [{}]: {} clauses segmented from {} OCR blocks", lang, clauses.length(), blocks.size());
        releasePages(pages);
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

    private static String prompt(String lang, List<Block> chunk, String prev) {
        StringBuilder sb = new StringBuilder();
        sb.append("TARGET LANGUAGE: ").append(lang).append(". The document may contain several languages; ignore ")
                .append("blocks that are not written in the target language.\n")
                .append("Below, the document is given as OCR blocks in reading order: [id|page|type] text. Table ")
                .append("blocks are already markdown.\n\n")
                .append("Segment the blocks into numbered clauses and sub-clauses (e.g. 1, 1.1, 1.2, 2.3.4).\n")
                .append("IMPORTANT: Every sub-clause (e.g. \"1.1\", \"1.2\") MUST be returned as a SEPARATE entry ")
                .append("with its own \"number\" and \"blocks\". Do NOT merge sub-clauses into their parent clause.\n")
                .append("For each clause return:\n")
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
                .append("Example: if the document has clause 1 (intro), clause 1.1 and clause 1.2, return THREE separate ")
                .append("entries: {\"number\":\"1\",...}, {\"number\":\"1.1\",...}, {\"number\":\"1.2\",...}.\n")
                .append("Blocks that belong to no numbered clause (title, parties, preamble, signatures) are omitted; ")
                .append("return the document title in the target language in \"title\" if it is in this part, else \"\".\n")
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

        Map<String, TreeSet<Integer>> found = new LinkedHashMap<>();
        Map<Integer, String> headingTexts = new LinkedHashMap<>();
        String currentClause = null;
        // currentParent: last clause opened via a non-parenthesized heading —
        // used to synthesize parent.N sub-clause numbers for groups 4/5/6.
        String currentParent = null;
        int parenthesizedCounter = 0;

        java.util.regex.Pattern headingPat = buildClauseHeadingPattern();

        for (Block b : blocks) {
            String trimmed = b.text.trim();

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
                             : m.group(3);

                if (rawNo != null) {
                    // ---- Non-parenthesized heading (groups 1-3) ----
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
                    // ---- Parenthesized heading (groups 4-6) ----
                    // Synthesize parent.N and check if it is in the expected set.
                    if (currentParent != null) {
                        int tentativeCounter = parenthesizedCounter + 1;
                        String no = currentParent + "." + tentativeCounter;
                        boolean validSuccessor = ClauseNumber.isValidNextClause(currentClause, no);
                        boolean isExpected = expectedNos.contains(no);
                        if (validSuccessor || isExpected) {
                            parenthesizedCounter = tentativeCounter;
                            currentClause = no;
                            found.computeIfAbsent(no, k -> new TreeSet<>());
                            String afterNo = trimmed.substring(m.end()).trim();
                            if (!afterNo.isEmpty()) {
                                headingTexts.put(b.id, afterNo);
                                found.get(currentClause).add(b.id);
                            }
                            continue;
                        }
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

        JSONObject clauses = new JSONObject();
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
            if (own.isEmpty()) continue;
            JSONObject c = new JSONObject();
            c.put("signed_content", content(own, true));
            BufferedImage img = cropClause(own, pages);
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
        StepAi segAi = router.get("clauseSegmentationPDFSignedDocument");
        int chunkChars = cfg.pipelineInt("docxChunkChars", 24000);

        List<String> sortedMissing = new ArrayList<>(expectedNos);
        sortedMissing.sort(ClauseNumber.COMPARATOR);
        String missingList = String.join(", ", sortedMissing);

        Map<String, TreeSet<Integer>> found = new LinkedHashMap<>();
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
                List<Integer> ids = DocxClauseExtractor.parseIds(c.opt("blocks"), blocks.size());
                if (ids.isEmpty()) continue;
                found.computeIfAbsent(no, k -> new TreeSet<>()).addAll(ids);
                prev = no;
            }
        }

        Map<Integer, Block> byId = new LinkedHashMap<>();
        for (Block b : blocks) byId.put(b.id, b);

        JSONObject clauses = new JSONObject();
        for (Map.Entry<String, TreeSet<Integer>> e : found.entrySet()) {
            List<Block> own = new ArrayList<>();
            for (int id : e.getValue()) {
                Block b = byId.get(id);
                if (b != null) own.add(b);
            }
            if (own.isEmpty()) continue;
            JSONObject c = new JSONObject();
            c.put("signed_content", content(own, false));
            BufferedImage img = cropClause(own, pages);
            if (img != null) c.put("signed_image", Images.toBase64Png(img));
            c.put("bboxes", bboxes(own));
            clauses.put(e.getKey(), c);
        }
        log.info("Signed PDF [{}] guided OCR pass — {}/{} missing clause(s) recovered",
                lang, clauses.length(), expectedNos.size());
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
}
