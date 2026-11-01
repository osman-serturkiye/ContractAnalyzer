package ai;

import util.HttpJson;
import util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Microsoft Azure AI Foundry / Microsoft Foundry client.
 *
 * Uses the OpenAI-compatible chat completions API.
 *
 * Expected configuration:
 *
 * {
 *   "baseUrl": "https://<resource>.openai.azure.com",
 *   "chatPath": "/openai/deployments/{model}/chat/completions",
 *   "query": "api-version=2024-10-21",
 *   "apiKeyEnv": "AZURE_OPENAI_API_KEY",
 *   "authHeader": "api-key",
 *   "authScheme": "",
 *   "timeoutSec": 180,
 *   "maxRetries": 3,
 *   "supportsJsonMode": true
 * }
 */
public final class MSFoundryClient extends BaseClient {

    private static final Logger log = LogManager.getLogger();

    public MSFoundryClient(String name, JSONObject cfg) {
        super(name, cfg);
    }

    /**
     * Returns true when {@code "blockOcr": true} is set in the provider config.
     * GPT-5/4.1-class vision models can transcribe a page image; we split the
     * resulting markdown into sequential blocks and assign synthetic vertical
     * bounding-boxes (0..1000 × 0..1000).  The coordinates are not pixel-accurate
     * but PdfClauseExtractor only needs reading order, not exact pixel positions.
     */
    @Override
    public boolean supportsBlockOcr() {
        return cfg.optBoolean("blockOcr", false);
    }

    /**
     * Vision-based block OCR: sends the page image to the deployed vision model,
     * receives a structured JSON response containing both the transcribed text and
     * approximate bounding box coordinates for every block. Falls back to markdown
     * splitting with synthetic coordinates if the model does not return valid JSON.
     *
     * Output contract matches {@link AiClient#ocrBlocks}: width=1000, height=1000,
     * blocks[].{type, content, x0, y0, x1, y1}.
     *
     * The model is asked to return coordinates normalized to 0..1000 (where 0,0 is
     * top-left and 1000,1000 is bottom-right of the page).  If a block's coordinates
     * are missing or clearly wrong the block is still included with a synthetic
     * fallback band so that text extraction is never lost.
     */
    @Override
    public JSONObject ocrBlocks(String model, String pngBase64) throws IOException {
        if (pngBase64 == null || pngBase64.trim().isEmpty()) {
            throw new IOException("MSFoundryClient.ocrBlocks: image is empty");
        }

        ChatRequest r = new ChatRequest();
        r.model = model;
        r.system = "You are an OCR engine. You only output JSON.";
        r.user = "Transcribe this page image into a JSON object.\n"
                + "Rules:\n"
                + "1. Identify every distinct text block on the page (headings, paragraphs, tables, "
                + "list items, footnotes, headers, footers).\n"
                + "2. For each block provide:\n"
                + "   - type: one of text | title | table | header | footer | signature | handwriting\n"
                + "   - content: full transcription; tables as GitHub-flavoured Markdown.\n"
                + "   - x0, y0, x1, y1: bounding box coordinates normalised to 0..1000 "
                + "(0,0 = top-left corner of the page; 1000,1000 = bottom-right corner). "
                + "x0<x1, y0<y1.\n"
                + "3. Keep all numbering exactly as printed.\n"
                + "4. Return blocks in top-to-bottom, left-to-right reading order.\n"
                + "Return JSON only: "
                + "{\"blocks\":[{\"type\":\"text\",\"content\":\"...\","
                + "\"x0\":0,\"y0\":0,\"x1\":1000,\"y1\":50}]}";
        r.images.add(pngBase64);
        r.jsonMode = true;

        String response = chat(r);

        // Parse structured JSON response
        try {
            JSONObject parsed = new JSONObject(response);
            JSONArray rawBlocks = parsed.optJSONArray("blocks");
            if (rawBlocks != null && rawBlocks.length() > 0) {
                JSONArray validatedBlocks = validateAndFillCoords(rawBlocks);
                return new JSONObject()
                        .put("width",  1000)
                        .put("height", 1000)
                        .put("blocks", validatedBlocks);
            }
        } catch (Exception e) {
            // JSON parse failed — fall through to markdown fallback below
        }

        // Fallback: treat response as plain markdown and split into synthetic bands
        return markdownToBlocks(response);
    }

