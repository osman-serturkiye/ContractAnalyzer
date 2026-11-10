package ai;

/**
 * Result of an AI chat call: response text + provider-reported token counts.
 *
 * <p>When the provider returns usage metadata (e.g. OpenAI {@code usage.prompt_tokens}),
 * the token counts are populated; otherwise both are {@code -1} (unknown).
 */
public final class ChatResult {
    /** The model's text response. */
    public final String text;
    /** Provider-reported prompt (input) tokens, or {@code -1} if unavailable. */
    public final long promptTokens;
    /** Provider-reported completion (output) tokens, or {@code -1} if unavailable. */
    public final long completionTokens;

    public ChatResult(String text, long promptTokens, long completionTokens) {
        this.text             = text;
        this.promptTokens     = promptTokens;
        this.completionTokens = completionTokens;
    }

    /** {@code true} when provider-reported token counts are available. */
    public boolean hasRealTokens() {
        return promptTokens >= 0 && completionTokens >= 0;
    }
}
