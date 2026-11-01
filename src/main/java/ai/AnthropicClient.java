package ai;

import util.HttpJson;
import util.Json;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

public final class AnthropicClient extends BaseClient {

    public AnthropicClient(String name, JSONObject cfg) {
        super(name, cfg);
    }

    private String url() {
        String path = cfg.optString(
                "chatPath",
                "/v1/messages"
        );

        String query = cfg.optString("query", "");

        return baseUrl + path +
                (query.isEmpty() ? "" : "?" + query);
    }

    @Override
    public String chat(ChatRequest r) throws IOException {

        JSONObject body = new JSONObject();

        body.put("model", r.model);

        /*
         * Anthropic requires max_tokens.
         */
        body.put(
                "max_tokens",
                r.maxTokens != null
                        ? r.maxTokens.intValue()
                        : 4096
        );

        /*
         * System prompt is NOT a message in Anthropic.
         */
        if (r.system != null &&
                !r.system.isEmpty()) {

            body.put("system", r.system);
        }

        JSONArray messages = new JSONArray();

        /*
         * User content
         */
        if (r.images == null ||
                r.images.isEmpty()) {

            messages.put(
                    new JSONObject()
                            .put("role", "user")
                            .put("content", r.user)
            );

        } else {

            JSONArray content = new JSONArray();

            /*
             * Text
             */
            content.put(
                    new JSONObject()
                            .put("type", "text")
                            .put("text", r.user)
            );

            /*
             * Images
             */
            for (String imageBase64 : r.images) {

                JSONObject source =
                        new JSONObject()
                                .put("type", "base64")
                                .put("media_type", "image/png")
                                .put("data", imageBase64);

                content.put(
                        new JSONObject()
                                .put("type", "image")
                                .put("source", source)
                );
            }

            messages.put(
                    new JSONObject()
                            .put("role", "user")
                            .put("content", content)
            );
        }

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

        JSONObject response = HttpJson.post(
                url(),
                headers(),
                body,
                timeoutSec,
                retries
        );

        /*
         * Anthropic error
         */
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

        /*
         * Anthropic response:
         *
         * {
         *   "content": [
         *      {
         *        "type": "text",
         *        "text": "..."
         *      }
         *   ]
         * }
         */
        JSONArray content =
                response.optJSONArray("content");

        if (content == null ||
                content.length() == 0) {

            throw new IOException(
                    "No content from " +
                            name +
                            ": " +
                            Json.abbreviate(
                                    response.toString(),
                                    1000
                            )
            );
        }

        StringBuilder result =
                new StringBuilder();

        for (int i = 0;
             i < content.length();
             i++) {

            JSONObject item =
                    content.optJSONObject(i);

            if (item == null) {
                continue;
            }

            if ("text".equals(
                    item.optString("type"))) {

                result.append(
                        item.optString(
                                "text",
                                ""
                        )
                );
            }
        }

        String text = result.toString();

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