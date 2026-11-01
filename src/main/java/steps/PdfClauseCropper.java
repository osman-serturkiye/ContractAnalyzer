// DEPRECATED: no longer called by Pipeline/ContractAnalyzer as of the PdfClauseExtractor patch (see PATCH_NOTES.md). Kept for reference only - safe to delete.
package steps;

import ai.Router;
import ai.StepAi;
import config.AppConfig;
import pdf.PdfDoc;
import util.ClauseNumber;
import util.Images;
import util.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clause cropping of a (scanned) signed PDF.
 *
 * <p>Why this design (instead of asking the vision model for full boxes):
 * vision models are good at READING numbers and bad at ESTIMATING pixel coordinates. So
 * <ol>
 *   <li>a labelled RULER + faint grid is drawn around the page image; the model reads positions off the ruler;</li>
 *   <li>the model only returns where each clause STARTS (y_start, column x0..x1) and, for the last clause of a column,
 *       where it ENDS; the bottom of every other clause is the start of the next clause in the same column
 *       (no gaps, no overlaps, parent clause never swallows its sub-clauses);</li>
 *   <li>columns are detected once per PAGE from the set of clause x-ranges (not re-guessed per clause), and every
 *       clause is snapped to its nearest column band — this is what keeps multi-column pages from bleeding into
 *       each other and stops indented sub-clauses from falling outside their parent's column;</li>
 *   <li>every edge is SNAPPED to the nearest blank pixel row/column (using an adaptive, Otsu-derived ink threshold,
 *       not a fixed gray value), so text lines are never cut through, and the crop is trimmed to the real ink bounds;</li>
 *   <li>page-spanning ("continuation") clauses are only merged into a previous fragment when the model's
 *       continuation flag AND page-contiguity agree; otherwise they're kept as separate, flagged fragments instead
 *       of being silently stacked onto a same-numbered clause from an unrelated page;</li>
 *   <li>debug overlays (boxes drawn on each page) + the raw model answer are written to the debug folder.</li>
 * </ol>
 */
public final class PdfClauseCropper {
    private static final Logger log = LogManager.getLogger();

    private static final String SYSTEM = "You are a precise document layout analyzer for legal contracts. "
            + "You read positions from the printed ruler. You only output JSON.";

    /** Column bands whose right-edge gap (in 0..1000 ruler units) is smaller than this are treated as adjacent
     *  enough to merge (covers wrapped/justified paragraphs of slightly different width in the same column). */
    private static final double COLUMN_MERGE_GAP = 60.0;

    /** Column bands whose LEFT edges (x0) are within this many ruler units are treated as the same column,
     *  regardless of how far their right edges differ. This is what a short heading like "2. TANIMLAR" needs:
     *  it starts at the same left margin as the body text below it, even though its own x1 is much smaller. */
    private static final double COLUMN_X0_MERGE_TOL = 80.0;

    private final AppConfig cfg;
    private final Router router;
    private Path debugDir;

    public PdfClauseCropper(AppConfig cfg, Router router) {
        this.cfg = cfg;
        this.router = router;
    }

    /** If set, per-page overlays (crop-debug/crop_TR_p1.png) and raw model answers are written there. */
    public void setDebugDir(Path dir) {
        this.debugDir = dir;
    }

    // ------------------------------------------------------------------ public

