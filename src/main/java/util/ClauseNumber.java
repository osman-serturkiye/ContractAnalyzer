package util;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClauseNumber {
    private static final Pattern NUM = Pattern.compile("\\d+(?:\\.\\d+)*");

    private ClauseNumber() {
    }

    /** "Madde 5" -> "5", "1.1." -> "1.1". Returns null for blank input. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = NUM.matcher(raw);
        if (m.find()) {
            return m.group();
        }
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Returns {@code true} when {@code candidate} is a plausible next clause number
     * after {@code current} in a real contract.
     *
     * <p>Rules (all must pass):
     * <ol>
     *   <li><b>Depth jump</b>: candidate depth may be at most currentDepth + 1
     *       (e.g. after "20" we can open "20.1", but not "20.1.1" directly).</li>
     *   <li><b>Same-level advance</b>: the last component of the candidate must be
     *       ≥ 1 and ≤ lastComponent + {@code MAX_SKIP} (default 10).
     *       After "20" the next top-level clause may be at most "30".
     *       After "20.3" the next sub-clause may be at most "20.13".</li>
     *   <li><b>New sub-level starts at 1</b>: when the candidate is one level deeper
     *       than current (e.g. "20" → "20.1"), the new last component must be 1.</li>
     *   <li><b>Prefix consistency</b>: when the candidate is at the same depth as
     *       current, all components except the last must be identical
     *       (e.g. "20.3" → "20.4" is fine; "20.3" → "21.4" is not — that should
     *       first appear as "21" alone).</li>
     * </ol>
     *
     * <p>When {@code current} is {@code null} or empty (no previous clause yet),
     * candidate is accepted if its first component is a small number (≤ 20) so
     * that a document that starts at clause 1 or even clause 10 is handled correctly
     * while rejecting mid-sentence references like "193.".
     *
     * @param current   the most recently accepted clause number, or {@code null}
     * @param candidate the normalized candidate clause number (digits and dots only)
     */
    public static boolean isValidNextClause(String current, String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;

        // candidate must be purely numeric with dots
        if (!candidate.matches("\\d+(\\.\\d+)*")) return false;

        final int MAX_SKIP = 10;

        int[] cands = parts(candidate);

        // No previous clause: accept if first component ≤ 20 (contracts rarely start beyond clause 20)
        if (current == null || current.isEmpty()) {
            return cands[0] <= 20;
        }

        int[] currs = parts(current);

        int curDepth  = currs.length;  // 1-based depth
        int candDepth = cands.length;

        // Rule 1: depth may increase by at most 1
        if (candDepth > curDepth + 1) return false;

        // Rule 2 & 3 & 4 — depends on relative depth

        if (candDepth == curDepth) {
            // Same depth: prefix must match, last component advances by 0..MAX_SKIP
            // (0 = same number repeated, allowed for chunk-continuation; >0 = new clause)
            for (int i = 0; i < candDepth - 1; i++) {
                if (cands[i] != currs[i]) return false;   // Rule 4
            }
            int diff = cands[candDepth - 1] - currs[curDepth - 1];
            return diff >= 0 && diff <= MAX_SKIP;          // Rule 2

        } else if (candDepth == curDepth + 1) {
            // Going one level deeper: prefix must equal current, new component must be 1
            for (int i = 0; i < curDepth; i++) {
                if (cands[i] != currs[i]) return false;   // prefix check
            }
            return cands[candDepth - 1] == 1;             // Rule 3

        } else {
            // candDepth < curDepth: popping up one or more levels
            // The shared prefix must match, and the resumed component must advance by 0..MAX_SKIP
            int shared = candDepth - 1;
            for (int i = 0; i < shared; i++) {
                if (cands[i] != currs[i]) return false;
            }
            // The last component of candidate vs the same-level component of current
            int currAtLevel = shared < curDepth ? currs[shared] : 0;
            int diff = cands[candDepth - 1] - currAtLevel;
            return diff >= 0 && diff <= MAX_SKIP;
        }
    }

    /** Parses "1.10.2" → {1, 10, 2}. */
    private static int[] parts(String no) {
        String[] segs = no.split("\\.");
        int[] out = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            try { out[i] = Integer.parseInt(segs[i]); } catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    public static final Comparator<String> COMPARATOR = (a, b) -> {
        String na = normalize(a); if (na == null) na = a;
        String nb = normalize(b); if (nb == null) nb = b;
        String[] x = na.split("\\.");
        String[] y = nb.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = part(x[i], y[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    };

    private static int part(String p, String q) {
        boolean pn = p.matches("\\d+");
        boolean qn = q.matches("\\d+");
        if (pn && qn) {
            return new BigInteger(p).compareTo(new BigInteger(q));
        }
        if (pn != qn) {
            return pn ? -1 : 1;
        }
        return p.compareToIgnoreCase(q);
    }
}
