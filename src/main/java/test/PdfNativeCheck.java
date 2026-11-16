package test;
import pdf.PdfDoc;
import java.io.File;
import java.util.List;

public class PdfNativeCheck {
    public static void main(String[] args) throws Exception {
        String pdfPath = args.length > 0 ? args[0] : "c:/contract-analyze/sample-07/Signed_Document.pdf";
        try (PdfDoc doc = PdfDoc.open(new File(pdfPath))) {
            int pages = doc.pageCount();
            List<String> texts = doc.pageTexts();
            int totalChars = 0;
            for (int i = 0; i < texts.size(); i++) {
                int ch = texts.get(i).replaceAll("\\s","").length();
                totalChars += ch;
                System.out.printf("Page %d: %d chars%n", i+1, ch);
            }
            int avg = totalChars / Math.max(1, texts.size());
            System.out.printf("Pages=%d  Avg non-ws chars/page=%d  Mode=%s%n",
                pages, avg, avg >= 100 ? "NATIVE" : "SCANNED/OCR");
        }
    }
}