    /**
     * Validates each block's bounding box coordinates.
     * Coordinates must be in [0, 1000] with x0 < x1 and y0 < y1.
     * Blocks with invalid or missing coordinates are assigned synthetic sequential
     * vertical bands as a fallback so no text is lost.
     */
    static JSONArray validateAndFillCoords(JSONArray rawBlocks) {
        int total = rawBlocks.length();
        JSONArray out = new JSONArray();
        for (int i = 0; i < total; i++) {
            JSONObject b = rawBlocks.optJSONObject(i);
            if (b == null) continue;

            double x0 = b.optDouble("x0", Double.NaN);
            double y0 = b.optDouble("y0", Double.NaN);
            double x1 = b.optDouble("x1", Double.NaN);
            double y1 = b.optDouble("y1", Double.NaN);

            boolean valid = Double.isFinite(x0) && Double.isFinite(y0)
                    && Double.isFinite(x1) && Double.isFinite(y1)
                    && x0 >= 0 && y0 >= 0 && x1 <= 1000 && y1 <= 1000
                    && x0 < x1 && y0 < y1;

            if (!valid) {
                // Assign a synthetic vertical band for this block
                x0 = 0;
                x1 = 1000;
                y0 = (double) i / total * 1000.0;
                y1 = (double) (i + 1) / total * 1000.0;
            }

            out.put(new JSONObject()
                    .put("type",    b.optString("type", "text"))
                    .put("content", b.optString("content", ""))
                    .put("x0", x0)
                    .put("y0", y0)
                    .put("x1", x1)
                    .put("y1", y1));
        }
        return out;
    }

    /**
     * Splits a markdown string into logical blocks and wraps them in the
     * provider-agnostic ocrBlocks output format (width=1000, height=1000).
     *
     * Splitting rules (applied in order):
     *  - A line starting with one or more '#' characters → type "title"
     *  - A line starting with '|' or a GFM table separator (---|:---:) → accumulated
     *    with adjacent table lines into one type "table" block
     *  - Blank lines act as paragraph separators
     *  - Everything else → type "text"
     *
     * Bounding boxes are synthetic sequential horizontal bands:
     *   y0 = (blockIndex / totalBlocks) * 1000
     *   y1 = ((blockIndex+1) / totalBlocks) * 1000
     *   x0 = 0, x1 = 1000
     */
    static JSONObject markdownToBlocks(String markdown) {
        if (markdown == null) markdown = "";

        // Collect raw segments: each entry is {type, content}
        java.util.List<String[]> segments = new java.util.ArrayList<>();
        String[] lines = markdown.split("\n", -1);

        StringBuilder current = new StringBuilder();
        String currentType = "text";
        boolean inTable = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Table line detection
            boolean isTableLine = trimmed.startsWith("|")
                    || trimmed.matches("[|:\\-\\s]+"); // separator row

            if (isTableLine) {
                if (!inTable) {
                    // flush previous non-table block
                    if (current.length() > 0) {
                        segments.add(new String[]{currentType, current.toString().trim()});
                        current.setLength(0);
                    }
                    inTable = true;
                    currentType = "table";
                }
                current.append(line).append("\n");
                continue;
            }

            // If we were in a table and hit a non-table line, flush the table
            if (inTable) {
                segments.add(new String[]{"table", current.toString().trim()});
                current.setLength(0);
                inTable = false;
                currentType = "text";
            }

            // Heading
            if (trimmed.startsWith("#")) {
                if (current.length() > 0) {
                    segments.add(new String[]{currentType, current.toString().trim()});
                    current.setLength(0);
                }
                segments.add(new String[]{"title", trimmed.replaceFirst("^#+\\s*", "")});
                currentType = "text";
                continue;
            }

            // Blank line → paragraph boundary
            if (trimmed.isEmpty()) {
                if (current.length() > 0) {
                    segments.add(new String[]{currentType, current.toString().trim()});
                    current.setLength(0);
                    currentType = "text";
                }
                continue;
            }

            current.append(line).append("\n");
        }

        // Flush remainder
        if (current.length() > 0) {
            segments.add(new String[]{currentType, current.toString().trim()});
        }

        // Remove empty segments
        java.util.List<String[]> blocks = new java.util.ArrayList<>();
        for (String[] seg : segments) {
            if (seg[1] != null && !seg[1].isEmpty()) {
                blocks.add(seg);
            }
        }

        int total = Math.max(blocks.size(), 1);
        JSONArray blocksJson = new JSONArray();
        for (int i = 0; i < blocks.size(); i++) {
            double y0 = (double) i / total * 1000.0;
            double y1 = (double) (i + 1) / total * 1000.0;
            blocksJson.put(new JSONObject()
                    .put("type",    blocks.get(i)[0])
                    .put("content", blocks.get(i)[1])
                    .put("x0", 0.0)
                    .put("y0", y0)
                    .put("x1", 1000.0)
                    .put("y1", y1));
        }

