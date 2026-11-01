package ai;

import org.json.JSONObject;

import java.io.IOException;

public interface AiClient {
    String chat(ChatRequest request) throws IOException;

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
}
