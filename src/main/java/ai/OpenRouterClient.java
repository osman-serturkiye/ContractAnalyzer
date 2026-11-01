package ai;

import util.HttpJson;
import util.Json;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenRouter (https://openrouter.ai) chat-completions client.
 *
 * Config (cfg) keys:
 *   baseUrl          default "https://openrouter.ai/api/v1" (BaseClient okuyorsa cfg'de verin)
 *   chatPath         default "/chat/completions"
 *   referer          optional, HTTP-Referer header (openrouter.ai sıralamaları için)
 *   appTitle         optional, X-Title header
 *   maxTokensParam   default "max_tokens"
 *   supportsJsonMode default true
 *   fallbackModels   optional JSONArray, ana model başarısız olursa denenecek modeller
 *   provider         optional JSONObject, örn. {"order":["Anthropic"],"allow_fallbacks":false,
 *                    "require_parameters":true,"data_collection":"deny"}
 *   reasoning        optional JSONObject, örn. {"effort":"low"} veya {"max_tokens":2000}
 */
public final class OpenRouterClient extends BaseClient {

    private static final String DEFAULT_CHAT_PATH = "/chat/completions";

    public OpenRouterClient(String name, JSONObject cfg) {
        super(name, cfg);
    }

    private String url() {
        return baseUrl + cfg.optString("chatPath", DEFAULT_CHAT_PATH);
    }

    /** BaseClient başlıklarına (Authorization vb.) OpenRouter'a özgü başlıkları ekler. */
    private Map<String, String> openRouterHeaders() {
        Map<String, String> h = new LinkedHashMap<>(headers());
        String referer = cfg.optString("referer", "");
        if (!referer.isEmpty()) {
            h.put("HTTP-Referer", referer);
        }
        String title = cfg.optString("appTitle", "");
        if (!title.isEmpty()) {
            h.put("X-Title", title);
        }
        return h;
    }

    @Override
    public String chat(ChatRequest r) throws IOException {
        JSONObject body = new JSONObject();
        body.put("model", r.model);

        // Model fallback zinciri: ana model hata verirse OpenRouter sıradakini dener
        JSONArray fallbacks = cfg.optJSONArray("fallbackModels");
        if (fallbacks != null && fallbacks.length() > 0) {
            JSONArray models = new JSONArray().put(r.model);
            for (int i = 0; i < fallbacks.length(); i++) {
                models.put(fallbacks.getString(i));
            }
            body.put("models", models);
        }

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

        JSONObject provider = cfg.optJSONObject("provider");
        if (provider != null) {
            body.put("provider", provider);
        }
        JSONObject reasoning = cfg.optJSONObject("reasoning");
        if (reasoning != null) {
            body.put("reasoning", reasoning);
        }

        JSONObject resp = HttpJson.post(url(), openRouterHeaders(), body, timeoutSec, retries);
        return extractText(resp, r.model);
    }

    private String extractText(JSONObject resp, String requestedModel) throws IOException {
        // 1) Üst seviye hata
        if (resp.has("error") && !resp.isNull("error")) {
            throw new IOException("OpenRouter " + name + " error: "
                    + Json.abbreviate(resp.get("error").toString(), 500));
        }

        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("No choices from " + name + ": " + Json.abbreviate(resp.toString(), 500));
        }
        JSONObject choice = choices.getJSONObject(0);

        // 2) HTTP 200 ama choice içinde hata (provider tarafı hatası)
        if (choice.has("error") && !choice.isNull("error")) {
            throw new IOException("OpenRouter " + name + " choice error: "
                    + Json.abbreviate(choice.get("error").toString(), 500));
        }
        String finish = choice.optString("finish_reason", "");
        if ("error".equals(finish)) {
            throw new IOException("OpenRouter " + name + " finish_reason=error (model "
                    + resp.optString("model", requestedModel) + ")");
        }

        // 3) İçerik: string veya parça dizisi olabilir
        JSONObject msg = choice.optJSONObject("message");
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
            // Reasoning modelleri token bütçesini düşünmeye harcayıp içerik dönmeyebilir
            boolean hasReasoning = msg != null
                    && (!msg.optString("reasoning", "").isEmpty() || msg.has("reasoning_details"));
            throw new IOException("Empty answer from " + name + " (model "
                    + resp.optString("model", requestedModel) + ", finish_reason=" + finish + ")"
                    + (hasReasoning && "length".equals(finish)
                    ? " - reasoning consumed the token budget, increase maxTokens" : ""));
        }
        return text;
    }
}