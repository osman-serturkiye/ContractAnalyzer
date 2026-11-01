package report;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

/**
 * One-shot utility: reads a PNG/JPG logo, makes white/near-white pixels
 * transparent, and writes the result as logo.png into the target folder.
 *
 * usage: LogoProcessor <input-image> <output-folder>
 */
public final class LogoProcessor {

    private LogoProcessor() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: LogoProcessor <input-image> <output-folder>");
            System.exit(1);
        }
        Path input  = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        outDir.toFile().mkdirs();

        // Read fully into memory first (safe even if input == output path)
        BufferedImage src = ImageIO.read(input.toFile());
        if (src == null) {
            System.err.println("Could not read image: " + input);
            System.exit(1);
        }
        BufferedImage out = makeWhiteTransparent(src, 40);

        // Write to a temp file first, then atomically replace the destination
        Path dest = outDir.resolve("logo.png");
        Path tmp  = outDir.resolve("logo.png.tmp");
        ImageIO.write(out, "PNG", tmp.toFile());
        java.nio.file.Files.move(tmp, dest,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        System.out.println("Logo written: " + dest.toAbsolutePath()
                + "  (" + out.getWidth() + "x" + out.getHeight() + ")");
    }

    /**
     * Returns a copy of the image where pixels within {@code threshold} distance
     * of white (255,255,255) are made fully transparent.
     */
    public static BufferedImage makeWhiteTransparent(BufferedImage src, int threshold) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >> 24) & 0xff;
                int r = (argb >> 16) & 0xff;
                int g = (argb >>  8) & 0xff;
                int b = (argb      ) & 0xff;

                // If already transparent, keep it
                if (a < 10) {
                    out.setRGB(x, y, 0x00000000);
                    continue;
                }

                // Distance from white
                int dist = (int) Math.sqrt(
                        (255 - r) * (255 - r) +
                        (255 - g) * (255 - g) +
                        (255 - b) * (255 - b)
                );

                if (dist <= threshold) {
                    // Fade edge pixels smoothly instead of hard cut
                    int newAlpha = (int) Math.min(255, (dist / (double) threshold) * 255);
                    out.setRGB(x, y, (newAlpha << 24) | (r << 16) | (g << 8) | b);
                } else {
                    out.setRGB(x, y, argb);
                }
            }
        }
        return out;
    }
}
