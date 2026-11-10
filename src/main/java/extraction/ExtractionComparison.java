package extraction;

import docx.DocxReader;
import org.json.JSONObject;
import pdf.PdfDoc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Sample-09 için clause extraction karşılaştırması:
 * 1. DOCX → DocxReader blocks → ClauseExtraction.fromDocxBlocks (deterministik)
 * 2. PDF  → PdfDoc native lines → ClauseExtraction.fromPdfLines (deterministik)
 * 3. LLM cached sonuçlarla karşılaştırma
 *
 * Hiyerarşi kuralı: 5 → 5.1 veya 6; 5.1 → 5.1.1 veya 5.2
 */
public class ExtractionComparison {

    public static void main(String[] args) throws Exception {
        String sampleDir = args.length > 0 ? args[0] : "C:/contract-analyze/sample-09/";
        String lang = args.length > 1 ? args[1] : "TR";
        // Auto-detect DOCX and PDF files in the sample directory
        File docxFile = findFile(sampleDir, ".docx");
        File pdfFile  = findFile(sampleDir, ".pdf");

        System.out.println("=== EXTRACTION COMPARISON (" + sampleDir + " lang=" + lang + ") ===\n");

        // ---- 1. DOCX deterministic extraction ----
        System.out.println("--- 1. DOCX (Master) — Deterministic Extraction ---");
        List<DocxReader.Block> docxBlocks = DocxReader.read(docxFile);
        List<ClauseBlock> docxClauseBlocks = toClauseBlocks(docxBlocks, ClauseBlock.Source.DOCX, lang);
        ClauseExtraction.ExtractionResult docxDet = ClauseExtraction.fromDocxBlocks(docxClauseBlocks, lang);
        System.out.println("DOCX blocks: " + docxBlocks.size());
        System.out.println("Deterministic clauses: " + docxDet.clauses.size());
        printClauseNumbers(docxDet.clauses.keySet(), "Deterministic DOCX");
        System.out.println("Diagnostics: " + docxDet.diagnosticReport());
        System.out.println();

        // ---- 2. DOCX LLM extraction (from cache) ----
        System.out.println("--- 2. DOCX (Master) — LLM Extraction (cached) ---");
        Set<String> docxLlm = loadCachedClauses(sampleDir + "work/original1." + lang + ".extract.json");
        System.out.println("LLM clauses: " + docxLlm.size());
        printClauseNumbers(docxLlm, "LLM DOCX");
        System.out.println();

        // ---- Compare DOCX deterministic vs LLM ----
        System.out.println("--- 3. DOCX Comparison: Deterministic vs LLM ---");
        compareClauseSets(docxDet.clauses.keySet(), docxLlm, "Deterministic", "LLM");
        System.out.println();

        // ---- 4. PDF native text extraction ----
        System.out.println("--- 4. PDF (Signed) — Deterministic Extraction ---");
        try (PdfDoc doc = PdfDoc.open(pdfFile)) {
            List<String> pageTexts = doc.pageTexts();
            boolean readable = isReadablePdf(pageTexts);
            System.out.println("PDF pages: " + doc.pageCount() + ", readable: " + readable);

            if (readable) {
                // Native text mode — filter header/footer like PdfClauseExtractor
                double headerEdge = 5.0 * 10.0;  // top 5%
                double footerEdge = 1000.0 - 6.0 * 10.0;  // bottom 6%
                List<ClauseBlock> pdfLines = new ArrayList<>();
                int id = 0;
                for (int p = 0; p < doc.pageCount(); p++) {
                    for (PdfDoc.LineBbox lb : doc.pageLineBboxes(p)) {
                        if (lb.text.isEmpty()) continue;
                        if (lb.y1 <= headerEdge || lb.y0 >= footerEdge) continue;
                        pdfLines.add(new ClauseBlock(id++, p, lb.text,
                                ClauseBlock.Source.PDF_LINE, -1,
                                lb.x0, lb.y0, lb.x1, lb.y1));
                    }
                }
                System.out.println("PDF native lines: " + pdfLines.size());
                ClauseExtraction.ExtractionResult pdfDet = ClauseExtraction.fromPdfLines(pdfLines, lang);
                System.out.println("Deterministic clauses: " + pdfDet.clauses.size());
                printClauseNumbers(pdfDet.clauses.keySet(), "Deterministic PDF");
                System.out.println("Diagnostics: " + pdfDet.diagnosticReport());
                System.out.println();

                // ---- 5. PDF LLM extraction (from cache) ----
                System.out.println("--- 5. PDF (Signed) — LLM Extraction (cached) ---");
                Set<String> pdfLlm = loadCachedClauses(sampleDir + "work/signed." + lang + ".extract.json");
                System.out.println("LLM clauses: " + pdfLlm.size());
                printClauseNumbers(pdfLlm, "LLM PDF");
                System.out.println();

                // ---- 6. PDF Comparison ----
                System.out.println("--- 6. PDF Comparison: Deterministic vs LLM ---");
                compareClauseSets(pdfDet.clauses.keySet(), pdfLlm, "Deterministic", "LLM");
                System.out.println();

                // ---- 7. Master (DOCX) vs Signed (PDF) comparison ----
                System.out.println("--- 7. Master (DOCX-LLM) vs Signed (PDF-LLM) ---");
                compareClauseSets(docxLlm, pdfLlm, "DOCX-LLM", "PDF-LLM");
                System.out.println();

                System.out.println("--- 8. Master (DOCX-Det) vs Signed (PDF-Det) ---");
                compareClauseSets(docxDet.clauses.keySet(), pdfDet.clauses.keySet(),
                        "DOCX-Det", "PDF-Det");
            } else {
                System.out.println("PDF is NOT readable (image-only), needs OCR — skipping native extraction");
            }
        }

        System.out.println("\n=== DONE ===");
    }

