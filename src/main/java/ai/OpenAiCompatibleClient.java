package ai;

import util.HttpJson;
import util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * OpenAI chat-completions compatible: OpenAI, OpenRouter, Azure OpenAI / Microsoft Foundry (via chatPath + query + authHeader).
 *
 * <p>When {@code "blockOcr": true} is set in the provider config, {@link #ocrBlocks} is also supported:
 * the page image is sent to a vision model which transcribes it as GitHub-flavoured Markdown and the result
 * is split into logical blocks (paragraph / table / title) that are assigned sequential vertical bands as
 * synthetic bounding boxes (0..1000 × 0..1000). These coordinates are not pixel-accurate but are sufficient
 * for {@link steps.PdfClauseExtractor}'s vertical-order clause segmentation, which only needs to know which
 * block comes before which — not their exact pixel positions.
 * </p>
 */
public final class OpenAiCompatibleClient extends BaseClient {

    private static final Logger log = LogManager.getLogger(OpenAiCompatibleClient.class);

    public OpenAiCompatibleClient(String name, JSONObject cfg) {
        super(name, cfg);
    }

    private String url(String model) {
        String path = cfg.optString("chatPath", "/chat/completions").replace("{model}", model);
        String q = cfg.optString("query", "");
        return baseUrl + path + (q.isEmpty() ? "" : "?" + q);
    }

    @Override
    public String chat(ChatRequest r) throws IOException {
        JSONObject body = new JSONObject();
        body.put("model", r.model);

        JSONArray messages = new JSONArray();
        if (r.system != null && !r.system.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", r.system));
        }
        Object content;
        if (r.images.isEmpty()) {
            content = r.user;
        } else {
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("type", "text").put("text", r.user));
            for (String img : r.images) {
                parts.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", "data:image/png;base64," + img)));
            }
            content = parts;
        }
        messages.put(new JSONObject().put("role", "user").put("content", content));
        body.put("messages", messages);

        if (r.temperature != null) {
            body.put("temperature", r.temperature.doubleValue());
        }
        if (r.maxTokens != null) {
            body.put(cfg.optString("maxTokensParam", "max_tokens"), r.maxTokens.intValue());
        }
        if (r.jsonMode && cfg.optBoolean("supportsJsonMode", true)) {
            body.put("response_format", new JSONObject().put("type", "json_object"));
        }

        JSONObject resp = HttpJson.post(url(r.model), headers(), body, timeoutSec, retries);
        if (resp.has("error") && !resp.isNull("error")) {
            throw new IOException("Provider " + name + " error: " + Json.abbreviate(resp.get("error").toString(), 500));
        }
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("No choices from " + name + ": " + Json.abbreviate(resp.toString(), 500));
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        Object c = msg == null ? null : msg.opt("content");
        String text;
        if (c instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) c;
            for (int i = 0; i < arr.length(); i++) {
                sb.append(arr.getJSONObject(i).optString("text", ""));
            }
            text = sb.toString();
        } else {
            text = c == null || JSONObject.NULL.equals(c) ? "" : c.toString();
        }
        if (text.trim().isEmpty()) {
            throw new IOException("Empty answer from " + name + " (model " + r.model + ")");
        }
        return text;
    }

    @Override
    public ChatResult chatWithUsage(ChatRequest r) throws IOException {
        // Re-use the same body-building logic by calling the internal method directly
        JSONObject body = new JSONObject();
        body.put("model", r.model);
        JSONArray messages = new JSONArray();
        if (r.system != null && !r.system.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", r.system));
        }
        Object content;
        if (r.images.isEmpty()) {
            content = r.user;
        } else {
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("type", "text").put("text", r.user));
            for (String img : r.images) {
                parts.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", "data:image/png;base64," + img)));
            }
            content = parts;
        }
        messages.put(new JSONObject().put("role", "user").put("content", content));
        body.put("messages", messages);
        if (r.temperature != null) body.put("temperature", r.temperature.doubleValue());
        if (r.maxTokens != null)   body.put(cfg.optString("maxTokensParam", "max_tokens"), r.maxTokens.intValue());
        if (r.jsonMode && cfg.optBoolean("supportsJsonMode", true))
            body.put("response_format", new JSONObject().put("type", "json_object"));

        JSONObject resp = HttpJson.post(url(r.model), headers(), body, timeoutSec, retries);
        if (resp.has("error") && !resp.isNull("error"))
            throw new IOException("Provider " + name + " error: " + Json.abbreviate(resp.get("error").toString(), 500));
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0)
            throw new IOException("No choices from " + name + ": " + Json.abbreviate(resp.toString(), 500));
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        Object c = msg == null ? null : msg.opt("content");
        String text;
        if (c instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) c;
            for (int i = 0; i < arr.length(); i++) sb.append(arr.getJSONObject(i).optString("text", ""));
            text = sb.toString();
        } else {
            text = c == null || JSONObject.NULL.equals(c) ? "" : c.toString();
        }
        if (text.trim().isEmpty())
            throw new IOException("Empty answer from " + name + " (model " + r.model + ")");

        // Extract provider-reported token counts
        JSONObject usage = resp.optJSONObject("usage");
        long promptTok = usage != null ? usage.optLong("prompt_tokens", -1) : -1;
        long completionTok = usage != null ? usage.optLong("completion_tokens", -1) : -1;
        return new ChatResult(text, promptTok, completionTok);
    }

    // ------------------------------------------------------------------ OCR (block-aware, vision-based)

    /**
     * Block-aware OCR using the vision capability of this chat-completion provider.
     *
     * <p>Activated when {@code "blockOcr": true} is set in the provider config.
     * The page image is sent to the vision model with a structured JSON prompt; the model
     * returns the page content as an array of blocks, each with its content and an estimated
     * position. Coordinates are scaled to 0–1000 (same normalised unit used by the rest of
     * the pipeline), so callers never need to know the original pixel dimensions.</p>
     *
     * <p>When the model returns partial or malformed JSON, the method falls back to treating
     * the entire response as a single text block covering the full page.</p>
     *
     * <p>Output format matches {@link AiClient#ocrBlocks}: {@code {width:1000, height:1000,
     * blocks:[{type, content, x0, y0, x1, y1}]}}.</p>
     */
    @Override
    public boolean supportsBlockOcr() {
        return cfg.optBoolean("blockOcr", false);
    }

    @Override
    public JSONObject ocrBlocks(String model, String pngBase64) throws IOException {
        if (!supportsBlockOcr()) {
            throw new IOException("Provider '" + name + "' does not have blockOcr=true in config");
        }

        ChatRequest r = new ChatRequest();
        r.model      = model;
        r.system     = OCR_BLOCKS_SYSTEM;
        r.user       = OCR_BLOCKS_USER;
        r.images     = java.util.List.of(pngBase64);
        r.jsonMode   = cfg.optBoolean("supportsJsonMode", true);
        r.maxTokens  = cfg.optInt("ocrBlocksMaxTokens", 4096);

        String raw = chat(r);
        return parseOcrBlocksResponse(raw, name, model);
    }

    /**
     * Whole-document PDF OCR using the vision capability of this chat-completion provider.
     *
     * <p>Activated when {@code "blockOcr": true} is set in the provider config.
     * The PDF is rendered to PNG pages internally and each page is sent separately to the
     * vision model, then the results are assembled into the standard per-page array.
     *
     * <p>This path renders the PDF via PDFBox locally (same DPI as {@code renderDpi} config,
     * default 150). It is less efficient than the native Azure CV path but enables any
     * OpenAI-compatible vision model to serve as an OCR backend.</p>
     */
    @Override
    public boolean supportsPdfOcr() {
        return cfg.optBoolean("blockOcr", false);
    }

    @Override
    public org.json.JSONArray ocrPdfBlocks(String model, byte[] pdfBytes, String language) throws IOException {
        if (!supportsPdfOcr()) {
            throw new IOException("Provider '" + name + "' does not have blockOcr=true in config");
        }
        // Write PDF to a temp file so PDFBox can open it
        java.io.File tmp = java.io.File.createTempFile("ocr_", ".pdf");
        try {
            Files.write(tmp.toPath(), pdfBytes);
            pdf.PdfDoc doc;
            try {
                doc = pdf.PdfDoc.open(tmp);
            } catch (Exception e) {
                throw new IOException("Could not open PDF for OCR (provider=" + name + "): " + e.getMessage(), e);
            }
            try {
                int totalPages = doc.pageCount();
                int renderDpi  = cfg.optInt("renderDpi", 150);
                log.info("OpenAI-compat PDF OCR: provider={}, model={}, pages={}, dpi={}",
                        name, model, totalPages, renderDpi);
                org.json.JSONArray result = new org.json.JSONArray();
                for (int p = 0; p < totalPages; p++) {
                    java.awt.image.BufferedImage pageImg = doc.render(p, renderDpi);
                    String b64 = util.Images.toBase64Png(pageImg);

                    ChatRequest r = new ChatRequest();
                    r.model     = model;
                    r.system    = OCR_BLOCKS_SYSTEM;
                    r.user      = OCR_BLOCKS_USER_PAGE
                            .replace("{PAGE}", String.valueOf(p + 1))
                            .replace("{LANG}", language != null && !language.isBlank() ? language : "auto");
                    r.images    = java.util.List.of(b64);
                    r.jsonMode  = cfg.optBoolean("supportsJsonMode", true);
                    r.maxTokens = cfg.optInt("ocrBlocksMaxTokens", 4096);

                    String raw;
                    try {
                        raw = chat(r);
                    } catch (IOException e) {
                        log.warn("OpenAI-compat PDF OCR page {}/{} failed ({}), skipping", p + 1, totalPages, e.getMessage());
                        continue;
                    }
                    JSONObject pageBlocks = parseOcrBlocksResponse(raw, name, model);
                    result.put(new JSONObject()
                            .put("page",   p)
                            .put("width",  pageBlocks.optDouble("width",  1000))
                            .put("height", pageBlocks.optDouble("height", 1000))
                            .put("blocks", pageBlocks.optJSONArray("blocks")));
                }
                log.info("OpenAI-compat PDF OCR completed: {} pages", result.length());
                return result;
            } finally {
                try { doc.close(); } catch (Exception ignored) {}
            }
        } finally {
            tmp.delete();
        }
    }

    // ------------------------------------------------------------------ OCR prompt constants

    /**
     * System prompt for block-aware OCR.
     * Instructs the model to return the page content as a structured JSON array of blocks,
     * each with its type, content, and approximate position (0–1000 normalised coordinates).
     */
    static final String OCR_BLOCKS_SYSTEM =
        "You are a precise OCR engine. Your task is to extract all text from the given document page image " +
        "and return it as structured JSON. " +
        "Output ONLY valid JSON, no markdown fences, no commentary. " +
        "The JSON must conform exactly to this schema:\n" +
        "{\n" +
        "  \"width\": 1000,\n" +
        "  \"height\": 1000,\n" +
        "  \"blocks\": [\n" +
        "    {\n" +
        "      \"type\": \"<text|title|table|header|footer|signature>\",\n" +
        "      \"content\": \"<exact text as printed, preserve line breaks with \\n>\",\n" +
        "      \"x0\": <left edge 0-1000>,\n" +
        "      \"y0\": <top edge 0-1000>,\n" +
        "      \"x1\": <right edge 0-1000>,\n" +
        "      \"y1\": <bottom edge 0-1000>\n" +
        "    }\n" +
        "  ]\n" +
        "}\n" +
        "Rules:\n" +
        "- width and height are always 1000 (normalised coordinate space).\n" +
        "- List blocks in reading order (top-to-bottom, left-to-right).\n" +
        "- type must be one of: text, title, table, header, footer, signature.\n" +
        "- For tables, render the content as a Markdown pipe table (| col1 | col2 |).\n" +
        "- Preserve the exact text including numbers, punctuation, and special characters.\n" +
        "- Do NOT summarise, translate, or add any commentary.\n" +
        "- Coordinates must reflect the actual position on the page (y0=0 is top, y1=1000 is bottom).\n" +
        "- If a block spans only part of the page width, reflect that in x0/x1.\n" +
        "- Do not merge separate paragraphs into one block; each paragraph is its own block.\n" +
        "- If the page is blank or unreadable, return: {\"width\":1000,\"height\":1000,\"blocks\":[]}";

    /** User prompt for single-image block OCR. */
    static final String OCR_BLOCKS_USER =
        "Extract all text from this document page image into the JSON block format described in the system prompt. " +
        "Return only the JSON object, nothing else.";

    /** User prompt for PDF page OCR — {PAGE} and {LANG} are replaced at call time. */
    static final String OCR_BLOCKS_USER_PAGE =
        "Extract all text from page {PAGE} of this document image into the JSON block format described in the system prompt. " +
        "The document language is: {LANG}. " +
        "Return only the JSON object, nothing else.";

    // ------------------------------------------------------------------ OCR response parser

    /**
     * Parses the model's raw response into the provider-agnostic OCR blocks format.
     *
     * <p>Handles three cases:
     * <ol>
     *   <li>Well-formed JSON with a {@code blocks} array — used directly.</li>
     *   <li>JSON wrapped in a markdown code fence (```json ... ```) — stripped then parsed.</li>
     *   <li>Completely malformed — the whole response text becomes a single full-page text block.</li>
     * </ol>
     */
    static JSONObject parseOcrBlocksResponse(String raw, String providerName, String model) {
        if (raw == null || raw.isBlank()) {
            log.warn("OCR blocks ({}): empty response from model {}", providerName, model);
            return emptyPage();
        }

        // Strip markdown code fences if present (```json ... ```)
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int first = cleaned.indexOf('\n');
            int last  = cleaned.lastIndexOf("```");
            if (first >= 0 && last > first) {
                cleaned = cleaned.substring(first + 1, last).trim();
            }
        }

        JSONObject j;
        try {
            j = new JSONObject(cleaned);
        } catch (Exception e) {
            log.warn("OCR blocks ({}): model {} returned non-JSON — treating as single text block. First 200 chars: {}",
                    providerName, model, Json.abbreviate(raw, 200));
            // Fallback: entire response as one text block covering the full page
            return singleBlockPage(raw.trim());
        }

        JSONArray blocks = j.optJSONArray("blocks");
        if (blocks == null) {
            log.warn("OCR blocks ({}): model {} response missing 'blocks' array — treating as single text block",
                    providerName, model);
            // Maybe the model returned a flat text field
            String content = j.optString("content", j.optString("text", ""));
            return content.isEmpty() ? emptyPage() : singleBlockPage(content);
        }

        // Validate and normalise each block
        JSONArray normalised = new JSONArray();
        double pw = j.optDouble("width",  1000);
        double ph = j.optDouble("height", 1000);
        if (pw <= 0) pw = 1000;
        if (ph <= 0) ph = 1000;

        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) continue;
            String content = b.optString("content", b.optString("text", "")).trim();
            if (content.isEmpty()) continue;

            String type = b.optString("type", "text").toLowerCase();
            // Validate type against known values
            if (!KNOWN_BLOCK_TYPES.contains(type)) type = "text";

            double x0 = clamp(b.optDouble("x0", 0),    0, pw);
            double y0 = clamp(b.optDouble("y0", 0),    0, ph);
            double x1 = clamp(b.optDouble("x1", pw),   0, pw);
            double y1 = clamp(b.optDouble("y1", ph),   0, ph);

            // Ensure x0 < x1 and y0 < y1
            if (x1 <= x0) x1 = Math.min(pw, x0 + 100);
            if (y1 <= y0) y1 = Math.min(ph, y0 + 20);

            normalised.put(new JSONObject()
                    .put("type",    type)
                    .put("content", content)
                    .put("x0",      x0)
                    .put("y0",      y0)
                    .put("x1",      x1)
                    .put("y1",      y1));
        }

        if (normalised.isEmpty()) {
            log.warn("OCR blocks ({}): model {} returned blocks array but all were empty", providerName, model);
            return emptyPage();
        }

        return new JSONObject()
                .put("width",  pw)
                .put("height", ph)
                .put("blocks", normalised);
    }

    private static final java.util.Set<String> KNOWN_BLOCK_TYPES =
            java.util.Set.of("text", "title", "table", "header", "footer", "signature");

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static JSONObject emptyPage() {
        return new JSONObject().put("width", 1000).put("height", 1000).put("blocks", new JSONArray());
    }

    private static JSONObject singleBlockPage(String content) {
        JSONArray blocks = new JSONArray();
        blocks.put(new JSONObject()
                .put("type",    "text")
                .put("content", content)
                .put("x0",      0).put("y0", 0)
                .put("x1",   1000).put("y1", 1000));
        return new JSONObject().put("width", 1000).put("height", 1000).put("blocks", blocks);
    }
}
