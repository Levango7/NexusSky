package io.aerofleet.cloud.vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Pixel-domain blob detector (batch E2): the language-agnostic "detector"
 * that proves the imaging chain works from PIXELS, not the metadata oracle.
 *
 * Pure JDK (zero native deps): decode JPEG -> grayscale -> adaptive
 * threshold (mean + k*stddev, robust to the noise floor) -> BFS connected
 * components -> per-component centroid + bounding box.
 *
 * This class IS the seam a real CV model plugs into later: swap it for a
 * Python/YOLO sidecar that consumes the same JPEG bytes and returns the
 * same {u, v, w, h, confidence} boxes; the geolocation and scoring steps
 * downstream never change.
 */
public final class BlobDetector {

    /** Detected box: centroid (u,v) and extents, in image-pixel space. */
    public static final class Box {
        public final double u;
        public final double v;
        public final double w;
        public final double h;
        public final int area;

        Box(double u, double v, double w, double h, int area) {
            this.u = u;
            this.v = v;
            this.w = w;
            this.h = h;
            this.area = area;
        }
    }

    /** Min component area (px^2) to be a real blob, not noise speckle. */
    private static final int MIN_AREA = 6;

    /** Detect bright blobs in a JPEG. Returns centroids in image-space. */
    public List<Box> detect(byte[] jpeg) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (img == null) {
            throw new IOException("not a decodable image");
        }
        return detect(img);
    }

    /** Detect on an already-decoded image (test hook). */
    public List<Box> detect(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] gray = new byte[w * h];
        long sum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int g = ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3;
                gray[y * w + x] = (byte) g;
                sum += g;
            }
        }
        double mean = sum / (double) (w * h);
        double var = 0;
        for (int i = 0; i < gray.length; i++) {
            var += (gray[i] - mean) * (gray[i] - mean);
        }
        double std = Math.sqrt(var / gray.length);
        int thr = (int) Math.round(mean + 3.5 * std);
        // Clamp into a sane band: the blob fill (>=200) must clear it even on
        // a bright noise floor; guard against thr collapsing below 128.
        thr = Math.max(128, Math.min(220, thr));

        boolean[] seen = new boolean[w * h];
        List<Box> boxes = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int start = y * w + x;
                if (seen[start] || (gray[start] & 0xFF) < thr) {
                    continue;
                }
                // BFS the 4-connected component.
                queue.clear();
                queue.add(start);
                seen[start] = true;
                long su = 0, sv = 0;
                int n = 0;
                int minX = x, maxX = x, minY = y, maxY = y;
                while (!queue.isEmpty()) {
                    int c = queue.removeFirst();
                    int cx = c % w;
                    int cy = c / w;
                    su += cx;
                    sv += cy;
                    n++;
                    minX = Math.min(minX, cx);
                    maxX = Math.max(maxX, cx);
                    minY = Math.min(minY, cy);
                    maxY = Math.max(maxY, cy);
                    if (cx > 0 && tryVisit(c - 1, seen, gray, thr)) queue.add(c - 1);
                    if (cx < w - 1 && tryVisit(c + 1, seen, gray, thr)) queue.add(c + 1);
                    if (cy > 0 && tryVisit(c - w, seen, gray, thr)) queue.add(c - w);
                    if (cy < h - 1 && tryVisit(c + w, seen, gray, thr)) queue.add(c + w);
                }
                if (n >= MIN_AREA) {
                    boxes.add(new Box(su / (double) n, sv / (double) n,
                            maxX - minX + 1.0, maxY - minY + 1.0, n));
                }
            }
        }
        return boxes;
    }

    /** Mark-and-return: true when idx is a fresh above-threshold pixel. */
    private static boolean tryVisit(int idx, boolean[] seen, byte[] gray, int thr) {
        if (!seen[idx] && (gray[idx] & 0xFF) >= thr) {
            seen[idx] = true;
            return true;
        }
        return false;
    }
}