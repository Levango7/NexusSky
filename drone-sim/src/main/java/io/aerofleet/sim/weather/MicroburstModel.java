package io.aerofleet.sim.weather;

public class MicroburstModel {

    public volatile double centerX;
    public volatile double centerY;
    public volatile double radius;
    public volatile double intensity;
    public volatile double duration;
    public volatile double elapsed;

    public MicroburstModel(double centerX, double centerY, double radius, double intensity, double duration) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = radius;
        this.intensity = intensity;
        this.duration = duration;
        this.elapsed = 0.0;
    }

    private double intensityFactor() {
        if (duration <= 0) {
            return 1.0;
        }
        double phase = elapsed / duration;
        if (phase < 0.1) {
            return 0.3 + 0.7 * (phase / 0.1);
        }
        if (phase > 0.9) {
            return Math.max(0, 0.3 + 0.7 * (1 - phase) / 0.1);
        }
        return 1.0;
    }

    public double[] getWindField(double lat, double lon, double alt) {
        double dx = lon - centerX;
        double dy = lat - centerY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > radius || dist < 0.0001) {
            return new double[]{0, 0, 0};
        }
        double factor = intensityFactor();
        double radialSpeed = intensity * factor * (dist / radius);
        double dirX = dx / dist;
        double dirY = dy / dist;
        double horizontalX = radialSpeed * dirX;
        double horizontalY = radialSpeed * dirY;
        double downdraft = getDowndraft(lat, lon);
        double altFactor = alt < 300 ? Math.max(0, alt / 300) : 1.0;
        return new double[]{
                horizontalX * altFactor,
                horizontalY * altFactor,
                -downdraft * altFactor
        };
    }

    public double getDowndraft(double lat, double lon) {
        double dx = lon - centerX;
        double dy = lat - centerY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > radius) {
            return 0;
        }
        double factor = intensityFactor();
        double coreFactor = Math.max(0, 1 - dist / (radius * 0.5));
        return intensity * factor * coreFactor * 0.8;
    }

    public double getWindShear(double lat, double lon) {
        double dx = lon - centerX;
        double dy = lat - centerY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > radius || dist < 0.0001) {
            return 0;
        }
        double factor = intensityFactor();
        double baseShear = intensity * factor / radius;
        double coreFactor = 1 - Math.abs(dist - radius * 0.5) / (radius * 0.5);
        return baseShear * Math.max(0, coreFactor);
    }

    public void tick(double dt) {
        elapsed += dt;
    }
}