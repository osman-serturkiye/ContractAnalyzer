package ai;

import util.HttpJson;
import util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
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
}
