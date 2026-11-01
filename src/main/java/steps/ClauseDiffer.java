package steps;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import util.TextNormalizer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic word-level diff (java-diff-utils) between two clause texts.
 *
 * <p>In addition to the flat inline-diff HTML ({@code html_base}, {@code html_other}),
 * the result now also contains {@code html_base_md} and {@code html_other_md}: these
 * preserve the <em>Markdown structure</em> of each side (lists, bold, headings, …) while
 * still annotating changed tokens with {@code <del>}/{@code <ins>} spans.  ReportBuilder
 * renders these through the CommonMark pipeline so that list items, paragraphs and bold
 * text appear correctly in the split-panel view.
 *
 * <h3>Algorithm for the Markdown-preserving diff</h3>
 * <ol>
 *   <li>The <em>normalised</em> text (flat, no Markdown) is used for the token diff as
 *       before — this drives the {@code ops}, {@code similarity} and legacy flat HTML.</li>
 *   <li>The <em>original</em> Markdown of each side is split into logical lines.</li>
 *   <li>Each line is matched back to a span of normalised tokens.  Changed tokens on
 *       that line are wrapped in {@code <del>}/{@code <ins>} inline HTML.</li>
 *   <li>The resulting annotated Markdown is returned as {@code html_base_md} /
 *       {@code html_other_md} — to be rendered by {@code ReportBuilder.mdDiff()}.</li>
 * </ol>
 */
