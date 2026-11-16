package steps;

import ai.Router;
import ai.StepAi;
import config.AppConfig;
import docx.DocxReader.Block;
import util.ClauseNumber;
import util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The LLM only decides WHICH blocks belong to which clause (it returns block ids), the clause text itself is copied
 * verbatim from the DOCX. This avoids hallucinated / paraphrased clause text and keeps LLM output small.
 */
public final class DocxClauseExtractor {
    private static final Logger log = LogManager.getLogger();

    private static final String SYSTEM = "You segment contract text into numbered clauses. You only output JSON.";

    private final AppConfig cfg;
    private final Router router;

    public DocxClauseExtractor(AppConfig cfg, Router router) {
        this.cfg = cfg;
        this.router = router;
    }

    public JSONObject extract(List<Block> blocks, String lang) throws IOException {
        StepAi ai = router.get("clauseExtractionDOCOriginalDocument");
        int chunkChars = cfg.pipelineInt("docxChunkChars", 24000);
        boolean debug = cfg.isDebug();
        // docxParenSubclauses: parantezli öğelerin (a)/(b)/(1) sub-clause olarak çıkarılıp
        // çıkarılmayacağı. pdfParenSubclauses ile aynı mantık — iki taraf tutarlı olsun.
        // Default false: (a)/(b)/(1) öğeleri varsayılan olarak body'e alınır.
        boolean parenEnabled = cfg.pipelineBoolean("docxParenSubclauses",
                cfg.pipelineBoolean("pdfParenSubclauses", false));
        if (!parenEnabled) {
            log.info("DOCX [{}]: parenthesized sub-clause synthesis disabled (docxParenSubclauses=false)", lang);
        }

        // Çift kolonlu tablo yapısı tespiti:
        // columnIndex >= 0 olan bloklar varsa bu bir bilingual DOCX'tir.
        // Her dil için sadece kendi sütununa ait blokları + sütun dışı blokları kullan.
        // Sütun ataması: detectedColumn, Pipeline tarafından dil tespiti sırasında belirlenir.
        // Burada basit bir strateji: hangi sütunun bu dile ait olduğunu blokların içeriğine bakarak belirle.
        boolean hasBilingualCols = blocks.stream().anyMatch(b -> b.columnIndex >= 0);
        List<Block> workingBlocks;
        int detectedColumn = -1;
        if (hasBilingualCols) {
            detectedColumn = detectColumnForLang(blocks, lang);
            final int col = detectedColumn;
            // Hedef sütunun blokları + sütun dışı bloklar (başlık, önsöz vb.)
            workingBlocks = blocks.stream()
                    .filter(b -> b.columnIndex < 0 || b.columnIndex == col)
                    .collect(java.util.stream.Collectors.toList());
            log.info("DOCX [{}] bilingual mod — sütun:{}, {} blok (toplam {}→{})",
                    lang, col, workingBlocks.size(), blocks.size(), workingBlocks.size());
        } else {
            workingBlocks = blocks;
        }

        int totalBlockChars = workingBlocks.stream().mapToInt(b -> b.text == null ? 0 : b.text.length()).sum();
        log.info("DOCX [{}] clauseExtract — {} blok, toplam {} karakter", lang, workingBlocks.size(), totalBlockChars);

        // ── Deterministic pre-pass: H1/H2 heading bloklarından kesin madde başlıklarını çıkar ──
        // Word'ün H1/H2 heading style'ı ile işaretlenmiş bloklar çok güvenilir birer başlıktır.
        // Bu bloklar için madde numarasını ClauseNumbering ile deterministik olarak tespit edip
        // "found" map'ine ekleriz. LLM'e gidecek bloklardan bunları çıkarmak gerekmez —
        // LLM kararını almaya devam eder, ama deterministik sonuç LLM'in KAÇIRDIĞI maddeleri kapatır.
        Map<String, TreeSet<Integer>> detPreFound = new LinkedHashMap<>();
        String detPrev = "";
        for (int idx = 0; idx < workingBlocks.size(); idx++) {
            Block b = workingBlocks.get(idx);
            String styleLabel = normalizeStyle(b.style);
            if (styleLabel != null && (styleLabel.equals("H1") || styleLabel.equals("H2")
                    || styleLabel.equals("H3") || styleLabel.equals("H4"))) {
                String stripped = extraction.ClauseNumbering.stripArtifactPrefix(
                        extraction.ClauseNumbering.stripMarkdownPrefix(b.text.trim()));
                extraction.ClauseNumbering.ParseResult pr = extraction.ClauseNumbering.parse(stripped);
                if (pr != null) {
                    extraction.ClauseNumbering.Relation rel =
                            extraction.ClauseNumbering.classify(detPrev.isEmpty() ? null : detPrev, pr.normalized);
                    if (rel != extraction.ClauseNumbering.Relation.INVALID) {
                        detPreFound.computeIfAbsent(pr.normalized, k -> new TreeSet<>()).add(idx);
                        detPrev = pr.normalized;
                    }
                }
            }
        }
        if (!detPreFound.isEmpty()) {
            log.info("DOCX [{}] deterministic pre-pass: {} clause heading(s) found via H1-H4 style",
                    lang, detPreFound.size());
        }

        List<List<Block>> chunks = chunk(workingBlocks, chunkChars);
        if (debug) log.info("[DEBUG] DocxClauseExtractor [{}] başlıyor — {} blok, {} chunk, chunkChars:{}",
                lang, workingBlocks.size(), chunks.size(), chunkChars);
        long t0 = System.currentTimeMillis();

        Map<String, TreeSet<Integer>> found = new LinkedHashMap<>();
        String title = "";
        String prev = "";

        for (List<Block> chunk : chunks) {
            if (debug) log.info("[DEBUG] DocxClauseExtractor [{}] chunk {}/{} — {} blok, ilk blok id:{}",
                    lang, chunks.indexOf(chunk) + 1, chunks.size(), chunk.size(),
                    chunk.isEmpty() ? "?" : chunk.get(0).id);
            String resp;
            try {
                resp = ai.chat(SYSTEM, prompt(lang, chunk, prev, parenEnabled), List.of());
            } catch (IOException e) {
                log.warn("DOCX [{}] extraction: chunk starting at block {} failed ({}), skipping chunk",
                        lang, chunk.isEmpty() ? "?" : chunk.get(0).id, e.getMessage());
                continue;
            }
            if (debug) log.info("[DEBUG] DocxClauseExtractor [{}] chunk {}/{} — AI raw response (first 500 chars): {}",
                    lang, chunks.indexOf(chunk) + 1, chunks.size(),
                    resp == null ? "null" : resp.substring(0, Math.min(500, resp.length())));
            JSONObject j = Json.parseLoose(resp);
            String t = j.optString("title", "").trim();
            if (title.isEmpty() && !t.isEmpty()) {
                title = t;
            }
            JSONArray arr = j.optJSONArray("clauses");
            if (arr == null) {
                if (debug) log.info("[DEBUG] DocxClauseExtractor [{}] chunk {}/{} — AI yanıtında 'clauses' yok",
                        lang, chunks.indexOf(chunk) + 1, chunks.size());
                continue;
            }
            if (debug) log.info("[DEBUG] DocxClauseExtractor [{}] chunk {}/{} — AI {} madde döndürdü",
                    lang, chunks.indexOf(chunk) + 1, chunks.size(), arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                String no = ClauseNumber.normalize(c.optString("number", ""));
                if (no == null) continue;

                // docxParenSubclauses=false → yalnızca LLM'in sentezlediği parantezli
                // sub-clause numaralarını reddet.
                //
                // Sentez testi: bu chunk'taki block metinlerinin hiçbiri "no" ile başlamıyorsa
                // (explicit metin yok) → LLM bunu (1)/(a) gibi bir parantezden türetti → reddet.
                // Eğer block'lardan en az biri "no" ile başlıyorsa explicit metin var → geçir.
                //
                // Neden değişti: eski koşul sadece derinliğe bakıyordu (depth==prevDepth+1 &&
                // prevDepth>=2). Bu 3.2.1 / 3.2.2 gibi meşru üçüncü seviye maddeleri de
                // yutuyordu — DOCX'te açıkça "3.2.1" yazıyor olsa bile.
                // parseIds bir kez çağrılır — hem depth filter hem ana atama için
                List<Integer> ids = parseIds(c.opt("blocks"), workingBlocks.size());

                if (!parenEnabled && no.contains(".")) {
                    long depth = no.chars().filter(ch -> ch == '.').count() + 1;
                    long prevDepth = prev.isEmpty() ? 0 : prev.chars().filter(ch -> ch == '.').count() + 1;
                    if (depth == prevDepth + 1 && prevDepth >= 2) {
                        // Sadece sentezlenmiş numaraları reddet: explicit metin kontrolü
                        boolean hasExplicitText = false;
                        for (int bid : ids) {
                            if (bid >= 0 && bid < workingBlocks.size()) {
                                String bt = extraction.ClauseNumbering.stripMarkdownPrefix(
                                        workingBlocks.get(bid).text.trim());
                                extraction.ClauseNumbering.ParseResult bpr =
                                        extraction.ClauseNumbering.parse(bt);
                                if (bpr != null && bpr.normalized.equals(no)) {
                                    hasExplicitText = true;
                                    break;
                                }
                            }
                        }
                        if (!hasExplicitText) {
                            log.debug("DOCX [{}] clause {} skipped — docxParenSubclauses=false, synthesized (no explicit text), depth={} after prev='{}'",
                                    lang, no, depth, prev);
                            continue;
                        }
                        log.debug("DOCX [{}] clause {} kept — docxParenSubclauses=false but explicit text found, depth={} after prev='{}'",
                                lang, no, depth, prev);
                    }
                }

                // Ardıllık doğrulama: sadece INVALID reddedilir.
                // VALID, DUPLICATE, RESET ve BACKWARD kabul edilir.
                // - RESET: parent atlanarak alt-maddeye giriş (örn. "6.4"→"7.1", "9.4"→"11.1")
                // - BACKWARD: belgede numara sırası bozuksa geri dönüş (örn. "11"→"9")
                // AI atıfları zaten filtrelemelidir; Java over-validate ederek geçerli maddeleri yutmamalı.
                extraction.ClauseNumbering.Relation rel =
                        extraction.ClauseNumbering.classify(prev.isEmpty() ? null : prev, no);
                if (rel == extraction.ClauseNumbering.Relation.INVALID) {
                    log.debug("DOCX [{}] clause {} rejected ({}) — prev='{}'", lang, no, rel, prev);
                    continue;
                }
                // ids zaten yukarıda parseIds ile hesaplandı
                if (ids.isEmpty()) {
                    log.warn("DOCX [{}] clause {}: no valid block ids in AI response (blocks={}), skipping",
                            lang, no, c.opt("blocks"));
                    continue;
                }
                found.computeIfAbsent(no, k -> new TreeSet<>()).addAll(ids);
                prev = no;
            }
        }

        // ── Deterministic supplement: LLM'in kaçırdığı H1-H4 heading bloklarını kurtarma ──
        // detPreFound içindeki maddeler LLM sonucunda yoksa ekle (LLM pass-1 wins).
        int detSupplCount = 0;
        for (Map.Entry<String, TreeSet<Integer>> e : detPreFound.entrySet()) {
            if (!found.containsKey(e.getKey())) {
                // Block index'lerini genişlet: bu heading bloğundan sonraki body bloklarını da ekle
                // (bir sonraki madde başlığına kadar).
                TreeSet<Integer> headingIdx = e.getValue();
                int firstIdx = headingIdx.first();
                // Bir sonraki deterministik heading'i bul
                int nextHeadingIdx = workingBlocks.size();
                for (String otherNo : detPreFound.keySet()) {
                    if (!otherNo.equals(e.getKey())) {
                        int otherFirst = detPreFound.get(otherNo).first();
                        if (otherFirst > firstIdx && otherFirst < nextHeadingIdx) {
                            nextHeadingIdx = otherFirst;
                        }
                    }
                }
                // Heading + body bloklarını topla
                TreeSet<Integer> allIdx = new TreeSet<>(headingIdx);
                for (int idx = firstIdx + 1; idx < nextHeadingIdx; idx++) {
                    // LLM'in başka bir maddeye zaten atadığı bloğu alma
                    final int fi = idx;
                    boolean claimedByLlm = found.values().stream().anyMatch(s -> s.contains(fi));
                    if (!claimedByLlm) allIdx.add(idx);
                }
                found.put(e.getKey(), allIdx);
                detSupplCount++;
                log.info("DOCX [{}] deterministic supplement: clause {} recovered (H-style heading, LLM missed)",
                        lang, e.getKey());
            }
        }
        if (detSupplCount > 0) {
            log.info("DOCX [{}]: {} clause(s) recovered via deterministic pre-pass supplement", lang, detSupplCount);
        }

        // İçerik birleştirme: workingBlocks üzerinden indeksleme
        // Not: AI workingBlocks içindeki lokal indeksleri döndürür (0..workingBlocks.size()-1)
        //
        // TABLE guard: "table" kind'ı olan bloklar madde başlığı olamaz.
        // LLM yanlış atasa bile — tablo bloğu tek başına bir clauseKey altındaysa
        // önceki clause'a taşı; önceki clause yoksa body olarak bırak.
        // Bu, S6#2 / S8#1 / S10#2 gibi tabloların kendi başına madde olarak görünmesini önler.
        {
            // Tablo bloğu → clause key mapping (tersine çevrilmiş: blockIdx → clauseKey)
            Map<Integer, String> blockToClause = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, TreeSet<Integer>> e : found.entrySet()) {
                for (int idx : e.getValue()) {
                    blockToClause.put(idx, e.getKey());
                }
            }

            // Sıralı clause key listesi (found'da ekleme sırası: LLM pass sırası)
            List<String> clauseOrder = new ArrayList<>(found.keySet());

            // Her tablo bloğu için: bu bloğun atandığı clause'da başka blok var mı?
            // Yoksa → bu clause sadece tablodan ibaret → bir önceki clause'a taşı.
            List<String> clausesToRemove = new ArrayList<>();
            for (int ci = 0; ci < clauseOrder.size(); ci++) {
                String key = clauseOrder.get(ci);
                TreeSet<Integer> indices = found.get(key);
                // Bu clause'daki tüm bloklar tablo mu?
                boolean allTables = !indices.isEmpty() && indices.stream().allMatch(idx ->
                        idx >= 0 && idx < workingBlocks.size()
                        && "table".equals(workingBlocks.get(idx).kind));
                if (!allTables) continue;

                // Hedef: bir önceki clause (clauseOrder'da)
                String prevKey = ci > 0 ? clauseOrder.get(ci - 1) : null;
                if (prevKey == null) {
                    // İlk clause tamamen tablo — body'e bırak (başka yapacak şey yok)
                    continue;
                }
                // Blokları önceki clause'a aktar
                found.get(prevKey).addAll(indices);
                clausesToRemove.add(key);
                log.info("DOCX [{}] TABLE guard: clause '{}' (table-only) merged into '{}'", lang, key, prevKey);
            }
            for (String k : clausesToRemove) {
                found.remove(k);
            }
        }

