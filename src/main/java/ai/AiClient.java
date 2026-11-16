package ai;

import org.json.JSONObject;

import java.io.IOException;

public interface AiClient {
    String chat(ChatRequest request) throws IOException;

    /**
     * Chat with provider-reported token counts (prompt + completion).
     * Default delegates to {@link #chat(ChatRequest)} with token counts -1 (unknown).
     * Override in providers that return usage metadata.
     */
    default ChatResult chatWithUsage(ChatRequest request) throws IOException {
        return new ChatResult(chat(request), -1, -1);
    }

    default boolean supportsOcr() {
        return false;
    }

    /** Dedicated OCR endpoint (e.g. mistral-ocr). Input: base64 PNG. Output: markdown. */
    default String ocr(String model, String pngBase64) throws IOException {
        throw new UnsupportedOperationException("OCR endpoint not supported by this provider");
    }

    default boolean supportsBlockOcr() {
        return false;
    }

    /**
     * Block-aware OCR of one page image: returns REAL, already-accurate bounding boxes per content block
     * instead of plain markdown, so callers never have to guess clause geometry (contrast with the old
     * ruler + vision-LLM approach in PdfClauseCropper, which asked a model to estimate pixel positions).
     *
     * Output contract (provider-agnostic):
     * {
     *   "width": <page width in the OCR engine's own unit>,
     *   "height": <page height, same unit>,
     *   "blocks": [
     *     {"type": "text|table|title|signature|...", "content": "...", "x0":.., "y0":.., "x1":.., "y1":..},
     *     ...
     *   ]
     * }
     * x0/y0/x1/y1 are in the SAME unit as width/height (whatever the provider reports) - callers normalize
     * by dividing by width/height, so the absolute unit never matters.
     */
    default JSONObject ocrBlocks(String model, String pngBase64) throws IOException {
        throw new UnsupportedOperationException("Block OCR not supported by this provider");
    }

    /**
     * Whether this provider supports whole-document PDF OCR (sending the PDF file in one request).
     * When true, {@link #ocrPdfBlocks} is used instead of rendering + calling {@link #ocrBlocks}
     * once per page.
     */
    default boolean supportsPdfOcr() {
        return false;
    }

    /**
     * Whole-document OCR: submit the raw PDF bytes once and receive per-page block data.
     *
     * <p>Output contract — a JSON array, one element per page (index 0 = first page):
     * <pre>
     * [
     *   {
     *     "page": 0,
     *     "width":  &lt;page width in provider units&gt;,
     *     "height": &lt;page height in provider units&gt;,
     *     "blocks": [
     *       {"type": "text", "content": "...", "x0": .., "y0": .., "x1": .., "y1": ..},
     *       ...
     *     ]
     *   },
     *   ...
     * ]
     * </pre>
     * Coordinate units are the same as {@code width}/{@code height} — callers normalise
     * by dividing by those values, so the absolute unit never matters.
     */
    default org.json.JSONArray ocrPdfBlocks(String model, byte[] pdfBytes) throws IOException {
        throw new UnsupportedOperationException("PDF OCR not supported by this provider");
    }

    /**
     * Whole-document OCR with an explicit document language hint.
     * Providers that support language-aware OCR (e.g. Azure CV Read API) pass the
     * BCP-47 language code (e.g. {@code "tr"}, {@code "en"}) to the OCR engine so
     * that language-specific character sets (Turkish İ/ı, German ü/ö/ä …) are
     * recognised more accurately.
     *
     * <p>Default implementation ignores the language and delegates to
     * {@link #ocrPdfBlocks(String, byte[])} so existing providers need no changes.
     *
     * @param model    provider model identifier (may be ignored by some providers)
     * @param pdfBytes raw PDF bytes
     * @param language BCP-47 language code, e.g. {@code "tr"} or {@code "en"};
     *                 {@code null} or empty means "auto-detect"
     */
    default org.json.JSONArray ocrPdfBlocks(String model, byte[] pdfBytes, String language) throws IOException {
        return ocrPdfBlocks(model, pdfBytes);   // default: ignore language hint
    }
}
