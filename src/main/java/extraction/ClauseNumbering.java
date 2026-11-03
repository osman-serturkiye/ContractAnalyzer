package extraction;

import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tek doğruluk kaynağı: ham madde numarası metnini parse eder ve normalize edilmiş
 * "Arabic digit + nokta" biçimine dönüştürür. Model hiç kullanılmaz.
 *
 * <p>Desteklenen formatlar:
 * <ul>
 *   <li>Arabic: "1", "1.1", "2.3.4"</li>
 *   <li>Türkçe: "Madde 5", "MADDE 5", "Md. 1", "Md.1"</li>
 *   <li>Almanca: "§ 2.1", "§2.1", "Artikel 3", "Ziffer 4"</li>
 *   <li>Fransızca/İngilizce: "Article 5", "Art. 3", "Section 2", "Clause 3"</li>
 *   <li>Arapça: "المادة 5"</li>
 *   <li>Roman: "I", "II", "III" ... "XII" (bağımsız satır olarak)</li>
 *   <li>Parantezli desimal: "(1)", "(2)"</li>
 *   <li>Parantezli harf: "(a)", "(b)"</li>
 *   <li>Parantezli roman: "(i)", "(ii)", "(iii)"</li>
 *   <li>Sondaki parantez: "1)", "a)", "i)"</li>
 * </ul>
 *
 * <p>Sıralama için {@link #COMPARATOR} kullanın.
 */
public final class ClauseNumbering {

    // ------------------------------------------------------------------
    // Sabitleri ve regex'leri

    /**
     * Bir satırın madde başlığı olup olmadığını test etmek için kullanılan
     * tüm formatları kapsayan ana pattern.
     *
     * Grup haritası:
     *   G1  → multi-level dotted "1.1", "1.1.2"
     *   G2  → single-level dotted "1."
     *   G3  → bare positive integer "1", "10" (tek başına satırda olmalı)
     *   G4  → keyword prefix (Madde/Article/Section/Clause/§/Artikel/Ziffer/Art.)
     *   G4a → keyword'den sonraki numara
     *   G5  → Arapça المادة N
     *   G5a → Arapça'dan sonraki numara
     *   G6  → Roman numeral (bağımsız, büyük harf)
     *   G7  → parenthesized decimal "(1)"
     *   G8  → parenthesized letter "(a)"
     *   G9  → parenthesized roman "(i)"
     *   G10 → trailing-paren decimal "1)"
     *   G11 → trailing-paren letter "a)"
     */
    private static final Pattern HEADING = Pattern.compile(
            // G1: multi-level  "1.1", "1.1.2", "10.3.1[.]"  — max 4 dots = 5 components
            // Trailing dot allowed only if NOT followed by a digit (avoids "1.2.3.4.5.6" → "1.2.3.4.5")
            "^(\\d{1,4}(?:\\.\\d{1,4}){1,4}\\.?)(?:[\\s:]|$|(?=\\.[^\\d]))"
            // G2: single-level trailing dot  "1."  "2."
            + "|^(\\d{1,4}\\.)(?:[\\s:]|$)"
            // G3: bare integer, ONLY when whole line (no lowercase text follows — guards refs like "3. maddede")
            + "|^(\\d{1,4})(?:\\s+[\\p{Lu}\\p{N}]|$)"
            // G4: keyword prefix (case-insensitive) — Madde, MADDE, Md., Article, Art., Section, Clause, §, Artikel, Ziffer
            + "|^(?:MADDE|Madde|madde|Md\\.?|ARTICLE|Article|article|Art\\.?|SECTION|Section|section"
            +   "|CLAUSE|Clause|clause|§\\s*|ARTIKEL|Artikel|artikel|ZIFFER|Ziffer|ziffer)\\s*(\\d[\\d.]*)"
            // G5: Arabic المادة N
            + "|\u0627\u0644\u0645\u0627\u062f\u0629\\s*(\\d[\\d.]*)"
            // G6: Roman numeral, standalone uppercase — I..XII
            + "|^(I{1,3}|IV|VI{0,3}|IX|XI{1,3}|XII)(?:[.\\s:]|$)"
            // G7: parenthesized decimal "(1)" .. "(99)"
            + "|^\\((\\d{1,2})\\)(?:[\\s.]|$)"
            // G8: parenthesized letter "(a)" .. "(z)"
            + "|^\\(([a-zA-Z])\\)(?:[\\s.]|$)"
            // G9: parenthesized roman "(i)" .. "(xvi)"
            + "|^\\((i{1,3}|iv|vi{0,3}|ix|x{1,3}|xi{1,3}|xiv|xv|xvi)\\)(?:[\\s.]|$)"
            // G10: trailing-paren decimal  "1)"  "12)"
            + "|^(\\d{1,2})\\)(?:\\s|$)"
            // G11: trailing-paren letter  "a)"  "b)"
            + "|^([a-zA-Z])\\)(?:\\s|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // Legal cross-reference guard: matches inline legal references (NOT headings).
    // Covers patterns like: "HMK'nın 193. maddesi", "3. maddede belirtilen", "Article 193 of the Code"
    // Strategy: a line is a legal reference when it contains a keyword followed by a number,
    // AND either (a) the number is > 20 with no sub-clause dot, OR (b) a possessive/relational
    // suffix follows (Turkish: -de/-da/-deki/-nin, English: "of the", German: "Abs.").
    private static final Pattern LEGAL_REF_SUFFIX = Pattern.compile(
            // Keyword + number + Turkish/German/English suffix indicating "of article N"
            "(?:mad(?:de|d\\.?)|maddesi|maddede[ki]*|maddeler(?:in|e|i)?|"
            + "article|art\\.?|section|clause|§)\\s*"
            + "\\d[\\d.]*"
            + "\\s*(?:'[a-z]+|[a-z]+[dn][ea]|\\s+of\\b|\\s+(?:und|von|des|Abs\\.?))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // Simpler: "X. maddede/maddesi/maddesinde" anywhere in the line — definitely a reference
    private static final Pattern LEGAL_REF_INFIX = Pattern.compile(
            "\\d[\\d.]*\\.?\\s+madde(?:de|si|sinde|ler|leri|nin|ye|yi)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // Numeric-only matcher for normalize()
    private static final Pattern NUMERIC_DOTTED = Pattern.compile("\\d+(?:\\.\\d+)*");

    // Roman numerals → decimal
    private static final String[] ROMAN_WORDS = {"XII","XI","IX","VIII","VII","VI","IV","III","II","I","X","V"};
    private static final int[]    ROMAN_VALS   = {  12,  11,   9,    8,    7,   6,   4,    3,   2,  1, 10,  5};

    private ClauseNumbering() {}

    // ------------------------------------------------------------------
    // Parse sonucu

    /** Bir satırın parse edilmiş madde numarası sonucu. */
    public enum Kind {
        /** Bağımsız sayısal/roman/keyword prefix biçimi: "1.", "Madde 5", "§ 2.1" */
        EXPLICIT,
        /** Parantezli veya sondaki parantez: "(1)", "(a)", "1)", "a)" */
        PARENTHESIZED
    }

    public static final class ParseResult {
        /** Normalize edilmiş numera: "1", "1.1", "2.3.4" biçimi */
        public final String normalized;
        /** Ham token (orijinal match) */
        public final String raw;
        /** Madde metninin geri kalanı (numera prefixinden sonraki kısım) */
        public final String tail;
        /** EXPLICIT veya PARENTHESIZED */
        public final Kind kind;

        private ParseResult(String normalized, String raw, String tail, Kind kind) {
            this.normalized = normalized;
            this.raw = raw;
            this.tail = tail;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return "ParseResult{no=" + normalized + ", kind=" + kind + ", tail='" + tail + "'}";
        }
    }

    /**
     * Verilen satırı madde başlığı olarak parse eder.
     *
     * @param line ham satır metni (trim edilmemiş olabilir)
     * @return parse sonucu veya {@code null} — madde başlığı değil
     */
    public static ParseResult parse(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty()) return null;

        // Yasal çapraz-atıf reddi: "HMK'nın 193. maddesi" gibi satırlar heading değil.
        // Tam satır bir referans ise çevirme yapma. Ama headings içinde de geçebilir
        // (örn. "Madde 5 — HMK 193 uyarınca") — bu yüzden sadece satırın tamamı
        // referans ise reddediyoruz.
        if (isLegalReference(t)) return null;

        Matcher m = HEADING.matcher(t);
        if (!m.find()) return null;

        String raw;
        String no;
        Kind kind;

        if (m.group(1) != null) {
            raw = m.group(1); no = stripTrailingDot(raw); kind = Kind.EXPLICIT;
        } else if (m.group(2) != null) {
            raw = m.group(2); no = stripTrailingDot(raw); kind = Kind.EXPLICIT;
        } else if (m.group(3) != null) {
            raw = m.group(3); no = raw; kind = Kind.EXPLICIT;
        } else if (m.group(4) != null) {
            raw = m.group(0).trim(); no = normalizeNo(m.group(4)); kind = Kind.EXPLICIT;
        } else if (m.group(5) != null) {
            raw = m.group(0).trim(); no = normalizeNo(m.group(5)); kind = Kind.EXPLICIT;
        } else if (m.group(6) != null) {
            raw = m.group(6); no = String.valueOf(romanToInt(raw.trim())); kind = Kind.EXPLICIT;
        } else if (m.group(7) != null) {
            raw = m.group(0).trim(); no = m.group(7); kind = Kind.PARENTHESIZED;
        } else if (m.group(8) != null) {
            raw = m.group(0).trim(); no = m.group(8).toLowerCase(); kind = Kind.PARENTHESIZED;
        } else if (m.group(9) != null) {
            raw = m.group(0).trim(); no = m.group(9).toLowerCase(); kind = Kind.PARENTHESIZED;
        } else if (m.group(10) != null) {
            raw = m.group(0).trim(); no = m.group(10); kind = Kind.PARENTHESIZED;
        } else if (m.group(11) != null) {
            raw = m.group(0).trim(); no = m.group(11).toLowerCase(); kind = Kind.PARENTHESIZED;
        } else {
            return null;
        }

        if (no == null || no.isEmpty()) return null;

        String tail = t.substring(m.end()).trim();
        return new ParseResult(no, raw, tail, kind);
    }

    // ------------------------------------------------------------------
    // Successor (ardıl) doğrulaması — 4 kural + ek sınıf

    /** İki ardıl arasındaki ilişki. */
    public enum Relation {
        /** Geçerli ardıl (normal ilerleme) */
        VALID,
        /** Geçerli ama "sıfırlama" (Ek-1 gibi yeni bölümde numara 1'e dönüş) */
        RESET,
        /** Aynı numera tekrarı (chunk-sınırı devamlılık) */
        DUPLICATE,
        /** Geriye gidiş — ek/schedule bölümünde kabul edilmeli */
        BACKWARD,
        /** Tamamen geçersiz (atıf, aşırı derinlik vb.) */
        INVALID
    }

    private static final int MAX_SKIP = 10;

    /**
     * {@code candidate}'nin {@code current}'tan sonra geçerli bir ardıl olup
     * olmadığını belirler.
     *
     * <p>{@link util.ClauseNumber#isValidNextClause} ile tam uyumludur — aynı 4
     * kuralı uygular. Fark: bu metot RESET ve BACKWARD durumlarını da tanır,
     * onları sessizce reddetmek yerine caller'ın karar vermesini sağlar.
     *
     * @param current   en son kabul edilen madde numarası (normalize edilmiş), null/boş = hiç yok
     * @param candidate adaylık eden madde numarası (normalize edilmiş, digits+dots)
     */
    public static Relation classify(String current, String candidate) {
        if (candidate == null || candidate.isEmpty()) return Relation.INVALID;
        if (!candidate.matches("\\d+(\\.\\d+)*")) return Relation.INVALID;

        int[] cands = parts(candidate);

        // İlk madde: first-component <= 20 ise VALID, > 20 ise muhtemelen yasal atıf
        if (current == null || current.isEmpty()) {
            return cands[0] <= 20 ? Relation.VALID : Relation.INVALID;
        }

        int[] currs = parts(current);
        int curDepth  = currs.length;
        int candDepth = cands.length;

        // Aşırı derinlik — direkt geçersiz
        if (candDepth > curDepth + 1) return Relation.INVALID;

        // Aynı numera → chunk-sınırı tekrarı
        if (candidate.equals(current)) return Relation.DUPLICATE;

        if (candDepth == curDepth) {
            // Prefix eşleşmeli
            for (int i = 0; i < candDepth - 1; i++) {
                if (cands[i] != currs[i]) {
                    // Prefix farklı ama ilk bileşen artıyor → normal pop-up + advance
                    // Örn. "3.2" → "4.1" direkt geçersiz (önce "4" gelmeli)
                    // Ama "3.2" → "4" → o zaman candDepth < curDepth path'ine girer
                    return Relation.INVALID;
                }
            }
            int diff = cands[candDepth - 1] - currs[curDepth - 1];
            if (diff > 0 && diff <= MAX_SKIP) return Relation.VALID;
            if (diff > MAX_SKIP) return Relation.RESET;   // Ek bölümde 1'den başlama
            if (diff < 0) return Relation.BACKWARD;
            return Relation.DUPLICATE; // diff == 0 (candidate == current) zaten yukarıda yakalandı

        } else if (candDepth == curDepth + 1) {
            // Bir seviye derine: prefix = current tam, yeni bileşen = 1
            for (int i = 0; i < curDepth; i++) {
                if (cands[i] != currs[i]) return Relation.INVALID;
            }
            return cands[candDepth - 1] == 1 ? Relation.VALID : Relation.INVALID;

        } else {
            // Seviye yukarı çıkış (candDepth < curDepth)
            int shared = candDepth - 1;
            for (int i = 0; i < shared; i++) {
                if (cands[i] != currs[i]) {
                    // Prefix farklı: geriye gidiş veya yeni bölüm
                    return Relation.RESET;
                }
            }
            int currAtLevel = shared < curDepth ? currs[shared] : 0;
            int diff = cands[candDepth - 1] - currAtLevel;
            if (diff > 0 && diff <= MAX_SKIP) return Relation.VALID;
            if (diff > MAX_SKIP) return Relation.RESET;
            if (diff < 0) return Relation.BACKWARD;
            return Relation.DUPLICATE;
        }
    }

    /**
     * Geriye uyumluluk köprüsü: mevcut {@link util.ClauseNumber#isValidNextClause}
     * ile aynı davranışı üretir — VALID veya DUPLICATE → true, diğerleri → false.
     */
    public static boolean isValidSuccessor(String current, String candidate) {
        Relation r = classify(current, candidate);
        return r == Relation.VALID || r == Relation.DUPLICATE;
    }

    // ------------------------------------------------------------------
    // normalize — mevcut util.ClauseNumber ile uyumlu

    /**
     * Ham metinden ilk sayısal+noktalı token'ı çıkarır.
     * "Madde 5" → "5", "1.1." → "1.1".
     * null/boş için null döner.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        Matcher m = NUMERIC_DOTTED.matcher(raw);
        if (m.find()) return m.group();
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    // ------------------------------------------------------------------
    // Sıralayıcı

    /**
     * Sayısal olarak doğru sıralayan karşılaştırıcı: "1.10" > "1.9".
     * util.ClauseNumber.COMPARATOR ile davranış özdeştir.
     */
    public static final Comparator<String> COMPARATOR = (a, b) -> {
        String na = normalize(a); if (na == null) na = a;
        String nb = normalize(b); if (nb == null) nb = b;
        String[] x = na.split("\\.");
        String[] y = nb.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = compareSegment(x[i], y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    };

    // ------------------------------------------------------------------
    // Yardımcı

    /** "HMK'nın 193. maddesi", "3. maddede belirtilen" gibi yasal atıf satırlarını tespit eder. */
    public static boolean isLegalReference(String line) {
        if (line == null || line.isEmpty()) return false;
        String t = line.trim();

        // Kural 1: satır keyword + büyük sayı ile başlıyor (tek bileşen, nokta yok) → atıf
        Matcher m = Pattern.compile(
                "^(?:article|art\\.?|section|clause|§\\s*|mad(?:de|d\\.?)?)\\s*(\\d+)(?![.\\d])",
                Pattern.CASE_INSENSITIVE).matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n > 20) return true;
        }

        // Kural 2: "X. maddede / maddesi / maddesinde" infix → kesinlikle atıf
        if (LEGAL_REF_INFIX.matcher(t).find()) return true;

        // Kural 3: keyword + numara + possessive/relational suffix
        return LEGAL_REF_SUFFIX.matcher(t).find();
    }

    /** Normalize edilmiş numera içindeki trailing dot'ı siler: "1.1." → "1.1" */
    private static String stripTrailingDot(String s) {
        if (s == null) return null;
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String normalizeNo(String raw) {
        return stripTrailingDot(raw == null ? null : raw.trim());
    }

    private static int[] parts(String no) {
        String[] segs = no.split("\\.");
        int[] out = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            try { out[i] = Integer.parseInt(segs[i]); } catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    private static int compareSegment(String p, String q) {
        boolean pn = p.matches("\\d+");
        boolean qn = q.matches("\\d+");
        if (pn && qn) return Integer.compare(Integer.parseInt(p), Integer.parseInt(q));
        if (pn != qn) return pn ? -1 : 1;
        return p.compareToIgnoreCase(q);
    }

    static int romanToInt(String s) {
        String u = s.toUpperCase().trim();
        for (int i = 0; i < ROMAN_WORDS.length; i++) {
            if (ROMAN_WORDS[i].equals(u)) return ROMAN_VALS[i];
        }
        return 0;
    }
}
