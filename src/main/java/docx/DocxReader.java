package docx;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNum;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrGeneral;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a DOCX into an ordered list of text blocks (paragraphs / tables-as-markdown).
 * Word auto-numbering (1. / 1.1 / a) ...) is resolved and prepended to the paragraph text, because
 * clause numbers are usually NOT part of the raw text.
 *
 * <p>Two numbering paths are supported:
 * <ol>
 *   <li><b>Auto-numbering</b> ({@link NumberingResolver}): paragraphs with a {@code <w:numPr>} element
 *       — Word resolves the number display internally; we reconstruct it via the abstract-num definition.</li>
 *   <li><b>Explicit-text numbering</b> ({@link ExplicitNumberExtractor}): paragraphs whose text begins
 *       with a number token followed by a tab character — e.g. {@code "1.2\tAlıcı …"} or
 *       {@code "(a)\tThe Buyer …"}.  The token is extracted, stripped from the text, and returned as the
 *       prefix so the pipeline sees the same {@code "1.2 Alıcı …"} form as it does for auto-numbered
 *       paragraphs.</li>
 * </ol>
 */
public final class DocxReader {
    private static final Logger log = LogManager.getLogger();

    public static final class Block {
        public final int id;
        public final String kind;        // "p" | "table" | "table-cell"
        public final String style;       // paragraph style id, may be null
        public final String text;
        /**
         * For blocks that originate from a bilingual (2-column) table cell:
         *  0 = left column (typically the primary/source language)
         *  1 = right column (typically the secondary/target language)
         * -1 = not a bilingual table cell (normal paragraph or non-bilingual table)
         */
        public final int columnIndex;

        Block(int id, String kind, String style, String text, int columnIndex) {
            this.id = id;
            this.kind = kind;
            this.style = style;
            this.text = text;
            this.columnIndex = columnIndex;
        }

        /** Convenience constructor for non-table blocks (columnIndex = -1). */
        Block(int id, String kind, String style, String text) {
            this(id, kind, style, text, -1);
        }
    }

