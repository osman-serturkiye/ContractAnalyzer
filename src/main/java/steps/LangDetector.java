package steps;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import ai.Router;
import ai.StepAi;
import config.AppConfig;
import docx.DocxReader;
import pdf.PdfDoc;
import util.Images;
import util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class LangDetector {
    private static final Logger log = LogManager.getLogger();

    private static final String SYSTEM = "You are a language identification component for legal documents.";

    private final AppConfig cfg;
    private final Router router;
    private LanguageDetector lingua;

    public LangDetector(AppConfig cfg, Router router) {
        this.cfg = cfg;
        this.router = router;
    }
    private static String toIso6391(Language language) {

        if (language == null || language == Language.UNKNOWN) {
            return null;
        }

        switch (language) {

            case TURKISH:
                return "TR";

            case ENGLISH:
                return "EN";

            case ARABIC:
                return "AR";

            case GERMAN:
                return "DE";

            case FRENCH:
                return "FR";

            case RUSSIAN:
                return "RU";

            case SPANISH:
                return "ES";

            case ITALIAN:
                return "IT";

            default:
                return null;
        }
    }
    // ------------------------------------------------------------------ signed PDF (AI)

    public List<String> detectPdf(File pdf) throws Exception {
        StepAi ai = router.get("langsDetectionPDFSignedDocument");
        int maxPages = cfg.pipelineInt("langDetectMaxPages", 6);
        int sampleChars = cfg.pipelineInt("langDetectSampleChars", 1500);
        int minChars = cfg.pipelineInt("minTextCharsPerPage", 150);

        StringBuilder text = new StringBuilder();
        List<String> images = new ArrayList<>();
        try (PdfDoc doc = PdfDoc.open(pdf)) {
            List<Integer> pages = samplePages(doc.pageCount(), maxPages);
            int withText = 0;
            for (int p : pages) {
                String t = doc.text(p).trim();
                if (t.length() >= minChars) {
                    withText++;
                    text.append("--- page ").append(p + 1).append(" ---\n")
                            .append(Json.abbreviate(t, sampleChars)).append('\n');
                }
            }
            boolean useText = withText >= Math.max(1, pages.size() / 2);
            if (!useText) {
                log.info("PDF has no usable text layer (scanned?) - sending page images to language detection");
                text.setLength(0);
                for (int p : pages) {
                    images.add(Images.toBase64Png(doc.render(p, 80f)));
                }
            }
        }

        String user = "Identify every natural language in which the contract text is written. "
                + "A contract may be multilingual (side-by-side columns, alternating paragraphs, or separate sections). "
                + "Ignore isolated names, brands or quoted foreign words.\n"
                + "Use upper-case ISO 639-1 codes (TR, EN, AR, DE, ...), most prevalent first.\n"
                + "Return JSON only: {\"languages\":[\"TR\",\"EN\"]}\n\n"
                + (images.isEmpty() ? "TEXT SAMPLES:\n" + text : "The attached images are sample pages.");
        JSONObject j = Json.parseLoose(ai.chat(SYSTEM, user, images));
        List<String> langs = normalize(j.optJSONArray("languages"));
        if (langs.isEmpty()) {
            throw new IllegalStateException("Language detection returned no languages for " + pdf.getName());
        }
        log.info("Signed PDF languages: {}", langs);
        if (cfg.isDebug()) log.info("[DEBUG] LangDetector.detectPdf — langs:{} images:{} text:{}chars",
                langs, images.size(), text.length());
        return langs;
    }

    private static List<Integer> samplePages(int n, int max) {
        List<Integer> l = new ArrayList<>();
        if (n <= max) {
            for (int i = 0; i < n; i++) {
                l.add(i);
            }
            return l;
        }
        for (int i = 0; i < max; i++) {
            l.add((int) Math.round(i * (n - 1) / (double) (max - 1)));
        }
        return l.stream().distinct().collect(Collectors.toList());
    }

    private static List<String> normalize(JSONArray arr) {
        Set<String> out = new LinkedHashSet<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String c = arr.optString(i, "").trim().toUpperCase();
                if (c.matches("[A-Z]{2,3}")) {
                    out.add(c);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /*

    public List<String> detectDocx(List<DocxReader.Block> blocks) {
        String text = blocks.stream().map(b -> b.text).collect(Collectors.joining("\n"));
        LanguageDetector d = lingua();
        double minShare = cfg.pipelineDouble("linguaMinShare", 0.05);

        Map<Language, Integer> chars = new LinkedHashMap<>();
        int total = 0;
        for (DetectionResult r : d.detectMultipleLanguagesOf(text)) {
            int len = Math.max(0, r.getEndIndex() - r.getStartIndex());
            chars.merge(r.getLanguage(), len, Integer::sum);
            total += len;
        }
        final int tot = total;
        List<String> langs = new ArrayList<>();
        chars.entrySet().stream()
                .filter(e -> tot > 0 && e.getValue() / (double) tot >= minShare && e.getKey() != Language.UNKNOWN)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> langs.add(e.getKey().getIsoCode639_1().name().toUpperCase()));
        if (langs.isEmpty()) {
            Language one = d.detectLanguageOf(text);
            if (one != Language.UNKNOWN) {
                langs.add(one.getIsoCode639_1().name().toUpperCase());
            }
        }
        if (langs.isEmpty()) {
            throw new IllegalStateException("Lingua could not detect any language in the DOCX");
        }
        log.info("DOCX languages (Lingua): {}", langs);
        return langs;
    }
    */
    public List<String> detectDocx(List<DocxReader.Block> blocks) {
        LanguageDetector d = lingua();
        // linguaMinShare: minimum karakter oranı (varsayılan %2).
        // Çok dilli sözleşmelerde ikinci dil genellikle %5 eşiğinin altında kalır;
        // güvenli alt sınır olan %2 daha fazla dili yakalar.
        double minShare = cfg.pipelineDouble("linguaMinShare", 0.02);
        boolean debug = cfg.isDebug();

        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalStateException("DOCX contains no text blocks");
        }

        long t0 = System.currentTimeMillis();

        // Çift kolonlu tablo tespiti: columnIndex >= 0 olan bloklar varsa sütun bazlı strateji.
        // Her sütundaki tüm metin birleştirilip tek seferde Lingua'ya verilir → kısa blok
        // sorununu ortadan kaldırır ve her sütunun dilini kesin olarak belirler.
        boolean hasBilingualCols = blocks.stream().anyMatch(b -> b.columnIndex >= 0);

        if (hasBilingualCols) {
            return detectDocxByColumn(d, blocks, debug, t0);
        }

        // --- Standart strateji: her blok için detectLanguageOf() ile kazanan dili belirle ---
        // computeLanguageConfidenceValues() tüm dillere birer skor verir; Latin alfabesi paylaşan
        // diller (TR, IT, ES, DE, FR) birbiriyle çakışır → tek dilli belgede çok dil tespit edilir.
        // detectLanguageOf() her blok için yalnızca bir kazanan dil döndürür; karakterler sadece o
        // dile eklenir → tek dilli belgede yalnızca o dil belirlenir.
        // Çok dilli belgeler için bu strateji de güvenlidir: her blok ağırlıklı olarak tek bir dile
        // ait olduğundan, her dil kendi bloklarından karakter biriktirir.
        Map<Language, Integer> charCount = new LinkedHashMap<>();
        int totalChars = 0;

        for (DocxReader.Block block : blocks) {
            if (block == null || block.text == null) continue;
            String text = block.text.trim();
            if (text.isEmpty()) continue;

            int len = text.length();
            totalChars += len;

            Language detected = d.detectLanguageOf(text);
            if (detected == null || detected == Language.UNKNOWN) continue;
            charCount.merge(detected, len, Integer::sum);
        }

        if (totalChars == 0 || charCount.isEmpty()) {
            throw new IllegalStateException("Lingua could not detect any language in the DOCX");
        }

        final int total = totalChars;
        // normalizedScore: her dilin karakter payı (0..1)
        Map<Language, Double> normalizedScore = new LinkedHashMap<>();
        charCount.forEach((lang, count) -> normalizedScore.put(lang, (double) count / total));

        if (debug) log.info("[DEBUG] LangDetector.detectDocx (standard) — {} blok, {} karakter, minShare:{}, skorlar:{}",
                blocks.size(), total, minShare, normalizedScore);

        return finalizeLangs(normalizedScore, minShare, total, blocks.size(), t0, debug, "standard");
    }

    /**
     * Sütun bazlı dil tespiti: her sütundaki tüm metin birleştirilip Lingua'ya verilir.
     * Sütun 0 → birincil dil, sütun 1 → ikincil dil.
     * Sütun dışındaki bloklar (columnIndex = -1) standart yöntemle eklenir.
     */
    private List<String> detectDocxByColumn(LanguageDetector d, List<DocxReader.Block> blocks,
                                             boolean debug, long t0) {
        double minShare = cfg.pipelineDouble("linguaMinShare", 0.02);

        // Sütun metinlerini biriktir
        Map<Integer, StringBuilder> colTexts = new LinkedHashMap<>();
        StringBuilder nonColText = new StringBuilder();

        for (DocxReader.Block b : blocks) {
            if (b == null || b.text == null || b.text.trim().isEmpty()) continue;
            if (b.columnIndex >= 0) {
                colTexts.computeIfAbsent(b.columnIndex, k -> new StringBuilder())
                        .append(b.text.trim()).append('\n');
            } else {
                nonColText.append(b.text.trim()).append('\n');
            }
        }

        // Her sütunun baskın dilini al — toplu metin çok daha güvenilir tespit sağlar
        List<String> colLangs = new ArrayList<>();
        for (Map.Entry<Integer, StringBuilder> entry : colTexts.entrySet()) {
            String colText = entry.getValue().toString();
            if (colText.trim().isEmpty()) continue;

            Language lang = d.detectLanguageOf(colText);
            String code = toIso6391(lang);
            if (code != null && !colLangs.contains(code)) {
                colLangs.add(code);
                if (debug) log.info("[DEBUG] LangDetector sütun {} → {} ({}karakter)",
                        entry.getKey(), code, colText.length());
            }
        }

        // Sütun dışı blokları da ekle (başlık, önsöz vb.)
        if (nonColText.length() > 0) {
            Map<Language, Double> conf = d.computeLanguageConfidenceValues(nonColText.toString());
            if (conf != null) {
                conf.entrySet().stream()
                        .filter(e -> e.getKey() != Language.UNKNOWN && e.getValue() >= minShare)
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .forEach(e -> {
                            String code = toIso6391(e.getKey());
                            if (code != null && !colLangs.contains(code)) colLangs.add(code);
                        });
            }
        }

        if (colLangs.isEmpty()) {
            throw new IllegalStateException("Lingua could not determine any language in the bilingual DOCX");
        }

        int totalChars = blocks.stream().mapToInt(b -> b.text == null ? 0 : b.text.trim().length()).sum();
        log.info("DOCX languages (Lingua/column): {} (toplam {} karakter, {} blok, {}ms)",
                colLangs, totalChars, blocks.size(), System.currentTimeMillis() - t0);
        return colLangs;
    }

    /** minShare filtresi ve fallback mantığı — her iki strateji için ortak. */
    private List<String> finalizeLangs(Map<Language, Double> normalizedScore, double minShare,
                                       int total, int blockCount, long t0, boolean debug, String strategy) {
        List<String> langs = normalizedScore.entrySet().stream()
                .filter(e -> e.getValue() >= minShare)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(e -> toIso6391(e.getKey()))
                .filter(code -> code != null)
                .distinct()
                .collect(Collectors.toList());

        if (langs.isEmpty()) {
            Language dominant = normalizedScore.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(Language.UNKNOWN);
            String code = toIso6391(dominant);
            if (code != null) langs.add(code);
        }

        if (langs.isEmpty()) {
            throw new IllegalStateException("Lingua could not determine any language in the DOCX");
        }

        log.info("DOCX languages (Lingua/{}): {} (toplam {} karakter, {} blok, {}ms)",
                strategy, langs, total, blockCount, System.currentTimeMillis() - t0);
        if (debug) log.info("[DEBUG] LangDetector.detectDocx tamamlandı — normalizedScore:{}", normalizedScore);
        return langs;
    }

    private synchronized LanguageDetector lingua() {
        if (lingua == null) {
            JSONArray conf = cfg.pipeline().optJSONArray("linguaLanguages");
            List<Language> langs = new ArrayList<>();
            if (conf != null) {
                for (int i = 0; i < conf.length(); i++) {
                    langs.add(Language.valueOf(conf.getString(i).trim().toUpperCase()));
                }
            }
            if (langs.size() < 2) {
                langs = List.of(Language.TURKISH, Language.ENGLISH, Language.ARABIC, Language.GERMAN,
                        Language.FRENCH, Language.RUSSIAN, Language.SPANISH, Language.ITALIAN);
            }
            lingua = LanguageDetectorBuilder.fromLanguages(langs.toArray(new Language[0])).build();
        }
        return lingua;
    }
}
