package ai;

import common.GeneralLib;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe collector for AI API usage statistics.
 *
 * <p>One instance is shared across all pipeline steps. Each AI call (chat / ocr / ocrBlocks)
 * registers itself via {@link #record}. At the end of the pipeline {@link #logSummary} prints
 * a single summary line with totals.</p>
 *
 * <h3>Token estimation</h3>
 * Exact token counts are only available when the provider returns usage metadata. For providers
 * that do not (or when the response is a plain string), we fall back to a simple heuristic:
 * <pre>  estimated_tokens ≈ characters / 4</pre>
 * This matches the ~4-char-per-token rule-of-thumb for English/Latin text. For languages with
 * multi-byte characters (e.g. Turkish, Arabic) the real count is slightly higher; the estimate
 * is intentionally labelled "~" in the log output to signal this.
 */
public final class AiUsageStats {

    private static final Logger log = LogManager.getLogger(AiUsageStats.class);

    /** chars / token approximation */
    private static final double CHARS_PER_TOKEN = 4.0;

    // ── per-call counters ─────────────────────────────────────────────────────

    private final AtomicInteger callCount       = new AtomicInteger(0);
    /** cumulative input character count (system + user prompt) */
    private final AtomicLong    inputChars      = new AtomicLong(0);
    /** cumulative output character count */
    private final AtomicLong    outputChars     = new AtomicLong(0);
    /** cumulative estimated input tokens */
    private final AtomicLong    inputTokensEst  = new AtomicLong(0);
    /** cumulative estimated output tokens */
    private final AtomicLong    outputTokensEst = new AtomicLong(0);
    /** cumulative provider-reported input tokens (when available) */
    private final AtomicLong    inputTokensReal = new AtomicLong(0);
    /** cumulative provider-reported output tokens (when available) */
    private final AtomicLong    outputTokensReal = new AtomicLong(0);
    /** cumulative wall-clock milliseconds spent waiting for AI responses */
    private final AtomicLong    totalDurationMs = new AtomicLong(0);
    /** number of calls with provider-reported token counts */
    private final AtomicInteger realTokenCalls  = new AtomicInteger(0);

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Records a completed AI call (no provider-reported token counts available).
     *
     * @param step        pipeline step name (e.g. {@code "aiClauseCompare"})
     * @param provider    provider name from config (e.g. {@code "openai"})
     * @param model       model identifier (e.g. {@code "gpt-4o"})
     * @param inputText   full input text sent (system + user combined) — used for char/token estimation
     * @param outputText  response text received
     * @param durationMs  wall-clock time from request start to response received
     */
    public void record(String step, String provider, String model,
                       String inputText, String outputText, long durationMs) {
        record(step, provider, model, inputText, outputText, durationMs, -1, -1);
    }

    /**
     * Records a completed AI call with provider-reported token counts.
     *
     * @param promptTokens     tokens in the prompt as reported by the provider, or {@code -1} if unknown
     * @param completionTokens tokens in the completion as reported by the provider, or {@code -1} if unknown
     */
    public void record(String step, String provider, String model,
                       String inputText, String outputText, long durationMs,
                       long promptTokens, long completionTokens) {

        int inChars  = inputText  == null ? 0 : inputText.length();
        int outChars = outputText == null ? 0 : outputText.length();

        long inEst  = Math.round(inChars  / CHARS_PER_TOKEN);
        long outEst = Math.round(outChars / CHARS_PER_TOKEN);

        callCount.incrementAndGet();
        inputChars.addAndGet(inChars);
        outputChars.addAndGet(outChars);
        inputTokensEst.addAndGet(inEst);
        outputTokensEst.addAndGet(outEst);
        totalDurationMs.addAndGet(durationMs);

        boolean hasReal = promptTokens >= 0 && completionTokens >= 0;
        if (hasReal) {
            inputTokensReal.addAndGet(promptTokens);
            outputTokensReal.addAndGet(completionTokens);
            realTokenCalls.incrementAndGet();
        }

        // Per-call log line
        String msg;
        if (hasReal) {
            msg = String.format("[AI-USAGE] step=%s provider=%s model=%s "
                            + "input=%dc/%dtok output=%dc/%dtok "
                            + "tokens_reported=%d/%d duration=%dms",
                    step, provider, model,
                    inChars, inEst, outChars, outEst,
                    promptTokens, completionTokens,
                    durationMs);
        } else {
            msg = String.format("[AI-USAGE] step=%s provider=%s model=%s "
                            + "input=%dc/~%dtok output=%dc/~%dtok duration=%dms",
                    step, provider, model,
                    inChars, inEst, outChars, outEst,
                    durationMs);
        }
        log.info(msg);
        plog(msg);
    }

    /**
     * Logs a one-line summary of all AI calls recorded so far.
     * Call this once at the end of the pipeline run.
     *
     * @param totalPipelineMs wall-clock duration of the entire pipeline run (may include non-AI work)
     */
    public void logSummary(long totalPipelineMs) {
        int    calls    = callCount.get();
        long   inC      = inputChars.get();
        long   outC     = outputChars.get();
        long   inEst    = inputTokensEst.get();
        long   outEst   = outputTokensEst.get();
        long   aiMs     = totalDurationMs.get();
        int    realN    = realTokenCalls.get();

        if (calls == 0) {
            log.info("[AI-USAGE-TOTAL] No AI calls made during this pipeline run.");
            plog("[AI-USAGE-TOTAL] No AI calls made during this pipeline run.");
            return;
        }

        // Prefer real token counts when available for at least some calls
        String summaryMsg;
        if (realN > 0) {
            long inReal  = inputTokensReal.get();
            long outReal = outputTokensReal.get();
            summaryMsg = String.format("[AI-USAGE-TOTAL] calls=%d | "
                            + "input=%dc/~%dtok output=%dc/~%dtok | "
                            + "tokens_reported(%dcalls)=%d/%d | "
                            + "ai_time=%dms pipeline_time=%dms",
                    calls,
                    inC, inEst, outC, outEst,
                    realN, inReal, outReal,
                    aiMs, totalPipelineMs);
        } else {
            summaryMsg = String.format("[AI-USAGE-TOTAL] calls=%d | "
                            + "input=%dc/~%dtok output=%dc/~%dtok | "
                            + "ai_time=%dms pipeline_time=%dms",
                    calls,
                    inC, inEst, outC, outEst,
                    aiMs, totalPipelineMs);
        }
        log.info(summaryMsg);
        plog(summaryMsg);
    }

    // ── accessors (for tests / assertions) ───────────────────────────────────

    public int    callCount()        { return callCount.get(); }
    public long   totalDurationMs()  { return totalDurationMs.get(); }
    public long   inputChars()       { return inputChars.get(); }
    public long   outputChars()      { return outputChars.get(); }
    public long   inputTokensEst()   { return inputTokensEst.get(); }
    public long   outputTokensEst()  { return outputTokensEst.get(); }

    // ── pipeline log helper ───────────────────────────────────────────────────

    /** GeneralLib.pipelineLog'a thread-safe, null-safe doğrudan yazar. */
    private static void plog(String msg) {
        PrintWriter pw = GeneralLib.pipelineLog;
        if (pw == null) return;
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date());
        pw.println(ts + " INFO  AiUsageStats - " + msg);
        pw.flush();
    }
}
