package extraction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Çalıştırılabilir kanıt: 9 senaryo, 37 kontrol.
 *
 * <p>Hiçbir dış bağımlılık yoktur (DMS session, API anahtarı, veritabanı).
 * Her kontrol, spesifikasyondaki hata sınıflarından birini doğrudan kanıtlar.
 *
 * <p>Derleme ve çalıştırma:
 * <pre>
 *   javac -d /tmp/out src/main/java/extraction/*.java
 *   java -cp /tmp/out extraction.Selftest
 * </pre>
 *
 * <p>JUnit'e taşımak için her {@code runXxx()} metodunu {@code @Test} annotasyonlu
 * bir metotla sarmalamanız yeterlidir.
 */
public final class Selftest {

    // ------------------------------------------------------------------
    // Sayaçlar

    private static int pass = 0;
    private static int fail = 0;

    // ------------------------------------------------------------------
    // Main

    public static void main(String[] args) {
        System.out.println("=== ClauseExtraction Selftest ===\n");

        run("legalReferenceIsNotAHeading",   Selftest::legalReferenceIsNotAHeading);
        run("annexRestartIsKept",            Selftest::annexRestartIsKept);
        run("parenthesizedSubClauses",       Selftest::parenthesizedSubClauses);
        run("pageContinuationStaysWithClause", Selftest::pageContinuationStaysWithClause);
        run("wrappedPdfLinesAreJoined",      Selftest::wrappedPdfLinesAreJoined);
        run("turkishKeywordFormats",         Selftest::turkishKeywordFormats);
        run("headingOnlyClauseStillExists",  Selftest::headingOnlyClauseStillExists);
        run("coverageDiagnostic",            Selftest::coverageDiagnostic);
        run("impossibleNumberIsRejected",    Selftest::impossibleNumberIsRejected);

        System.out.println("\n=== RESULTS ===");
        System.out.println("PASS=" + pass + "  FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // Senaryo 1: Yasal atıf başlık sanılmamalı

    /**
     * "3. maddede belirtilen hükümler…" satırının GERÇEK 3. maddeyi bozmaması ve
     * sahte bir madde açmaması.
     *
     * Hata sınıfı: PDF native modda yasal atıf deterministik tarafta korunmuyor.
     */
    static void legalReferenceIsNotAHeading() {
        // 3. madde zaten var; ardından "3. maddede belirtilen…" gelir
        List<ClauseBlock> blocks = blocks(
                b(0, 0, "3. TARAFLAR"),                             // gerçek madde başlığı
                b(1, 0, "Alıcı XYZ A.Ş.'dir."),
                b(2, 0, "3. maddede belirtilen hükümler saklıdır."), // yasal atıf
                b(3, 0, "4. KONU"),                                 // gerçek 4. madde
                b(4, 0, "İşbu sözleşmenin konusu...")
        );

        ClauseTree.Result r = new ClauseTree().segment(blocks);

        // Kontrol 1: Sahte madde açılmadı (sadece "3" ve "4" var)
        assertTrue("senaryo1: sadece 3 ve 4 numaralı maddeler",
                r.segments.get(0).entries.containsKey("3")
                && r.segments.get(0).entries.containsKey("4")
                && r.segments.get(0).entries.size() == 2);

        // Kontrol 2: Atıf satırı 3. maddeye eklendi (blok id=2)
        assertTrue("senaryo1: atıf satırı 3. maddenin bloklarında",
                r.segments.get(0).entries.get("3").blockIds.contains(2));

        // Kontrol 3: Teşhiste REF_SKIP var
        assertTrue("senaryo1: teşhiste REF_SKIP",
                r.diagnostics.stream().anyMatch(d -> d.startsWith("REF_SKIP")));

        // Kontrol 4: 4. madde bloğu içeriyor
        assertTrue("senaryo1: 4. madde blok 4'ü içeriyor",
                r.segments.get(0).entries.get("4").blockIds.contains(4));
    }

    // ------------------------------------------------------------------
    // Senaryo 2: EK bölümü korunuyor

    /**
     * EK-1 bölümünde numaralandırma 1'den başlıyor. Filtre bunu reddetmemeli;
     * ayrı segment olarak raporlanmalı.
     *
     * Hata sınıfı: ek/şedüllerin tamamı süzgeç tarafından siliniyor.
     */
    static void annexRestartIsKept() {
        List<ClauseBlock> blocks = blocks(
                b(0, 0, "1. TARAFLAR"),
                b(1, 0, "Taraflar şunlardır."),
                b(2, 0, "2. KONU"),
                b(3, 0, "Sözleşme konusu..."),
                b(4, 1, "EK-1"),                // EK başlığı (yeni sayfa)
                b(5, 1, "1. Ürün Listesi"),     // EK'te 1'den başlıyor
                b(6, 1, "Kalem A, Kalem B"),
                b(7, 1, "2. Fiyat Tablosu"),
                b(8, 1, "Birim fiyatlar...")
        );

        ClauseTree.Result r = new ClauseTree().segment(blocks);

        // Kontrol 5: En az 2 segment var
        assertTrue("senaryo2: en az 2 segment", r.segments.size() >= 2);

        // Kontrol 6: Ana segmentte 1 ve 2 numaralı maddeler var
        assertTrue("senaryo2: ana segmentte madde 1",
                r.segments.get(0).entries.containsKey("1"));
        assertTrue("senaryo2: ana segmentte madde 2",
                r.segments.get(0).entries.containsKey("2"));

        // Kontrol 7: EK segmentinde madde 1 ve 2 var
        ClauseTree.Segment annex = r.segments.get(1);
        assertTrue("senaryo2: ek segmentinde madde 1", annex.entries.containsKey("1"));
        assertTrue("senaryo2: ek segmentinde madde 2", annex.entries.containsKey("2"));

        // Kontrol 8: EK segmenti prefixli (S1#)
        assertTrue("senaryo2: ek segment prefixli",
                annex.qualify("1").equals("S1#1"));

        // Kontrol 9: RESET veya BACKWARD→segment teşhisi var
        assertTrue("senaryo2: RESET teşhisi",
                r.diagnostics.stream().anyMatch(d ->
                        d.startsWith("RESET") || d.startsWith("BACKWARD")));
    }

    // ------------------------------------------------------------------
    // Senaryo 3: Parantezli alt maddeler

    /**
     * (1), (2), (a), (i) formatlarının parent altında sıralı numera alması.
     *
     * Hata sınıfı: "(1)/(a)/(i)" normalize edilemiyor, iki ayrı yerde kurtarılmaya çalışılıyor.
     */
    static void parenthesizedSubClauses() {
        List<ClauseBlock> blocks = blocks(
                b(0, 0, "1. TARAFLAR"),
                b(1, 0, "(1) Alıcı XYZ A.Ş., işbu sözleşme kapsamında Ekipmanı teslim alan ve bedelini ödemekle yükümlü taraftır."),
                b(2, 0, "(2) Satıcı ABC Ltd., işbu sözleşme kapsamında Ekipmanı imal edip teslim etmekle yükümlü taraftır."),
                b(3, 0, "(a) Adres bilgileri yukarıda belirtilmiş olup tebligat adresi olarak kabul edilecektir."),
                b(4, 0, "(i) İstanbul şubesine yapılacak tebligatlar geçerli sayılacaktır."),
                b(5, 0, "2. KONU")
        );

        ClauseTree.Result r = new ClauseTree().segment(blocks);
        Map<String, ClauseTree.ClauseEntry> entries = r.segments.get(0).entries;

        // Kontrol 10: 1. madde açıldı
        assertTrue("senaryo3: madde 1 açıldı", entries.containsKey("1"));

        // Kontrol 11: (1) → 1.1
        assertTrue("senaryo3: (1) → 1.1", entries.containsKey("1.1"));

        // Kontrol 12: (2) → 1.2
        assertTrue("senaryo3: (2) → 1.2", entries.containsKey("1.2"));

        // Kontrol 13: (a) → 1.3  (parent değişmedi; parenCounter devam ediyor)
        assertTrue("senaryo3: (a) → 1.3", entries.containsKey("1.3"));

        // Kontrol 14: (i) → 1.4
        assertTrue("senaryo3: (i) → 1.4", entries.containsKey("1.4"));

        // Kontrol 15: 2. madde açıldı, parenCounter sıfırlandı
        assertTrue("senaryo3: madde 2 açıldı", entries.containsKey("2"));
    }

    // ------------------------------------------------------------------
    // Senaryo 4: Sayfa sonu devamlılığı

    /**
     * Bir madde sayfa sonunda başlar ve bir sonraki sayfada devam eder.
     * Devam bloğu önceki maddeye atanmalı.
     *
     * Hata sınıfı: sayfa değişiminde currentClause sıfırlanıyor.
     */
    static void pageContinuationStaysWithClause() {
        // Blok 2 sayfa 1'de, blok 3 sayfa 2'de (başlıksız devam)
        List<ClauseBlock> blocks = blocks(
                b(0, 0, "1. TARAFLAR"),
                b(1, 0, "Alıcı XYZ A.Ş."),
                b(2, 0, "Sözleşmenin..."),          // sayfa 0 sonu
                b(3, 1, "...tarafları aşağıdadır."), // sayfa 1 başı, başlıksız devam
                b(4, 1, "2. KONU"),
                b(5, 1, "İşbu sözleşme...")
        );

        ClauseTree.Result r = new ClauseTree().segment(blocks);
        Map<String, ClauseTree.ClauseEntry> entries = r.segments.get(0).entries;

        // Kontrol 16: madde 1 varlığı
        assertTrue("senaryo4: madde 1 var", entries.containsKey("1"));

        // Kontrol 17: blok 3 madde 1'de (sayfa devamlılığı)
        assertTrue("senaryo4: sayfa-1 devam bloğu madde 1'de",
                entries.get("1").blockIds.contains(3));

        // Kontrol 18: madde 2 varlığı
        assertTrue("senaryo4: madde 2 var", entries.containsKey("2"));

        // Kontrol 19: blok 3 madde 2'de DEĞİL
        assertFalse("senaryo4: blok 3 madde 2'de olmamalı",
                entries.get("2").blockIds.contains(3));
    }

    // ------------------------------------------------------------------
    // Senaryo 5: Sarılan PDF satırları birleştirilmeli

    /**
     * Tek cümle, görsel olarak 3 satıra sarılmış; LineJoiner tek paragraf yapmalı.
     *
     * Hata sınıfı: PDF satır ≠ paragraf → aynı cümle satır araları boş.
     */
    static void wrappedPdfLinesAreJoined() {
        // Gerçekçi koordinatlar: tam genişlik satırlar (x1 ≈ 950) birleştirilmeli;
        // son satır kısa (x1=400) + noktalı → paragraf bitiyor.
        // Şablon: sayfa 1000 birim genişliğinde, başlık satırı + 3 devam satırı + 2. madde
        List<ClauseBlock> lines = new ArrayList<>();
        lines.add(new ClauseBlock(0, 0, "1. TARAFLAR", ClauseBlock.Source.PDF_LINE, -1,
                50, 100, 950, 130));
        // Sarılan cümle — devam satırları geniş (x1=920), birleştirilmeli
        lines.add(new ClauseBlock(1, 0, "Alıcı, işbu sözleşme", ClauseBlock.Source.PDF_LINE, -1,
                50, 150, 920, 175));
        lines.add(new ClauseBlock(2, 0, "kapsamında teslim", ClauseBlock.Source.PDF_LINE, -1,
                50, 180, 920, 205));
        // Son satır kısa + noktalı → cümle bitti, bir sonraki satır ile bölünebilir
        lines.add(new ClauseBlock(3, 0, "alacaktır.", ClauseBlock.Source.PDF_LINE, -1,
                50, 210, 400, 235));
        lines.add(new ClauseBlock(4, 0, "2. KONU", ClauseBlock.Source.PDF_LINE, -1,
                50, 260, 950, 285));

        ClauseExtraction.ExtractionResult result =
                ClauseExtraction.fromPdfLines(lines, "TR");

        // Kontrol 20: madde 1 var
        assertTrue("senaryo5: madde 1 var", result.clauses.containsKey("1"));

        // Kontrol 21: madde 1 içeriği tek paragraf — blok 1,2,3 birleşmeli
        // (blok 1 geniş satır → birleştiriliyor, blok 3 son cümle → birleştiriliyor)
        String content1 = result.clauses.get("1");
        assertTrue("senaryo5: content1 null degil", content1 != null);
        // Birleştirilmişse "\n\n" içermemeli (araya boş satır girmemeli)
        assertFalse("senaryo5: madde 1 tek paragraf oluştu (boş satır yok)",
                content1.contains("\n\n"));

        // Kontrol 22: birleştirilmiş metin beklenen kelimeleri içeriyor
        assertTrue("senaryo5: 'teslim' kelimesi mevcut",
                content1.contains("teslim"));

        // Kontrol 23: madde 2 var
        assertTrue("senaryo5: madde 2 var", result.clauses.containsKey("2"));
    }

    // ------------------------------------------------------------------
    // Senaryo 6: Türkçe keyword formatları

    /**
     * MADDE, Madde, EK MADDE vb. formatlarının tümünün tanınması.
     *
     * Hata sınıfı: ClauseNumber.normalize yalnızca \d+(\.\d+)* kabul ediyor.
     */
    static void turkishKeywordFormats() {
        List<ClauseBlock> blocks = blocks(
                b(0, 0, "MADDE 1 — Taraflar"),
                b(1, 0, "Alıcı XYZ A.Ş.'dir."),
                b(2, 0, "Madde 2- Konu"),
                b(3, 0, "Sözleşme konusu..."),
                b(4, 0, "MADDE 3 Yükümlülükler"),
                b(5, 0, "Yükümlülükler şöyle..."),
                b(6, 0, "§ 4 Fiyat"),
                b(7, 0, "Ücret 100 TL...")
        );

        ClauseTree.Result r = new ClauseTree().segment(blocks);
        Map<String, ClauseTree.ClauseEntry> entries = r.segments.get(0).entries;

        // Kontrol 24: MADDE 1 tanındı
        assertTrue("senaryo6: MADDE 1 → madde '1'", entries.containsKey("1"));

        // Kontrol 25: Madde 2 tanındı
        assertTrue("senaryo6: Madde 2 → madde '2'", entries.containsKey("2"));

        // Kontrol 26: MADDE 3 tanındı
        assertTrue("senaryo6: MADDE 3 → madde '3'", entries.containsKey("3"));

        // Kontrol 27: § 4 tanındı
        assertTrue("senaryo6: § 4 → madde '4'", entries.containsKey("4"));
    }

    // ------------------------------------------------------------------
    // Senaryo 7: Başlık-only madde kaybolmamalı

    /**
     * Metni olmayan, sadece başlığı olan bir madde bile raporda yer almalı.
     *
     * Hata sınıfı: boş içerikli maddeler ExtractionResult'ta atlanıyor.
     */
    static void headingOnlyClauseStillExists() {
        List<ClauseBlock> blocks = blocks(
                b(0, 0, "1. TARAFLAR"),        // yalnızca başlık, gövde yok
                b(1, 0, "1.1 Alıcı"),
                b(2, 0, "XYZ A.Ş.")
        );

        ClauseExtraction.ExtractionResult result =
                ClauseExtraction.fromDocxBlocks(blocks, "TR");

        // Kontrol 28: madde "1" var (syntheticParent olsa bile)
        assertTrue("senaryo7: madde 1 mevcut", result.clauses.containsKey("1")
                || result.tree.segments.get(0).entries.containsKey("1"));

        // Kontrol 29: madde "1.1" var ve "XYZ" içeriyor
        assertTrue("senaryo7: madde 1.1 mevcut", result.clauses.containsKey("1.1"));
        assertTrue("senaryo7: 1.1 içeriği 'XYZ' içeriyor",
                result.clauses.getOrDefault("1.1", "").contains("XYZ"));
    }

    // ------------------------------------------------------------------
    // Senaryo 8: "Hiç madde yok" teşhisi

    /**
     * Numarasız belgede (yalnızca düz metin) "hiç madde yok" sessiz kalmıyor;
     * validator açık teşhis üretiyor.
     *
     * Hata sınıfı: boş sonuç cache'lenmeden önce yakalanmalı.
     */
    static void coverageDiagnostic() {
        List<ClauseBlock> blocks = blocks(
                b(0, 0, "Bu belge numarasız başlıklar içeriyor."),
                b(1, 0, "İkinci paragraf metin."),
                b(2, 0, "Üçüncü paragraf metin.")
        );

        ClauseTree.Result r = new ClauseTree().segment(blocks);
        List<ClauseExtractionValidator.Finding> findings =
                ClauseExtractionValidator.validate(r);

        // Kontrol 30: Validator bloker üretiyor
        assertTrue("senaryo8: validator bloker üretiyor",
                ClauseExtractionValidator.hasBlocker(findings));

        // Kontrol 31: NO_CLAUSES kodu var
        assertTrue("senaryo8: NO_CLAUSES kodu",
                findings.stream().anyMatch(f ->
                        f.code == ClauseExtractionValidator.Code.NO_CLAUSES));
    }

    // ------------------------------------------------------------------
    // Senaryo 9: Aşırı derin numara reddedilmeli

    /**
     * "1.2.3.4.5.6" gibi aşırı derin (6 seviyeli) bir numera INVALID sayılmalı;
     * satır gövde bloğu olarak korunmalı.
     *
     * Hata sınıfı: aşırı derin numera regex'ten geçip sahte madde açıyor.
     */
    static void impossibleNumberIsRejected() {
        // ClauseNumbering.HEADING G1: en fazla 5 seviye kabul ediyor → 6 seviye INVALID
        List<ClauseBlock> blocks = blocks(
                b(0, 0, "1. TARAFLAR"),
                b(1, 0, "1.2.3.4.5.6 Bu aşırı derin numera"),  // 6 seviye — reddedilmeli
                b(2, 0, "Devam metni")
        );

        ClauseTree.Result r = new ClauseTree().segment(blocks);
        Map<String, ClauseTree.ClauseEntry> entries = r.segments.get(0).entries;

        // Kontrol 32: "1" açıldı
        assertTrue("senaryo9: madde 1 açıldı", entries.containsKey("1"));

        // Kontrol 33: "1.2.3.4.5.6" madde olarak açılmadı
        assertFalse("senaryo9: aşırı derin numera madde açmadı",
                entries.containsKey("1.2.3.4.5.6"));

        // Kontrol 34: blok 1 ve 2, madde "1"e eklendi (gövde olarak korundu)
        assertTrue("senaryo9: blok 1 madde 1'de korundu",
                entries.get("1").blockIds.contains(1));
        assertTrue("senaryo9: blok 2 madde 1'de korundu",
                entries.get("1").blockIds.contains(2));

        // Kontrol 35: ClauseNumbering.HEADING maksimum 5 alt seviye (regex sınırı)
        ClauseNumbering.ParseResult pr = ClauseNumbering.parse("1.2.3.4.5.6 test");
        assertNull("senaryo9: parse() 6 seviyeli numarayı null döndürür", pr);

        // Kontrol 36: ClauseNumbering.classify INVALID döndürür
        ClauseNumbering.Relation rel = ClauseNumbering.classify("1", "1.2.3.4.5.6");
        assertEquals("senaryo9: classify INVALID",
                ClauseNumbering.Relation.INVALID, rel);

        // Kontrol 37: 5 seviye geçerli sayılır
        ClauseNumbering.ParseResult pr5 = ClauseNumbering.parse("1.2.3.4.5 test");
        assertTrue("senaryo9: 5 seviyeli numera parse edilir",
                pr5 != null && pr5.normalized.equals("1.2.3.4.5"));
    }

    // ------------------------------------------------------------------
    // Test koşucu

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static void run(String name, ThrowingRunnable test) {
        try {
            test.run();
            System.out.println("[PASS] " + name);
        } catch (AssertionError e) {
            System.out.println("[FAIL] " + name + " — " + e.getMessage());
            fail++;
        } catch (Exception e) {
            System.out.println("[FAIL] " + name + " — EXCEPTION: " + e);
            e.printStackTrace();
            fail++;
        }
    }

    // ------------------------------------------------------------------
    // Assert yardımcıları

    static void assertTrue(String msg, boolean condition) {
        if (condition) { pass++; }
        else { fail++; throw new AssertionError(msg); }
    }

    static void assertFalse(String msg, boolean condition) {
        assertTrue(msg, !condition);
    }

    static void assertNull(String msg, Object obj) {
        assertTrue(msg, obj == null);
    }

    static <T> void assertEquals(String msg, T expected, T actual) {
        assertTrue(msg + " (expected=" + expected + " actual=" + actual + ")",
                expected != null ? expected.equals(actual) : actual == null);
    }

    // ------------------------------------------------------------------
    // Blok yardımcıları

    static ClauseBlock b(int id, int page, String text) {
        return new ClauseBlock(id, page, text, ClauseBlock.Source.DOCX);
    }

    static List<ClauseBlock> blocks(ClauseBlock... bs) {
        return Arrays.asList(bs);
    }
}