public final class ClauseDiffer {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{M}\\p{N}]+|[^\\s\\p{L}\\p{M}\\p{N}]");

    private ClauseDiffer() {
    }

    public static JSONObject diff(String base, String other, String clauseNo) {
        List<String> a = tokens(prepare(base, clauseNo));
        List<String> b = tokens(prepare(other, clauseNo));
        Patch<String> patch = DiffUtils.diff(a, b);

        JSONArray ops = new JSONArray();
        StringBuilder html = new StringBuilder();     // merged inline diff (<del> + <ins>)
        StringBuilder left = new StringBuilder();     // baseline text, deletions highlighted
        StringBuilder right = new StringBuilder();    // other text, insertions highlighted
        int pos = 0;
        int changedBase = 0;
        for (AbstractDelta<String> d : patch.getDeltas()) {
            int sp = d.getSource().getPosition();
            List<String> equal = a.subList(pos, sp);
            append(html, equal, null);
            append(left, equal, null);
            append(right, equal, null);
            List<String> src = d.getSource().getLines();
            List<String> tgt = d.getTarget().getLines();
            if (!src.isEmpty()) {
                append(html, src, "del");
                append(left, src, "del");
            }
            if (!tgt.isEmpty()) {
                append(html, tgt, "ins");
                append(right, tgt, "ins");
            }
            pos = sp + d.getSource().size();
            changedBase += src.size();
            ops.put(new JSONObject()
                    .put("type", d.getType().name())
                    .put("base", String.join(" ", src))
                    .put("other", String.join(" ", tgt)));
        }
        List<String> tail = a.subList(pos, a.size());
        append(html, tail, null);
        append(left, tail, null);
        append(right, tail, null);

        int equalTokens = a.size() - changedBase;
        double sim = a.size() + b.size() == 0 ? 1.0 : 2.0 * equalTokens / (a.size() + b.size());

        // ------------------------------------------------------------------
        // Markdown-preserving diff panels
        // ------------------------------------------------------------------
        String baseMd  = markdownDiff(base,  a, patch, Side.BASE,  clauseNo);
        String otherMd = markdownDiff(other, b, patch, Side.OTHER, clauseNo);

        return new JSONObject()
                .put("equal", patch.getDeltas().isEmpty())
                .put("similarity", Math.round(sim * 10000.0) / 10000.0)
                .put("ops", ops)
                .put("html", html.toString().trim())
                .put("html_base", left.toString().trim())
                .put("html_other", right.toString().trim())
                .put("html_base_md", baseMd)
                .put("html_other_md", otherMd);
    }

    // ------------------------------------------------------------------ Markdown-preserving diff

    private enum Side { BASE, OTHER }

    /**
     * Annotates the original {@code markdown} text with {@code <del>}/{@code <ins>} inline
     * spans for changed tokens, while leaving all Markdown structural characters
     * (list markers, bold, headings, …) in place so the result can be fed through a
     * CommonMark renderer.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Split the original Markdown into lines.</li>
     *   <li>For each line strip its Markdown structure to get "plain words" (same normaliser
     *       that ClauseDiffer uses, but only collapsing whitespace, not removing bullets).</li>
     *   <li>Build a per-line token list.  Walk the diff patch to determine which token
     *       positions on this side are deleted/inserted.</li>
     *   <li>Reconstruct the line replacing changed tokens with
     *       {@code <del>token</del>}/{@code <ins>token</ins>} spans.</li>
     * </ol>
     *
     * @param markdown  the original Markdown of the side being annotated
     * @param normToks  the normalised token list for this side (already computed)
     * @param patch     the diff patch
     * @param side      which side to annotate (BASE uses deltas' source, OTHER uses target)
     * @param clauseNo  clause number to strip from the start (may be null)
     */
    private static String markdownDiff(
            String markdown,
            List<String> normToks,
            Patch<String> patch,
            Side side,
            String clauseNo) {

        if (markdown == null || markdown.isEmpty()) return "";

        // Build a boolean array: isChanged[i] = true when token i on this side is del/ins
        boolean[] isChanged = new boolean[normToks.size()];
        int normPos = 0; // pointer into normToks
        for (AbstractDelta<String> d : patch.getDeltas()) {
            if (side == Side.BASE) {
                int start = d.getSource().getPosition();
                int end   = start + d.getSource().size();
                for (int i = start; i < end; i++) {
                    if (i < isChanged.length) isChanged[i] = true;
                }
            } else {
                int start = d.getTarget().getPosition();
                int end   = start + d.getTarget().size();
                for (int i = start; i < end; i++) {
                    if (i < isChanged.length) isChanged[i] = true;
                }
            }
        }

        // Walk lines of the original Markdown; for each line find which normToks belong to it
        // and annotate changed tokens inline.
        String[] mdLines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int tokIdx = 0; // current position in normToks

        for (String line : mdLines) {
            // Number of normalised tokens this line contributes
            String normLine = TextNormalizer.normalize(line);
            List<String> lineToks = tokens(normLine);
            int lineTokenCount = lineToks.size();

            if (lineTokenCount == 0) {
                // Blank / structural-only line (separator row, empty line) — keep as-is
                out.append(line).append('\n');
                continue;
            }

            // Check whether any token on this line is changed
            boolean anyChanged = false;
            for (int i = tokIdx; i < Math.min(tokIdx + lineTokenCount, isChanged.length); i++) {
                if (isChanged[i]) { anyChanged = true; break; }
            }

            if (!anyChanged) {
                // Fast path: no changes on this line
                out.append(line).append('\n');
                tokIdx += lineTokenCount;
                continue;
            }

            // Slow path: annotate changed tokens while preserving the Markdown structure.
            // We re-tokenise the RAW line and map each raw token to its normalised counterpart.
            out.append(annotateLineTokens(line, lineToks, isChanged, tokIdx, side)).append('\n');
            tokIdx += lineTokenCount;
        }

        // Trim trailing blank lines
        String result = out.toString();
        while (result.endsWith("\n\n")) result = result.substring(0, result.length() - 1);
        return result.trim();
    }

    /**
     * Re-walks a single Markdown line and wraps changed tokens in del/ins spans.
     *
     * <p>The approach is character-level: for each raw token we look for it in the line
     * text and decide whether to wrap it.  We iterate in order so the output preserves
     * surrounding Markdown punctuation (asterisks, hyphens, pipes, …) unchanged.
     */
    private static String annotateLineTokens(
            String line,
            List<String> normLineToks,
            boolean[] isChanged,
            int tokOffset,
            Side side) {

        // If the line has no alphabetic/numeric content just return as-is (structural line)
        if (normLineToks.isEmpty()) return line;

        // Use the raw TOKEN pattern on the original line
        Matcher m = TOKEN.matcher(line);
        StringBuilder sb = new StringBuilder();
        int prev = 0;
        int normIdx = 0;

        while (m.find()) {
            String rawTok = m.group();
            // Append everything between previous match and this token (Markdown punctuation etc.)
            sb.append(line, prev, m.start());
            prev = m.end();

            // Is this raw token a "content" token (letter/digit sequence) or punctuation?
            boolean isContent = rawTok.chars().anyMatch(ch ->
                    Character.isLetter(ch) || Character.isDigit(ch));

            if (!isContent || normIdx >= normLineToks.size()) {
                // Punctuation or Markdown structural character — never wrap
                sb.append(rawTok);
                continue;
            }

            // Map to norm token index
            int globalIdx = tokOffset + normIdx;
            boolean changed = globalIdx < isChanged.length && isChanged[globalIdx];
            normIdx++;

            if (changed) {
                String tag = (side == Side.BASE) ? "del" : "ins";
                sb.append('<').append(tag).append('>').append(escHtmlInline(rawTok)).append("</").append(tag).append('>');
            } else {
                sb.append(rawTok);
            }
        }
        // Append remaining suffix (e.g. trailing punctuation)
        sb.append(line, prev, line.length());
        return sb.toString();
    }

    /** Escapes HTML special chars for inline use (does NOT escape the token's own text). */
    private static String escHtmlInline(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ public helpers

    public static double similarity(String base, String other) {
        return diff(base, other, "").getDouble("similarity");
    }

    static String prepare(String text, String clauseNo) {
        String t = TextNormalizer.normalize(text);
        if (clauseNo != null && !clauseNo.isEmpty()) {
            // drop the clause's own leading number ("1.1", "1.1.", "Madde 1.1", "Article 1.1")
            t = t.replaceFirst("(?iu)^(?:(?:madde|article|art\\.?|clause|md\\.?)\\s*)?" + Pattern.quote(clauseNo) + "[.):]?\\s+", "");
        }
        return t;
    }

    static List<String> tokens(String s) {
        List<String> l = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            l.add(m.group());
        }
        return l;
    }

    private static void append(StringBuilder sb, List<String> toks, String tag) {
        if (toks.isEmpty()) {
            return;
        }
        String s = escape(String.join(" ", toks));
        if (tag == null) {
            sb.append(s).append(' ');
        } else {
            sb.append('<').append(tag).append('>').append(s).append("</").append(tag).append("> ");
        }
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
