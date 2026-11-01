package ai;

import common.GeneralLib;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** The AI system + model configured for ONE pipeline step (with optional fallback target). */
public final class StepAi {
    private static final Logger log = LogManager.getLogger();

    public static final class Target {
        final String provider;
        final String model;
        final AiClient client;

        public Target(String provider, String model, AiClient client) {
            this.provider = provider;
            this.model = model;
            this.client = client;
        }
    }

    private final String step;
    private final List<Target> targets;
    private final JSONObject options;
    private final AiUsageStats usageStats;

    public StepAi(String step, List<Target> targets, JSONObject stepConfig, AiUsageStats usageStats) {
        this.step = step;
        this.targets = targets;
        this.options = stepConfig;
        this.usageStats = usageStats;
    }

    /** Step-level options from the config (jsonMode, temperature, attachSignedImage, ...). */
    public JSONObject options() {
        return options;
    }

    public String describe() {
        List<String> l = new ArrayList<>();
        for (Target t : targets) {
            l.add(t.provider + "/" + t.model);
        }
        return String.join(" -> ", l);
    }

    public String chat(String system, String user, List<String> images) throws IOException {
        IOException last = null;
        for (Target t : targets) {
            try {
                ChatRequest r = new ChatRequest();
                r.model = t.model;
                r.system = system;
                r.user = user;
                r.images = images == null ? new ArrayList<>() : images;
                r.jsonMode = options.optBoolean("jsonMode", false);
                if (options.has("temperature")) {
                    r.temperature = options.getDouble("temperature");
                }
                if (options.has("maxTokens")) {
                    r.maxTokens = options.getInt("maxTokens");
                }
                long t0 = System.currentTimeMillis();
                String response = t.client.chat(r);
                long durationMs = System.currentTimeMillis() - t0;
                String inputText = (system == null ? "" : system) + (user == null ? "" : user);
                usageStats.record(step, t.provider, t.model, inputText, response, durationMs);
                return response;
            } catch (IOException e) {
                last = e;
                String warnMsg = String.format("Step %s failed on %s/%s: %s", step, t.provider, t.model, e.getMessage());
                log.warn(warnMsg);
                plog("WARN", warnMsg);
            }
        }
        throw new IOException("All targets failed for step " + step + ": " + (last == null ? "" : last.getMessage()), last);
    }

    /** Image (base64 PNG) to markdown; uses a dedicated OCR endpoint if the provider has one, else a vision LLM. */
    public String ocr(String pngBase64) throws IOException {
        IOException last = null;
        for (Target t : targets) {
            try {
                long t0 = System.currentTimeMillis();
                String response;
                if (t.client.supportsOcr()) {
                    response = t.client.ocr(t.model, pngBase64);
                } else {
                    ChatRequest r = new ChatRequest();
                    r.model = t.model;
                    r.system = "You are an OCR engine.";
                    r.user = "Transcribe everything in this image into GitHub-flavoured Markdown. "
                            + "Render tables as Markdown tables. Keep numbering exactly as printed. "
                            + "Return only the transcription, no commentary.";
                    r.images.add(pngBase64);
                    response = t.client.chat(r);
                }
                long durationMs = System.currentTimeMillis() - t0;
                // Input size: base64 image length (no text prompt chars are meaningful for OCR token count)
                usageStats.record(step + ".ocr", t.provider, t.model,
                        pngBase64 == null ? "" : pngBase64, response, durationMs);
                return response;
            } catch (IOException e) {
                last = e;
                String warnMsg = String.format("OCR step %s failed on %s/%s: %s", step, t.provider, t.model, e.getMessage());
                log.warn(warnMsg);
                plog("WARN", warnMsg);
            }
        }
        throw new IOException("All OCR targets failed for step " + step + ": " + (last == null ? "" : last.getMessage()), last);
    }

    /**
     * Block-aware OCR of one rendered page image: returns real per-block bounding boxes (see
     * {@link AiClient#ocrBlocks}) instead of plain markdown. Only providers with supportsBlockOcr() (currently
     * MistralOcrClient, OCR 4+) can serve this - a provider without it fails over to the next target exactly
     * like ocr()/chat() do, it does not silently fall back to guessing geometry.
     */
    public JSONObject ocrBlocks(String pngBase64) throws IOException {
        IOException last = null;
        for (Target t : targets) {
            try {
                if (!t.client.supportsBlockOcr()) {
                    throw new IOException("Provider " + t.provider + " does not support block OCR");
                }
                long t0 = System.currentTimeMillis();
                JSONObject response = t.client.ocrBlocks(t.model, pngBase64);
                long durationMs = System.currentTimeMillis() - t0;
                // Represent input as the base64 image; output as the serialised JSON response
                usageStats.record(step + ".ocrBlocks", t.provider, t.model,
                        pngBase64 == null ? "" : pngBase64,
                        response == null ? "" : response.toString(),
                        durationMs);
                return response;
            } catch (IOException e) {
                last = e;
                String warnMsg = String.format("Block-OCR step %s failed on %s/%s: %s", step, t.provider, t.model, e.getMessage());
                log.warn(warnMsg);
                plog("WARN", warnMsg);
            }
        }
        throw new IOException("All block-OCR targets failed for step " + step + ": " + (last == null ? "" : last.getMessage()), last);
    }

    // ── pipeline log helper ───────────────────────────────────────────────────

    private static void plog(String level, String msg) {
        PrintWriter pw = GeneralLib.pipelineLog;
        if (pw == null) return;
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date());
        pw.println(ts + " " + level + "  StepAi - " + msg);
        pw.flush();
    }
}
