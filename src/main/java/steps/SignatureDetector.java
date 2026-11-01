package steps;

import ai.Router;
import ai.StepAi;
import config.AppConfig;
import pdf.PdfDoc;
import util.Images;
import util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects ink signature / official-stamp areas in a signed PDF and produces a crop image for each.
 *
 * <h3>Why page-level, not block-level?</h3>
 * <p>The earlier block-level approach triggered on <em>every</em> OCR block that contained
 * a keyword like "imza" — including paragraph paraphs (small initials at the bottom of each page)
 * that are NOT the final ink signatures. The new approach avoids this by working at the page level:
 *
 * <h3>Strategy (two steps)</h3>
 * <ol>
 *   <li><b>Candidate page selection</b> — A page is examined when EITHER:
 *     <ul>
 *       <li>it is one of the <em>last {@code signatureTailPages} pages</em> of the document
 *           (config key {@code pipeline.signatureTailPages}, default 4), OR</li>
 *       <li>its OCR blocks contain a strong signature-area signal: block {@code type} is
 *           {@code "signature"} / {@code "handwriting"}, or the block text begins a dedicated
 *           signature line (e.g. "İMZA:", "KAŞE:", "Signature:") rather than just containing
 *           the word as part of a clause sentence.</li>
 *     </ul>
 *     Blocks that are clearly inside numbered clauses (detected by a clause-number prefix) are
 *     excluded from triggering candidacy, so mid-contract paraphs do not pull in their page.
 *   </li>
 *   <li><b>Full-page vision analysis</b> — Each candidate page is sent as a full rendered image
 *       to a vision-capable model ({@code steps.signaturePageDetection}).  The prompt asks:
 *       <ul>
 *         <li>Is this a <em>signature page</em> (i.e. a page whose primary purpose is collecting
 *             signatures / stamps rather than clause text)?</li>
 *         <li>If yes, list each signature/stamp region as a bounding box (0–1000 coords) and an
 *             optional label (party name / role visible near it).</li>
 *       </ul>
 *       Returning full pages gives the model enough visual context to distinguish a real signature
 *       block from a paraph or a printed "Signature: ____" blank line.
 *   </li>
 * </ol>
 *
 * <h3>Output (written to {@code analyze.signatures})</h3>
 * <pre>
 * "signatures": [
 *   {
 *     "page":  12,              // 1-based page number
 *     "label": "ABC Ltd.",      // party / role text near the signature, or ""
 *     "image": "<base64 PNG>"   // cropped signature region (or full page if no bbox given)
 *   }, ...
 * ]
 * </pre>
 *
 * <p>The step is activated only when {@code steps.signaturePageDetection} is present in config.json.
 */
public final class SignatureDetector {
    private static final Logger log = LogManager.getLogger();

    // Default number of tail pages always checked (contract signatures are almost always at the end)
    private static final int DEFAULT_TAIL_PAGES = 4;

    // Block-level signal: text STARTS WITH one of these labels (dedicated signature line header)
    // We use startsWith-style matching rather than contains to avoid mid-clause keyword hits.
    private static final Pattern SIG_LINE_START = Pattern.compile(
            "(?i)^\\s*(i?mza|kaşe|kase|i?mzalayan|authorized\\s+sign|signature|unterzeichn|signatur|"
            + "sign[eé]\\s*:|firm[ae]\\s*:|подпис|مهر\\s*:|امضا\\s*:)");

    // OCR block types that indicate an actual ink region
    private static final Set<String> SIG_BLOCK_TYPES = Set.of("signature", "handwriting");

    // Clause-number prefix: a block whose text starts with "1.", "2.3", "Article" etc. is clause body
    private static final Pattern CLAUSE_START = Pattern.compile(
            "^\\s*(\\d{1,3}\\.\\d{0,3}|madde\\s+\\d|article\\s+\\d|§\\s*\\d)", Pattern.CASE_INSENSITIVE);

    private static final String SYSTEM =
            "You are a document analyst. You only output JSON.";

    private final AppConfig cfg;
    private final Router router;

    public SignatureDetector(AppConfig cfg, Router router) {
        this.cfg = cfg;
        this.router = router;
    }

    // ------------------------------------------------------------------ public entry point

