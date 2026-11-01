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

    private DocxReader() {
    }

    public static List<Block> read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
            NumberingResolver numbering = new NumberingResolver(doc);
            List<Block> out = new ArrayList<>();
            int id = 0;
            for (IBodyElement e : doc.getBodyElements()) {
                if (e instanceof XWPFParagraph) {
                    XWPFParagraph p = (XWPFParagraph) e;
                    String t = paragraphToMarkdown(p);
                    String prefix = numbering.prefix(p);
                    if (t.isEmpty()) {
                        continue;
                    }
                    String headingPrefix = headingPrefix(p);
                    String full = prefix.isEmpty() ? t : prefix + " " + t;
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
                String headingPrefix = headingPrefix(p);
                String full = prefix.isEmpty() ? t : prefix + " " + t;
                if (!headingPrefix.isEmpty()) full = headingPrefix + " " + full;
                if (sb.length() > 0) sb.append('\n');
                sb.append(full);
            } else if (e instanceof XWPFTable) {
                XWPFTable nested = (XWPFTable) e;
                // Nested bilingual tables: flatten same-column cells into running text.
                // This handles the common pattern where each clause is a row in a nested table.
                if (isBilingualTable(nested)) {
                    // Collect only the matching column from the nested table — caller decides column.
                    // Since we don't know the target column here, flatten ALL cells with a separator.
                    String md = tableToMarkdown(nested);
                    if (!md.trim().isEmpty()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(md);
                    }
                } else {
                    String md = tableToMarkdown(nested);
                    if (!md.trim().isEmpty()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(md);
                    }
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
