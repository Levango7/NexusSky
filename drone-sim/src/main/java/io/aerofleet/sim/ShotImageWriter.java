package io.aerofleet.sim;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Renders a camera shot into a REAL JPEG (batch E2), so the downstream
 * detector consumes pixels - not the metadata oracle. This closes the
 * "audio/video + data-transmission" gap honestly: image bytes exist,
 * have a real size, and can be pulled over the link.
 *
 * Geometry contract (must hold exactly, or the geolocation chain breaks):
 *   - Output resolution = 640x360, a UNIFORM 1/3 scale of the 1920x1080
 *     pinhole model (both 16:9). Uniform scale + same HFOV keeps the
 *     ray-angle ratio (u-cu)/f invariant, so a detector centroid in this
 *     space solves identically to the truth space.
 *   - Blobs: solid bright filled circles centred on the projected (u,v),
 *     radius ~ max(3, altM/12) px, coloured by target kind.
 *   - Noise floor: per-pixel gaussian noise ~ N(35, 12), clamped, seeded
 *     deterministically by frameSeq so e2e is reproducible.
 */
public final class ShotImageWriter {

    public static final int IMG_W = 640;
    public static final int IMG_H = 360;
    /** Scale factor from the 1920x1080 pinhole model. */
    public static final double SCALE = IMG_W / 1920.0;

    private ShotImageWriter() {
    }

    /** Render one shot to JPEG bytes. */
    public static byte[] render(CameraModel.Shot shot) throws IOException {
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(shot.frameSeq * 0x9E3779B9L);
        for (int y = 0; y < IMG_H; y++) {
            for (int x = 0; x < IMG_W; x++) {
                int g = clampGauss(rnd, 35, 12);
                img.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        }
        for (CameraModel.CapturedTarget t : shot.targets) {
            int cx = (int) Math.round(t.u * SCALE);
            int cy = (int) Math.round(t.v * SCALE);
            int r = Math.max(3, (int) Math.round(shot.altM / 12.0));
            int rgb = kindColor(t.kind);
            int rC = (rgb >> 16) & 0xFF, gC = (rgb >> 8) & 0xFF, bC = rgb & 0xFF;
            for (int y = cy - r; y <= cy + r; y++) {
                for (int x = cx - r; x <= cx + r; x++) {
                    if (x < 0 || x >= IMG_W || y < 0 || y >= IMG_H) {
                        continue;
                    }
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
                        img.setRGB(x, y, (rC << 16) | (gC << 8) | bC);
                    }
                }
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", bos);
        return bos.toByteArray();
    }

    /** kind -> fill colour: static=white, vehicle=red, pedestrian=green, else yellow. */
    private static int kindColor(String kind) {
        return switch (kind) {
            case "vehicle" -> 0xE33E2B;      // red-ish
            case "pedestrian" -> 0x2BD46A;   // green
            case "static" -> 0xF0F0F0;       // near-white
            default -> 0xF5D328;             // yellow
        };
    }

    /**
     * Render one shot to a RAW 8-bit grayscale frame (w×h, row-major, origin
     * top-left) for the onboard edge vision chain (M12, 2026-10-04).
     *
     * Same geometry contract as {@link #render(CameraModel.Shot)}: the shot's
     * (u,v) live in the 1920x1080 pinhole space and are uniformly scaled, so a
     * detection centroid here solves to the same ground truth. Differences
     * vs the JPEG path, both deliberate:
     *   - no JPEG encode: the edge {@code VideoStreamAnalyzer} eats raw gray
     *     bytes, so no ImageIO round-trip and no IOException;
     *   - noise floor sigma is 6 (vs 12): the downstream frame-differencing
     *     threshold is 30, and differencing two N(35,12) floors gives sigma≈17
     *     which would light ~8% of pixels as false motion. sigma=6 keeps the
     *     diff sigma≈8.5 so only real blob movement crosses the threshold.
     * Blobs render as uniform bright (220) filled circles - frame differencing
     * is intensity-based, colour carries no information for this consumer.
     */
    public static byte[] renderGray(CameraModel.Shot shot, int w, int h) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("w/h must be positive, got " + w + "x" + h);
        }
        double sx = w / 1920.0;
        double sy = h / 1080.0;
        byte[] out = new byte[w * h];
        Random rnd = new Random(shot.frameSeq * 0x9E3779B9L);
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) clampGauss(rnd, 35, 6);
        }
        double blobScale = w / 640.0;
        for (CameraModel.CapturedTarget t : shot.targets) {
            int cx = (int) Math.round(t.u * sx);
            int cy = (int) Math.round(t.v * sy);
            int r = Math.max(1, (int) Math.round(shot.altM / 12.0 * blobScale));
            for (int y = cy - r; y <= cy + r; y++) {
                for (int x = cx - r; x <= cx + r; x++) {
                    if (x < 0 || x >= w || y < 0 || y >= h) {
                        continue;
                    }
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
                        out[y * w + x] = (byte) 220;
                    }
                }
            }
        }
        return out;
    }

    /** Deterministic gaussian-ish noise: mean + sigma * (sum of 3 uniforms - 1.5). */
    private static int clampGauss(Random rnd, double mean, double sigma) {
        double v = (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5) * 2 * sigma;
        return (int) Math.max(0, Math.min(255, Math.round(mean + v)));
    }
}