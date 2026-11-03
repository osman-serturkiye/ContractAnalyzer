package extraction;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF görsel satırlarını paragraf düzeyine birleştirir.
 *
 * <p><b>Sorun:</b> {@code PdfDoc.LineBboxStripper} her görsel satırı ayrı blok
 * olarak verir. DOCX ise paragraf bazlı çalışır. Bu fark:
 * <ul>
 *   <li>Sarılan bir cümle 4–5 ayrı blok olur; raporda satır araları boş görünür.</li>
 *   <li>Sarılan bir başlık yalnızca ilk satırından tanınır; kalanı bir sonraki
 *       maddenin gövdesine girer.</li>
 *   <li>ClauseDiffer token bazlı çalıştığı için fark oranı şişer.</li>
 * </ul>
 *
 * <p><b>Birleştirme kuralları</b> (birleştirmeyi <em>kesen</em> koşullar):
 * <ol>
 *   <li>Sayfa değişimi</li>
 *   <li>Sütun değişimi (iki dilli belgeler)</li>
 *   <li>Yeni madde numarası başlığı ({@link ClauseNumbering#parse} != null)</li>
 *   <li>Önceki satır bir cümle sonu noktalı virülü ile bitiyor
 *       (., ?, !, …, ;, :) <em>ve</em> ardından büyük harf/rakam başlıyor</li>
 *   <li>Önceki satır belirgin biçimde kısa (satırın geri kalanını kullanmak
 *       yerine boş alan bırakıyor) — {@code shortLineFactor} ile ayarlanabilir</li>
 * </ol>
 *
 * <p>Satır sonu tirelemesi ({@code "kelime-\nkalan" → "kelimekalan"}) birleştirme
 * sırasında otomatik kaldırılır.
 *
 * <p>OCR blokları zaten paragraf düzeyindedir; bu sınıfı <em>uygulama</em>
 * ({@code Source.OCR_BLOCK}) durumunda atlanabilir; metot yine de güvenle
 * çağrılabilir — bloklar değişmeden döner.
 */
public final class LineJoiner {

    /**
     * Satır birleştirme eşiği.
     *
     * <p>Önceki satır, sayfa genişliğinin ({@code x1} koordinatı temel alınarak)
     * bu oranından daha kısa ise yeni paragraf başladığı kabul edilir.
     * Default: 0.55 (satır uzunluğunun %55'i veya daha azı → kısa satır).
     */
    private final double shortLineFactor;

    /**
     * Sayfa genişliği (0..1000 birim) — kısa satır hesabı için.
     * PDF native modda her sayfa için aynı nominale (1000) göre normalize edilir.
     */
    private static final double PAGE_WIDTH = 1000.0;

    public LineJoiner() {
        this(0.55);
    }

    public LineJoiner(double shortLineFactor) {
        this.shortLineFactor = shortLineFactor;
    }

    // ------------------------------------------------------------------
    // Ana metot

    /**
     * Satır bloklarını paragraflara birleştirir.
     *
     * @param lines kaynak satır blokları (sıralı, tüm sayfalar)
     * @return birleştirilmiş paragraf blokları; her çıkış bloğunun {@code id}'si
     *         birleştirilen ilk satırın {@code id}'si, {@code page}'i o satırın
     *         sayfasıdır; bbox yok sayılır (PdfClauseExtractor bbox'ları kendi
     *         blok setinden hesaplar)
     */
    public List<ClauseBlock> join(List<ClauseBlock> lines) {
        if (lines == null || lines.isEmpty()) return new ArrayList<>();

        List<ClauseBlock> result = new ArrayList<>();
        // Mevcut paragraf akümülatörü
        ClauseBlock first = lines.get(0);
        StringBuilder buf = new StringBuilder(TextClean.clean(first.text));
        int curPage = first.page;
        int curCol  = first.columnIndex;
        int curId   = first.id;

        for (int i = 1; i < lines.size(); i++) {
            ClauseBlock cur = lines.get(i);
            ClauseBlock prev = lines.get(i - 1);
            String curText = TextClean.clean(cur.text);

            if (shouldBreak(prev, cur, buf.toString())) {
                // Paragrafı bitir
                result.add(new ClauseBlock(curId, curPage, buf.toString().trim(),
                        cur.source, curCol));
                buf  = new StringBuilder(curText);
                curPage = cur.page;
                curCol  = cur.columnIndex;
                curId   = cur.id;
            } else {
                // Birleştir: satır sonu tiresini kaldır, değilse boşlukla birleştir
                String prevBuf = buf.toString();
                if (prevBuf.endsWith("-")) {
                    // Satır sonu tirelemesi: tire kaldır, doğrudan birleştir
                    buf.setLength(buf.length() - 1);
                    buf.append(curText);
                } else {
                    if (prevBuf.length() > 0 && curText.length() > 0) {
                        buf.append(' ');
                    }
                    buf.append(curText);
                }
            }
        }

        // Son paragraf
        if (buf.length() > 0) {
            result.add(new ClauseBlock(curId, curPage, buf.toString().trim(),
                    lines.get(lines.size() - 1).source, curCol));
        }

        return result;
    }

    // ------------------------------------------------------------------
    // Karar mantığı

    /**
     * Bir sonraki satıra geçişin yeni paragraf mı yoksa devam mı olduğunu belirler.
     *
     * @param prev    önceki satır bloğu
     * @param next    bir sonraki satır bloğu
     * @param prevBuf önceki paragraf akümülatörünün mevcut içeriği
     * @return {@code true} → yeni paragraf başlat
     */
    boolean shouldBreak(ClauseBlock prev, ClauseBlock next, String prevBuf) {
        // Kural 1: Sayfa değişimi
        if (next.page != prev.page) return true;

        // Kural 2: Sütun değişimi (her ikisi de -1 değilse)
        if (prev.columnIndex >= 0 && next.columnIndex >= 0
                && prev.columnIndex != next.columnIndex) return true;

        String nextText = TextClean.clean(next.text).trim();

        // Kural 3: Yeni madde başlığı
        if (ClauseNumbering.parse(nextText) != null) return true;

        // Kural 4: Önceki satır cümle sonu + sonraki büyük/rakam başlar
        String bufTrimmed = prevBuf.trim();
        if (bufTrimmed.length() > 0 && nextText.length() > 0) {
            char last = bufTrimmed.charAt(bufTrimmed.length() - 1);
            char nextFirst = nextText.charAt(0);
            boolean sentenceEnd = last == '.' || last == '?' || last == '!'
                    || last == '\u2026' || last == ';' || last == ':';
            boolean upperOrDigit = Character.isUpperCase(nextFirst)
                    || Character.isDigit(nextFirst)
                    || nextFirst == '(' || nextFirst == '[';
            if (sentenceEnd && upperOrDigit) return true;
        }

        // Kural 5: Kısa satır (önceki satır çok kısa → paragraf bitti)
        // prev.x1 koordinatı kısa satır tespiti için kullanılır
        // Sadece koordinat bilgisi varsa (x1 > 0) uygula
        if (prev.x1 > 0 && (prev.x1 / PAGE_WIDTH) < shortLineFactor) {
            return true;
        }

        return false;
    }
}