    /**
     * Detect signature / stamp areas in {@code pdf}.
     *
     * @param pdf      The signed PDF (rendered at pipeline renderDpi).
     * @param ocrPages Per-page OCR results collected during {@link PdfClauseExtractor} OCR pass.
     *                 Index {@code p} = raw {@code {width, height, blocks[]}} for page {@code p}.
     *                 May be empty — in that case only tail-page heuristic is used.
     * @return JSON array of confirmed signature entries (see class javadoc). Never null.
     */
    public JSONArray detect(File pdf, List<JSONObject> ocrPages) throws Exception {
        if (ocrPages.isEmpty() && !new File(pdf.getPath()).exists()) {
            log.info("SignatureDetector: nothing to analyse");
            return new JSONArray();
        }

        int tailPages = cfg.pipelineInt("signatureTailPages", DEFAULT_TAIL_PAGES);
        int totalPages = Math.max(ocrPages.size(), 1);  // will be refined after render

        // ---- Step 1: render all pages (needed both for candidate crops and tail detection)
        List<BufferedImage> rendered = renderPages(pdf);
        totalPages = rendered.size();

        // ---- Step 2: decide which pages are candidates
        List<Integer> candidatePageIndices = selectCandidatePages(ocrPages, totalPages, tailPages);
        if (candidatePageIndices.isEmpty()) {
            log.info("SignatureDetector: no candidate pages found");
            return new JSONArray();
        }
        log.info("SignatureDetector: {} candidate page(s) to analyse: {}", candidatePageIndices.size(),
                candidatePageIndices.stream().map(i -> String.valueOf(i + 1)).reduce((a, b) -> a + "," + b).orElse(""));

        // ---- Step 3: for each candidate page, ask the vision LLM
        return analysePages(candidatePageIndices, rendered, ocrPages);
    }

    // ------------------------------------------------------------------ Step 1: render pages

    private List<BufferedImage> renderPages(File pdf) throws Exception {
        float dpi = cfg.pipelineInt("renderDpi", 150);
        List<BufferedImage> pages = new ArrayList<>();
        try (PdfDoc doc = PdfDoc.open(pdf)) {
            for (int p = 0; p < doc.pageCount(); p++) {
                pages.add(doc.render(p, dpi));
            }
        }
        return pages;
    }

    // ------------------------------------------------------------------ Step 2: candidate page selection

    /**
     * Returns 0-based page indices that should be inspected for signatures.
     *
     * A page qualifies when:
     * (a) it is within the last {@code tailPages} pages, OR
     * (b) its OCR blocks contain a dedicated signature-line header (not a mid-clause paraph).
     *
     * Mid-clause paraph blocks are identified by their block's text starting with a clause-number
     * pattern — those pages are NOT promoted even if the block type is "signature".
     */
    private List<Integer> selectCandidatePages(List<JSONObject> ocrPages, int totalPages, int tailPages) {
        List<Integer> result = new ArrayList<>();
        int tailStart = Math.max(0, totalPages - tailPages);

        for (int p = 0; p < totalPages; p++) {
            if (p >= tailStart) {
                result.add(p);
                continue;
            }
            // Check OCR blocks for a strong signal on non-tail pages
            if (p < ocrPages.size() && hasSignatureTriggerBlock(ocrPages.get(p))) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Returns true if this page has at least one block that looks like a dedicated signature
     * section header — not a mid-clause paraph or a reference inside clause text.
     */
    private boolean hasSignatureTriggerBlock(JSONObject pageOcr) {
        JSONArray blocks = pageOcr.optJSONArray("blocks");
        if (blocks == null) {
            return false;
        }
        int sigBlockCount = 0;
        int totalBlocks   = 0;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) {
                continue;
            }
            totalBlocks++;
            String type = b.optString("type", "text").toLowerCase();
            String text = b.optString("content", b.optString("text", "")).trim();

            // Skip clause-body blocks: they cannot be signature headers
            if (CLAUSE_START.matcher(text).find()) {
                continue;
            }

            boolean isSigType    = SIG_BLOCK_TYPES.contains(type);
            boolean isSigLineHdr = SIG_LINE_START.matcher(text).find();

            if (isSigType || isSigLineHdr) {
                sigBlockCount++;
            }
        }
        if (sigBlockCount == 0) {
            return false;
        }
        // Heuristic: a real signature page has relatively FEW text blocks (mostly whitespace +
        // signature fields). If there are many clause-text blocks the page is likely a content
        // page with paraphs, not a signature page. Threshold: sig blocks ≥ 20% of total blocks.
        double ratio = totalBlocks > 0 ? (double) sigBlockCount / totalBlocks : 0;
        return ratio >= 0.20;
    }

