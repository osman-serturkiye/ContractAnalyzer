package steps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.stream.Stream;

/** Caches every expensive step result as a JSON file in the work dir, so a failed run can resume. */
public final class Checkpoint {
    private static final Logger log = LogManager.getLogger();

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }

    private final Path dir;

    public Checkpoint(Path dir, boolean fresh) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        if (fresh) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.getFileName().toString().endsWith(".json")) {
                        Files.delete(p);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ public API

    /** Cache a {@link JSONObject} result.
     *  A result is only written to disk when it is considered non-empty:
     *  for extraction steps the result must contain a non-empty "clauses" object.
     *  Results without a "clauses" key (e.g. AI comparison results) are always written.
     *  This prevents a transient failure (empty-response, content_filter, etc.) from
     *  being permanently cached and blocking future retries. */
    public JSONObject obj(String name, IoSupplier<JSONObject> supplier) throws IOException {
        Path f = file(name);
        if (Files.exists(f)) {
            log.info("Checkpoint hit: {}", f.getFileName());
            return new JSONObject(Files.readString(f));
        }
        JSONObject r = supplier.get();
        // Only persist if the result is substantive.
        // Extraction results carry a "clauses" key: skip caching when it is empty
        // so that the next run re-attempts the extraction instead of replaying a blank result.
        if (r.has("clauses")) {
            JSONObject cl = r.optJSONObject("clauses");
            if (cl == null || cl.length() == 0) {
                log.warn("Checkpoint {}: clauses are empty, result NOT cached (will retry on next run)", name);
                return r;
            }
        }
        write(f, r.toString());
        return r;
    }

    /** Cache a {@link JSONArray} result. Stored as {@code {"_arr":[...]}} to keep the file valid JSON. */
    public JSONArray arr(String name, IoSupplier<JSONArray> supplier) throws IOException {
        Path f = file(name);
        if (Files.exists(f)) {
            log.info("Checkpoint hit: {}", f.getFileName());
            return new JSONObject(Files.readString(f)).getJSONArray("_arr");
        }
        JSONArray r = supplier.get();
        write(f, new JSONObject().put("_arr", r).toString());
        return r;
    }

    // ------------------------------------------------------------------ file hash utilities

    /**
     * Returns the SHA-256 hex digest of a file.
     * Used by callers to include the source file hash in the checkpoint key so that
     * re-running with a different file on the same output directory does not replay
     * a stale cache entry.
     *
     * <p>Example usage in Pipeline:
     * <pre>
     *   String signedKey = "signed." + lang + ".extract." + Checkpoint.fileHash(signedPdf);
     *   JSONObject extracted = cp.obj(signedKey, () -> ...);
     * </pre>
     *
     * @param file the file to hash
     * @return lowercase hex SHA-256 string, or empty string if the file cannot be read
     */
    public static String fileHash(java.io.File file) {
        if (file == null || !file.exists()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(file));
                 DigestInputStream dis = new DigestInputStream(in, md)) {
                byte[] buf = new byte[65536];
                while (dis.read(buf) != -1) { /* consume */ }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            // Return first 12 hex chars (48 bits) — enough to avoid accidental collisions
            // while keeping key names short and readable.
            return sb.substring(0, 12);
        } catch (Exception e) {
            log.debug("Checkpoint.fileHash: could not hash {} ({})", file.getName(), e.getMessage());
            return "";
        }
    }

    // ------------------------------------------------------------------ internals

    private Path file(String name) {
        return dir.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }

    private void write(Path f, String content) throws IOException {
        Path tmp = dir.resolve(f.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
    }
}