    public JSONObject crop(File pdf, String lang) throws Exception {
        StepAi ai = router.get("clauseImageCropPDFSignedDocument");
        float dpi = cfg.pipelineInt("renderDpi", 150);
        int pad = cfg.pipelineInt("cropPaddingPx", 8);
        double blankRatio = cfg.pipelineDouble("cropBlankRowRatio", 0.004);
        double snapTolPct = cfg.pipelineDouble("cropSnapTolerancePct", 2.5);
        double footerPct = cfg.pipelineDouble("cropFooterMarginPct", 4.0);

        Map<String, List<BufferedImage>> frags = new LinkedHashMap<>();
        Map<String, JSONArray> boxes = new LinkedHashMap<>();
        Map<String, Integer> lastSeenPage = new LinkedHashMap<>();
        String title = "";
        String prev = "";

        try (PdfDoc doc = PdfDoc.open(pdf)) {
            int n = doc.pageCount();
            for (int p = 0; p < n; p++) {
                int pageNo = p + 1;
                BufferedImage page = doc.render(p, dpi);
                Ink ink = new Ink(page, blankRatio);

                JSONObject j = askModel(ai, page, lang, pageNo, n, prev);
                if (j == null) {
                    log.warn("Signed PDF [{}] page {}/{}: no usable model answer", lang, pageNo, n);
                    continue;
                }
                String t = j.optString("title", "").trim();
                if (title.isEmpty() && !t.isEmpty()) {
                    title = t;
                }

                List<Start> starts = parseStarts(j.optJSONArray("clauses"));
                List<Region> regions = layout(starts, ink, snapTolPct, footerPct, dpi);

                Set<String> seen = new HashSet<>();
                List<Region> kept = new ArrayList<>();
                for (Region r : regions) {
                    String origNo = r.no;
                    if (!seen.add(origNo)) {
                        log.warn("Page {} clause {}: duplicate on the same page ignored", pageNo, origNo);
                        continue;
                    }

                    // Only trust "continuation" when it's contiguous with where we last saw this clause number.
                    // Otherwise this is either a hallucinated flag or a coincidentally-repeated number on an
                    // unrelated page; keep it as its own fragment rather than silently stacking it onto the wrong one.
                    if (r.cont) {
                        Integer lastPage = lastSeenPage.get(origNo);
                        if (lastPage == null || pageNo - lastPage > 1) {
                            log.warn("Page {} clause {}: marked as continuation but not contiguous with last seen "
                                    + "page ({}) — keeping as a separate fragment", pageNo, origNo, lastPage);
                            r.no = origNo + "_p" + pageNo;
                        }
                    }

                    int[] tight = ink.trim(r.x0, r.y0, r.x1, r.y1);
                    if (tight == null) {
                        log.warn("Page {} clause {}: region has no ink, ignored", pageNo, origNo);
                        continue;
                    }
                    int x0 = Math.max(0, tight[0] - pad);
                    int y0 = Math.max(0, tight[1] - pad);
                    int x1 = Math.min(ink.w, tight[2] + pad);
                    int y1 = Math.min(ink.h, tight[3] + pad);
                    if (x1 - x0 < 16 || y1 - y0 < 8) {
                        continue;
                    }
                    // If the trimmed ink box fills almost the entire coarse (pre-trim) region, trim() likely
                    // picked up unrelated neighboring content (a table, image or stray mark) rather than just
                    // this clause's own text — surface it so a run with visibly bloated crops can be diagnosed
                    // from the log instead of only from the overlay image.
                    int coarseH = Math.max(1, r.y1 - r.y0);
                    int coarseW = Math.max(1, r.x1 - r.x0);
                    if ((y1 - y0) > coarseH * 0.92 && coarseH > 200) {
                        log.warn("Page {} clause {}: trimmed box height ({}) is >92% of its coarse region ({}) "
                                + "— may have absorbed neighboring content", pageNo, origNo, y1 - y0, coarseH);
                    }
                    if ((x1 - x0) > coarseW * 0.98 && coarseW > 200) {
                        log.warn("Page {} clause {}: trimmed box width ({}) is >98% of its coarse region ({}) "
                                + "— may have absorbed neighboring content", pageNo, origNo, x1 - x0, coarseW);
                    }
                    r.x0 = x0;
                    r.y0 = y0;
                    r.x1 = x1;
                    r.y1 = y1;
                    frags.computeIfAbsent(r.no, k -> new ArrayList<>()).add(copy(page, x0, y0, x1, y1));
                    boxes.computeIfAbsent(r.no, k -> new JSONArray()).put(new JSONArray()
                            .put(pageNo)
                            .put(Math.round(x0 * 1000.0 / ink.w))
                            .put(Math.round(y0 * 1000.0 / ink.h))
                            .put(Math.round(x1 * 1000.0 / ink.w))
                            .put(Math.round(y1 * 1000.0 / ink.h)));
                    kept.add(r);
                    lastSeenPage.put(origNo, pageNo);
                    prev = origNo;
                }
                writeDebugOverlay(page, kept, lang, pageNo);
                log.info("Signed PDF [{}] page {}/{}: {} clause crops", lang, pageNo, n, kept.size());
            }
        }

        JSONObject clauses = new JSONObject();
        for (Map.Entry<String, List<BufferedImage>> e : frags.entrySet()) {
            JSONObject c = new JSONObject();
            c.put("signed_image", Images.toBase64Png(Images.stackVertically(e.getValue())));
            c.put("bboxes", boxes.get(e.getKey()));
            clauses.put(e.getKey(), c);
        }
        return new JSONObject().put("title", title).put("clauses", clauses);
    }

