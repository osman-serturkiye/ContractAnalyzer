package pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/** PDFBox 3.x wrapper. Not thread safe. */
public final class PdfDoc implements AutoCloseable {

    /**
     * One visual line of native text with its bounding box.
     *
     * Coordinates are normalised to 0–1000 relative to the page dimensions,
     * matching the convention used throughout PdfClauseExtractor and Images.crop().
     *
     * x0, y0 = top-left corner; x1, y1 = bottom-right corner.
     * y increases downward (same as pixel coordinates).
     */
    public static final class LineBbox {
        public final String text;
        public final double x0, y0, x1, y1;

        public LineBbox(String text, double x0, double y0, double x1, double y1) {
            this.text = text;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
    }

    // ------------------------------------------------------------------ line-bbox extractor

    /**
     * PDFTextStripper subclass that intercepts individual TextPosition objects,
     * groups them into lines (by Y-position tolerance), and records the union
     * bounding box of each line normalised to 0–1000 page coordinates.
     *
     * PDFBox reports Y in PDF user space (origin at bottom-left, Y increases upward).
     * We convert to screen-space (origin top-left, Y increases downward):
     *   screenY = pageHeight - pdfY
     *
     * The page dimensions come from PDPage.getMediaBox() in points (1 pt = 1/72 inch).
     */
    private static final class LineBboxStripper extends PDFTextStripper {

        private final List<List<TextPosition>> lines = new ArrayList<>();
        private List<TextPosition> current = new ArrayList<>();
        private float lastBaselineY = Float.NaN;

        // Tolerance in PDF user-space points within which two characters are on the same line.
        private static final float LINE_TOLERANCE_PT = 2.5f;

        LineBboxStripper() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void processTextPosition(TextPosition tp) {
            float baseline = tp.getTextMatrix().getTranslateY();
            if (Float.isNaN(lastBaselineY)
                    || Math.abs(baseline - lastBaselineY) > LINE_TOLERANCE_PT) {
                if (!current.isEmpty()) {
                    lines.add(current);
                    current = new ArrayList<>();
                }
                lastBaselineY = baseline;
            }
            current.add(tp);
            // Do NOT call super.processTextPosition(tp) — PDFBox calls this method
            // from within its own processing loop; calling super here would cause
            // infinite recursion (StackOverflowError) because the parent's
            // writeString() machinery re-invokes processTextPosition internally.
            // We only need to accumulate TextPosition objects; the parent's
            // output-writing pipeline is irrelevant for bbox extraction.
        }

        void flush() {
            if (!current.isEmpty()) {
                lines.add(current);
                current = new ArrayList<>();
            }
        }

        /** Convert collected lines to LineBbox list using the supplied page dimensions (in points). */
        List<LineBbox> toBboxes(float pageWidthPt, float pageHeightPt) {
            List<LineBbox> out = new ArrayList<>(lines.size());
            for (List<TextPosition> line : lines) {
                if (line.isEmpty()) continue;

                // Collect text
                StringBuilder sb = new StringBuilder();
                for (TextPosition tp : line) {
                    sb.append(tp.getUnicode());
                }
                String text = sb.toString().trim();
                if (text.isEmpty()) continue;

                // Union bounding box in PDF user-space (origin bottom-left)
                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
                float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (TextPosition tp : line) {
                    float x = tp.getXDirAdj();
                    float y = tp.getTextMatrix().getTranslateY();
                    float w = tp.getWidthDirAdj();
                    float h = tp.getHeightDir();
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x + w);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y + h);
                }

                // Convert: PDF y is bottom-left origin; flip to top-left origin
                // screenY0 (top of line) = pageHeight - maxY (highest PDF y = top of text)
                // screenY1 (bottom of line) = pageHeight - minY
                double sx0 = pageWidthPt  > 0 ? minX / pageWidthPt  * 1000.0 : 0;
                double sx1 = pageWidthPt  > 0 ? maxX / pageWidthPt  * 1000.0 : 1000;
                double sy0 = pageHeightPt > 0 ? (pageHeightPt - maxY) / pageHeightPt * 1000.0 : 0;
                double sy1 = pageHeightPt > 0 ? (pageHeightPt - minY) / pageHeightPt * 1000.0 : 1000;

                // Clamp
                sx0 = Math.max(0, Math.min(1000, sx0));
                sx1 = Math.max(0, Math.min(1000, sx1));
                sy0 = Math.max(0, Math.min(1000, sy0));
                sy1 = Math.max(0, Math.min(1000, sy1));

                if (sx1 > sx0 && sy1 > sy0) {
                    out.add(new LineBbox(text, sx0, sy0, sx1, sy1));
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ PDDocument fields

    private final PDDocument doc;
    private final PDFRenderer renderer;

    private PdfDoc(PDDocument doc) {
        this.doc = doc;
        this.renderer = new PDFRenderer(doc);
    }

    public static PdfDoc open(File f) throws Exception {
        return new PdfDoc(Loader.loadPDF(f));
    }

    public int pageCount() {
        return doc.getNumberOfPages();
    }

    /** pageIdx is 0-based. */
    public BufferedImage render(int pageIdx, float dpi) throws IOException {
        return renderer.renderImageWithDPI(pageIdx, dpi, ImageType.RGB);
    }

    /** pageIdx is 0-based. */
    public String text(int pageIdx) throws IOException {
        PDFTextStripper s = new PDFTextStripper();
        s.setSortByPosition(true);
        s.setStartPage(pageIdx + 1);
        s.setEndPage(pageIdx + 1);
        return s.getText(doc);
    }

    /**
     * Returns native (embedded) text for every page, in page order.
     * Each entry is the raw text of that page; may be empty for scanned/image-only pages.
     * Callers can use this to decide whether the PDF is "readable" (has enough embedded text)
     * or needs OCR.
     */
    public List<String> pageTexts() throws IOException {
        List<String> out = new ArrayList<>(doc.getNumberOfPages());
        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            out.add(text(p));
        }
        return out;
    }

    /**
     * Returns one {@link LineBbox} per visual line for the given page (0-based).
     *
     * Each entry carries the line's text and its bounding box normalised to 0–1000
     * page coordinates (top-left origin, y increases downward), matching the
     * convention used in {@code PdfClauseExtractor} and {@code Images.crop()}.
     *
     * Lines are returned in top-to-bottom reading order.
     */
    public List<LineBbox> pageLineBboxes(int pageIdx) throws IOException {
        PDPage page = doc.getPage(pageIdx);
        PDRectangle mediaBox = page.getMediaBox();
        float pageW = mediaBox.getWidth();
        float pageH = mediaBox.getHeight();

        LineBboxStripper stripper = new LineBboxStripper();
        stripper.setStartPage(pageIdx + 1);
        stripper.setEndPage(pageIdx + 1);
        // getText triggers processTextPosition calls
        stripper.getText(doc);
        stripper.flush();

        List<LineBbox> lines = stripper.toBboxes(pageW, pageH);
        // Sort top-to-bottom (y0 ascending)
        lines.sort((a, b) -> Double.compare(a.y0, b.y0));
        return lines;
    }

    /**
     * Returns per-line bboxes for all pages, in page order.
     * Index {@code p} corresponds to 0-based page {@code p}.
     */
    public List<List<LineBbox>> allPageLineBboxes() throws IOException {
        List<List<LineBbox>> out = new ArrayList<>(doc.getNumberOfPages());
        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            out.add(pageLineBboxes(p));
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        doc.close();
    }
}