        return new JSONObject()
                .put("width",  1000)
                .put("height", 1000)
                .put("blocks", blocksJson);
    }

    private String url(String model) {
        String path = cfg.optString(
                "chatPath",
                "/openai/deployments/{model}/chat/completions"
        ).replace("{model}", model);

        String q = cfg.optString("query", "");

        return baseUrl
                + path
                + (q.isEmpty() ? "" : "?" + q);
    }

    @Override
    public String chat(ChatRequest r) throws IOException {

        JSONObject body = new JSONObject();

        /*
         * Azure / Microsoft Foundry deployment based endpoint:
         *
         * /openai/deployments/{deployment}/chat/completions
         *
         * Therefore model is already part of the URL.
         *
         * Some Foundry-compatible endpoints also accept "model".
         * Make this configurable.
         */
        if (cfg.optBoolean("sendModelInBody", false)) {
            body.put("model", r.model);
        }

        JSONArray messages = new JSONArray();

        if (r.system != null && !r.system.isEmpty()) {
            messages.put(
                    new JSONObject()
                            .put("role", "system")
                            .put("content", r.system)
            );
        }

        Object content;

        if (r.images == null || r.images.isEmpty()) {

            content = r.user;

        } else {

            JSONArray parts = new JSONArray();

            parts.put(
                    new JSONObject()
                            .put("type", "text")
                            .put("text", r.user)
            );

            for (String img : r.images) {

                parts.put(
                        new JSONObject()
                                .put("type", "image_url")
                                .put(
                                        "image_url",
                                        new JSONObject()
                                                .put(
                                                        "url",
                                                        "data:image/png;base64," + img
                                                )
                                )
                );
            }

            content = parts;
        }

        messages.put(
                new JSONObject()
                        .put("role", "user")
                        .put("content", content)
        );

        body.put("messages", messages);

        /*
         * Temperature
         */
        if (r.temperature != null) {
            body.put(
                    "temperature",
                    r.temperature.doubleValue()
            );
        }

        /*
         * Max tokens
         *
         * Azure / Foundry may use different parameter names
         * depending on the deployed model/API version.
         */
        if (r.maxTokens != null) {

            String maxTokensParam =
                    cfg.optString(
                            "maxTokensParam",
                            "max_tokens"
                    );

            body.put(
                    maxTokensParam,
                    r.maxTokens.intValue()
            );
        }

        /*
         * JSON mode
         */
        if (r.jsonMode &&
                cfg.optBoolean("supportsJsonMode", true)) {

            body.put(
                    "response_format",
                    new JSONObject()
                            .put("type", "json_object")
            );
        }

        JSONObject resp = HttpJson.post(
                url(r.model),
                headers(),
                body,
                timeoutSec,
                retries
        );

        /*
         * Provider error
         */
        if (resp.has("error") &&
                !resp.isNull("error")) {

            throw new IOException(
                    "Microsoft Foundry " + name +
                            " error: " +
                            Json.abbreviate(
                                    resp.get("error").toString(),
                                    500
                            )
            );
        }

        /*
         * OpenAI-compatible response:
         *
         * {
         *   "choices": [
         *     {
         *       "message": {
         *         "content": "..."
         *       }
         *     }
         *   ]
         * }
         */
        JSONArray choices =
                resp.optJSONArray("choices");

        if (choices == null ||
                choices.length() == 0) {

            throw new IOException(
                    "No choices from Microsoft Foundry " +
                            name + ": " +
                            Json.abbreviate(
                                    resp.toString(),
                                    500
                            )
            );
        }

        JSONObject choice =
                choices.getJSONObject(0);

        // Log finish_reason so we can distinguish content_filter from length truncation.
        String finishReason = choice.optString("finish_reason", "");
        boolean truncated = "length".equals(finishReason);
        if (!finishReason.isEmpty()
                && !"stop".equals(finishReason)
                && !"end_turn".equals(finishReason)) {
            // content_filter or length: the model stopped abnormally.
            // We still try to extract whatever content was returned before throwing.
            log.warn("Microsoft Foundry {} ({}): finish_reason={}", name, r.model, finishReason);
        }

        JSONObject msg =
                choice.optJSONObject("message");

        Object c =
                msg == null
                        ? null
                        : msg.opt("content");

        String text;

        if (c instanceof JSONArray) {

            StringBuilder sb =
                    new StringBuilder();

            JSONArray arr =
                    (JSONArray) c;

            for (int i = 0;
                 i < arr.length();
                 i++) {

                JSONObject item =
                        arr.optJSONObject(i);

                if (item != null) {
                    sb.append(
                            item.optString(
                                    "text",
                                    ""
                            )
                    );
                }
            }

            text = sb.toString();

        } else {

            text =
                    c == null ||
                            JSONObject.NULL.equals(c)
                            ? ""
                            : c.toString();
        }

        if (text.trim().isEmpty()) {
            String reason = finishReason.isEmpty() ? "no content" : "finish_reason=" + finishReason;
            throw new IOException(
                    "Empty answer from Microsoft Foundry " +
                            name +
                            " (model/deployment " +
                            r.model +
                            ", " + reason + ")"
            );
        }

        // When the model hit the output-token limit (finish_reason=length) but still returned
        // partial text, we try to salvage it. Json.parseLoose can recover a truncated JSON
        // object. We log a warning so callers know the response was cut short.
        if (truncated) {
            log.warn("Microsoft Foundry {} ({}): response truncated (finish_reason=length), {} chars returned — attempting partial parse",
                    name, r.model, text.length());
        }

        return text;
    }
}