    // ------------------------------------------------------------------ model call

    private JSONObject askModel(StepAi ai, BufferedImage page, String lang, int pageNo, int pages, String prev)
            throws IOException {
        String img = Images.toBase64Png(withRuler(page));
        String user = prompt(lang, pageNo, pages, prev);
        for (int attempt = 0; attempt < 2; attempt++) {
            String resp = ai.chat(SYSTEM, user, List.of(img));
            writeDebugText(resp, lang, pageNo);
            try {
                return Json.parseLoose(resp);
            } catch (RuntimeException ex) {
                log.warn("Page {}: model answer is not valid JSON (attempt {}): {}", pageNo, attempt + 1, ex.getMessage());
            }
        }
        return null;
    }

    private static String prompt(String lang, int page, int pages, String prev) {
        return "The attached image is page " + page + " of " + pages + " of a contract that may contain several languages "
                + "(side-by-side columns or alternating blocks).\n\n"
                + "RULER: a red ruler was drawn around the page. The LEFT and RIGHT rulers give the VERTICAL position, the TOP "
                + "ruler gives the HORIZONTAL position. Both use a scale of 0..1000 relative to the page "
                + "(0 = top / left edge of the page, 1000 = bottom / right edge). Faint red lines mark every 50 units "
                + "vertically and every 100 units horizontally. READ the values from the ruler and the grid lines; "
                + "do not estimate pixels.\n\n"
                + "TARGET LANGUAGE: " + lang + ". Consider ONLY text written in this language.\n\n"
                + "List every numbered clause or sub-clause whose text is visible on this page, in reading order "
                + "(numbering examples: \"1\", \"1.1\", \"2.3.4\", \"Article 5\", \"Madde 7\"). For each one return:\n"
                + " - number: digits and dots only, e.g. \"2.3\"\n"
                + " - y_start: ruler value of the TOP edge of the clause's first line (the line that carries its number)\n"
                + " - y_end: ruler value of the BOTTOM edge of the last line of the clause's OWN text, i.e. just before the "
                + "next clause / sub-clause begins\n"
                + " - x0, x1: left and right ruler value of the column or block that contains the clause "
                + "(a full-width clause is about 50..950, a clause in the left column of a two-column page about 50..490)\n"
                + " - continuation: true if the text at the top of this page only continues a clause that started on the "
                + "previous page (its number is not printed again); then number = that previous clause number and y_start = "
                + "top of the continued text.\n"
                + "The last clause seen on the previous page was: \"" + prev + "\".\n\n"
                + "Rules:\n"
                + " - A parent clause (\"4\") covers only its own heading/text, NOT its sub-clauses (\"4.1\", \"4.2\"); "
                + "every sub-clause is listed separately with its own y_start.\n"
                + " - Tables, bullet lists and paragraphs that belong to a clause are part of that clause.\n"
                + " - Ignore the document title, parties, preamble, page headers/footers, page numbers, stamps and "
                + "signature blocks.\n"
                + " - Return the document title in the target language in \"title\" if this page shows it, else \"\".\n\n"
                + "Return JSON only, no markdown, no comments:\n"
                + "{\"title\":\"\",\"clauses\":[{\"number\":\"4.1\",\"y_start\":230,\"y_end\":310,\"x0\":60,\"x1\":940,"
                + "\"continuation\":false}]}";
    }

    // ------------------------------------------------------------------ parsing + layout

    private static final class Start {
        String no;
        double x0 = 0;
        double x1 = 1000;
        double ys;
        double ye = Double.NaN;
        boolean cont;
    }

    private static final class Region {
        String no;
        boolean cont;
        int x0;
        int y0;
        int x1;
        int y1;
    }

