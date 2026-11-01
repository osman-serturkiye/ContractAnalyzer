package report;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-shot utility: adds or updates step entries in {@code config.json -> steps}.
 *
 * <p>Single mode: {@code StepConfigUpdater <config.json> <stepName> <stepJsonObject>}
 * <p>Batch mode:  {@code StepConfigUpdater <config.json> __batch__ <jsonArray>}
 * where each array element is a JSONObject with a "name" key plus the step config fields.
 */
public final class StepConfigUpdater {

    private StepConfigUpdater() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: StepConfigUpdater <config.json> <stepName|__batch__> <json>");
            System.exit(1);
        }
        Path   cfgPath = Path.of(args[0]);
        String mode    = args[1];
        String payload = args[2];

        String cfgText = Files.exists(cfgPath)
                ? Files.readString(cfgPath, StandardCharsets.UTF_8).trim()
                : "{}";
        if (cfgText.startsWith("\uFEFF")) cfgText = cfgText.substring(1); // strip BOM

        JSONObject cfg   = cfgText.startsWith("{") ? new JSONObject(cfgText) : new JSONObject();
        JSONObject steps = cfg.optJSONObject("steps");
        if (steps == null) { steps = new JSONObject(); cfg.put("steps", steps); }

        if ("__batch__".equals(mode)) {
            // payload is a JSON array of step objects, each with a "name" field
            JSONArray batch = new JSONArray(payload);
            for (int i = 0; i < batch.length(); i++) {
                JSONObject entry = batch.getJSONObject(i);
                String name = entry.getString("name");
                entry.remove("name");
                boolean existed = steps.has(name);
                steps.put(name, entry);
                System.out.println((existed ? "Updated" : "Added") + ": steps." + name);
            }
        } else {
            JSONObject sv     = new JSONObject(payload);
            boolean   existed = steps.has(mode);
            steps.put(mode, sv);
            System.out.println((existed ? "Updated" : "Added") + ": steps." + mode);
        }

        Files.writeString(cfgPath, cfg.toString(4), StandardCharsets.UTF_8);
        System.out.println("Written -> " + cfgPath);
    }
}
