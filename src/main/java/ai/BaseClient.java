package ai;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

abstract class BaseClient implements AiClient {
    protected final String name;
    protected final JSONObject cfg;
    protected final String baseUrl;
    protected final int timeoutSec;
    protected final int retries;
    private final String apiKey;

    BaseClient(String name, JSONObject cfg) {
        this.name = name;
        this.cfg = cfg;
        String b = cfg.optString("baseUrl", "");
        if (b.isEmpty()) {
            throw new IllegalStateException("providers." + name + ".baseUrl is missing");
        }
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.timeoutSec = cfg.optInt("timeoutSec", 180);
        this.retries = cfg.optInt("maxRetries", 3);

        String key = cfg.optString("apiKey", "");
        if (key.isEmpty()) {
            String env = cfg.optString("apiKeyEnv", "");
            if (!env.isEmpty()) {
                String v = System.getenv(env);
                key = v == null ? "" : v.trim();
            }
        }
        if (key.isEmpty()) {
            throw new IllegalStateException("No API key for provider '" + name + "' (set env var "
                    + cfg.optString("apiKeyEnv", "<apiKeyEnv>") + " or providers." + name + ".apiKey)");
        }
        this.apiKey = key;
    }

    /** Auth + custom headers. */
    protected Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        String header = cfg.optString("authHeader", "Authorization");
        String scheme = cfg.optString("authScheme", "Bearer");
        h.put(header, scheme.isEmpty() ? apiKey : scheme + " " + apiKey);
        JSONObject extra = cfg.optJSONObject("headers");
        if (extra != null) {
            for (String k : extra.keySet()) {
                h.put(k, extra.optString(k));
            }
        }
        return h;
    }
}
