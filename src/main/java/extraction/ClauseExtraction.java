package extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mevcut okuyuculara ({@code DocxClauseExtractor}, {@code PdfClauseExtractor}) adaptör.
 *
 * <p>Üç giriş noktası:
 * <ul>
 *   <li>{@link #fromDocxBlocks}  — DOCX paragraf blokları (model çağrısı kaldırılıyor)</li>
 *   <li>{@link #fromPdfLines}    — PDF native mod görsel satırları (LineJoiner açık)</li>
 *   <li>{@link #fromOcrBlocks}   — Mistral OCR blokları (zaten paragraf düzeyinde)</li>
 * </ul>
 *
 * <p>Tüm metodlar aynı {@link ExtractionResult} tipini döndürür; downstream
 * ({@code ReportBuilder}, {@code AnalyzeMerger}) bu sonucu mevcut
 * {@code {"title", "clauses": {"1.1": {"content":"..","blocks":[..]}}}} formatına
 * çevirmek için sadece 3–5 satır kod gerektirir (bkz. INTEGRATION.md Adım 2/3).
 */
public final class ClauseExtraction {

    private ClauseExtraction() {}

    // ------------------------------------------------------------------
    // Sonuç modeli

    /**
     * Çıkarım sonucu: madde numarası → içerik metni + blok id listesi.
     *
     * <p>"Segment-nitelikli" numaralar kullanılır: ana gövde için sade "1", "1.1";
     * ekler için "S1#1", "S1#2" vb. Bu önek raporlarda ek bölümünü ayırt eder.
     */
    public static final class ExtractionResult {
        /** Doküman başlığı (ilk madde öncesi metin, boş string olabilir). */
        public final String title;

        /**
         * Madde numarası (segment-nitelikli) → düz metin içerik.
         * {@link ClauseNumbering#COMPARATOR} ile sıralanmış.
         */
        public final Map<String, String> clauses;

        /**
         * Madde numarası → blok id listesi (orijinal blok kimliklerinden geliyor;
         * {@code DocxClauseExtractor} bunu {@code "blocks"} alanına yazar).
         */
        public final Map<String, List<Integer>> blocksByClause;

        /** Ham segmentasyon ağacı (validator ve teşhis için). */
        public final ClauseTree.Result tree;

        ExtractionResult(String title,
                         Map<String, String> clauses,
                         Map<String, List<Integer>> blocksByClause,
                         ClauseTree.Result tree) {
            this.title         = title != null ? title : "";
            this.clauses       = clauses;
            this.blocksByClause = blocksByClause;
            this.tree          = tree;
        }

        /** Şüpheli sonuç mu? (validator aracılığıyla çağrılır). */
        public boolean isSuspicious() {
            return tree.isEmpty() || hasLowCoverage();
        }

        private boolean hasLowCoverage() {
            // Basit işaret: madde sayısı 0 ya da teşhiste EMPTY/BACKWARD mesaj çoksa
            long problems = tree.diagnostics.stream()
                    .filter(d -> d.startsWith("EMPTY") || d.startsWith("BACKWARD"))
                    .count();
            return problems >= 3;
        }

        /** Teşhis raporunu insan okunur string olarak döndürür. */
        public String diagnosticReport() {
            if (tree.diagnostics.isEmpty()) return "OK";
            return String.join("; ", tree.diagnostics);
        }
    }

    // ------------------------------------------------------------------
    // DOCX giriş noktası

    /**
     * DOCX paragraf bloklarını maddelere böler.
     *
     * <p>LineJoiner <em>uygulanmaz</em> — DOCX blokları zaten paragraf düzeyinde.
     * Bilingual sütun filtresi caller tarafından yapılmış olmalıdır.
     *
     * @param blocks   DOCX blokları ({@link ClauseBlock.Source#DOCX})
     * @param lang     dil kodu (teşhis için)
     * @return çıkarım sonucu
     */
    public static ExtractionResult fromDocxBlocks(List<ClauseBlock> blocks, String lang) {
        ClauseTree tree = new ClauseTree();
        ClauseTree.Result result = tree.segment(blocks);
        return buildResult(result, blocks, lang);
    }

    // ------------------------------------------------------------------
    // PDF native mod giriş noktası

    /**
     * PDF native mod satır bloklarını maddelere böler.
     *
     * <p>Önce {@link LineJoiner} ile satırlar paragrafa birleştirilir,
     * ardından {@link ClauseTree} ile segmentasyon yapılır.
     *
     * @param lines  PDF satır blokları ({@link ClauseBlock.Source#PDF_LINE})
     * @param lang   dil kodu (teşhis için)
     * @return çıkarım sonucu
     */
    public static ExtractionResult fromPdfLines(List<ClauseBlock> lines, String lang) {
        LineJoiner joiner = new LineJoiner();
        List<ClauseBlock> paragraphs = joiner.join(lines);
        ClauseTree tree = new ClauseTree();
        ClauseTree.Result result = tree.segment(paragraphs);
        return buildResult(result, paragraphs, lang);
    }

    // ------------------------------------------------------------------
    // OCR blok giriş noktası

    /**
     * Mistral OCR bloklarını maddelere böler.
     *
     * <p>OCR blokları zaten paragraf düzeyinde olduğundan LineJoiner atlanır.
     *
     * @param ocrBlocks  OCR blokları ({@link ClauseBlock.Source#OCR_BLOCK})
     * @param lang       dil kodu (teşhis için)
     * @return çıkarım sonucu
     */
    public static ExtractionResult fromOcrBlocks(List<ClauseBlock> ocrBlocks, String lang) {
        ClauseTree tree = new ClauseTree();
        ClauseTree.Result result = tree.segment(ocrBlocks);
        return buildResult(result, ocrBlocks, lang);
    }

    // ------------------------------------------------------------------
    // Ortak sonuç oluşturucu

    private static ExtractionResult buildResult(ClauseTree.Result treeResult,
                                                 List<ClauseBlock> blocks,
                                                 String lang) {
        // id → block hızlı erişim için
        Map<Integer, ClauseBlock> blockById = new LinkedHashMap<>();
        for (ClauseBlock b : blocks) {
            blockById.put(b.id, b);
        }

        // Başlık: segmentlerin tamamında madde öncesi metin toplanamaz;
        // şimdilik boş — DocxClauseExtractor zaten ayrı olarak yönetiyor.
        String title = "";

        // Segment-nitelikli madde numaraları → içerik ve blok id listesi
        // ClauseNumbering.COMPARATOR ile sıralanmış TreeMap
        Map<String, String> clauses = new TreeMap<>(ClauseNumbering.COMPARATOR);
        Map<String, List<Integer>> blocksByClause = new TreeMap<>(ClauseNumbering.COMPARATOR);

        for (ClauseTree.Segment seg : treeResult.segments) {
            for (Map.Entry<String, ClauseTree.ClauseEntry> e : seg.entries.entrySet()) {
                String qualifiedNo = seg.qualify(e.getKey());
                ClauseTree.ClauseEntry entry = e.getValue();

                // Blokları metin olarak birleştir
                StringBuilder sb = new StringBuilder();
                for (int blockId : entry.blockIds) {
                    ClauseBlock b = blockById.get(blockId);
                    if (b == null) continue;
                    String text = TextClean.clean(b.text);
                    if (text.isEmpty()) continue;
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(text);
                }

                // Başlık maddesi (syntheticParent): içerik yoksa heading kullan
                if (sb.length() == 0 && !entry.heading.isEmpty()) {
                    sb.append(entry.heading);
                }

                String content = sb.toString().trim();
                if (!content.isEmpty() || entry.syntheticParent) {
                    clauses.put(qualifiedNo, content);
                    blocksByClause.put(qualifiedNo, new ArrayList<>(entry.blockIds));
                }
            }
        }

        return new ExtractionResult(title, clauses, blocksByClause, treeResult);
    }
}
