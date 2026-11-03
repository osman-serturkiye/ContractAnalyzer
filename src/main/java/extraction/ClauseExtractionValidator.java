package extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kalite kapısı: {@link steps.Checkpoint} önünde çalışır; kötü çıkarım sonuçlarını
 * cache'lenmeden önce yakalar.
 *
 * <p>Mevcut {@code Checkpoint.obj()} yalnızca <em>boş</em> clauses sonucunu
 * cache'lemiyor; ama <em>eksik</em> sonucu (örn. 3 madde çıkarılabilmiş ama
 * gerçekte 30 madde var) cache'liyor. Bu validator o boşluğu kapatır.
 *
 * <p>Kullanım (Adım 4 — bkz. INTEGRATION.md):
 * <pre>{@code
 * // Checkpoint.obj çağrısından ÖNCE:
 * List<Finding> findings = ClauseExtractionValidator.validate(extractionResult.tree);
 * if (ClauseExtractionValidator.hasBlocker(findings)) {
 *     throw new IOException("Clause extraction blocked: " + findings);
 * }
 * for (Finding f : findings) {
 *     plog(f.severe ? "ERROR" : "WARN", f.toString());
 * }
 * }</pre>
 */
public final class ClauseExtractionValidator {

    // ------------------------------------------------------------------
    // Bulgu modeli

    /** Bir doğrulama bulgusu. */
    public static final class Finding {
        /** Bulgunun kodu (sabit). */
        public final Code code;
        /** İnsan okunur mesaj. */
        public final String message;
        /** {@code true} → blokeri: cache'leme yapılmamalı, run durdurulmalı. */
        public final boolean severe;

        Finding(Code code, String message, boolean severe) {
            this.code    = code;
            this.message = message;
            this.severe  = severe;
        }

        @Override
        public String toString() {
            return (severe ? "ERROR" : "WARN") + " [" + code + "] " + message;
        }
    }

    /** Bulgu kodları. */
    public enum Code {
        /** Hiç madde bulunamadı. */
        NO_CLAUSES,
        /** Madde sayısı çok az — şüpheli çıkarım. */
        TOO_FEW_CLAUSES,
        /** Madde içeriklerinin büyük çoğunluğu boş. */
        HIGH_EMPTY_RATE,
        /** Ek/şedül segmenti var ama raporlanmıyor — bilgilendirme. */
        ANNEX_SEGMENT_FOUND,
        /** Karşı tarafta baz dokümanın %20'sinden fazla madde yok — muhtemelen hatalı segmentasyon. */
        PAIRING_MISMATCH,
        /** Teşhis geçti. */
        OK
    }

    private ClauseExtractionValidator() {}

    // ------------------------------------------------------------------
    // validate(tree)

    /**
     * Segmentasyon ağacını doğrular.
     *
     * @param tree {@link ClauseTree.Result} nesnesi
     * @return bulgular listesi; boş liste → tamamen geçti
     */
    public static List<Finding> validate(ClauseTree.Result tree) {
        List<Finding> out = new ArrayList<>();

        if (tree == null) {
            out.add(new Finding(Code.NO_CLAUSES, "tree is null", true));
            return out;
        }

        int total = tree.totalClauses();

        // NO_CLAUSES
        if (total == 0) {
            out.add(new Finding(Code.NO_CLAUSES,
                    "no clauses extracted; diagnostics: " + tree.diagnostics, true));
            return out;  // diğer kontroller anlamsız
        }

        // TOO_FEW_CLAUSES — 2'den az madde şüpheli
        if (total < 2) {
            out.add(new Finding(Code.TOO_FEW_CLAUSES,
                    "only " + total + " clause(s) extracted", false));
        }

        // HIGH_EMPTY_RATE — boş içerikli maddeler
        int emptyCount = 0;
        for (ClauseTree.Segment seg : tree.segments) {
            for (ClauseTree.ClauseEntry e : seg.entries.values()) {
                if (e.blockIds.isEmpty() && !e.syntheticParent) emptyCount++;
            }
        }
        if (emptyCount > 0) {
            double rate = (double) emptyCount / total;
            boolean severe = rate >= 0.5;
            out.add(new Finding(Code.HIGH_EMPTY_RATE,
                    emptyCount + "/" + total + " clauses have no blocks (rate=" +
                    String.format("%.0f%%", rate * 100) + ")", severe));
        }

        // ANNEX_SEGMENT_FOUND — bilgilendirme
        if (tree.segments.size() > 1) {
            out.add(new Finding(Code.ANNEX_SEGMENT_FOUND,
                    (tree.segments.size() - 1) + " annex/schedule segment(s) found and preserved",
                    false));
        }

        if (out.isEmpty()) {
            out.add(new Finding(Code.OK, "all checks passed (" + total + " clauses)", false));
        }

        return out;
    }

    // ------------------------------------------------------------------
    // validatePairing

    /**
     * İki doküman çiftini karşılaştırır: baz dokümanın maddelerinin ne kadarı
     * karşı tarafta da mevcut?
     *
     * <p>%80'den azı eşleşiyorsa bu gerçek bir "madde eklendi/silindi" değil,
     * büyük olasılıkla segmentasyon hatasıdır.
     *
     * @param baselineNos  baz doküman madde numaraları seti
     * @param otherNos     karşı doküman madde numaraları seti
     * @return bulgular listesi
     */
    public static List<Finding> validatePairing(Set<String> baselineNos, Set<String> otherNos) {
        List<Finding> out = new ArrayList<>();
        if (baselineNos == null || baselineNos.isEmpty()) return out;

        long matched = baselineNos.stream().filter(otherNos::contains).count();
        double coverage = (double) matched / baselineNos.size();

        if (coverage < 0.80) {
            out.add(new Finding(Code.PAIRING_MISMATCH,
                    String.format("only %.0f%% of baseline clauses (%d/%d) found in counterpart — " +
                            "likely segmentation mismatch, not genuine clause diff",
                            coverage * 100, matched, baselineNos.size()),
                    coverage < 0.50));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // hasBlocker

    /**
     * Listede bloker seviyesinde bir bulgu var mı?
     *
     * @param findings {@link #validate} veya {@link #validatePairing} çıktısı
     * @return {@code true} → cache'leme durdurulmalı, pipeline hata fırlatmalı
     */
    public static boolean hasBlocker(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.severe);
    }
}
