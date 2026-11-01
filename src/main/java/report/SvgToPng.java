package report;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

/**
 * Draws the Kiro-style mascot logo programmatically and saves it as
 * settings/logo.png with a transparent background.
 *
 * usage: SvgToPng <output-folder>
 */
public final class SvgToPng {

    private SvgToPng() {}

    public static void main(String[] args) throws Exception {
        Path outDir = args.length > 0 ? Path.of(args[0]) : Path.of("settings");
        outDir.toFile().mkdirs();

        int size = 200;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,       RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,          RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,      RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING,    RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,     RenderingHints.VALUE_STROKE_PURE);

        // ---- transparent background (logo.png will have no white border) ----
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, size, size);

        // ---- gradient background rounded-square ----
        GradientPaint bgGrad = new GradientPaint(
                0, 0,   new Color(0x4F8EF7),
                size, size, new Color(0xEC4899));
        g.setPaint(bgGrad);
        RoundRectangle2D bgRect = new RoundRectangle2D.Float(6, 6, size - 12, size - 12, 54, 54);
        g.fill(bgRect);

        // ---- side ears ----
        g.setPaint(bgGrad);
        g.fill(new Ellipse2D.Float(4, 82, 26, 36));
        g.fill(new Ellipse2D.Float(size - 30, 82, 26, 36));

        // ---- face ellipse ----
        GradientPaint faceGrad = new GradientPaint(
                40, 50, new Color(0xE0EEFF),
                size - 40, size - 30, new Color(0xC7D9FF));
        g.setPaint(faceGrad);
        g.fill(new Ellipse2D.Float(34, 52, 132, 116));

        // ---- eyes ----
        g.setColor(new Color(0x1e3a8a));
        g.fill(new Ellipse2D.Float(58, 76, 26, 30));
        g.fill(new Ellipse2D.Float(116, 76, 26, 30));

        // ---- eye shine ----
        g.setColor(new Color(255, 255, 255, 200));
        g.fill(new Ellipse2D.Float(62, 79, 9, 9));
        g.fill(new Ellipse2D.Float(120, 79, 9, 9));

        // ---- smile ----
        g.setColor(new Color(0x1e3a8a));
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        java.awt.geom.QuadCurve2D smile = new java.awt.geom.QuadCurve2D.Float(
                58, 128,   100, 156,   142, 128);
        g.draw(smile);

        // ---- cheek blush ----
        g.setColor(new Color(0xEC4899, false));
        Composite oldComp = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
        g.fill(new Ellipse2D.Float(36, 120, 30, 18));
        g.fill(new Ellipse2D.Float(134, 120, 30, 18));
        g.setComposite(oldComp);

        // ---- antenna ----
        g.setColor(new Color(0xA855F7));
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(100, 52, 100, 24);
        g.fill(new Ellipse2D.Float(88, 12, 24, 24));

        g.dispose();

        File dest = outDir.resolve("logo.png").toFile();
        ImageIO.write(img, "PNG", dest);
        System.out.println("Logo written: " + dest.getAbsolutePath());
    }
}
