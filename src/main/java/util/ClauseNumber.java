package util;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Madde numarası yardımcısı — normalize ve ardıl doğrulaması.
 *
 * <p><b>Geçiş notu:</b> Yeni kod için {@link extraction.ClauseNumbering} kullanın.
 * Bu sınıf geriye uyumluluk için korunmaktadır; {@link #isValidNextClause} ve
 * {@link #normalize} metodları {@code ClauseNumbering} ile davranış olarak özdeştir.
 *
 * <p>{@code ClauseNumbering}'in ek özellikleri:
 * <ul>
 *   <li>RESET / BACKWARD / DUPLICATE ayrımı ({@link extraction.ClauseNumbering#classify})</li>
 *   <li>Tüm format desteği — Madde/§/المادة/Roman/parantezli ({@link extraction.ClauseNumbering#parse})</li>
 *   <li>Yasal atıf tespiti ({@link extraction.ClauseNumbering#isLegalReference})</li>
 * </ul>
 */
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
     * Bridge metodu: {@link extraction.ClauseNumbering#isValidSuccessor} ile özdeş davranış.
     *
     * <p>Yeni kod doğrudan {@code ClauseNumbering.isValidSuccessor(current, candidate)}
     * veya daha ayrıntılı {@code ClauseNumbering.classify(current, candidate)} çağırmalıdır.
     * Bu metot yalnızca VALID ve DUPLICATE → {@code true}, diğerleri → {@code false} döner.
     * RESET (ek/şedül başlangıcı) ve BACKWARD durumlarını ayırt etmez; bu nedenle yeni
     * segmentasyon kodu için yetersizdir.
     */
    public static boolean isValidNextClause(String current, String candidate) {
        // Delegate to ClauseNumbering for consistent single source of truth.
        // Behaviour is identical: VALID|DUPLICATE → true, RESET|BACKWARD|INVALID → false.
        extraction.ClauseNumbering.Relation r =
                extraction.ClauseNumbering.classify(current, candidate);
        return r == extraction.ClauseNumbering.Relation.VALID
            || r == extraction.ClauseNumbering.Relation.DUPLICATE;
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
