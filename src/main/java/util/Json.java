package util;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Json {
    private Json() {
    }

    /**
     * Parses a JSON object out of an LLM answer.
     * Tolerates:
     *   - {@code <think>…</think>} blocks (chain-of-thought)
     *   - Markdown code fences (```json … ```)
     *   - Leading/trailing chatter outside the JSON braces
     *   - Duplicate keys produced by hallucinating LLMs (first occurrence wins)
     */
    public static JSONObject parseLoose(String s) {
        if (s == null || s.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty AI response");
        }
        String t = s.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
            t = t.trim();
        }
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a < 0 || b < a) {
            throw new IllegalArgumentException("No JSON object in AI response: " + abbreviate(t, 300));
        }
        String json = t.substring(a, b + 1);
        // org.json throws on duplicate keys; deduplicate (first-wins) before parsing.
        json = deduplicateKeys(json);
        return new JSONObject(json);
    }

    /**
     * Removes duplicate top-level and nested JSON keys from a raw JSON string,
     * keeping the first occurrence of each key.  This is a lightweight, regex-based
     * approach that handles the common LLM failure mode where a field name appears
     * twice in the same object (e.g. two "ocr_suspected" entries).
     *
     * <p>Strategy: scan for {@code "key"   :} patterns; when a key appears for the
     * second time inside the same brace-depth, remove the second key–value pair.
     * Because proper JSON parsing is needed to skip over nested values, we walk the
     * string character-by-character and track brace/bracket nesting depth.
     */
    static String deduplicateKeys(String json) {
        // Fast path: if the string contains no duplicate key candidate, skip the walk.
        // A duplicate would require the same quoted token to appear twice, so we check
        // whether any quoted string appears more than once.
        if (!hasDuplicateKeyCandidate(json)) {
            return json;
        }

        StringBuilder out = new StringBuilder(json.length());
        int len = json.length();
        int i = 0;
        // Per-depth key sets: depth→set of already-seen keys at that depth
        java.util.Map<Integer, Set<String>> seenAtDepth = new java.util.HashMap<>();
        int depth = 0;

        while (i < len) {
            char c = json.charAt(i);

            if (c == '{' || c == '[') {
                if (c == '{') {
                    seenAtDepth.put(depth + 1, new LinkedHashSet<>());
                }
                out.append(c);
                depth++;
                i++;
            } else if (c == '}' || c == ']') {
                seenAtDepth.remove(depth);
                out.append(c);
                depth--;
                i++;
            } else if (c == '"' && depth > 0) {
                // Read the quoted string
                int keyStart = i;
                int keyEnd = nextStringEnd(json, i + 1); // index of closing '"'
                String key = json.substring(i + 1, keyEnd); // content without quotes

                // Peek past whitespace to see if this is a key (followed by ':')
                int afterKey = keyEnd + 1;
                while (afterKey < len && (json.charAt(afterKey) == ' ' || json.charAt(afterKey) == '\t'
                        || json.charAt(afterKey) == '\n' || json.charAt(afterKey) == '\r')) {
                    afterKey++;
                }
                boolean isKey = afterKey < len && json.charAt(afterKey) == ':';

                if (isKey) {
                    Set<String> seen = seenAtDepth.get(depth);
                    if (seen == null) {
                        seen = new LinkedHashSet<>();
                        seenAtDepth.put(depth, seen);
                    }
                    if (seen.contains(key)) {
                        // Duplicate key: skip the entire key:value pair (including trailing comma).
                        i = skipKeyValuePair(json, keyEnd + 1, len); // skip past ':'
                        // Also skip a trailing comma/whitespace if present
                        while (i < len && (json.charAt(i) == ' ' || json.charAt(i) == '\t'
                                || json.charAt(i) == '\n' || json.charAt(i) == '\r')) {
                            i++;
                        }
                        if (i < len && json.charAt(i) == ',') {
                            i++; // consume the comma that followed the duplicate pair
                        }
                    } else {
                        seen.add(key);
                        out.append(json, keyStart, afterKey + 1); // "key":
                        i = afterKey + 1;
                    }
                } else {
                    // It's a string value, not a key
                    out.append(json, keyStart, keyEnd + 1);
                    i = keyEnd + 1;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Checks whether any quoted token appears more than once (cheap pre-filter). */
    private static boolean hasDuplicateKeyCandidate(String json) {
        Pattern p = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"");
        Matcher m = p.matcher(json);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            if (!seen.add(m.group())) return true;
        }
        return false;
    }

    /**
     * Returns the index of the closing {@code "} of a JSON string,
     * starting the scan at {@code i} (the character after the opening {@code "}).
     */
    private static int nextStringEnd(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2; // skip escape sequence
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return i; // malformed; return end
    }

    /**
     * Given that we are positioned just after the {@code :} of a key–value pair,
     * skips over the entire value (which may be a string, number, boolean, null,
     * array, or nested object) and returns the index of the first character after it.
     */
    private static int skipKeyValuePair(String s, int i, int len) {
        // Skip whitespace before the value
        while (i < len && (s.charAt(i) == ' ' || s.charAt(i) == '\t'
                || s.charAt(i) == '\n' || s.charAt(i) == '\r')) {
            i++;
        }
        if (i >= len) return len;
        char c = s.charAt(i);
        if (c == '"') {
            // String value
            return nextStringEnd(s, i + 1) + 1;
        } else if (c == '{' || c == '[') {
            // Object or array: walk until matching close bracket
            char open = c, close = c == '{' ? '}' : ']';
            int depth = 1;
            i++;
            while (i < len && depth > 0) {
                char ch = s.charAt(i);
                if (ch == '"') {
                    i = nextStringEnd(s, i + 1) + 1;
                    continue;
                }
                if (ch == open) depth++;
                else if (ch == close) depth--;
                i++;
            }
            return i;
        } else {
            // Primitive (number, boolean, null): read until ',' or '}' or ']'
            while (i < len) {
                char ch = s.charAt(i);
                if (ch == ',' || ch == '}' || ch == ']') break;
                i++;
            }
            return i;
        }
    }

    public static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
