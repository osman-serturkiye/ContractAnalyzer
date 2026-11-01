package config;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Thin wrapper around the JSON config file (providers, per-step AI assignment, pipeline + report options). */
public final class AppConfig {
    private final JSONObject root;

    public AppConfig(JSONObject root) {
        this.root = root;
    }

    public static AppConfig load(Path file) throws IOException {
        return new AppConfig(new JSONObject(Files.readString(file)));
    }

    public JSONObject raw() {
        return root;
    }

    public JSONObject provider(String name) {
        JSONObject providers = root.optJSONObject("providers");
        return providers == null ? null : providers.optJSONObject(name);
    }

    public JSONObject step(String name) {
        JSONObject steps = root.optJSONObject("steps");
        return steps == null ? null : steps.optJSONObject(name);
    }

    public JSONObject pipeline() {
        JSONObject p = root.optJSONObject("pipeline");
        return p == null ? new JSONObject() : p;
    }

    public JSONObject report() {
        JSONObject p = root.optJSONObject("report");
        return p == null ? new JSONObject() : p;
    }

    /**
     * Returns report display options (language, fontPath, fontBoldPath).
     * Reads from the top-level {@code "report-options"} key first;
     * falls back to the legacy {@code "report"} object for backward compatibility.
     */
    public JSONObject reportOptions() {
        JSONObject opts = root.optJSONObject("report-options");
        return opts != null ? opts : report();
    }

    /**
     * Returns the default display language for the report (BCP-47 code, e.g. "tr", "en").
     * Reads {@code report-options.defaultLang} first, falls back to the legacy
     * {@code report-options.language} key, then to {@code "tr"}.
     */
    public String defaultLang() {
        JSONObject opts = reportOptions();
        String v = opts.optString("defaultLang", "");
        if (!v.isEmpty()) return v;
        return opts.optString("language", "tr");
    }

    /**
     * Returns the report assets map (name → {data, mime}).
     * Reads from the top-level {@code "report-assets"} key first;
     * falls back to the legacy {@code "report" → "assets"} object.
     */
    public JSONObject reportAssets() {
        JSONObject assets = root.optJSONObject("report-assets");
        if (assets != null) return assets;
        return report().optJSONObject("assets");
    }

    /**
     * Returns a new AppConfig that behaves identically to this one except that
     * {@link #defaultLang()} returns the given language code.
     * Used to produce language-specific reports (e.g. "tr" → report_TR.pdf, "en" → report_EN.pdf)
     * without mutating the original config or re-reading any files.
     */
    public AppConfig withLang(String lang) {
        // Deep-copy only the report-options sub-object to avoid mutating the shared root
        JSONObject newRoot = new JSONObject(root.toString());
        JSONObject opts = newRoot.optJSONObject("report-options");
        if (opts == null) {
            opts = new JSONObject();
            newRoot.put("report-options", opts);
        }
        opts.put("defaultLang", lang);
        return new AppConfig(newRoot);
    }

    public int pipelineInt(String key, int def) {
        return pipeline().optInt(key, def);
    }

    public double pipelineDouble(String key, double def) {
        return pipeline().optDouble(key, def);
    }

    public String pipelineString(String key, String def) {
        return pipeline().optString(key, def);
    }

    public boolean pipelineBoolean(String key, boolean def) {
        return pipeline().optBoolean(key, def);
    }

    /**
     * Returns true when {@code pipeline.debug} is set to {@code true} in config.
     * When enabled, all pipeline steps emit detailed INFO-level log messages
     * (timings, token counts, intermediate results, parallelism decisions, etc.).
     */
    public boolean isDebug() {
        return pipelineBoolean("debug", false);
    }
}