    // ------------------------------------------------------------------ Step 3: per-page vision analysis

    /**
     * Sends each candidate page as a full rendered image to the vision LLM.
     * The model identifies signature/stamp regions and returns their bounding boxes.
     *
     * <h3>Crop strategy</h3>
     * <p>LLM bboxes often cover only the ink stroke itself and miss the surrounding
     * context (stamp, party name label, date line).  To guarantee the entire signature
     * block — including kaşe/mühür, name, and date — is always visible, we apply a
     * generous <em>context padding</em> on every side of the LLM-supplied bbox before
     * cropping.  The padding is expressed as a fraction of the page dimension so that
     * it scales with the rendered page size:
     *
     * <pre>
     *   padX = signatureContextPaddingPct × 1000   (in normalised coords)
     *   padY = signatureContextPaddingPct × 1000
     *   cropX0 = max(0,    region.x0 - padX)
     *   cropY0 = max(0,    region.y0 - padY)
     *   cropX1 = min(1000, region.x1 + padX)
     *   cropY1 = min(1000, region.y1 + padY)
     * </pre>
     *
     * <p>When the LLM returns no regions but still confirms a signature page, we fall
     * back to cropping from {@code signaturePageCropStartPct} downward (default 45 %),
     * which covers the signature area of typical contracts without including all the
     * clause text above it.
     */
    private JSONArray analysePages(List<Integer> pageIndices, List<BufferedImage> rendered,
                                   List<JSONObject> ocrPages) throws Exception {
        StepAi ai = router.get("signaturePageDetection");

        // Context padding added to every side of the LLM bbox (0-1000 units = fraction of page)
        double ctxPad = cfg.pipelineDouble("signatureContextPaddingPct", 0.12) * 1000.0;

        // Fallback Y start when LLM gives no regions (0-1 fraction of page height)
        double cropStartPct = cfg.pipelineDouble("signaturePageCropStartPct", 0.45);

        JSONArray result = new JSONArray();

        for (int pageIdx : pageIndices) {
            BufferedImage pageImg = rendered.get(pageIdx);
            String pageB64 = Images.toBase64Png(pageImg);

            String prompt = buildPagePrompt(pageIdx + 1);
            String resp;
            try {
                resp = ai.chat(SYSTEM, prompt, List.of(pageB64));
            } catch (Exception e) {
                log.warn("SignatureDetector: LLM call failed for page {}: {}", pageIdx + 1, e.getMessage());
                continue;
            }

            JSONObject j = Json.parseLoose(resp);
            boolean isSignaturePage = j.optBoolean("is_signature_page", false);
            if (!isSignaturePage) {
                log.debug("SignatureDetector: page {} — not a signature page", pageIdx + 1);
                continue;
            }

            log.info("SignatureDetector: page {} confirmed as signature page", pageIdx + 1);
            JSONArray regions = j.optJSONArray("regions");

            if (regions == null || regions.length() == 0) {
                // No region bboxes: fall back to lower portion of the page
                double y0Fall = cropStartPct * 1000.0;
                BufferedImage crop = Images.crop(pageImg, 0, y0Fall, 1000, 1000, 0);
                result.put(new JSONObject()
                        .put("page", pageIdx + 1)
                        .put("label", j.optString("page_label", ""))
                        .put("image", Images.toBase64Png(crop != null ? crop : pageImg)));
                log.info("SignatureDetector: page {} — no regions, fallback crop from y={}", pageIdx + 1, (int) y0Fall);
                continue;
            }

            // For each region: expand by ctxPad on all four sides, then crop
            for (int r = 0; r < regions.length(); r++) {
                JSONObject region = regions.optJSONObject(r);
                if (region == null) {
                    continue;
                }
                double rx0 = region.optDouble("x0", 0);
                double ry0 = region.optDouble("y0", 0);
                double rx1 = region.optDouble("x1", 1000);
                double ry1 = region.optDouble("y1", 1000);
                String label = region.optString("label", "").trim();

                // Expand uniformly on all sides — clamp to page boundary handled inside Images.crop
                double cx0 = Math.max(0,    rx0 - ctxPad);
                double cy0 = Math.max(0,    ry0 - ctxPad);
                double cx1 = Math.min(1000, rx1 + ctxPad);
                double cy1 = Math.min(1000, ry1 + ctxPad);

                // Pass pad=0: we already added our own generous padding above
                BufferedImage crop = Images.crop(pageImg, cx0, cy0, cx1, cy1, 0);
                if (crop == null) {
                    log.warn("SignatureDetector: crop failed for page {} region {} bbox=({:.0f},{:.0f},{:.0f},{:.0f}) expanded=({:.0f},{:.0f},{:.0f},{:.0f})",
                            pageIdx + 1, r, rx0, ry0, rx1, ry1, cx0, cy0, cx1, cy1);
                    continue;
                }
                log.info("SignatureDetector: page {} region {} — LLM bbox ({},{},{},{}) expanded to ({},{},{},{}) → {}x{}px label='{}'",
                        pageIdx + 1, r,
                        Math.round(rx0), Math.round(ry0), Math.round(rx1), Math.round(ry1),
                        Math.round(cx0), Math.round(cy0), Math.round(cx1), Math.round(cy1),
                        crop.getWidth(), crop.getHeight(), label);
                result.put(new JSONObject()
                        .put("page", pageIdx + 1)
                        .put("label", label)
                        .put("image", Images.toBase64Png(crop)));
            }
        }

        log.info("SignatureDetector: {} signature region(s) found across {} candidate page(s)",
                result.length(), pageIndices.size());
        return result;
    }

