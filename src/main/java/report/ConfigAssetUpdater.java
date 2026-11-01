package report;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One-shot utility: reads every non-JSON asset file from a folder and updates
 * (or creates) the matching entry inside {@code config.json -> report-assets}.
 *
 * <p>usage: ConfigAssetUpdater &lt;assets-folder&gt; &lt;config.json&gt;
 */
public final class ConfigAssetUpdater {

    private ConfigAssetUpdater() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: ConfigAssetUpdater <assets-folder> <config.json>");
            System.exit(1);
        }
        Path dir    = Path.of(args[0]);
        Path cfgPath = Path.of(args[1]);

        // ---- load config (create minimal skeleton if missing / empty) --------
        String cfgText = "";
        if (Files.exists(cfgPath)) {
            cfgText = Files.readString(cfgPath, StandardCharsets.UTF_8).trim();
        }
        JSONObject cfg = cfgText.startsWith("{") ? new JSONObject(cfgText) : new JSONObject();

        // ---- ensure report-assets object exists -----------------------------
        JSONObject assets = cfg.optJSONObject("report-assets");
        if (assets == null) {
            assets = new JSONObject();
            cfg.put("report-assets", assets);
        }

        // ---- iterate asset files -------------------------------------------
        java.util.List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile)
                     .filter(f -> !f.getFileName().toString().toLowerCase().endsWith(".json"))
                     .filter(f -> !f.getFileName().toString().endsWith(".tmp"))
                     .sorted()
                     .collect(Collectors.toList());
        }

        int updated = 0;
        int added   = 0;
        for (Path f : files) {
            String name  = f.getFileName().toString();
            String mime  = guessMime(name);
            String b64   = Base64.getEncoder().encodeToString(Files.readAllBytes(f));

            if (assets.has(name)) {
                assets.getJSONObject(name).put("data", b64);
                updated++;
                System.out.println("Updated: " + name);
            } else {
                JSONObject entry = new JSONObject();
                entry.put("mime", mime);
                entry.put("data", b64);
                assets.put(name, entry);
                added++;
                System.out.println("Added:   " + name);
            }
        }

        // ---- write back -----------------------------------------------------
        Files.writeString(cfgPath, cfg.toString(4), StandardCharsets.UTF_8);
        System.out.println("Done. updated=" + updated + " added=" + added + " -> " + cfgPath);
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".css"))                    return "text/css";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".png"))                    return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg"))                    return "image/svg+xml";
        if (n.endsWith(".gif"))                    return "image/gif";
        if (n.endsWith(".ttf"))                    return "font/ttf";
        if (n.endsWith(".otf"))                    return "font/otf";
        return "application/octet-stream";
    }
}