    private static List<Start> parseStarts(JSONArray arr) {
        List<Start> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) {
                continue;
            }
            String no = ClauseNumber.normalize(c.optString("number", ""));
            if (no == null) {
                continue;
            }
            Start s = new Start();
            s.no = no;
            s.cont = c.optBoolean("continuation", false);
            JSONArray bb = c.optJSONArray("bbox");                    // tolerated: [x0,y0,x1,y1]
            if (bb != null && bb.length() >= 4) {
                s.x0 = bb.optDouble(0, 0);
                s.ys = bb.optDouble(1, Double.NaN);
                s.x1 = bb.optDouble(2, 1000);
                s.ye = bb.optDouble(3, Double.NaN);
            }
            s.x0 = c.optDouble("x0", s.x0);
            s.x1 = c.optDouble("x1", s.x1);
            s.ys = c.optDouble("y_start", c.optDouble("y0", s.ys));
            s.ye = c.optDouble("y_end", c.optDouble("y1", s.ye));
            if (Double.isNaN(s.ys) || Double.isNaN(s.x0) || Double.isNaN(s.x1)) {
                continue;
            }
            double max = Math.max(Math.max(Math.abs(s.x0), Math.abs(s.x1)), Math.max(Math.abs(s.ys),
                    Double.isNaN(s.ye) ? 0 : Math.abs(s.ye)));
            if (max <= 1.0) {                                        // model answered 0..1 instead of 0..1000
                s.x0 *= 1000;
                s.x1 *= 1000;
                s.ys *= 1000;
                s.ye *= 1000;
            }
            s.x0 = clamp(s.x0);
            s.x1 = clamp(s.x1);
            s.ys = clamp(s.ys);
            if (!Double.isNaN(s.ye)) {
                s.ye = clamp(s.ye);
            }
            // NOTE: a degenerate/too-narrow x-range is NOT forced to full width here anymore.
            // Forcing it to 0..1000 used to make the clause "overlap" with every column on the page,
            // which is exactly what caused columns to bleed into each other. Instead, layout() snaps
            // it to the nearest column band actually detected on this page.
            out.add(s);
        }
        out.sort(Comparator.comparingDouble((Start s) -> s.ys));
        return out;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1000, v));
    }

    /**
     * Detects column bands for the page from the set of non-degenerate clause x-ranges (ruler units, 0..1000).
     * Intervals whose gap is smaller than {@link #COLUMN_MERGE_GAP} are merged into the same band, so slightly
     * indented sub-clauses land in the same band as their parent instead of forming a spurious extra column.
     */
    private static List<double[]> detectColumnBands(List<Start> starts) {
        List<double[]> intervals = new ArrayList<>();
        for (Start s : starts) {
            // Lower width floor than before (15 instead of 30): a short heading like "2. TANIMLAR" must still
            // enter banding, otherwise it's excluded and later gets assigned to whichever band happens to be
            // nearest by nearestBand()'s midpoint metric, which is exactly what broke on this page.
            if (s.x1 - s.x0 >= 15) {
                intervals.add(new double[]{s.x0, s.x1});
            }
        }
        if (intervals.isEmpty()) {
            List<double[]> fallback = new ArrayList<>();
            fallback.add(new double[]{0, 1000});
            return fallback;
        }
        intervals.sort(Comparator.comparingDouble(a -> a[0]));
        List<double[]> bands = new ArrayList<>();
        double[] cur = intervals.get(0).clone();
        for (int i = 1; i < intervals.size(); i++) {
            double[] iv = intervals.get(i);
            // Same column when EITHER the left margins line up (a short heading starting at the same x0 as the
            // paragraphs below it — this is the case that broke) OR the intervals are adjacent/overlapping on
            // the right edge (wrapped paragraphs of varying width within the same column).
            boolean sameLeftMargin = Math.abs(iv[0] - cur[0]) < COLUMN_X0_MERGE_TOL;
            double gap = iv[0] - cur[1];
            boolean adjacentOrOverlapping = gap < COLUMN_MERGE_GAP;
            if (sameLeftMargin || adjacentOrOverlapping) {
                cur[0] = Math.min(cur[0], iv[0]);
                cur[1] = Math.max(cur[1], iv[1]);
            } else {
                bands.add(cur);
                cur = iv.clone();
            }
        }
        bands.add(cur);
        return bands;
    }

    private static int nearestBand(List<double[]> bands, Start s) {
        double mid = (s.x0 + s.x1) / 2.0;
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < bands.size(); i++) {
            double[] b = bands.get(i);
            double dist = (mid >= b[0] && mid <= b[1]) ? 0 : Math.min(Math.abs(mid - b[0]), Math.abs(mid - b[1]));
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /** Start positions -> non-overlapping regions; bottoms come from the next start in the same column. */
    private static List<Region> layout(List<Start> starts, Ink ink, double snapTolPct, double footerPct, float dpi) {
        int w = ink.w;
        int h = ink.h;
        // tol used to be a flat percentage of page HEIGHT (e.g. 2.5%), which on a tall page is far larger than a
        // real line gap (~20-25px at 150 DPI) — snapBottom/snapRight search outward up to this budget when the
        // starting row/col isn't already blank, and since that search is one-directional, an oversized tol shows
        // up specifically as extra whitespace on the BOTTOM and RIGHT edges of crops, never the top/left. Cap it
        // by a DPI-derived budget (roughly 1.2x a typical single-line gap) so it can't run away on long pages.
        int tolPctBased = Math.max(6, (int) Math.round(h * snapTolPct / 100.0));
        int tolDpiBased = Math.max(6, Math.round(dpi * 0.12f));
        int tol = Math.min(tolPctBased, tolDpiBased);
        int footer = (int) Math.round(h * (1.0 - footerPct / 100.0));
        int n = starts.size();

        // Column detection happens ONCE for the whole page, not per clause: a clause's own x-range is often
        // unreliable (narrow/degenerate), but the set of ranges across all clauses on the page reliably reveals
        // how many columns exist and where they sit.
        List<double[]> bands = detectColumnBands(starts);
        boolean singleColumn = bands.size() <= 1;
        int[] col = new int[n];
        for (int i = 0; i < n; i++) {
            Start s = starts.get(i);
            col[i] = nearestBand(bands, s);
            if (s.x1 - s.x0 < 30) {
                // Degenerate width from the model: trust the detected band rather than forcing full page width.
                double[] b = bands.get(col[i]);
                s.x0 = b[0];
                s.x1 = b[1];
            }
        }

        int[] x0 = new int[n];
        int[] x1 = new int[n];
        int[] top = new int[n];
        for (int i = 0; i < n; i++) {
            Start s = starts.get(i);
            int ax = (int) Math.round(s.x0 / 1000.0 * w);
            int bx = (int) Math.round(s.x1 / 1000.0 * w);
            int ty = (int) Math.round(s.ys / 1000.0 * h);
            top[i] = ink.snapTop(ty, ax, bx, tol);
            x0[i] = ink.snapLeft(ax, top[i], Math.min(h - 1, top[i] + tol * 4), tol);
            x1[i] = ink.snapRight(bx, top[i], Math.min(h - 1, top[i] + tol * 4), tol);
        }

        int minGap = Math.max(4, h / 100);
        List<Region> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Start s = starts.get(i);
            int bottom = -1;
            for (int j = 0; j < n; j++) {
                if (j == i || top[j] <= top[i] + minGap) {
                    continue;
                }
                // On a single-column page, ANY later start ends the current clause — indentation of a sub-clause
                // must never let a parent's region swallow it. On a multi-column page, only a start in the same
                // detected column band can end this clause.
                boolean sameColumn = singleColumn || col[j] == col[i];
                if (sameColumn) {
                    bottom = bottom < 0 ? top[j] : Math.min(bottom, top[j]);
                }
            }
            if (bottom < 0) {                                        // last clause of its column
                int ye = Double.isNaN(s.ye) ? footer : (int) Math.round(s.ye / 1000.0 * h);
                bottom = ink.snapBottom(Math.min(Math.max(ye, top[i] + minGap), footer), x0[i], x1[i], tol);
            }
            if (bottom - top[i] < 8) {
                continue;
            }
            Region r = new Region();
            r.no = s.no;
            r.cont = s.cont;
            r.x0 = x0[i];
            r.x1 = x1[i];
            r.y0 = top[i];
            r.y1 = Math.min(bottom, h);
            out.add(r);
        }
        return out;
    }

    // ------------------------------------------------------------------ ink analysis (snapping / trimming)

    private static final class Ink {
        final int w;
        final int h;
        final double blankRatio;
        final boolean[] dark;

        Ink(BufferedImage img, double blankRatio) {
            this.w = img.getWidth();
            this.h = img.getHeight();
            this.blankRatio = blankRatio;
            int[] gray = new int[w * h];
            int[] hist = new int[256];
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                img.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    int p = row[x];
                    int gv = (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
                    gray[y * w + x] = gv;
                    hist[gv]++;
                }
            }
            // Adaptive (Otsu) threshold instead of a fixed gray value: scanned pages vary a lot in background
            // tone / JPEG noise, and a fixed threshold either misses faint text or never sees a row as "blank",
            // which starves the snap logic and lets crops cut straight through text lines.
            int threshold = otsuThreshold(hist, w * h);
            this.dark = new boolean[w * h];
            for (int i = 0; i < gray.length; i++) {
                dark[i] = gray[i] < threshold;
            }
        }

        private static int otsuThreshold(int[] hist, int total) {
            if (total <= 0) {
                return 170;
            }
            long sum = 0;
            for (int t = 0; t < 256; t++) {
                sum += (long) t * hist[t];
            }
            long sumB = 0;
            int wB = 0;
            double maxVar = -1;
            int threshold = 170;
            for (int t = 0; t < 256; t++) {
                wB += hist[t];
                if (wB == 0) {
                    continue;
                }
                int wF = total - wB;
                if (wF == 0) {
                    break;
                }
                sumB += (long) t * hist[t];
                double mB = sumB / (double) wB;
                double mF = (sum - sumB) / (double) wF;
                double varBetween = (double) wB * wF * (mB - mF) * (mB - mF);
                if (varBetween > maxVar) {
                    maxVar = varBetween;
                    threshold = t;
                }
            }
            // Guard against near-blank / near-solid pages (stamps, heavy scan noise) pushing Otsu to an extreme.
            return Math.max(120, Math.min(200, threshold));
        }

        int rowCount(int y, int x0, int x1) {
            int c = 0;
            int base = y * w;
            for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
                if (dark[base + x]) {
                    c++;
                }
            }
            return c;
        }

        int colCount(int x, int y0, int y1) {
            int c = 0;
            for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
                if (dark[y * w + x]) {
                    c++;
                }
            }
            return c;
        }

        boolean blankRow(int y, int x0, int x1) {
            return rowCount(y, x0, x1) <= Math.max(1, (int) ((x1 - x0) * blankRatio));
        }

        boolean blankCol(int x, int y0, int y1) {
            return colCount(x, y0, y1) <= Math.max(1, (int) ((y1 - y0) * blankRatio));
        }

        /** Moves an edge that cuts through a text line UP to the blank gap above it. */
        int snapTop(int y, int x0, int x1, int tol) {
            y = Math.max(0, Math.min(h - 1, y));
            if (blankRow(y, x0, x1)) {
                return y;
            }
            for (int d = 1; d <= tol && y - d >= 0; d++) {
                if (blankRow(y - d, x0, x1)) {
                    return y - d;
                }
            }
            return y;
        }

        /** Moves an edge that cuts through a text line DOWN (else up) to the nearest blank gap. */
        int snapBottom(int y, int x0, int x1, int tol) {
            y = Math.max(0, Math.min(h - 1, y));
            if (blankRow(y, x0, x1)) {
                return y;
            }
            for (int d = 1; d <= tol && y + d < h; d++) {
                if (blankRow(y + d, x0, x1)) {
                    return y + d;
                }
            }
            for (int d = 1; d <= tol && y - d >= 0; d++) {
                if (blankRow(y - d, x0, x1)) {
                    return y - d;
                }
            }
            return y;
        }

        int snapLeft(int x, int y0, int y1, int tol) {
            x = Math.max(0, Math.min(w - 1, x));
            if (blankCol(x, y0, y1)) {
                return x;
            }
            for (int d = 1; d <= tol && x - d >= 0; d++) {
                if (blankCol(x - d, y0, y1)) {
                    return x - d;
                }
            }
            return x;
        }

        int snapRight(int x, int y0, int y1, int tol) {
            x = Math.max(0, Math.min(w - 1, x));
            if (blankCol(x, y0, y1)) {
                return x;
            }
            for (int d = 1; d <= tol && x + d < w; d++) {
                if (blankCol(x + d, y0, y1)) {
                    return x + d;
                }
            }
            return x;
        }

        /** Shrinks a rectangle to the bounds of the ink inside it; null if it holds no ink. */
        int[] trim(int x0, int y0, int x1, int y1) {
            x0 = Math.max(0, x0);
            y0 = Math.max(0, y0);
            x1 = Math.min(w, x1);
            y1 = Math.min(h, y1);
            int minY = -1;
            int maxY = -1;
            for (int y = y0; y < y1; y++) {
                if (!blankRow(y, x0, x1)) {
                    if (minY < 0) {
                        minY = y;
                    }
                    maxY = y;
                }
            }
            if (minY < 0) {
                return null;
            }
            int minX = -1;
            int maxX = -1;
            for (int x = x0; x < x1; x++) {
                if (!blankCol(x, minY, maxY + 1)) {
                    if (minX < 0) {
                        minX = x;
                    }
                    maxX = x;
                }
            }
            if (minX < 0) {
                return null;
            }
            return new int[]{minX, minY, maxX + 1, maxY + 1};
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    private static BufferedImage copy(BufferedImage src, int x0, int y0, int x1, int y1) {
        BufferedImage out = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src.getSubimage(x0, y0, x1 - x0, y1 - y0), 0, 0, null);
        g.dispose();
        return out;
    }

    /** Page + red ruler (left/right = y, top = x, 0..1000) + faint grid. Only used as model input. */
    static BufferedImage withRuler(BufferedImage page) {
        int w = page.getWidth();
        int h = page.getHeight();
        int m = Math.max(48, Math.round(Math.min(w, h) * 0.05f));
        BufferedImage out = new BufferedImage(w + 2 * m, h + m, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(page, m, m, null);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(13, m / 3)));
        FontMetrics fm = g.getFontMetrics();
        Color red = new Color(200, 0, 0);
        Color faint = new Color(220, 0, 0, 55);
        g.setStroke(new BasicStroke(1f));

        for (int u = 0; u <= 1000; u += 10) {
            int yy = m + (int) Math.round(u / 1000.0 * h);
            int len = u % 50 == 0 ? m / 2 : m / 5;
            g.setColor(red);
            g.drawLine(m - len, yy, m, yy);
            g.drawLine(m + w, yy, m + w + len, yy);
            if (u % 50 == 0) {
                g.setColor(faint);
                g.drawLine(m, yy, m + w, yy);
                g.setColor(red);
                String s = String.valueOf(u);
                g.drawString(s, Math.max(0, m - len - fm.stringWidth(s) - 3), yy + fm.getAscent() / 2 - 2);
                g.drawString(s, m + w + len + 3, yy + fm.getAscent() / 2 - 2);
            }
            int xx = m + (int) Math.round(u / 1000.0 * w);
            int xlen = u % 50 == 0 ? m / 2 : m / 5;
            g.setColor(red);
            g.drawLine(xx, m - xlen, xx, m);
            if (u % 100 == 0) {
                g.setColor(faint);
                g.drawLine(xx, m, xx, m + h);
                g.setColor(red);
                String s = String.valueOf(u);
                g.drawString(s, xx - fm.stringWidth(s) / 2, Math.max(fm.getAscent(), m - xlen - 3));
            }
        }
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------ debug output

    private void writeDebugOverlay(BufferedImage page, List<Region> regions, String lang, int pageNo) {
        if (debugDir == null) {
            return;
        }
        try {
            Files.createDirectories(debugDir);
            BufferedImage dbg = new BufferedImage(page.getWidth(), page.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dbg.createGraphics();
            g.drawImage(page, 0, 0, null);
            g.setStroke(new BasicStroke(3f));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(16, page.getHeight() / 70)));
            int k = 0;
            for (Region r : regions) {
                Color c = Color.getHSBColor((k++ * 0.17f) % 1f, 0.9f, 0.8f);
                g.setColor(c);
                g.drawRect(r.x0, r.y0, r.x1 - r.x0, r.y1 - r.y0);
                g.drawString(r.no, r.x0 + 4, r.y0 + g.getFontMetrics().getAscent());
            }
            g.dispose();
            ImageIO.write(dbg, "png", debugDir.resolve("crop_" + lang + "_p" + pageNo + ".png").toFile());
        } catch (IOException e) {
            log.warn("Debug overlay could not be written: {}", e.getMessage());
        }
    }

    private void writeDebugText(String resp, String lang, int pageNo) {
        if (debugDir == null) {
            return;
        }
        try {
            Files.createDirectories(debugDir);
            Files.writeString(debugDir.resolve("crop_" + lang + "_p" + pageNo + ".json"), resp == null ? "" : resp,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Debug answer could not be written: {}", e.getMessage());
        }
    }
}