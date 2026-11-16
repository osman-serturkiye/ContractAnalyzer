package util;

import java.text.Normalizer;

/**
 * Strips markdown/formatting noise so that OCR markdown and DOCX markdown can be
 * compared fairly on plain text.
 *
 * <p>Key design rules:
 * <ol>
 *   <li>Table cell <em>content</em> must be preserved; only structural characters
 *       (pipe delimiters, separator rows, escaped pipes) are removed.</li>
 *   <li>Escaped pipes ({@code \|}) from DocxReader.tableToMarkdown() are converted
 *       to a space BEFORE the raw {@code |} pass, so no cell text is lost.</li>
 *   <li>The table-separator regex covers both plain {@code ---} and the wider set of
 *       Unicode dash/rule characters that some OCR engines emit (e.g. {@code \u2500},
 *       {@code \u2014}).</li>
 *   <li>Markdown bold/italic markers use a dedicated, unambiguous regex that does NOT
 *       rely on {@code |} as a regex alternation operator mixed with literal pipe
 *       removal — they are handled in a separate, clearly-scoped pass.</li>
 * </ol>
 */
public final class TextNormalizer {
    private TextNormalizer() {
    }

    /**
     * Folds Turkish-specific characters to their ASCII near-equivalents so that
     * OCR output (which may confuse İ↔I, ı↔i, Ş↔S etc.) compares fairly against
     * the original DOCX text.
     *
     * <p>This mapping is applied ONLY inside {@link #normalize} — it is NOT applied
     * to the displayed clause content, only to the token stream used for diff and
     * similarity computation.
     */
    private static String foldTurkish(String t) {
        // Upper-case fold: Ü Ğ İ Ş Ç Ö → U G I S C O
        t = t.replace('Ü', 'U').replace('Ğ', 'G').replace('İ', 'I')
             .replace('Ş', 'S').replace('Ç', 'C').replace('Ö', 'O');
        // Lower-case fold: ü ğ ı ş ç ö → u g i s c o
        t = t.replace('ü', 'u').replace('ğ', 'g').replace('ı', 'i')
             .replace('ş', 's').replace('ç', 'c').replace('ö', 'o');
        return t;
    }

    public static String normalize(String md) {
        if (md == null) {
            return "";
        }

        // 1. Unicode normalization + typographic character substitution
        String t = Normalizer.normalize(md, Normalizer.Form.NFKC);
        t = t.replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-')
                .replace('\u00A0', ' ');

        // 2. HTML line-breaks → space; inline images → space
        t = t.replaceAll("(?i)<br\\s*/?>", " ");
        t = t.replaceAll("(?m)^\\s*!\\[[^\\]]*\\]\\([^)]*\\)\\s*$", " ");

        // 3. Markdown table separator rows (handles ---, :---:, Unicode box-drawing dashes ─ \u2500).
        //    Must run BEFORE pipe removal so the row structure is still recognisable.
        t = t.replaceAll(
                "(?m)^\\s*\\|?\\s*:?[\\-\u2500]{3,}:?\\s*(\\|\\s*:?[\\-\u2500]{3,}:?\\s*)*\\|?\\s*$",
                " ");
        // Also strip standalone horizontal rules (--- or *** or ___ on their own line)
        // that OCR emits for section separators / page borders.
        t = t.replaceAll("(?m)^\\s*[-*_\u2500\u2501\u2502]{3,}\\s*$", " ");

        // 4. HTML tags → space
        t = t.replaceAll("<[^>]+>", " ");

        // 5. Markdown headings (# … ######)
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "");

        // 6. Markdown emphasis/strong markers: *** ** * ___ __ _
        //    Also strip backslash escape sequences (\_ \* \\ etc.) that DocxReader
        //    produces for literal underscores/asterisks in DOCX text — OCR never
        //    emits these escapes so they create false diff tokens (e.g. "\\_" vs "_").
        t = t.replaceAll("\\\\([_*\\\\`|])", "$1");   // unescape \_ \* \\ \` \|  → bare char
        t = t.replaceAll("\\*{1,3}", "");
        t = t.replaceAll("(?<![\\p{L}\\p{N}])_{1,3}(?![\\p{L}\\p{N}])", "");

        // 7. Markdown list markers (- / * / + at line start) and Unicode bullets (•, ◦, ▪, ▸ …)
        //    Convert Unicode bullets to a standard "-" marker first so the stripping regex
        //    covers them uniformly.  Mistral OCR frequently emits "• item" instead of "- item".
        //    NOTE: asterisk-based list markers (* item) are already removed by step 6 above;
        //    this step handles dash/plus markers and Unicode bullets.
        t = t.replaceAll("(?m)^\\s*[\\u2022\\u25E6\\u25AA\\u25AB\\u25B8\\u2023\\u2043\\u204C\\u204D]\\s+", "- ");
        t = t.replaceAll("(?m)^\\s*[-+]\\s+", "");

        // 8. Table pipe characters.
        //    DocxReader.tableToMarkdown() escapes literal pipes inside cell text as \|
        //    → convert \| → space first so the cell content survives, then strip naked |.
        t = t.replace("\\|", " ").replace("|", " ");

        // 9. Markdown inline code backticks
        t = t.replaceAll("`+", "");

        // 10. OCR artifacts: stray lone punctuation lines (e.g. "." or ". ." on their own line)
        //     that OCR engines emit when they misread table borders or list markers.
        //     Only remove lines that are NOTHING but dots, spaces and commas (not real sentences).
        t = t.replaceAll("(?m)^\\s*[\\.\\,\\;]{1,3}\\s*$", " ");

        // 11. Collapse all whitespace to a single space
        t = t.replaceAll("\\s+", " ").trim();

        // 12. Fold Turkish special characters to ASCII near-equivalents so that
        //     OCR errors like İ↔I or ı↔i do not create spurious diff tokens.
        //     Applied last so earlier steps (Unicode NFKC, typographic replacements)
        //     have already run; only affects the diff/similarity token stream —
        //     the displayed clause content is never passed through this method.
        t = foldTurkish(t);
        return t;
    }
}
