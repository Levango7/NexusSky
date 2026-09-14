package io.aerofleet.cloud.vision;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BlobDetector unit tests (batch E2): synthetic dark image with bright
 * blobs -> threshold + connected components recover centroids to sub-pixel
 * accuracy; noise-only image yields no false positives.
 */
class BlobDetectorTest {

    private final BlobDetector detector = new BlobDetector();

    private static BufferedImage dark(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    private static void fillCenteredCircle(BufferedImage img, int cx, int cy, int r) {
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                if (x < 0 || x >= img.getWidth() || y < 0 || y >= img.getHeight()) {
                    continue;
                }
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
                    img.setRGB(x, y, 0xFFFFFF);
                }
            }
        }
    }

    @Test
    void findsSingleBlobCentroid() {
        BufferedImage img = dark(640, 360);
        fillCenteredCircle(img, 320, 180, 12);
        List<BlobDetector.Box> boxes = detector.detect(img);
        assertEquals(1, boxes.size(), "exactly one blob");
        assertEquals(320, boxes.get(0).u, 0.5, "centroid u");
        assertEquals(180, boxes.get(0).v, 0.5, "centroid v");
        assertTrue(boxes.get(0).area >= 100, "blob area is non-trivial");
    }

    @Test
    void findsTwoBlobs() {
        BufferedImage img = dark(640, 360);
        fillCenteredCircle(img, 160, 90, 10);
        fillCenteredCircle(img, 480, 270, 10);
        List<BlobDetector.Box> boxes = detector.detect(img);
        assertEquals(2, boxes.size());
        // order is scan order (top-left first): (160,90) then (480,270)
        assertEquals(160, boxes.get(0).u, 0.6);
        assertEquals(90, boxes.get(0).v, 0.6);
        assertEquals(480, boxes.get(1).u, 0.6);
        assertEquals(270, boxes.get(1).v, 0.6);
    }

    @Test
    void emptyImageYieldsNoBlobs() {
        BufferedImage img = dark(640, 360);
        // all-zero (black) image: thr = max(128, ...) = 128 > 0, nothing detected
        assertTrue(detector.detect(img).isEmpty());
    }

    @Test
    void tinySpeckleBelowMinAreaIsIgnored() {
        BufferedImage img = dark(640, 360);
        fillCenteredCircle(img, 100, 100, 1);   // area ~5 px < MIN_AREA=6
        List<BlobDetector.Box> boxes = detector.detect(img);
        assertTrue(boxes.isEmpty(), "sub-threshold speckle dropped, got " + boxes.size());
    }
}