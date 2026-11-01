package ai;

import util.HttpJson;
import util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Mistral OCR API client.
 *
 * Mistral OCR:
 *   POST https://api.mistral.ai/v1/ocr
 *
 * Expected response (markdown mode):
 *
 * {
 *   "pages": [
 *     {
 *       "index": 0,
 *       "markdown": "..."
 *     }
 *   ]
 * }
 *
 * The client returns the Markdown content from all pages.
 *
 * Block mode (include_blocks=true, requires OCR 4 / mistral-ocr-4-0 or newer, e.g. "mistral-ocr-latest"):
 * each page additionally carries a "blocks" array (paragraph-level bounding boxes + block label, in reading
 * order) and a "dimensions" object with the page width/height the boxes are expressed in. This is what
 * {@link #ocrBlocks(String, String)} uses to give callers REAL geometry instead of a model's pixel guess.
 *
 * Java 11 compatible.
 */
public final class MistralOcrClient extends BaseClient {

    private static final Logger log =
            LogManager.getLogger(MistralOcrClient.class);

    private static final String DEFAULT_OCR_PATH =
            "/v1/ocr";

    public MistralOcrClient(
            String name,
            JSONObject cfg) {

        super(name, cfg);
    }

    /**
     * Mistral OCR is an OCR-only provider.
     */
    @Override
    public String chat(
            ChatRequest request) throws IOException {

        throw new IOException(
                "Provider '" + name +
                        "' (mistral-ocr) supports OCR only, not chat"
        );
    }

    @Override
    public boolean supportsOcr() {
        return true;
    }

    @Override
    public boolean supportsBlockOcr() {
        return true;
    }

    /**
     * Builds Mistral OCR endpoint URL.
     */
    private String ocrUrl() {

        String path = cfg.optString(
                "ocrPath",
                DEFAULT_OCR_PATH
        );

        if (path == null ||
                path.trim().isEmpty()) {

            path = DEFAULT_OCR_PATH;
        }

        path = path.trim();

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return baseUrl + path;
    }

    /**
     * Executes Mistral OCR.
     *
     * @param model     Mistral OCR model
     * @param pngBase64 PNG image encoded as Base64
     * @return extracted Markdown
     */
    @Override
    public String ocr(
            String model,
            String pngBase64) throws IOException {

        validateInput(
                model,
                pngBase64
        );

        model = model.trim();

        String url = ocrUrl();

        log.debug(
                "Mistral OCR request: provider={}, url={}, model={}",
                name,
                url,
                model
        );

        JSONObject document =
                new JSONObject();

        document.put(
                "type",
                "image_url"
        );

        document.put(
                "image_url",
                normalizeImageDataUrl(
                        pngBase64
                )
        );

        JSONObject body =
                new JSONObject();

        body.put(
                "model",
                model
        );

        body.put(
                "document",
                document
        );

        body.put(
                "include_image_base64",
                false
        );

        String tableFormat =
                cfg.optString(
                        "tableFormat",
                        "markdown"
                );

        if (tableFormat == null ||
                tableFormat.trim().isEmpty()) {

            tableFormat = "markdown";
        }

        body.put(
                "table_format",
                tableFormat.trim()
        );

        JSONObject response;

        try {

            response = HttpJson.post(
                    url,
                    headers(),
                    body,
                    timeoutSec,
                    retries
            );

        } catch (IOException e) {

            throw new IOException(
                    "Mistral OCR request failed. " +
                            "URL=" + url +
                            ", model=" + model +
                            ", error=" + e.getMessage(),
                    e
            );
        }

        if (response == null) {

            throw new IOException(
                    "Mistral OCR returned null response. " +
                            "URL=" + url +
                            ", model=" + model
            );
        }

        if (response.has("error") &&
                !response.isNull("error")) {

            throw new IOException(
                    "Mistral OCR error: " +
                            Json.abbreviate(
                                    response
                                            .get("error")
                                            .toString(),
                                    2000
                            )
            );
        }

        JSONArray pages =
                response.optJSONArray(
                        "pages"
                );

        if (pages == null) {

            throw new IOException(
                    "Unexpected Mistral OCR response. " +
                            "Expected 'pages' array. " +
                            "Response=" +
                            Json.abbreviate(
                                    response.toString(),
                                    3000
                            )
            );
        }

        if (pages.length() == 0) {

            throw new IOException(
                    "Mistral OCR returned zero pages. " +
                            "Model=" + model
            );
        }

        List<PageMarkdown> markdownPages =
                new ArrayList<>();

        for (int i = 0;
             i < pages.length();
             i++) {

            JSONObject page =
                    pages.optJSONObject(i);

            if (page == null) {
                continue;
            }

            int index =
                    page.optInt(
                            "index",
                            i
                    );

            String markdown =
                    page.optString(
                            "markdown",
                            ""
                    );

            markdown =
                    normalizeMarkdown(
                            markdown
                    );

            if (markdown.isEmpty()) {

                log.warn(
                        "Mistral OCR returned empty markdown for page {}",
                        index
                );

                continue;
            }

            markdownPages.add(
                    new PageMarkdown(
                            index,
                            markdown
                    )
            );
        }

        if (markdownPages.isEmpty()) {

            throw new IOException(
                    "Mistral OCR returned pages but no Markdown content. " +
                            "Model=" + model +
                            ", pages=" + pages.length() +
                            ", response=" +
                            Json.abbreviate(
                                    response.toString(),
                                    3000
                            )
            );
        }

        markdownPages.sort(
                Comparator.comparingInt(
                        PageMarkdown::getIndex
                )
        );

        StringBuilder result =
                new StringBuilder();

        for (PageMarkdown page :
                markdownPages) {

            if (result.length() > 0) {

                result.append("\n\n");
                result.append("<hr/>");
                result.append("\n\n");
            }

            result.append(
                    page.getMarkdown()
            );
        }

        String text =
                normalizeMarkdown(
                        result.toString()
                );

        if (text.isEmpty()) {

            throw new IOException(
                    "Mistral OCR returned empty Markdown after merge. " +
                            "Model=" + model
            );
        }

        log.info(
                "Mistral OCR completed: provider={}, model={}, " +
                        "pages={}, markdownLength={}",
                name,
                model,
                markdownPages.size(),
                text.length()
        );

        return text;
    }

    /**
     * Block-aware OCR of a single image: same request as {@link #ocr}, plus {@code include_blocks=true}.
     * Returns the provider-agnostic {width, height, blocks[]} contract described in {@link AiClient#ocrBlocks}.
     *
     * NOTE: the raw Mistral field names below (top_left_x/y, bottom_right_x/y, dimensions.width/height,
     * block.content) are reconstructed from Mistral's public docs for OCR 4 / 4.1 "structured blocks".
     * Log a sample raw response once against your live API key/model and adjust the four optDouble/optString
     * keys below if your account's exact response shape differs.
     */
    @Override
    public JSONObject ocrBlocks(
            String model,
            String pngBase64) throws IOException {

        validateInput(model, pngBase64);
        model = model.trim();

        String url = ocrUrl();

        JSONObject document = new JSONObject()
                .put("type", "image_url")
                .put("image_url", normalizeImageDataUrl(pngBase64));

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("document", document);
        body.put("include_image_base64", false);
        body.put("include_blocks", true);

        String tableFormat = cfg.optString("tableFormat", "markdown");
        body.put("table_format", tableFormat == null || tableFormat.trim().isEmpty() ? "markdown" : tableFormat.trim());

        JSONObject response;
        try {
            response = HttpJson.post(url, headers(), body, timeoutSec, retries);
        } catch (IOException e) {
            throw new IOException("Mistral OCR block request failed. URL=" + url + ", model=" + model
                    + ", error=" + e.getMessage(), e);
        }

        if (response == null) {
            throw new IOException("Mistral OCR (blocks) returned null response. URL=" + url + ", model=" + model);
        }
        if (response.has("error") && !response.isNull("error")) {
            throw new IOException("Mistral OCR error: " + Json.abbreviate(response.get("error").toString(), 2000));
        }

        JSONArray pages = response.optJSONArray("pages");
        if (pages == null || pages.length() == 0) {
            throw new IOException("Mistral OCR (blocks) returned no pages. Model=" + model
                    + ", response=" + Json.abbreviate(response.toString(), 2000));
        }

        JSONObject page = pages.getJSONObject(0);      // one image submitted -> exactly one page
        JSONObject dims = page.optJSONObject("dimensions");
        double width = dims != null ? dims.optDouble("width", 0) : 0;
        double height = dims != null ? dims.optDouble("height", 0) : 0;

        JSONArray rawBlocks = page.optJSONArray("blocks");
        JSONArray blocks = new JSONArray();
        if (rawBlocks == null) {
            log.warn("Mistral OCR (blocks): no 'blocks' array in response - is include_blocks honoured by model {} "
                    + "(needs OCR 4 / mistral-ocr-4-0 or newer)?", model);
        } else {
            for (int i = 0; i < rawBlocks.length(); i++) {
                JSONObject b = rawBlocks.optJSONObject(i);
                if (b == null) {
                    continue;
                }
                String blockContent = normalizeMarkdown(
                        b.optString("content", b.optString("text", "")));
                blocks.put(new JSONObject()
                        .put("type", b.optString("type", "text"))
                        .put("content", blockContent)
                        .put("x0", b.optDouble("top_left_x", 0))
                        .put("y0", b.optDouble("top_left_y", 0))
                        .put("x1", b.optDouble("bottom_right_x", width))
                        .put("y1", b.optDouble("bottom_right_y", height)));
            }
        }

        if (width <= 0 || height <= 0) {
            // No page dimensions reported: fall back to treating x0..y1 as already 0..1000 so the caller's
            // normalization (divide by width/height) is a no-op instead of a divide-by-zero.
            width = 1000;
            height = 1000;
            log.warn("Mistral OCR (blocks) response had no page 'dimensions' - block coordinates may be "
                    + "misaligned with the rendered page image; verify against a live response");
        }

        return new JSONObject().put("width", width).put("height", height).put("blocks", blocks);
    }

    private static void validateInput(
            String model,
            String pngBase64) throws IOException {

        if (model == null ||
                model.trim().isEmpty()) {

            throw new IOException(
                    "Mistral OCR model is missing"
            );
        }

        if (pngBase64 == null ||
                pngBase64.trim().isEmpty()) {

            throw new IOException(
                    "Mistral OCR image is empty"
            );
        }
    }

    private static String normalizeImageDataUrl(
            String value) {

        String data =
                value.trim();

        if (data.regionMatches(
                true,
                0,
                "data:",
                0,
                5
        )) {

            return data;
        }

        return "data:image/png;base64," + data;
    }

    private static String normalizeMarkdown(
            String markdown) {

        if (markdown == null) {
            return "";
        }

        String value =
                markdown
                        .replace("\r\n", "\n")
                        .replace('\r', '\n');

        value = value
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFEFF", "");

        value = value.replaceAll(
                "\n{4,}",
                "\n\n"
        );

        // Strip Markdown image references produced by Mistral OCR (e.g. ![img-0.jpeg](img-0.jpeg)).
        // These bare filenames cannot be resolved by openhtmltopdf and have no useful content
        // in a contract analysis report — the actual page scan is already embedded as a base64 image.
        // Block-level images (own line):
        value = value.replaceAll(
                "(?m)^\\s*!\\[[^]]*]\\([^)]*\\)\\s*$\\n?",
                ""
        );
        // Inline images (mid-paragraph):
        value = value.replaceAll(
                "!\\[[^]]*]\\([^)]*\\)",
                ""
        );

        return value.trim();
    }

    private static final class PageMarkdown {

        private final int index;
        private final String markdown;

        private PageMarkdown(
                int index,
                String markdown) {

            this.index = index;
            this.markdown = markdown;
        }

        private int getIndex() {
            return index;
        }

        private String getMarkdown() {
            return markdown;
        }
    }
}
