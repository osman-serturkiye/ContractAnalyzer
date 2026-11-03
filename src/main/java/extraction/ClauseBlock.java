package extraction;

/**
 * Kaynak-bağımsız blok modeli.
 *
 * <p>Hem DOCX paragraflarını ({@code DocxReader.Block}) hem de PDF satır/OCR
 * bloklarını ({@code PdfClauseExtractor.Block}) aynı türle temsil eder.
 * Adaptörler ({@link ClauseExtraction}) kaynak tipini bu ortak modele dönüştürür;
 * {@link ClauseTree} yalnızca bu tiple çalışır.
 */
public final class ClauseBlock {

    // ------------------------------------------------------------------
    // Blok türleri

    /** Blok kaynağının tipi. */
    public enum Source {
        /** Apache POI / DocxReader paragrafı. */
        DOCX,
        /** PDFBox LineBboxStripper görsel satırı. */
        PDF_LINE,
        /** Mistral OCR bloğu (zaten paragraf düzeyinde). */
        OCR_BLOCK
    }

    // ------------------------------------------------------------------
    // Alanlar

    /** Doküman genelinde sıralı, 0-tabanlı blok kimliği. */
    public final int id;

    /** 0-tabanlı sayfa numarası. */
    public final int page;

    /** Blok metni (ham; TextClean uygulanmamış). */
    public final String text;

    /** Blok kaynağı. */
    public final Source source;

    /**
     * Sütun dizini — iki dilli belgeler için:
     * <ul>
     *   <li>{@code -1} — tek sütun veya tam genişlikli paylaşılan blok</li>
     *   <li>{@code 0}  — sol sütun (birincil dil)</li>
     *   <li>{@code 1}  — sağ sütun (ikincil dil)</li>
     * </ul>
     */
    public final int columnIndex;

    /**
     * Bounding box, 0..1000 koordinat sisteminde (sayfa genişliği/yüksekliği 1000 birim).
     * DOCX bloklarında bbox mevcut değildir; bu durumda tüm değerler {@code 0}.
     */
    public final double x0, y0, x1, y1;

    // ------------------------------------------------------------------
    // Yapılandırıcılar

    /** Tam yapılandırıcı (PDF/OCR blokları için). */
    public ClauseBlock(int id, int page, String text, Source source, int columnIndex,
                       double x0, double y0, double x1, double y1) {
        this.id = id;
        this.page = page;
        this.text = text != null ? text : "";
        this.source = source;
        this.columnIndex = columnIndex;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    /** DOCX blokları için kısa yapılandırıcı (bbox yok). */
    public ClauseBlock(int id, int page, String text, Source source, int columnIndex) {
        this(id, page, text, source, columnIndex, 0, 0, 0, 0);
    }

    /** En basit yapılandırıcı (test ve tek dilli belgeler için). */
    public ClauseBlock(int id, int page, String text, Source source) {
        this(id, page, text, source, -1, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------
    // Yardımcı

    /** {@code true} ise bu blok tam genişlikli (paylaşılan başlık/önsöz vb.). */
    public boolean isFullWidth() {
        return columnIndex < 0;
    }

    @Override
    public String toString() {
        return "ClauseBlock{id=" + id + ", p=" + page + ", col=" + columnIndex
                + ", text='" + abbreviate(text, 60) + "'}";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
