package util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class HttpJson {
    private static final Logger log = LogManager.getLogger();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private HttpJson() {
    }

    /** POSTs JSON, retries on IO errors / 429 / 5xx with exponential back-off. */
    public static JSONObject post(String url, Map<String, String> headers, JSONObject body,
                                  int timeoutSec, int maxRetries) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long wait = Math.min(30000L, 1000L * (1L << attempt));
                log.warn("Retry {}/{} in {} ms ({})", attempt, maxRetries, wait, last == null ? "" : last.getMessage());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", ie);
                }
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Content-Type", "application/json");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
            rb.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));

            HttpResponse<String> resp;
            try {
                resp = CLIENT.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", ie);
            } catch (IOException ioe) {
                last = ioe;
                continue;
            }
            int sc = resp.statusCode();
            if (sc >= 200 && sc < 300) {
                return new JSONObject(resp.body());
            }
            String msg = "HTTP " + sc + " from " + URI.create(url).getHost() + ": " + Json.abbreviate(resp.body(), 500);
            if (sc == 429 || sc >= 500) {
                last = new IOException(msg);
                continue;
            }
            throw new IOException(msg);
        }
        throw last != null ? last : new IOException("Request failed: " + url);
    }
}