    // ------------------------------------------------------------------
    // Helpers

    static List<ClauseBlock> toClauseBlocks(List<DocxReader.Block> blocks, ClauseBlock.Source source, String lang) {
        // Bilingual: filter by language column (TR=0, EN=1; -1 = shared)
        boolean hasBilingual = blocks.stream().anyMatch(b -> b.columnIndex >= 0);
        int targetCol = -1;
        if (hasBilingual) {
            // Simple heuristic: TR → col 0, EN → col 1
            targetCol = "TR".equalsIgnoreCase(lang) ? 0 : 1;
        }
        List<ClauseBlock> out = new ArrayList<>();
        int id = 0;
        for (DocxReader.Block b : blocks) {
            if (hasBilingual && b.columnIndex >= 0 && b.columnIndex != targetCol) continue;
            // Split multi-line cell text into separate paragraph blocks
            String[] lines = b.text.split("\n");
            for (String line : lines) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    out.add(new ClauseBlock(id++, 0, t, source, b.columnIndex));
                }
            }
        }
        return out;
    }

    static File findFile(String dir, String ext) {
        File d = new File(dir);
        File[] files = d.listFiles((f, name) -> name.toLowerCase().endsWith(ext));
        if (files == null || files.length == 0)
            throw new RuntimeException("No " + ext + " file found in " + dir);
        // Prefer standard naming: Approved_Document / Signed_Document
        for (String prefix : new String[]{"Approved_Document", "Signed_Document", "original1", "signed"}) {
            for (File f : files)
                if (f.getName().startsWith(prefix)) return f;
        }
        return files[0];
    }

    static boolean isReadablePdf(List<String> pageTexts) {
        int samplePages = Math.min(pageTexts.size(), 5);
        if (samplePages == 0) return false;
        long total = 0;
        for (int p = 0; p < samplePages; p++) {
            String t = pageTexts.get(p);
            for (int i = 0; i < t.length(); i++) {
                if (!Character.isWhitespace(t.charAt(i))) total++;
            }
        }
        return (total / samplePages) >= 100;
    }

    static Set<String> loadCachedClauses(String path) throws Exception {
        String json = Files.readString(Path.of(path));
        JSONObject obj = new JSONObject(json);
        JSONObject clauses = obj.optJSONObject("clauses");
        if (clauses == null) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String key : clauses.keySet()) {
            out.add(key);
        }
        return out;
    }

    static void printClauseNumbers(Set<String> numbers, String label) {
        List<String> sorted = new ArrayList<>(numbers);
        sorted.sort(ExtractionComparison::compareClauseNumber);
        System.out.println(label + " (" + sorted.size() + "): " + sorted);
    }

    static int compareClauseNumber(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            try {
                int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
                if (c != 0) return c;
            } catch (NumberFormatException e) {
                return x[i].compareTo(y[i]);
            }
        }
        return Integer.compare(x.length, y.length);
    }

    static void compareClauseSets(Set<String> setA, Set<String> setB, String labelA, String labelB) {
        Set<String> onlyA = new LinkedHashSet<>(setA);
        onlyA.removeAll(setB);
        Set<String> onlyB = new LinkedHashSet<>(setB);
        onlyB.removeAll(setA);
        Set<String> both = new LinkedHashSet<>(setA);
        both.retainAll(setB);

        System.out.println(labelA + " total: " + setA.size());
        System.out.println(labelB + " total: " + setB.size());
        System.out.println("Common (both): " + both.size());
        System.out.println("Only in " + labelA + " (" + onlyA.size() + "): "
                + new TreeSet<>(onlyA));
        System.out.println("Only in " + labelB + " (" + onlyB.size() + "): "
                + new TreeSet<>(onlyB));

        if (!onlyA.isEmpty() || !onlyB.isEmpty()) {
            double coverage = setA.isEmpty() ? 0 : (double) both.size() / setA.size() * 100;
            System.out.println(String.format("Coverage (%s → %s): %.1f%%", labelA, labelB, coverage));
        }
    }
}
