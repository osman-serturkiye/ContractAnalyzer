package ai;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import util.HttpJson;
import util.Json;

import java.io.IOException;
import java.util.Map;

/**
 * Azure Computer Vision Read API client.
 *
 * <p>Submits the entire PDF file in a <strong>single request</strong> and returns per-page block data
 * for the whole document. This avoids rendering PDF pages to PNG images locally and sending them one
 * by one — the Azure Read API handles PDF natively, including multi-page documents.</p>
 *
 * <h2>API flow</h2>
 * <ol>
 *   <li>{@code POST {endpoint}/vision/v3.2/read/analyze}
 *       with {@code Content-Type: application/pdf} and the raw PDF bytes as the body.</li>
 *   <li>Response: HTTP 202 + {@code Operation-Location} header containing the polling URL.</li>
 *   <li>Poll {@code Operation-Location} with GET until {@code status == "succeeded"}.</li>
 *   <li>Parse {@code analyzeResult.readResults[]} — one entry per page — into the provider-agnostic
 *       {@link AiClient#ocrPdfBlocks} output contract.</li>
 * </ol>
 *
 * <h2>Provider config keys</h2>
 * <pre>
 * "type"            : "azure-cv-ocr"
 * "baseUrl"         : "https://&lt;resource&gt;.cognitiveservices.azure.com"
 * "apiKeyEnv"       : "AZURE_CV_KEY"              // env var holding the subscription key
 * "authHeader"      : "Ocp-Apim-Subscription-Key" // set explicitly — no "Bearer " prefix
 * "authScheme"      : ""                           // empty = key sent as-is
 * "apiVersion"      : "3.2"                        // Read API version (default "3.2")
 * "timeoutSec"      : 120
 * "maxRetries"      : 3
 * "pollIntervalMs"  : 3000                         // ms between status polls (default 3000)
 * "pollMaxAttempts" : 60                           // give up after N polls (default 60 = ~3 min)
 * </pre>
 *
 * <h2>Example config.json snippet</h2>
 * <pre>{@code
 * "providers": {
 *   "azure_cv_ocr_1": {
 *     "type"       : "azure-cv-ocr",
 *     "baseUrl"    : "https://my-resource.cognitiveservices.azure.com",
 *     "apiKeyEnv"  : "AZURE_CV_KEY",
 *     "authHeader" : "Ocp-Apim-Subscription-Key",
 *     "authScheme" : "",
 *     "timeoutSec" : 120,
 *     "maxRetries" : 3
 *   }
 * },
 * "steps": {
 *   "pdfPageBlockOcr": {
 *     "provider": "azure_cv_ocr_1",
 *     "model"   : "3.2"
 *   }
 * }
 * }</pre>
 */
public final class AzureCvOcrClient extends BaseClient {

    private static final Logger log = LogManager.getLogger(AzureCvOcrClient.class);

    // ------------------------------------------------------------------ constants

    /** Read API submit path.  Version is appended as a query parameter. */
    private static final String READ_ANALYZE_PATH = "/vision/v3.2/read/analyze";

    private static final int DEFAULT_POLL_INTERVAL_MS  = 3000;
    private static final int DEFAULT_POLL_MAX_ATTEMPTS = 60;   // 60 × 3 s = 3 min max

    // ------------------------------------------------------------------ constructor

    public AzureCvOcrClient(String name, JSONObject cfg) {
        super(name, cfg);
    }

    // ------------------------------------------------------------------ AiClient interface

    @Override
    public String chat(ChatRequest request) throws IOException {
        throw new IOException(
                "Provider '" + name + "' (azure-cv-ocr) supports PDF OCR only, not chat");
    }

    /** This provider does not support per-page PNG OCR. */
    @Override
    public boolean supportsOcr() {
        return false;
    }

    /** This provider does not support per-page block OCR (use {@link #ocrPdfBlocks} instead). */
    @Override
    public boolean supportsBlockOcr() {
        return false;
    }

    /** Azure CV sends the whole PDF at once — this is the preferred path. */
    @Override
    public boolean supportsPdfOcr() {
        return true;
    }

    // ------------------------------------------------------------------ PDF OCR (whole document)

    /**
     * Submits the full PDF and returns per-page block data.
     *
     * <p>The {@code model} parameter is accepted for interface compatibility but ignored —
     * the API version is taken from {@code cfg.apiVersion} (default "3.2").</p>
     *
     * <p>Output contract — JSON array, one object per page:
     * <pre>
     * [
     *   {
     *     "page"  : 0,            // 0-based index
     *     "width" : &lt;pixels&gt;,
     *     "height": &lt;pixels&gt;,
     *     "blocks": [
     *       {"type":"text", "content":"...", "x0":..., "y0":..., "x1":..., "y1":...},
     *       ...
     *     ]
     *   },
     *   ...
     * ]
     * </pre>
     */
    @Override
    public JSONArray ocrPdfBlocks(String model, byte[] pdfBytes) throws IOException {
        return ocrPdfBlocks(model, pdfBytes, null);
    }

