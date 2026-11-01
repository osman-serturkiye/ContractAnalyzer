package util;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

public final class Images {

    private static final double COORD_MAX = 1000.0;

    private Images() {
    }

    public static String toBase64Png(BufferedImage img) throws IOException {
        if (img == null) {
            throw new IllegalArgumentException("Image must not be null");
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        ImageIO.write(img, "png", bos);

        return Base64.getEncoder().encodeToString(
                bos.toByteArray()
        );
    }

    public static BufferedImage fromBase64(String b64)
            throws IOException {

        if (b64 == null || b64.trim().isEmpty()) {
            return null;
        }

        return ImageIO.read(
                new ByteArrayInputStream(
                        Base64.getDecoder().decode(b64)
                )
        );
    }

    /**
     * Crops a region from an image.
     *
     * Coordinates are normalized to 0..1000.
     * Origin is top-left.
     *
     * x0,y0 = top-left
     * x1,y1 = bottom-right
     */
    public static BufferedImage crop(
            BufferedImage src,
            double x0,
            double y0,
            double x1,
            double y1,
            int pad
    ) {

        if (src == null) {
            return null;
        }

        if (!Double.isFinite(x0)
                || !Double.isFinite(y0)
                || !Double.isFinite(x1)
                || !Double.isFinite(y1)) {
            return null;
        }

        int width = src.getWidth();
        int height = src.getHeight();

        if (width <= 0 || height <= 0) {
            return null;
        }

        /*
         * Normalize coordinate order.
         */
        double left = Math.min(x0, x1);
        double top = Math.min(y0, y1);
        double right = Math.max(x0, x1);
        double bottom = Math.max(y0, y1);

        /*
         * Clamp normalized coordinates.
         */
        left = clamp(left);
        top = clamp(top);
        right = clamp(right);
        bottom = clamp(bottom);

        /*
         * Convert normalized coordinates to pixels.
         */
        int ax = (int) Math.floor(
                left / COORD_MAX * width
        );

        int ay = (int) Math.floor(
                top / COORD_MAX * height
        );

        int bx = (int) Math.ceil(
                right / COORD_MAX * width
        );

        int by = (int) Math.ceil(
                bottom / COORD_MAX * height
        );

        /*
         * Padding is applied AFTER coordinate conversion.
         */
        int safePad = Math.max(0, pad);

        ax -= safePad;
        ay -= safePad;
        bx += safePad;
        by += safePad;

        /*
         * Clamp pixel coordinates.
         */
        ax = Math.max(0, ax);
        ay = Math.max(0, ay);
        bx = Math.min(width, bx);
        by = Math.min(height, by);

        int cropWidth = bx - ax;
        int cropHeight = by - ay;

        /*
         * Ignore extremely small / invalid regions.
         */
        if (cropWidth < 16 || cropHeight < 8) {
            return null;
        }

        BufferedImage out =
                new BufferedImage(
                        cropWidth,
                        cropHeight,
                        BufferedImage.TYPE_INT_RGB
                );

        Graphics2D g = out.createGraphics();

        try {
            g.drawImage(
                    src,
                    0,
                    0,
                    cropWidth,
                    cropHeight,
                    ax,
                    ay,
                    bx,
                    by,
                    null
            );
        } finally {
            g.dispose();
        }

        return out;
    }

    private static double clamp(double value) {
        return Math.max(
                0.0,
                Math.min(COORD_MAX, value)
        );
    }

    /**
     * Stacks clause fragments vertically.
     *
     * Fragments are expected to be in page order.
     */
    public static BufferedImage stackVertically(
            List<BufferedImage> parts
    ) {

        if (parts == null || parts.isEmpty()) {
            return null;
        }

        if (parts.size() == 1) {
            return parts.get(0);
        }

        final int gap = 12;

        int width = 0;
        int height = 0;

        for (BufferedImage part : parts) {

            if (part == null) {
                continue;
            }

            width = Math.max(
                    width,
                    part.getWidth()
            );

            height += part.getHeight();
        }

        /*
         * Add gaps only between valid images.
         */
        int validCount = 0;

        for (BufferedImage part : parts) {
            if (part != null) {
                validCount++;
            }
        }

        if (validCount > 1) {
            height += (validCount - 1) * gap;
        }

        if (width <= 0 || height <= 0) {
            return null;
        }

        BufferedImage out =
                new BufferedImage(
                        width,
                        height,
                        BufferedImage.TYPE_INT_RGB
                );

        Graphics2D g = out.createGraphics();

        try {

            g.setColor(Color.WHITE);

            g.fillRect(
                    0,
                    0,
                    width,
                    height
            );

            int y = 0;

            int index = 0;

            for (BufferedImage part : parts) {

                if (part == null) {
                    continue;
                }

                g.drawImage(
                        part,
                        0,
                        y,
                        null
                );

                y += part.getHeight();

                index++;

                if (index < validCount) {
                    y += gap;
                }
            }

        } finally {
            g.dispose();
        }

        return out;
    }
}