    // ------------------------------------------------------------------ prompt

    private static String buildPagePrompt(int pageNo) {
        return "You are analysing page " + pageNo + " of a signed contract.\n\n"
             + "Task: determine whether this is a SIGNATURE PAGE — a page whose primary purpose\n"
             + "is to collect handwritten ink signatures and/or official stamps/seals from the\n"
             + "contracting parties. A signature page typically appears at the END of the contract\n"
             + "and contains party names/titles, date fields, and ACTUAL handwritten ink marks\n"
             + "or rubber stamps (kaşe/mühür).\n\n"
             + "Do NOT treat the following as signature pages:\n"
             + " - Pages that only have paragraph initials (small handwritten marks in the margin\n"
             + "   or bottom corner next to clause numbers) — these are paraphs, not final signatures.\n"
             + " - Pages with only blank '______' lines labelled 'Signature:' but NO actual ink.\n"
             + " - Cover pages, table-of-contents pages, or purely clause-text pages.\n\n"
             + "If this IS a signature page, identify EACH distinct signature or stamp block.\n"
             + "Signature blocks are usually arranged side-by-side in the lower portion of the page.\n"
             + "Each block typically includes: party name/title label, a date line, a stamp area,\n"
             + "and the actual ink signature.\n\n"
             + "IMPORTANT bbox rules:\n"
             + " - Return ONE bbox per signatory party (covering their entire block: label + stamp + ink).\n"
             + " - Make each bbox GENEROUS — include a bit of whitespace around the ink area.\n"
             + " - Coordinates are 0-1000 where 0,0 = top-left and 1000,1000 = bottom-right.\n"
             + " - A typical signature block bbox should span at least 25% of the page width and\n"
             + "   15% of the page height.\n"
             + " - Do NOT return a tiny bbox covering only the ink stroke; include the surrounding\n"
             + "   label and stamp area.\n\n"
             + "Return JSON only — no explanation:\n"
             + "{\n"
             + "  \"is_signature_page\": true | false,\n"
             + "  \"page_label\": \"e.g. 'İmza Sayfası / Signature Page'\",\n"
             + "  \"regions\": [\n"
             + "    { \"x0\": 20, \"y0\": 650, \"x1\": 490, \"y1\": 980,\n"
             + "      \"label\": \"Satıcı / Seller — ABC Ltd.\" },\n"
             + "    { \"x0\": 510, \"y0\": 650, \"x1\": 980, \"y1\": 980,\n"
             + "      \"label\": \"Alıcı / Buyer — XYZ A.Ş.\" }\n"
             + "  ]\n"
             + "}\n\n"
             + "If is_signature_page is false, omit or leave regions empty.";
    }
}