    @Override
    public JSONArray ocrPdfBlocks(String model, byte[] pdfBytes, String language) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IOException("Azure CV OCR: PDF bytes are empty");
        }

        // 1. Submit PDF -------------------------------------------------------
        String submitUrl = buildSubmitUrl(language);
        log.info("Azure CV OCR: submitting PDF ({} bytes) to {} (language={})",
                pdfBytes.length, submitUrl, language != null ? language : "auto");

        JSONObject submitResp;
        try {
            submitResp = HttpJson.postBytes(submitUrl, headers(), pdfBytes,
                    "application/pdf", timeoutSec, retries);
        } catch (IOException e) {
            throw new IOException(
                    "Azure CV OCR submit failed. URL=" + submitUrl + ", error=" + e.getMessage(), e);
        }

        String operationUrl = submitResp == null ? "" : submitResp.optString("operationLocation", "").trim();
        if (operationUrl.isEmpty()) {
            throw new IOException(
                    "Azure CV OCR submit did not return Operation-Location. URL=" + submitUrl
                            + ", response=" + Json.abbreviate(
                            submitResp == null ? "null" : submitResp.toString(), 500));
        }
        log.debug("Azure CV OCR: operation URL = {}", operationUrl);

        // 2. Poll until succeeded / failed ------------------------------------
        JSONObject result = poll(operationUrl);

        // 3. Parse result into provider-agnostic per-page blocks --------------
        return parseResult(result);
    }

    // ------------------------------------------------------------------ submit URL

    private String buildSubmitUrl() {
        return buildSubmitUrl(null);
    }

    /**
     * Builds the Read API submit URL, optionally appending a {@code language} query parameter.
     *
     * <p>Azure Computer Vision Read 3.2 supports a {@code language} hint:
     * when set, the OCR engine prioritises the specified language's character repertoire,
     * which significantly improves recognition of language-specific glyphs such as
     * Turkish İ/ı, Ş, Ğ, Ç, Ö, Ü or German ü/ö/ä/ß.
     *
     * <p>Supported BCP-47 codes include {@code "tr"} (Turkish), {@code "en"} (English),
     * {@code "de"} (German), {@code "fr"} (French), {@code "ar"} (Arabic), etc.
     * Pass {@code null} or empty string to let Azure auto-detect the language.
     */
    private String buildSubmitUrl(String language) {
        String url = baseUrl + READ_ANALYZE_PATH;
        if (language != null && !language.isBlank()) {
            // Normalise: Azure expects lowercase BCP-47 codes ("tr", "en", "de" …)
            url += "?language=" + language.trim().toLowerCase();
        }
        return url;
    }

    // ------------------------------------------------------------------ polling

    private JSONObject poll(String operationUrl) throws IOException {
        int intervalMs  = cfg.optInt("pollIntervalMs",  DEFAULT_POLL_INTERVAL_MS);
        int maxAttempts = cfg.optInt("pollMaxAttempts", DEFAULT_POLL_MAX_ATTEMPTS);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while polling Azure CV OCR", ie);
            }

            JSONObject pollResp;
            try {
                pollResp = HttpJson.get(operationUrl, headers(), timeoutSec, retries);
            } catch (IOException e) {
                throw new IOException(
                        "Azure CV OCR poll failed (attempt " + attempt + "/" + maxAttempts
                                + "). URL=" + operationUrl + ", error=" + e.getMessage(), e);
            }

            String status = pollResp.optString("status", "").toLowerCase();
            log.debug("Azure CV OCR poll {}/{}: status={}", attempt, maxAttempts, status);

            switch (status) {
                case "succeeded":
                    return pollResp;
                case "failed":
                    throw new IOException(
                            "Azure CV OCR operation failed. URL=" + operationUrl
                                    + ", response=" + Json.abbreviate(pollResp.toString(), 1000));
                default:
                    // "running" or "notStarted" — keep polling
            }
        }
        throw new IOException(
                "Azure CV OCR timed out after " + maxAttempts + " polls. URL=" + operationUrl);
    }

    // ------------------------------------------------------------------ response parsing

    /**
     * Converts the succeeded poll response into the provider-agnostic per-page array.
     *
     * <p>Azure Read 3.2 response shape (under {@code analyzeResult.readResults[]}):
     * <pre>
     * {
     *   "status": "succeeded",
     *   "analyzeResult": {
     *     "readResults": [
     *       {
     *         "page": 1,              // 1-based in Azure, converted to 0-based here
     *         "width": 2480,
     *         "height": 3508,
     *         "unit": "pixel",
     *         "lines": [
     *           {
     *             "text": "MADDE 1",
     *             "boundingBox": [x0,y0, x1,y1, x2,y2, x3,y3],  // 8-element polygon
     *             "words": [...]
     *           },
     *           ...
     *         ]
     *       },
     *       ...
     *     ]
     *   }
     * }
     * </pre>
     */
    private JSONArray parseResult(JSONObject response) throws IOException {
        JSONObject analyzeResult = response.optJSONObject("analyzeResult");
        if (analyzeResult == null) {
            throw new IOException(
                    "Azure CV OCR: 'analyzeResult' missing in succeeded response. "
                            + "Response=" + Json.abbreviate(response.toString(), 1000));
        }

        JSONArray readResults = analyzeResult.optJSONArray("readResults");
        if (readResults == null || readResults.length() == 0) {
            throw new IOException(
                    "Azure CV OCR: 'analyzeResult.readResults' is empty or missing. "
                            + "Response=" + Json.abbreviate(response.toString(), 1000));
        }

        JSONArray pages = new JSONArray();

        for (int p = 0; p < readResults.length(); p++) {
            JSONObject pageResult = readResults.optJSONObject(p);
            if (pageResult == null) continue;

            // Azure uses 1-based page numbers — convert to 0-based
            int pageIndex = pageResult.optInt("page", p + 1) - 1;
            double width  = pageResult.optDouble("width",  0);
            double height = pageResult.optDouble("height", 0);
            if (width  <= 0) width  = 1000;
            if (height <= 0) height = 1000;

            JSONArray lines    = pageResult.optJSONArray("lines");
            JSONArray blocks   = linesToBlocks(lines, width, height);

            log.info("Azure CV OCR page {}: {}×{} px, {} blocks",
                    pageIndex + 1, (int) width, (int) height, blocks.length());

            pages.put(new JSONObject()
                    .put("page",   pageIndex)
                    .put("width",  width)
                    .put("height", height)
                    .put("blocks", blocks));
        }

        if (pages.isEmpty()) {
            throw new IOException("Azure CV OCR: parsed 0 pages from analyzeResult");
        }
        return pages;
    }

    // ------------------------------------------------------------------ line → block conversion

    /**
     * Converts an array of Azure line objects into provider-agnostic block objects.
     *
     * <p>Azure Read 3.2 uses an 8-element {@code boundingBox} array:
     * [x0,y0, x1,y1, x2,y2, x3,y3] (four corners, clockwise from top-left).</p>
     */
    private static JSONArray linesToBlocks(JSONArray lines, double pageWidth, double pageHeight) {
        JSONArray blocks = new JSONArray();
        if (lines == null) return blocks;

        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) continue;

            String text = line.optString("text", line.optString("content", "")).trim();
            if (text.isEmpty()) continue;

            double[] bbox = extractBoundingBox(line, pageWidth, pageHeight);

            blocks.put(new JSONObject()
                    .put("type",    "text")
                    .put("content", text)
                    .put("x0",      bbox[0])
                    .put("y0",      bbox[1])
                    .put("x1",      bbox[2])
                    .put("y1",      bbox[3]));
        }
        return blocks;
    }

    /**
     * Extracts [x0, y0, x1, y1] from a line object.
     *
     * <ul>
     *   <li>8-element {@code boundingBox}: [x0,y0, x1,y1, x2,y2, x3,y3] — take min/max.</li>
     *   <li>4-element {@code boundingBox}: [x, y, w, h].</li>
     *   <li>{@code boundingPolygon}: array of {@code {x, y}} objects (newer API shape).</li>
     * </ul>
     * Falls back to full-page dimensions when nothing is recognised.
     */
    private static double[] extractBoundingBox(JSONObject line, double pageWidth, double pageHeight) {
        // ---- 8-element or 4-element flat array (Read 3.2 primary shape)
        JSONArray bb = line.optJSONArray("boundingBox");
        if (bb != null && bb.length() >= 4) {
            if (bb.length() == 4) {
                // [x, y, width, height]
                double x = bb.optDouble(0, 0);
                double y = bb.optDouble(1, 0);
                double w = bb.optDouble(2, 0);
                double h = bb.optDouble(3, 0);
                return new double[]{x, y, x + w, y + h};
            } else {
                // 8-element polygon: x0,y0, x1,y1, x2,y2, x3,y3
                double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                for (int i = 0; i + 1 < bb.length(); i += 2) {
                    double px = bb.optDouble(i,     0);
                    double py = bb.optDouble(i + 1, 0);
                    if (px < minX) minX = px;
                    if (py < minY) minY = py;
                    if (px > maxX) maxX = px;
                    if (py > maxY) maxY = py;
                }
                return new double[]{minX, minY, maxX, maxY};
            }
        }

        // ---- boundingPolygon: [{x, y}, ...] (newer API versions)
        JSONArray polygon = line.optJSONArray("boundingPolygon");
        if (polygon != null && polygon.length() >= 2) {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int i = 0; i < polygon.length(); i++) {
                JSONObject pt = polygon.optJSONObject(i);
                if (pt == null) continue;
                double px = pt.optDouble("x", 0);
                double py = pt.optDouble("y", 0);
                if (px < minX) minX = px;
                if (py < minY) minY = py;
                if (px > maxX) maxX = px;
                if (py > maxY) maxY = py;
            }
            if (maxX > minX) return new double[]{minX, minY, maxX, maxY};
        }

        // Fallback: full page
        return new double[]{0, 0, pageWidth, pageHeight};
    }
}
