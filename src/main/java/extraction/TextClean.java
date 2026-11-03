package extraction;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Ham metin temizleyici.
 *
 * <p>Madde içeriği karşılaştırmaya (ClauseDiffer) veya rapora girmeden önce
 * bu sınıftan geçirilir. Ligatür açımı, yumuşak tire kaldırma, NBSP çevirme
 * ve minimal Markdown temizliği yapılır.
 *
 * <p>Bu sınıf yalnızca <b>normalize</b> eder; Markdown yapısını <em>yok etmez</em>.
 * Tam Markdown soyutlama için {@link util.TextNormalizer} kullanın.
 */
public final class TextClean {

    // ------------------------------------------------------------------
    // Karakter düzeltmeleri

    /** Unicode ligatür açım tablosu. */
    private static final char[][] LIGATURES = {
        // Latin ligatürleri (PDF/OCR çıktısında yaygın)
        {'\uFB00', 'f', 'f'},        // ﬀ → ff
        {'\uFB01', 'f', 'i'},        // ﬁ → fi
        {'\uFB02', 'f', 'l'},        // ﬂ → fl
        {'\uFB03', 'f', 'f', 'i'},   // ﬃ → ffi
        {'\uFB04', 'f', 'f', 'l'},   // ﬄ → ffl
        {'\uFB05', 's', 't'},        // ﬅ → st
        {'\uFB06', 's', 't'},        // ﬆ → st
        // Türkçe/yaygın
        {'\u0132', 'I', 'J'},        // Ĳ → IJ
        {'\u0133', 'i', 'j'},        // ĳ → ij
        {'\u0152', 'O', 'E'},        // Œ → OE
        {'\u0153', 'o', 'e'},        // œ → oe
        {'\u00C6', 'A', 'E'},        // Æ → AE
        {'\u00E6', 'a', 'e'},        // æ → ae
    };

    // Yumuşak tire (soft hyphen) — Unicode U+00AD
    private static final char SOFT_HYPHEN = '\u00AD';
    // Non-breaking space — Unicode U+00A0
    private static final char NBSP = '\u00A0';
    // Sıfır-genişlikli boşluklar
    private static final Pattern ZERO_WIDTH = Pattern.compile(
            "[\u200B\u200C\u200D\uFEFF\u00AD]");  // ZWSP, ZWNJ, ZWJ, BOM, soft-hyphen
    // Satır sonu tirelemesi: kelime- \n → kelime (tek tire + satır sonu)
    private static final Pattern LINE_HYPHEN = Pattern.compile(
            "-\\s*\\n\\s*");
    // Birden fazla boşluk → tek boşluk
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");

    private TextClean() {}

    // ------------------------------------------------------------------
    // Ana metot

    /**
     * Ham metin üzerinde tüm temizleme adımlarını uygular.
     *
     * <p>Adımlar:
     * <ol>
     *   <li>null/boş guard</li>
     *   <li>Unicode NFKC normalizasyonu (yarım-genişlik → tam genişlik vb.)</li>
     *   <li>Ligatür açımı (ﬁ→fi, ﬂ→fl vb.)</li>
     *   <li>NBSP → boşluk</li>
     *   <li>Sıfır-genişlikli karakterleri kaldır (yumuşak tire dahil)</li>
     *   <li>Satır sonu tirelemesi düzelt ("kelime-\nkalan" → "kelimekalan")</li>
     *   <li>Tipografik tırnakları ASCII'ye çevir (karşılaştırma tutarlılığı)</li>
     *   <li>Ardışık boşlukları tek boşluğa indir (satır başı/sonu boşluğu korunur)</li>
     * </ol>
     *
     * @param raw ham metin (null güvenli)
     * @return temizlenmiş metin; null girişe "" döner
     */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) return "";

        // 1. NFKC: yarım-genişlik, uyumluluk ayrışımı vb.
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);

        // 2. Ligatürler
        s = expandLigatures(s);

        // 3. NBSP → regular space
        s = s.replace(NBSP, ' ');

        // 4. Sıfır-genişlikli karakterler ve soft hyphen
        s = ZERO_WIDTH.matcher(s).replaceAll("");

        // 5. Satır sonu tirelemesi: "kelime-\nkalan" → "kelimekalan"
        s = LINE_HYPHEN.matcher(s).replaceAll("");

        // 6. Tipografik tırnaklar → ASCII
        s = s.replace('\u2018', '\'').replace('\u2019', '\'')
             .replace('\u201C', '"').replace('\u201D', '"')
             .replace('\u201E', '"').replace('\u201F', '"');

        // 7. Ardışık boşluklar → tek boşluk (satır başı/sonu dokunulmadan)
        s = MULTI_SPACE.matcher(s).replaceAll(" ");

        return s;
    }

    /**
     * Hem {@link #clean} uygular hem de Markdown yapısal karakterlerini kaldırır.
     * Tam düz metin karşılaştırması için kullanın.
     *
     * <p>Markdown karakterleri: *, _, #, `, [, ], |
     */
    public static String cleanToPlain(String raw) {
        String s = clean(raw);
        // Markdown emphasis, headings, tables, code
        s = s.replaceAll("[*_#`|\\[\\]]", " ");
        s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    // ------------------------------------------------------------------
    // Yardımcı

    private static String expandLigatures(String s) {
        // Tablo küçük olduğu için StringBuilder ile tek geçiş daha verimli
        StringBuilder sb = null;  // lazy init
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char[] expansion = findLigature(c);
            if (expansion != null) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 8);
                    sb.append(s, 0, i);
                }
                for (char e : expansion) sb.append(e);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : s;
    }

    private static char[] findLigature(char c) {
        for (char[] row : LIGATURES) {
            if (row[0] == c) {
                char[] out = new char[row.length - 1];
                System.arraycopy(row, 1, out, 0, out.length);
                return out;
            }
        }
        return null;
    }
}