        JSONObject clauses = new JSONObject();
        for (Map.Entry<String, TreeSet<Integer>> e : found.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (int idx : e.getValue()) {
                if (idx < 0 || idx >= workingBlocks.size()) continue;
                if (sb.length() > 0) sb.append("\n\n");
                // Strip leading markdown heading markers (## ###) injected by DocxReader's
                // visual-heading detection — they are style signals, not content.
                // Without this, DOCX "## TARAFLAR:" vs PDF "TARAFLAR:" produces a
                // spurious "##" diff token in the fraud-detection comparison.
                String blockText = workingBlocks.get(idx).text;
                blockText = blockText.replaceAll("^#{1,6}\\s+", "");
                sb.append(blockText);
            }
            if (sb.length() == 0) continue;
            clauses.put(e.getKey(), new JSONObject()
                    .put("content", sb.toString())
                    .put("blocks", new JSONArray(e.getValue())));
        }
        log.info("DOCX [{}]: {} clauses extracted", lang, clauses.length());
        if (debug) log.info("[DEBUG] DocxClauseExtractor [{}] tamamlandı — {}ms, {} madde, başlık:'{}'",
                lang, System.currentTimeMillis() - t0, clauses.length(), title);
        return new JSONObject().put("title", title).put("clauses", clauses);
    }

    /**
     * Hangi sütunun (0 veya 1) hedef dile ait olduğunu belirler.
     * Her sütunun tüm metnini Lingua ile sınıflandırır; hedef dile en yakın skoru olan sütunu döndürür.
     * Belirsiz durumlarda 0 (sol sütun) döner.
     */
    private int detectColumnForLang(List<Block> blocks, String lang) {
        Map<Integer, StringBuilder> colTexts = new java.util.HashMap<>();
        for (Block b : blocks) {
            if (b.columnIndex < 0 || b.text == null || b.text.trim().isEmpty()) continue;
            colTexts.computeIfAbsent(b.columnIndex, k -> new StringBuilder())
                    .append(b.text.trim()).append('\n');
        }
        if (colTexts.isEmpty()) return 0;

        // Lingua ile her sütunun baskın dilini bul — sadece desteklenen 8 dil yüklenir
        LanguageDetector d;
        try {
            d = LanguageDetectorBuilder.fromLanguages(
                    Language.TURKISH, Language.ENGLISH, Language.GERMAN, Language.FRENCH,
                    Language.ARABIC, Language.RUSSIAN, Language.SPANISH, Language.ITALIAN
            ).build();
        } catch (Exception ex) {
            log.debug("detectColumnForLang: Lingua build failed ({}), defaulting to column 0", ex.getMessage());
            return 0;
        }

        for (Map.Entry<Integer, StringBuilder> entry : colTexts.entrySet()) {
            Language detected = d.detectLanguageOf(entry.getValue().toString());
            String code = langCodeOf(detected);
            if (lang.equalsIgnoreCase(code)) {
                return entry.getKey();
            }
        }
        // Eşleşme yoksa sütun 0'ı döndür (en yaygın durum: birincil dil sol sütunda)
        return 0;
    }

    /** Lingua Language → ISO-639-1 üst-harfli kod (sadece desteklenen diller). */
    private static String langCodeOf(Language lang) {
        if (lang == null) return null;
        switch (lang) {
            case TURKISH:  return "TR";
            case ENGLISH:  return "EN";
            case GERMAN:   return "DE";
            case FRENCH:   return "FR";
            case ARABIC:   return "AR";
            case RUSSIAN:  return "RU";
            case SPANISH:  return "ES";
            case ITALIAN:  return "IT";
            default:       return null;
        }
    }

    private static List<List<Block>> chunk(List<Block> blocks, int maxChars) {
        List<List<Block>> chunks = new ArrayList<>();
        List<Block> cur = new ArrayList<>();
        int size = 0;
        for (Block b : blocks) {
            int len = line(b).length();
            if (!cur.isEmpty() && size + len > maxChars) {
                chunks.add(cur);
                cur = new ArrayList<>();
                size = 0;
            }
            cur.add(b);
            size += len;
        }
        if (!cur.isEmpty()) {
            chunks.add(cur);
        }
        return chunks;
    }

    private static String line(Block b) {
        // Style normalization: "Heading1"/"heading1"/"1" → "H1", "Heading2" → "H2", vb.
        // LLM'e geçirilen [id|style] alanı böylece tutarlı ve açıklayıcı olur.
        // "table" kind'ı TABLE olarak işaretlenir — LLM bu blokları her zaman önceki maddeye atar.
        String styleLabel;
        if ("table".equals(b.kind)) {
            styleLabel = "TABLE";
        } else {
            styleLabel = normalizeStyle(b.style);
        }
        return "[" + b.id + (styleLabel != null ? "|" + styleLabel : "") + "] " + Json.abbreviate(b.text, 1200) + "\n";
    }

    /**
     * Word style ID'sini LLM-dostu kısa etikete dönüştürür.
     * "Heading1" / "heading 1" / "1" → "H1"
     * "Normal" / "BodyText" → null (style label eklenmez)
     */
    private static String normalizeStyle(String sid) {
        if (sid == null) return null;
        String norm = sid.toLowerCase().replace(" ", "").replace("-", "");
        if (norm.startsWith("heading")) {
            String rest = norm.substring("heading".length());
            try { int lvl = Integer.parseInt(rest); return "H" + lvl; } catch (NumberFormatException ignored) {}
        }
        // Plain numeric style IDs "1"…"6" → heading
        try {
            int lvl = Integer.parseInt(norm);
            if (lvl >= 1 && lvl <= 6) return "H" + lvl;
        } catch (NumberFormatException ignored) {}
        // Keep other non-trivial styles (table-cell, listparagraph, etc.)
        if (norm.equals("normal") || norm.equals("bodytext") || norm.equals("defaultparagraphfont")
                || norm.equals("texttekst") || norm.startsWith("body")) return null;
        // Truncate to keep prompt compact
        return sid.length() > 20 ? sid.substring(0, 20) : sid;
    }

    private static String prompt(String lang, List<Block> chunk, String prev, boolean parenEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("TARGET LANGUAGE: ").append(lang).append(". The document may contain several languages; ignore blocks that are ")
                .append("not written in the target language.\n")
                .append("Below, the document is given as blocks: [id|style] text.\n")
                .append("  - style is H1/H2/H3/H4/H5/H6 for Word heading paragraphs (H1=top, H6=lowest), or omitted for body text.\n")
                .append("  - H1/H2 blocks are ALWAYS clause headings. H3/H4 are likely sub-clause headings.\n")
                .append("  - Blocks marked TABLE are Word tables already rendered as markdown (pipe-separated). ")
                .append("A TABLE block is NEVER a clause heading — always assign it to the SAME clause as the block immediately preceding it.\n")
                .append("  - Tables are already in markdown. Automatic list numbers (1. / 1.1 / a)) are already prepended to the text when known.\n\n")
                .append("Segment the blocks into numbered clauses and sub-clauses (e.g. 1, 1.1, 1.2, 2.3.4).\n")
                .append("CLAUSE NUMBERING FORMATS: Clauses may be numbered in many ways. Recognize ALL of the following as numbered clauses:\n")
                .append("  - Arabic numerals: \"1\", \"1.1\", \"2.3.4\"\n")
                .append("  - Word+numeral (Turkish): \"Madde 1\", \"MADDE 1\", \"Md. 1\", \"Md.1\"\n")
                .append("  - Word+numeral (German): \"Artikel 1\", \"§ 1\", \"Ziffer 1\"\n")
                .append("  - Word+numeral (French): \"Article 1\", \"Art. 1\"\n")
                .append("  - Word+numeral (English): \"Article 1\", \"Section 1\", \"Clause 1\"\n")
                .append("  - Word+numeral (Arabic): \"المادة 1\"\n")
                .append("  - Roman numerals used as top-level clause numbers: \"I\", \"II\", \"III\"\n");
        if (parenEnabled) {
            sb.append("  - Parenthesized decimal sub-clauses (very common in English/international contracts): ")
              .append("\"(1)\", \"(2)\", \"(3)\" — these are sub-clauses ONLY when their content is a full obligation/right/condition (typically 100+ characters).\n")
              .append("    Example: inside clause 4, the sequence (1), (2), (3) maps to \"4.1\", \"4.2\", \"4.3\".\n")
              .append("    EXCEPTION: Short definition list items like \"(a) United States;\", \"(b) United Kingdom;\" are NOT sub-clauses — assign them to the parent clause body.\n")
              .append("  - Parenthesized letter sub-clauses: \"(a)\", \"(b)\", \"(c)\" — sub-clauses of the enclosing clause ONLY when substantive (100+ chars).\n")
              .append("    Example: inside clause 5, the sequence (a), (b) maps to \"5.1\", \"5.2\".\n")
              .append("    EXCEPTION: Short items like \"(a) Amerika Birleşik Devletleri;\", \"(b) Birleşik Krallık;\" are definition-list items — keep in parent clause body.\n")
              .append("    EXCEPTION: If there are 7 or more such items, they form an enumeration list — keep ALL in parent clause body.\n")
              .append("  - Parenthesized roman numeral sub-clauses: \"(i)\", \"(ii)\", \"(iii)\" — sub-clauses of the enclosing clause.\n")
              .append("    Example: inside clause 5.1, the sequence (i), (ii) maps to \"5.1.1\", \"5.1.2\".\n");
        } else {
            sb.append("  IMPORTANT: Parenthesized items \"(a)\", \"(b)\", \"(c)\", \"(1)\", \"(2)\", \"(i)\", \"(ii)\" etc. are NEVER sub-clauses in this document.\n")
              .append("    Assign ALL parenthesized items to the body of their enclosing parent clause.\n")
              .append("    Do NOT create numbered entries for (a), (b), (c), (1), (2) etc. — they are list items, not clause headings.\n");
        }
        sb.append("For the \"number\" field always use Arabic digits and dots only:\n")
                .append("  \"Madde 3\" → \"3\", \"§ 2.1\" → \"2.1\", \"Article 5\" → \"5\"");
        if (parenEnabled) {
            sb.append(",\n  \"(1)\" inside clause 4 → \"4.1\", \"(b)\" inside clause 4 → \"4.2\".\n");
        } else {
            sb.append(".\n");
        }
        sb.append("If a block clearly marks the start of a new section/clause even without a visible number (e.g. a bold all-caps heading that begins a section), ")
                .append("assign it a sequential number continuing from the last known clause.\n")
                .append("IMPORTANT: Every sub-clause (e.g. \"1.1\", \"1.2\") MUST be returned as a SEPARATE entry ")
                .append("with its own \"number\" and \"blocks\". Do NOT merge sub-clauses into their parent clause.\n")
                .append("PARENT CLAUSE BODY RULE: Blocks that appear AFTER the last sub-clause (e.g. after 1.2) but ")
                .append("belong to the same section topic should be assigned to the LAST sub-clause, NOT the parent.\n")
                .append("  Example: if clause 1 has 1.1 and 1.2, and a paragraph appears after 1.2 about parties — ")
                .append("that paragraph belongs to 1.2 (last sub-clause), not to 1 (parent).\n")
                .append("  EXCEPTION: If a paragraph appears BETWEEN the parent heading and the first sub-clause, it belongs to the parent.\n")
                .append("For each clause return:\n")
                .append(" - number: Arabic digits and dots only (e.g. \"1\", \"1.1\", \"1.2\", \"2.3.4\")\n")
                .append(" - blocks: ids of ALL blocks that belong to that clause's OWN text, as a compact string of ids and ")
                .append("ranges, e.g. \"12-14,17\" (include tables that belong to it). A parent clause (\"1\") lists only its own ")
                .append("heading/introductory text, NOT the blocks of its sub-clauses (\"1.1\", \"1.2\").\n")
                .append("Example: if clause 1 has sub-clauses (1) and (2), return THREE separate entries: ")
                .append("{\"number\":\"1\",...}, {\"number\":\"1.1\",...}, {\"number\":\"1.2\",...}.\n")
                .append("Blocks that belong to no numbered clause (document title/header, pre-contractual recitals before clause 1, signature blocks at the end) are omitted; return the ")
                .append("document title in the target language in \"title\" if it is in this part, else \"\".\n")
                .append("CRITICAL: Address lines, party names, company details, PO boxes, and registration numbers that appear ")
                .append("AFTER a sub-clause heading (e.g. after \"1.1.\") are PART OF THAT SUB-CLAUSE — assign them to it. ")
                .append("Do NOT treat them as 'parties/preamble' and omit them.\n")
                .append("ANNEX/EXHIBIT LIST RULE: A block that lists document annexes/exhibits (e.g. \"EKLER:\", \"EK - 1 ...\", \"Annex 1 ...\", ")
                .append("\"ATTACHMENTS:\") is NOT a new clause — assign it to the LAST numbered clause in this chunk. ")
                .append("Do NOT invent a new clause number for it (e.g. do not assign it clause 19 if the last real clause was 18).\n")
                .append("The previous part ended inside clause \"").append(prev).append("\": if the first blocks continue it, ")
                .append("return that same number again.\n")
                .append("IMPORTANT — clause numbering must continue logically from \"").append(prev).append("\":\n")
                .append("  - The next top-level clause after N is typically N+1 but may skip (e.g. 9→11 if 10 is absent).\n")
                .append("  - A sub-clause of N is N.1; a sub-clause of N.K is N.K.1.\n")
                .append("  - After sub-clause N.K the next valid numbers are: N.K+1 (sibling), N+1 (back to parent level),\n")
                .append("    or N+1.1 (parent advanced then into sub-clause). All of these are acceptable.\n");
        if (parenEnabled) {
            sb.append("  - Parenthesized sub-clauses (1),(2)/(a),(b)/(i),(ii) are sequential children of the enclosing clause: ")
              .append("first → N.1, second → N.2, etc. (continuing from the last assigned child if the chunk starts mid-sequence).\n");
        }
        sb.append("  - Numbers appearing in body text as legal references (e.g. \"HMK'nın 193. maddesi\", ")
                .append("\"Article 193 of the Code\") are NOT new clause headings — do NOT assign them a clause number.\n")
                .append("  - Only assign a new clause number when the block is a structural heading that ")
                .append("advances the document outline by one logical step.\n")
                .append("Return JSON only: {\"title\":\"\",\"clauses\":[{\"number\":\"1\",\"blocks\":\"3-5\"},{\"number\":\"1.1\",\"blocks\":\"6-8\"},{\"number\":\"1.2\",\"blocks\":\"9-11\"}]}\n\nBLOCKS:\n");
        for (Block b : chunk) {
            sb.append(line(b));
        }
        return sb.toString();
    }

    /** Accepts "3-5,8" or [3,4,5,8] or ["3-5"]. Ids outside 0..max-1 are dropped. */
    static List<Integer> parseIds(Object o, int max) {
        List<Integer> out = new ArrayList<>();
        if (o == null) {
            return out;
        }
        List<String> parts = new ArrayList<>();
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                parts.add(String.valueOf(a.get(i)));
            }
        } else {
            for (String s : o.toString().split(",")) {
                parts.add(s);
            }
        }
        for (String p : parts) {
            String t = p.trim().replace('\u2013', '-').replace('\u2014', '-');
            if (t.isEmpty()) {
                continue;
            }
            try {
                if (t.contains("-")) {
                    String[] r = t.split("-");
                    int a = Integer.parseInt(r[0].trim());
                    int b = Integer.parseInt(r[1].trim());
                    for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                        if (i >= 0 && i < max) {
                            out.add(i);
                        }
                    }
                } else {
                    int i = Integer.parseInt(t);
                    if (i >= 0 && i < max) {
                        out.add(i);
                    }
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignore) {
                // skip malformed token
            }
        }
        return out;
    }
}
