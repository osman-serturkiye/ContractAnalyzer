package ai;

import config.AppConfig;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Router {
    private final AppConfig cfg;
    private final Map<String, AiClient> clients = new HashMap<>();
    private final AiUsageStats usageStats;

    public Router(AppConfig cfg) {
        this(cfg, new AiUsageStats());
    }

    public Router(AppConfig cfg, AiUsageStats usageStats) {
        this.cfg = cfg;
        this.usageStats = usageStats;
    }

    public AiUsageStats usageStats() {
        return usageStats;
    }

    public synchronized StepAi get(String step) {
        JSONObject sc = cfg.step(step);
        if (sc == null) {
            throw new IllegalStateException("Missing config section steps." + step);
        }
        List<StepAi.Target> targets = new ArrayList<>();
        targets.add(target(step, sc.optString("provider", ""), sc.optString("model", "")));
        JSONObject fb = sc.optJSONObject("fallback");
        if (fb != null) {
            targets.add(target(step, fb.optString("provider", ""), fb.optString("model", "")));
        }
        return new StepAi(step, targets, sc, usageStats);
    }
    public void validate(JSONObject steps) {
        for (String s : steps.keySet()) {
            get(s);
        }
    }

    private StepAi.Target target(String step, String provider, String model) {
        if (provider.isEmpty() || model.isEmpty()) {
            throw new IllegalStateException("steps." + step + ": provider and model are required");
        }
        return new StepAi.Target(provider, model, client(provider));
    }

    private AiClient client(String provider) {
        AiClient c = clients.get(provider);
        if (c != null) {
            return c;
        }
        JSONObject pc = cfg.provider(provider);
        if (pc == null) {
            throw new IllegalStateException("Unknown provider '" + provider + "' (not in providers section)");
        }
        String type = pc.optString("type", "openai");
        switch (type) {
            case "openai":
            case "openai-compatible":
                c = new OpenAiCompatibleClient(provider, pc);
                break;
            case "nvidia-ai":
                c = new NvidiaAiClient(provider, pc);
                break;
            case "openrouter":
                c = new OpenRouterClient(provider, pc);
                break;
            case "ms-foundry":
                c = new MSFoundryClient(provider, pc);
                break;
            case "anthropic":
                c = new AnthropicClient(provider, pc);
                break;
            case "mistral-ocr":
                c = new MistralOcrClient(provider, pc);
                break;
            default:
                throw new IllegalStateException("providers." + provider + ".type '" + type + "' is not supported");
        }
        clients.put(provider, c);
        return c;
    }
}
