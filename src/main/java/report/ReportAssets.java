package report;

import config.AppConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Asset registry loaded from {@code config.json -> report.assets}: name -> {mime, data(base64)}. Used for css, html templates, images and fonts. */
public final class ReportAssets {
    private static final Logger log = LogManager.getLogger();

    private final Map<String, byte[]> data = new LinkedHashMap<>();
    private final Map<String, String> mime = new LinkedHashMap<>();

    private ReportAssets() {
    }

    /** Loads assets from {@code config.json -> report-assets} (falls back to legacy {@code report.assets}). */
    public static ReportAssets load(AppConfig cfg) {
        ReportAssets a = new ReportAssets();
        try {
            JSONObject assetsObj = cfg.reportAssets();
            String json = (assetsObj != null ? assetsObj : new JSONObject()).toString();
            a.parse(json);
            log.info("Report assets loaded: {}", a.names());
        } catch (Exception e) {
            log.warn("Report assets could not be loaded, using built-in defaults: {}", e.getMessage());
        }
        return a;
    }

    private void parse(String json) {
        JSONObject root = new JSONObject(json);
        for (String k : root.keySet()) {
            Object v = root.get(k);
            String b64;
            String m;
            if (v instanceof JSONObject) {
                b64 = ((JSONObject) v).optString("data", "");
                m = ((JSONObject) v).optString("mime", guessMime(k));
            } else {
                b64 = String.valueOf(v);
                m = guessMime(k);
            }
            if (!b64.isEmpty()) {
                data.put(k, Base64.getMimeDecoder().decode(b64));
                mime.put(k, m);
            }
        }
    }

    public boolean has(String name) {
        return data.containsKey(name);
    }

    public Set<String> names() {
        return data.keySet();
    }

    public byte[] bytes(String name) {
        return data.get(name);
    }

    public String text(String name, String fallback) {
        byte[] b = data.get(name);
        if (b == null) return fallback;
        String s = new String(b, StandardCharsets.UTF_8);
        // Strip UTF-8 BOM (U+FEFF) if present — Windows editors often save files with a BOM,
        // which causes openhtmltopdf's XML parser to throw "Content is not allowed in prolog".
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    /** data: URI for images (ready for src="..."). */
    public String dataUri(String name) {
        byte[] b = data.get(name);
        return b == null ? "" : "data:" + mime.get(name) + ";base64," + Base64.getEncoder().encodeToString(b);
    }

    /** Replaces {{key}} placeholders. kv = key1, value1, key2, value2 ... */
    public static String fill(String tpl, String... kv) {
        String r = tpl;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            r = r.replace("{{" + kv[i] + "}}", kv[i + 1]);
        }
        return r;
    }

    // ---------------------------------------------------------------- packer

    private static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".otf")) return "font/otf";
        return "application/octet-stream";
    }

    /** Packs every non-JSON file of a folder (non-recursive) into report-assets.json. */
    public static void pack(Path dir, Path out) throws IOException {
        pack(dir, out, null);
    }

    /**
     * Packs every non-JSON file of a folder (non-recursive) into report-assets.json,
     * and optionally updates the {@code report.assets} block inside a config.json file.
     *
     * @param dir    folder containing the asset files (e.g. {@code settings/})
     * @param out    target {@code report-assets.json} path
     * @param config optional path to {@code config.json}; when non-null its
     *               {@code report.assets} entries are updated in-place for every
     *               asset file found in {@code dir}
     */
    public static void pack(Path dir, Path out, Path config) throws IOException {
        JSONObject root = new JSONObject();
        java.util.List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile)
                     .filter(f -> !f.getFileName().toString().toLowerCase().endsWith(".json"))
                     .sorted().collect(Collectors.toList());
        }
        for (Path f : files) {
            String name = f.getFileName().toString();
            JSONObject e = new JSONObject();
            e.put("mime", guessMime(name));
            e.put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(f)));
            root.put(name, e);
        }
        Files.writeString(out, root.toString(2), StandardCharsets.UTF_8);
        System.out.println("Packed " + files.size() + " file(s) -> " + out);

        if (config != null && Files.exists(config)) {
            String cfgText = Files.readString(config, StandardCharsets.UTF_8);
            JSONObject cfg = new JSONObject(cfgText);
            // Write to top-level "report-assets" key
            JSONObject assets = cfg.optJSONObject("report-assets");
            if (assets == null) {
                assets = new JSONObject();
                cfg.put("report-assets", assets);
            }
            int updated = 0;
            for (String name : root.keySet()) {
                if (assets.has(name)) {
                    assets.getJSONObject(name).put("data", root.getJSONObject(name).getString("data"));
                    updated++;
                }
            }
            Files.writeString(config, cfg.toString(4), StandardCharsets.UTF_8);
            System.out.println("Updated " + updated + " asset(s) in " + config);
        }
    }

    /** usage: ReportAssets &lt;assets-folder&gt; &lt;report-assets.json&gt; [config.json] */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: ReportAssets <assets-folder> <report-assets.json> [config.json]");
            return;
        }
        Path config = args.length >= 3 ? Path.of(args[2]) : null;
        pack(Path.of(args[0]), Path.of(args[1]), config);
    }
}