    /**
     * Returns the number of pages as recorded in the DOCX extended properties metadata.
     *
     * <p>Word stores the page count in {@code docProps/app.xml → Pages} during each save.
     * This value may be stale if the document was saved without repaginating, but it is a
     * good-enough upper bound for clause extraction — the signed PDF rarely has fewer pages
     * of contractual content than the original DOCX.
     *
     * <p>Returns 0 when the metadata is absent or cannot be parsed (caller should treat 0
     * as "no limit").
     */
    public static int pageCount(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            try {
                int pages = doc.getProperties()
                        .getExtendedProperties()
                        .getUnderlyingProperties()
                        .getPages();
                return Math.max(0, pages);
            } catch (Exception e) {
                log.debug("DocxReader.pageCount: could not read page metadata from {}: {}", file.getName(), e.getMessage());
                return 0;
            }
        }
    }

    private DocxReader() {
    }

    public static List<Block> read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            // ── Tracked Changes detection ──
            // Word'de kabul edilmemiş değişiklikler (w:ins / w:del) extraction'a sessizce
            // giriyor: w:ins metni mevcut metin gibi okunur, w:del metni yok sayılır.
            // Bu durum fraud detection'ı engeller: DOCX'te olmayan metin var gibi görünür
            // veya var olan metin yok gibi görünür.
            // Uyarı ver — Accept All Changes yapılmasını tavsiye et.
            int trackedChanges = countTrackedChanges(doc);
            if (trackedChanges > 0) {
                log.warn("UYARI [DocxReader]: '{}' dosyasında {} adet kabul edilmemiş değişiklik (tracked change) " +
                        "tespit edildi. Lütfen Word'de 'Accept All Changes' uygulayın ve dosyayı yeniden kaydedin. " +
                        "Mevcut halde extraction yanlış metin içerebilir, fraud tespiti etkilenebilir.",
                        file.getName(), trackedChanges);
            }
            NumberingResolver numbering = new NumberingResolver(doc);
            List<Block> out = new ArrayList<>();
            int id = 0;
            for (IBodyElement e : doc.getBodyElements()) {
                if (e instanceof XWPFParagraph) {
                    XWPFParagraph p = (XWPFParagraph) e;
                    String t = paragraphToMarkdown(p);
                    if (t.isEmpty()) {
                        continue;
                    }
                    // ── Numbering: auto (Word numPr) → explicit text (tab/space) → none ──
                    String prefix = numbering.prefix(p);
                    String body   = t;
                    if (prefix.isEmpty()) {
                        // Try explicit-text numbering: "1.2\tAlıcı…", "(a)\tBuyer…", "3.10.1. YÜKLENİCİ…"
                        String[] ep = ExplicitNumberExtractor.extract(t);
                        if (ep != null) {
                            prefix = ep[0];   // the number token ("1.2", "(a)", etc.)
                            body   = ep[1];   // text with the token + separator stripped
                        }
                    }
                    String headingPrefix = headingPrefix(p);
                    // ── Visual heading detection ──
                    // No number, no Word heading style, but the paragraph looks like a heading
                    // (all-caps, short).  Mark it with ## so the LLM and deterministic pre-pass
                    // can use it for clause comparison and fraud detection.
                    // These are NOT skipped — they are visible to the pipeline.
                    // Guard: only apply when no clause number was extracted (prefix is empty).
                    if (headingPrefix.isEmpty() && prefix.isEmpty()
                            && ExplicitNumberExtractor.isHeadingLike(t)) {
                        headingPrefix = "##";
                    }
                    String full = prefix.isEmpty() ? body : prefix + " " + body;
                    if (!headingPrefix.isEmpty()) {
                        full = headingPrefix + " " + full;
                    }
                    out.add(new Block(id++, "p", p.getStyle(), full));
                } else if (e instanceof XWPFTable) {
                    XWPFTable tbl = (XWPFTable) e;
                    if (isBilingualTable(tbl)) {
                        // 2-kolonlu çift dilli tablo: her hücreyi ayrı blok olarak emit et.
                        // columnIndex=0 → sol (birincil dil), columnIndex=1 → sağ (ikincil dil).
                        for (XWPFTableRow row : tbl.getRows()) {
                            List<XWPFTableCell> cells = row.getTableCells();
                            for (int col = 0; col < Math.min(cells.size(), 2); col++) {
                                String cellText = cellToText(cells.get(col), numbering);
                                if (!cellText.isEmpty()) {
                                    out.add(new Block(id++, "table-cell", null, cellText, col));
                                }
                            }
                        }
                    } else {
                        String md = tableToMarkdown(tbl);
                        if (!md.trim().isEmpty()) {
                            out.add(new Block(id++, "table", null, md));
                        }
                    }
                } else if (e instanceof XWPFSDT) {
                    String t = clean(((XWPFSDT) e).getContent().getText());
                    if (!t.isEmpty()) {
                        out.add(new Block(id++, "p", null, t));
                    }
                }
            }
            return out;
        }
    }

    /**
     * Converts a paragraph's runs to markdown text, preserving bold (**) and italic (*) formatting.
     * Consecutive runs with the same formatting are merged before wrapping to avoid fragmented
     * markers like "**A****ddenum**" → "**Addendum**".
     * Falls back to plain getText() if no runs are found.
     */
    static String paragraphToMarkdown(XWPFParagraph p) {
        List<XWPFRun> runs = p.getRuns();
        if (runs == null || runs.isEmpty()) {
            return clean(p.getText());
        }

        // Pass 1: collect non-empty (text, bold, italic) tuples
        // Pass 2: merge consecutive runs with identical formatting
        // Each entry: [text, bold(bool), italic(bool)]
        List<Object[]> merged = new ArrayList<>();
        for (XWPFRun run : runs) {
            String text = run.getText(0);
            if (text == null || text.isEmpty()) continue;
            text = text.replace('\u00A0', ' ');
            boolean bold   = Boolean.TRUE.equals(run.isBold());
            boolean italic = Boolean.TRUE.equals(run.isItalic());
            if (!merged.isEmpty()) {
                Object[] last = merged.get(merged.size() - 1);
                if ((Boolean) last[1] == bold && (Boolean) last[2] == italic) {
                    last[0] = (String) last[0] + text;
                    continue;
                }
            }
            merged.add(new Object[]{text, bold, italic});
        }

        // Pass 3: escape and wrap with markdown markers
        StringBuilder sb = new StringBuilder();
        for (Object[] c : merged) {
            String text   = (String)  c[0];
            boolean bold  = (Boolean) c[1];
            boolean italic= (Boolean) c[2];
            // Don't wrap whitespace-only runs in bold/italic markers
            boolean onlySpace = text.isBlank();
            String escaped = text.replace("\\", "\\\\")
                                 .replace("*", "\\*")
                                 .replace("_", "\\_")
                                 .replace("`", "\\`");
            if (!onlySpace && bold && italic) {
                sb.append("***").append(escaped).append("***");
            } else if (!onlySpace && bold) {
                sb.append("**").append(escaped).append("**");
            } else if (!onlySpace && italic) {
                sb.append("*").append(escaped).append("*");
            } else {
                sb.append(escaped);
            }
        }

        String result = sb.toString().replaceAll("\\s+", " ").trim();
        return result.isEmpty() ? clean(p.getText()) : result;
    }

    /**
     * Returns the markdown heading prefix ("# " through "###### ") for a heading paragraph,
     * or an empty string for normal paragraphs.
     *
     * <p>Word heading style IDs vary by locale and template:
     * English: "Heading1" … "Heading6"
     * Turkish / others may use the same IDs (Word always stores style IDs in English internally).
     * Some templates use numeric IDs like "1" … "6".
     * This method normalises to lower-case and strips spaces before matching.
     */
    static String headingPrefix(XWPFParagraph p) {
        String sid = p.getStyleID();
        if (sid == null) {
            return "";
        }
        String norm = sid.toLowerCase().replace(" ", "").replace("-", "");
        // "heading1" … "heading6"
        if (norm.startsWith("heading")) {
            String rest = norm.substring("heading".length());
            int level = parseLevel(rest);
            return level > 0 ? "#".repeat(level) : "";
        }
        // Some templates use plain numeric style IDs "1" … "6" for headings
        int level = parseLevel(norm);
        if (level > 0 && level <= 6) {
            return "#".repeat(level);
        }
        return "";
    }

    private static int parseLevel(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String clean(String s) {
        return s == null ? "" : s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ Tracked changes detection

    /**
     * Counts the total number of pending tracked-change insertions and deletions in the document.
     *
     * <p>Apache POI does not expose a high-level revision API, so we walk the raw XML using
     * the XmlBeans layer. We count {@code <w:ins>} and {@code <w:del>} run-level elements
     * directly under paragraph run containers — these represent accepted-but-not-committed
     * insertions and deletions respectively.</p>
     *
     * <p>Returns 0 when the document has no pending changes or when the XML cannot be
     * inspected (graceful fallback).</p>
     */
    public static int countTrackedChanges(XWPFDocument doc) {
        try {
            // Use the raw XML string to count w:ins and w:del occurrences.
            // This is simpler than recursing through XmlBeans objects and covers
            // all nesting levels (runs, tables, paragraphs).
            String xml = doc.getDocument().xmlText();
            int insCount = countOccurrences(xml, "<w:ins ");
            int delCount = countOccurrences(xml, "<w:del ");
            return insCount + delCount;
        } catch (Exception e) {
            log.debug("countTrackedChanges: could not inspect XML ({})", e.getMessage());
            return 0;
        }
    }

    /**
     * Returns {@code true} when the DOCX file contains any pending tracked-change
     * insertions or deletions. Convenience method for callers that only need a boolean.
     */
    public static boolean hasTrackedChanges(File file) {
        try (InputStream in = new FileInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            return countTrackedChanges(doc) > 0;
        } catch (Exception e) {
            log.debug("hasTrackedChanges: could not open {} ({})", file.getName(), e.getMessage());
            return false;
        }
    }

    private static int countOccurrences(String text, String pattern) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) >= 0) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    /**
     * Returns true when the table looks like a bilingual side-by-side layout:
     * - Exactly 2 columns in the majority of data rows.
     * - At least 1 row with 2 cells (a single-row bilingual table is valid — some
     *   contracts put the entire text in one giant 2-cell row with nested content).
     * - The combined text across both columns must be substantial (≥ 500 characters)
     *   to exclude signature blocks, price tables, and other short 2-column tables
     *   that are not bilingual.
     */
    private static boolean isBilingualTable(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return false;
        int twoColRows = 0;
        int otherRows = 0;
        for (XWPFTableRow row : rows) {
            int cellCount = row.getTableCells().size();
            if (cellCount == 2) {
                twoColRows++;
            } else if (cellCount > 0) {
                otherRows++;
            }
        }
        if (!(twoColRows >= 1 && twoColRows >= otherRows)) return false;

        // Require substantial text content to distinguish real bilingual tables
        // from short structural tables (signature blocks, price/date tables, etc.)
        int totalTextLen = 0;
        for (XWPFTableRow row : rows) {
            for (XWPFTableCell cell : row.getTableCells()) {
                String t = cell.getText();
                if (t != null) totalTextLen += t.trim().length();
            }
        }
        return totalTextLen >= 500;
    }

    /**
     * Extracts all paragraph text from a table cell, resolving numbering prefixes and heading levels,
     * and joins them with newlines. Nested bilingual tables inside cells are recursively expanded;
     * other nested tables are rendered as markdown.
     */
    private static String cellToText(XWPFTableCell cell, NumberingResolver numbering) {
        StringBuilder sb = new StringBuilder();
        for (IBodyElement e : cell.getBodyElements()) {
            if (e instanceof XWPFParagraph) {
                XWPFParagraph p = (XWPFParagraph) e;
                String t = paragraphToMarkdown(p);
                if (t.isEmpty()) continue;
                String prefix = numbering.prefix(p);
                String body   = t;
                if (prefix.isEmpty()) {
                    String[] ep = ExplicitNumberExtractor.extract(t);
                    if (ep != null) {
                        prefix = ep[0];
                        body   = ep[1];
                    }
                }
                String headingPrefix = headingPrefix(p);
                if (headingPrefix.isEmpty() && prefix.isEmpty()
                        && ExplicitNumberExtractor.isHeadingLike(t)) {
                    headingPrefix = "##";
                }
                String full = prefix.isEmpty() ? body : prefix + " " + body;
                if (!headingPrefix.isEmpty()) full = headingPrefix + " " + full;
                if (sb.length() > 0) sb.append('\n');
                sb.append(full);
            } else if (e instanceof XWPFTable) {
                XWPFTable nested = (XWPFTable) e;
                // Nested tables (bilingual or not) are rendered as markdown.
                // The old code had separate if/else branches for bilingual vs non-bilingual
                // nested tables, but both did the same thing — consolidated here.
                String md = tableToMarkdown(nested);
                if (!md.trim().isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(md);
                }
            }
        }
        return sb.toString().trim();
    }

    private static String tableToMarkdown(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        int cols = 0;
        for (XWPFTableRow r : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell c : r.getTableCells()) {
                cells.add(clean(c.getText()).replace("|", "\\|"));
            }
            cols = Math.max(cols, cells.size());
            rows.add(cells);
        }
        if (rows.isEmpty() || cols == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            sb.append("|");
            for (int c = 0; c < cols; c++) {
                sb.append(' ').append(c < cells.size() ? cells.get(c) : "").append(" |");
            }
            sb.append('\n');
            if (i == 0) {
                sb.append("|");
                for (int c = 0; c < cols; c++) {
                    sb.append(" --- |");
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // Explicit-text number extractor
    // Handles paragraphs where the clause number is embedded as explicit text
    // followed by a tab: "1.2\tAlıcı …", "(a)\tThe Buyer …", "1.2.3\tScope".
    // This is common in DOCX files that use tabs for alignment instead of Word
    // auto-numbering, or where auto-numbering is disabled / inherited from a template
    // that emits the number as literal text.
    //
    // A contract can use MULTIPLE numbering patterns simultaneously:
    //   - "1." / "1.1" / "1.2.3" (dotted Arabic)
    //   - "(a)" / "(1)" / "(iv)" (parenthesized)
    //   - "a)" / "1)" (trailing-paren)
    //   - "Madde 5" / "Article 3" (keyword)
    //   - Tab-separated: "1.2\tAlıcı…"
    //   - Space-separated: "3.10.1. YÜKLENİCİ…"
    // All of these must be recognised so every paragraph is correctly assigned
    // to its clause — missing or misassigned blocks directly cause missed diffs
    // in the fraud-detection comparison.

    static final class ExplicitNumberExtractor {

        // Full number token: (A) | (a) | (1) | (iv)  OR  1 | 1. | 1.2 | 1.2.3 | 1.2.3.
        private static final String NUM_TOKEN =
                "\\(\\s*(?:\\d+|[A-Za-z]{1,4})\\s*\\)" +   // (A) (a) (1) (iv)
                "|" +
                "\\d+(?:\\.\\d+)*\\.?";                     // 1 | 1. | 1.2 | 1.2.3 | 1.2.3.

        // "Safe" token for space-separated matching: only patterns that are very
        // unlikely to appear as non-clause text.  Single bare integers ("1 yıl",
        // "3 days") are EXCLUDED — they cause too many false positives.
        //   - Parenthesized: (a) (1) (iv) — always a sub-clause marker
        //   - Multi-level dotted: 1.2 1.2.3 — always a clause number
        //   - Trailing-paren: 1) a) — sub-clause or list item
        //   - Single trailing-dot: 1. 2. — clause number (moderately safe)
        private static final String NUM_TOKEN_SAFE =
                "\\(\\s*(?:\\d+|[A-Za-z]{1,4})\\s*\\)"      // (A) (a) (1) (iv)
                + "|" +
                "\\d+\\.\\d+(?:\\.\\d+)*\\.?"                 // 1.2 1.2.3 1.2.3. — multi-level (2+ components)
                + "|" +
                "(?:\\d{1,2}|[a-zA-Z])\\)"                     // 1) a) A) — trailing-paren
                + "|" +
                "\\d+\\.(?=\\s)"                               // 1. 2. — single trailing-dot + space
                ;

        // Tab-separated: number + tab (most reliable — tab is a structural separator)
        private static final Pattern PARA_NUMBER_TAB =
                Pattern.compile("^\\s*(" + NUM_TOKEN + ")\\t");

        // Space-separated: number + whitespace + body
        // Only for safe patterns — avoids "1 yıl" / "3 days" false positives.
        private static final Pattern PARA_NUMBER_SPACE =
                Pattern.compile("^\\s*(" + NUM_TOKEN_SAFE + ")\\s+(.*)", Pattern.DOTALL);

        /**
         * Attempts to extract an explicit number prefix from a paragraph's rendered text.
         *
         * <p>Two matching strategies are tried in order:
         * <ol>
         *   <li><b>Tab-separated</b> ({@code "1.2\tAlıcı…"}): the most reliable form —
         *       a tab is a structural separator, not used in running prose.</li>
         *   <li><b>Space-separated</b> ({@code "3.10.1. YÜKLENİCİ…"}): only "safe" patterns
         *       (multi-level dotted, parenthesized, trailing-paren) are matched to avoid
         *       false positives like {@code "1 yıl"} or {@code "3 days"}.</li>
         * </ol>
         *
         * @param text the full rendered text of the paragraph (from {@link #paragraphToMarkdown})
         * @return a two-element array {@code [prefix, body]} where {@code prefix} is the extracted
         *         number token (e.g. {@code "1.2"}) and {@code body} is the remaining text with the
         *         token and separator stripped; or {@code null} if no explicit number is found.
         */
        static String[] extract(String text) {
            if (text == null || text.isEmpty()) return null;

            // 1. Tab-separated: "1.2\tAlıcı…" — most reliable
            Matcher m = PARA_NUMBER_TAB.matcher(text);
            if (m.find()) {
                String token = m.group(1).replaceAll("\\s+", "");
                String body  = text.substring(m.end()).trim();
                if (body.isEmpty()) return null;
                return new String[]{token, body};
            }

            // 2. Space-separated on raw text: "3.10.1. YÜKLENİCİ…" or "(a) yaptırım…"
            //    Only for safe patterns — avoids "1 yıl" false positives.
            Matcher sm = PARA_NUMBER_SPACE.matcher(text);
            if (sm.find()) {
                String token = sm.group(1).replaceAll("\\s+", "");
                String body  = sm.group(2).trim();
                if (body.isEmpty()) return null;
                return new String[]{token, body};
            }

            // 3. Markdown-stripped fallback: paragraphToMarkdown() wraps bold/italic text
            //    in ** or * markers — "**1.2. SUMP POMPA**" fails the patterns above.
            //    Strip all leading markdown markers and retry the space-separated match.
            //    This handles paragraphs whose clause number is bold in Word.
            String stripped = text.replaceAll("^[*_#`\\s]+", "").trim();
            if (!stripped.equals(text.trim())) {
                // Try tab-separated on stripped
                Matcher mt = PARA_NUMBER_TAB.matcher(stripped);
                if (mt.find()) {
                    String token = mt.group(1).replaceAll("\\s+", "");
                    String body  = stripped.substring(mt.end()).replaceAll("[*_]+$", "").trim();
                    if (!body.isEmpty()) return new String[]{token, body};
                }
                // Try space-separated on stripped
                Matcher ms2 = PARA_NUMBER_SPACE.matcher(stripped);
                if (ms2.find()) {
                    String token = ms2.group(1).replaceAll("\\s+", "");
                    // Remove trailing markdown from body
                    String body  = ms2.group(2).replaceAll("[*_]+$", "").trim();
                    if (!body.isEmpty()) return new String[]{token, body};
                }
            }

            return null;
        }

        /**
         * Detects visual headings — paragraphs that look like section headings
         * even though they have no Word heading style and no clause number.
         *
         * <p>These are NOT skipped (unlike the old all-caps-colon guard which
         * returned {@code null}).  Instead they are marked with a {@code ##}
         * prefix so the pipeline can use them for clause comparison and
         * fraud detection.
         *
         * <p>Heuristics:
         * <ul>
         *   <li>All-uppercase text (allowing spaces, colons, dots, hyphens)</li>
         *   <li>At least 3 letters (not just "I:" or "A:")</li>
         *   <li>Short (≤ 80 chars) — a heading, not a paragraph</li>
         * </ul>
         *
         * <p>Examples: {@code "TARAFLAR:"}, {@code "KONU"}, {@code "GENEL HÜKÜMLER"},
         * {@code "TANIMLAR:"}, {@code "İŞİN YAPILMASI:"}.
         */
        static boolean isHeadingLike(String text) {
            if (text == null || text.isEmpty()) return false;
            String t = text.trim();
            if (t.isEmpty()) return false;
            // Must have at least 3 letters
            long letterCount = t.chars().filter(Character::isLetter).count();
            if (letterCount < 3) return false;
            // Short — a heading, not a paragraph
            if (t.length() > 80) return false;
            // Strip leading markdown markers before checking case
            String stripped = t.replaceAll("^[*_#`\\s]+", "").trim();
            // Guard: if the stripped text starts with a clause number (e.g. "1.2. SUMP POMPA"),
            // it is NOT a heading — it is a numbered paragraph that ExplicitNumberExtractor
            // should handle. Without this guard, "**1.2. SUMP POMPA**" gets "##" injected and
            // the clause number becomes invisible to the LLM.
            if (stripped.matches("^\\d+[.\\d]*[.\\s].*")) return false;
            if (stripped.matches("^\\(\\w+\\).*")) return false;
            // All-uppercase (non-letter chars like spaces, colons, dots are OK)
            if (!t.equals(t.toUpperCase(java.util.Locale.ROOT))) return false;
            return true;
        }
    }

    /** Computes list numbers ("1.", "1.1", "a)") the way Word would display them (decimal/letters/roman). */
    private static final class NumberingResolver {
        private final XWPFDocument doc;
        private final Map<BigInteger, int[]> counters = new HashMap<>();

        NumberingResolver(XWPFDocument doc) {
            this.doc = doc;
        }

        String prefix(XWPFParagraph p) {
            try {
                return computePrefix(p);
            } catch (RuntimeException ex) {
                log.debug("Numbering could not be resolved: {}", ex.toString());
                return "";
            }
        }

        private CTNumPr numPr(XWPFParagraph p) {
            CTPPr ppr = p.getCTP().getPPr();
            if (ppr != null && ppr.isSetNumPr()) {
                return ppr.getNumPr();
            }
            String sid = p.getStyleID();
            if (sid != null && doc.getStyles() != null) {
                XWPFStyle st = doc.getStyles().getStyle(sid);
                int guard = 0;
                while (st != null && guard++ < 10) {
                    //CTPPr sp = st.getCTStyle().getPPr();
                    CTPPrGeneral sp = st.getCTStyle().getPPr();
                    if (sp != null && sp.isSetNumPr()) {
                        return sp.getNumPr();
                    }
                    String basedOn = st.getBasisStyleID();
                    st = basedOn == null ? null : doc.getStyles().getStyle(basedOn);
                }
            }
            return null;
        }

        private String computePrefix(XWPFParagraph p) {
            CTNumPr np = numPr(p);
            if (np == null || !np.isSetNumId()) {
                return "";
            }
            BigInteger numId = np.getNumId().getVal();
            if (numId.signum() == 0) {
                return "";
            }
            int ilvl = np.isSetIlvl() ? np.getIlvl().getVal().intValue() : 0;
            if (ilvl < 0 || ilvl > 8) {
                return "";
            }
            XWPFNumbering nb = doc.getNumbering();
            if (nb == null) {
                return "";
            }
            XWPFNum num = nb.getNum(numId);
            if (num == null) {
                return "";
            }
            BigInteger absId = num.getCTNum().getAbstractNumId().getVal();
            XWPFAbstractNum abs = nb.getAbstractNum(absId);
            if (abs == null) {
                return "";
            }
            CTLvl[] lvls = abs.getCTAbstractNum().getLvlArray();
            if (ilvl >= lvls.length) {
                return "";
            }
            CTLvl lvl = lvls[ilvl];
            String fmt = fmt(lvl);
            if ("bullet".equals(fmt)) {
                return "-";
            }
            if ("none".equals(fmt)) {
                return "";
            }
            int[] c = counters.computeIfAbsent(absId, k -> {
                int[] a = new int[9];
                Arrays.fill(a, -1);
                return a;
            });
            if (c[ilvl] < 0) {
                c[ilvl] = start(lvl);
            } else {
                c[ilvl]++;
            }
            for (int j = ilvl + 1; j < 9; j++) {
                c[j] = -1;
            }
            String text = lvl.isSetLvlText() ? lvl.getLvlText().getVal() : "%" + (ilvl + 1) + ".";
            Matcher m = Pattern.compile("%(\\d)").matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                int k = Integer.parseInt(m.group(1)) - 1;
                CTLvl kl = k >= 0 && k < lvls.length ? lvls[k] : lvl;
                int v = k >= 0 && k < 9 && c[k] >= 0 ? c[k] : start(kl);
                m.appendReplacement(sb, Matcher.quoteReplacement(format(v, fmt(kl))));
            }
            m.appendTail(sb);
            return sb.toString().trim();
        }

        private static String fmt(CTLvl l) {
            return l.isSetNumFmt() ? l.getNumFmt().getVal().toString() : "decimal";
        }

        private static int start(CTLvl l) {
            return l.isSetStart() ? l.getStart().getVal().intValue() : 1;
        }

        private static String format(int v, String fmt) {
            switch (fmt) {
                case "lowerLetter":
                    return letters(v).toLowerCase();
                case "upperLetter":
                    return letters(v);
                case "lowerRoman":
                    return roman(v).toLowerCase();
                case "upperRoman":
                    return roman(v);
                case "decimalZero":
                    return v < 10 ? "0" + v : String.valueOf(v);
                default:
                    return String.valueOf(v);
            }
        }

        private static String letters(int v) {
            StringBuilder sb = new StringBuilder();
            int n = Math.max(v, 1);
            while (n > 0) {
                n--;
                sb.insert(0, (char) ('A' + n % 26));
                n /= 26;
            }
            return sb.toString();
        }

        private static String roman(int v) {
            int[] val = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            String[] sym = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
            StringBuilder sb = new StringBuilder();
            int n = Math.max(v, 1);
            for (int i = 0; i < val.length; i++) {
                while (n >= val[i]) {
                    sb.append(sym[i]);
                    n -= val[i];
                }
            }
            return sb.toString();
        }
    }
}
