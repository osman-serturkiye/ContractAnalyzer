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
                resp = ai.chat(SYSTEM, prompt(lang, chunk, prev), List.of());
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
                if (!ClauseNumber.isValidNextClause(prev.isEmpty() ? null : prev, no)) {
                    log.debug("DOCX [{}] clause {} rejected — not a valid successor of '{}'", lang, no, prev);
                    continue;
                }
                List<Integer> ids = parseIds(c.opt("blocks"), workingBlocks.size());
                if (ids.isEmpty()) {
                    log.warn("DOCX [{}] clause {}: no valid block ids in AI response (blocks={}), skipping",
                            lang, no, c.opt("blocks"));
                    continue;
                }
                found.computeIfAbsent(no, k -> new TreeSet<>()).addAll(ids);
                prev = no;
            }
        }

        // İçerik birleştirme: workingBlocks üzerinden indeksleme
        // Not: AI workingBlocks içindeki lokal indeksleri döndürür (0..workingBlocks.size()-1)
        JSONObject clauses = new JSONObject();
        for (Map.Entry<String, TreeSet<Integer>> e : found.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (int idx : e.getValue()) {
                if (idx < 0 || idx >= workingBlocks.size()) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(workingBlocks.get(idx).text);
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
        return "[" + b.id + (b.style != null ? "|" + b.style : "") + "] " + Json.abbreviate(b.text, 1200) + "\n";
    }

    private static String prompt(String lang, List<Block> chunk, String prev) {
        StringBuilder sb = new StringBuilder();
        sb.append("TARGET LANGUAGE: ").append(lang).append(". The document may contain several languages; ignore blocks that are ")
                .append("not written in the target language.\n")
                .append("Below, the document is given as blocks: [id|style] text. Tables are markdown. Automatic list numbers ")
                .append("(1. / 1.1 / a)) are already prepended to the text when known.\n\n")
                .append("Segment the blocks into numbered clauses and sub-clauses (e.g. 1, 1.1, 1.2, 2.3.4).\n")
                .append("CLAUSE NUMBERING FORMATS: Clauses may be numbered in many ways. Recognize ALL of the following as numbered clauses:\n")
                .append("  - Arabic numerals: \"1\", \"1.1\", \"2.3.4\"\n")
                .append("  - Word+numeral (Turkish): \"Madde 1\", \"MADDE 1\", \"Md. 1\", \"Md.1\"\n")
                .append("  - Word+numeral (German): \"Artikel 1\", \"§ 1\", \"Ziffer 1\"\n")
                .append("  - Word+numeral (French): \"Article 1\", \"Art. 1\"\n")
                .append("  - Word+numeral (English): \"Article 1\", \"Section 1\", \"Clause 1\"\n")
                .append("  - Word+numeral (Arabic): \"المادة 1\"\n")
                .append("  - Roman numerals used as top-level clause numbers: \"I\", \"II\", \"III\"\n")
                .append("  - Parenthesized decimal sub-clauses (very common in English/international contracts): ")
                .append("\"(1)\", \"(2)\", \"(3)\" — these are sub-clauses of the enclosing numbered clause.\n")
                .append("    Example: inside clause 4, the sequence (1), (2), (3) maps to \"4.1\", \"4.2\", \"4.3\".\n")
                .append("  - Parenthesized letter sub-clauses: \"(a)\", \"(b)\", \"(c)\" — also sub-clauses of the enclosing clause.\n")
                .append("    Example: inside clause 5, the sequence (a), (b) maps to \"5.1\", \"5.2\".\n")
                .append("  - Parenthesized roman numeral sub-clauses: \"(i)\", \"(ii)\", \"(iii)\" — sub-clauses of the enclosing clause.\n")
                .append("    Example: inside clause 5.1, the sequence (i), (ii) maps to \"5.1.1\", \"5.1.2\".\n")
                .append("For the \"number\" field always use Arabic digits and dots only:\n")
                .append("  \"Madde 3\" → \"3\", \"§ 2.1\" → \"2.1\", \"Article 5\" → \"5\",\n")
                .append("  \"(1)\" inside clause 4 → \"4.1\", \"(b)\" inside clause 4 → \"4.2\".\n")
                .append("If a block clearly marks the start of a new section/clause even without a visible number (e.g. a bold all-caps heading that begins a section), ")
                .append("assign it a sequential number continuing from the last known clause.\n")
                .append("IMPORTANT: Every sub-clause (e.g. \"1.1\", \"1.2\") MUST be returned as a SEPARATE entry ")
                .append("with its own \"number\" and \"blocks\". Do NOT merge sub-clauses into their parent clause.\n")
                .append("For each clause return:\n")
                .append(" - number: Arabic digits and dots only (e.g. \"1\", \"1.1\", \"1.2\", \"2.3.4\")\n")
                .append(" - blocks: ids of ALL blocks that belong to that clause's OWN text, as a compact string of ids and ")
                .append("ranges, e.g. \"12-14,17\" (include tables that belong to it). A parent clause (\"1\") lists only its own ")
                .append("heading/introductory text, NOT the blocks of its sub-clauses (\"1.1\", \"1.2\").\n")
                .append("Example: if clause 1 has sub-clauses (1) and (2), return THREE separate entries: ")
                .append("{\"number\":\"1\",...}, {\"number\":\"1.1\",...}, {\"number\":\"1.2\",...}.\n")
                .append("Blocks that belong to no numbered clause (title, parties, preamble, signatures) are omitted; return the ")
                .append("document title in the target language in \"title\" if it is in this part, else \"\".\n")
                .append("The previous part ended inside clause \"").append(prev).append("\": if the first blocks continue it, ")
                .append("return that same number again.\n")
                .append("IMPORTANT — clause numbering must continue logically from \"").append(prev).append("\":\n")
                .append("  - The next top-level clause after N is N+1 (small increment, typically 1).\n")
                .append("  - A sub-clause of N is N.1; a sub-clause of N.K is N.K.1.\n")
                .append("  - Parenthesized sub-clauses (1),(2)/(a),(b)/(i),(ii) are sequential children of the enclosing clause: ")
                .append("first → N.1, second → N.2, etc. (continuing from the last assigned child if the chunk starts mid-sequence).\n")
                .append("  - Numbers appearing in body text as legal references (e.g. \"HMK'nın 193. maddesi\", ")
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
