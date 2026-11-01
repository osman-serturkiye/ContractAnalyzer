package ai;

import util.HttpJson;
import util.Json;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * NVIDIA NIM / NVIDIA API OpenAI-compatible client.
 *
 * Supports:
 * - Chat Completions
 * - Text input
 * - Vision input (base64 images)
 * - JSON response mode when supported by the model
 */
public final class NvidiaAiClient extends BaseClient {

    public NvidiaAiClient(String name, JSONObject cfg) {
        super(name, cfg);
    }

    private String url() {
        String path = cfg.optString(
                "chatPath",
                "/v1/chat/completions"
        );

        String query = cfg.optString("query", "");

        return baseUrl + path +
                (query.isEmpty() ? "" : "?" + query);
    }

    @Override
    public String chat(ChatRequest r) throws IOException {

        JSONObject body = new JSONObject();

        body.put("model", r.model);

        JSONArray messages = new JSONArray();

        // System message
        if (r.system != null && !r.system.isEmpty()) {
            messages.put(
                    new JSONObject()
                            .put("role", "system")
                            .put("content", r.system)
            );
        }

        // User content
        if (r.images == null || r.images.isEmpty()) {

            messages.put(
                    new JSONObject()
                            .put("role", "user")
                            .put("content", r.user)
            );

        } else {

            JSONArray content = new JSONArray();

            content.put(
                    new JSONObject()
                            .put("type", "text")
                            .put("text", r.user)
            );

            for (String imageBase64 : r.images) {

                content.put(
                        new JSONObject()
                                .put("type", "image_url")
                                .put(
                                        "image_url",
                                        new JSONObject()
                                                .put(
                                                        "url",
                                                        "data:image/png;base64,"
                                                                + imageBase64
                                                )
                                )
                );
            }

            messages.put(
                    new JSONObject()
                            .put("role", "user")
                            .put("content", content)
            );
        }

        body.put("messages", messages);

        // Temperature
        if (r.temperature != null) {
            body.put(
                    "temperature",
                    r.temperature.doubleValue()
            );
        }

        // Max tokens
        if (r.maxTokens != null) {
            body.put(
                    cfg.optString(
                            "maxTokensParam",
                            "max_tokens"
                    ),
                    r.maxTokens.intValue()
            );
        }

        // JSON mode
        if (r.jsonMode &&
                cfg.optBoolean("supportsJsonMode", true)) {

            body.put(
                    "response_format",
                    new JSONObject()
                            .put("type", "json_object")
            );
        }

        JSONObject response = HttpJson.post(
                url(),
                headers(),
                body,
                timeoutSec,
                retries
        );

        // Provider error
        if (response.has("error") &&
                !response.isNull("error")) {

            throw new IOException(
                    "Provider " + name + " error: " +
                            Json.abbreviate(
                                    response.get("error").toString(),
                                    1000
                            )
            );
        }

        // choices
        JSONArray choices =
                response.optJSONArray("choices");

        if (choices == null ||
                choices.length() == 0) {

            throw new IOException(
                    "No choices from " + name + ": " +
                            Json.abbreviate(
                                    response.toString(),
                                    1000
                            )
            );
        }

        JSONObject message =
                choices.getJSONObject(0)
                        .optJSONObject("message");

        if (message == null) {
            throw new IOException(
                    "No message in response from " + name
            );
        }

        Object content = message.opt("content");

        String text;

        if (content instanceof JSONArray) {

            StringBuilder sb =
                    new StringBuilder();

            JSONArray arr =
                    (JSONArray) content;

            for (int i = 0; i < arr.length(); i++) {

                JSONObject part =
                        arr.optJSONObject(i);

                if (part != null) {
                    sb.append(
                            part.optString(
                                    "text",
                                    ""
                            )
                    );
                }
            }

            text = sb.toString();

        } else {

            text =
                    content == null ||
                            JSONObject.NULL.equals(content)
                            ? ""
                            : content.toString();
        }

        if (text.trim().isEmpty()) {

            throw new IOException(
                    "Empty answer from " +
                            name +
                            " (model " +
                            r.model +
                            ")"
            );
        }

        return text;
    }
}