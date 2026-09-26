package io.aerofleet.sim.terrain;

import java.util.Random;

public class DemTerrainModel {

    public volatile int rows;
    public volatile int cols;
    public volatile double resolution;
    public volatile double[][] elevations;
    public volatile double originLat;
    public volatile double originLon;

    public DemTerrainModel(int rows, int cols, double resolution) {
        this.rows = rows;
        this.cols = cols;
        this.resolution = resolution;
        this.elevations = new double[rows][cols];
        this.originLat = 0.0;
        this.originLon = 0.0;
    }

    public double getElevation(double lat, double lon) {
        double rowF = (lat - originLat) / resolution;
        double colF = (lon - originLon) / resolution;
        if (rowF < 0 || rowF >= rows - 1 || colF < 0 || colF >= cols - 1) {
            return 0.0;
        }
        int r0 = (int) Math.floor(rowF);
        int c0 = (int) Math.floor(colF);
        double dr = rowF - r0;
        double dc = colF - c0;
        double e00 = elevations[r0][c0];
        double e01 = elevations[r0][c0 + 1];
        double e10 = elevations[r0 + 1][c0];
        double e11 = elevations[r0 + 1][c0 + 1];
        return (1 - dr) * (1 - dc) * e00
                + (1 - dr) * dc * e01
                + dr * (1 - dc) * e10
                + dr * dc * e11;
    }

    public double getSlope(double lat, double lon) {
        double halfRes = resolution * 0.5;
        double eN = getElevation(lat + halfRes, lon);
        double eS = getElevation(lat - halfRes, lon);
        double eE = getElevation(lat, lon + halfRes);
        double eW = getElevation(lat, lon - halfRes);
        double metersPerDegLat = 111000.0;
        double distMeters = halfRes * metersPerDegLat;
        if (distMeters < 1e-9) {
            return 0.0;
        }
        double dzdx = (eE - eW) / (2 * distMeters);
        double dzdy = (eN - eS) / (2 * distMeters);
        return Math.atan(Math.sqrt(dzdx * dzdx + dzdy * dzdy));
    }

    public static DemTerrainModel loadAscii(String content) {
        String[] lines = content.trim().split("\n");
        String[] header = lines[0].trim().split("\\s+");
        int hdrRows = Integer.parseInt(header[0]);
        int hdrCols = Integer.parseInt(header[1]);
        double hdrRes = header.length > 2 ? Double.parseDouble(header[2]) : 1.0;
        DemTerrainModel model = new DemTerrainModel(hdrRows, hdrCols, hdrRes);
        for (int r = 0; r < hdrRows && (r + 1) < lines.length; r++) {
            String[] parts = lines[r + 1].trim().split("\\s+");
            for (int c = 0; c < hdrCols && c < parts.length; c++) {
                model.elevations[r][c] = Double.parseDouble(parts[c]);
            }
        }
        return model;
    }

    public static DemTerrainModel syntheticHills(int rows, int cols, double resolution) {
        DemTerrainModel model = new DemTerrainModel(rows, cols, resolution);
        Random rnd = new Random(42);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double x = (c / (double) cols) * Math.PI * 4;
                double y = (r / (double) rows) * Math.PI * 4;
                model.elevations[r][c] = 100 * Math.sin(x) * Math.cos(y)
                        + 50 * Math.sin(x * 2) * Math.cos(y * 2)
                        + rnd.nextDouble() * 5;
            }
        }
        return model;
    }

    public static DemTerrainModel syntheticMountain(int rows, int cols, double resolution) {
        DemTerrainModel model = new DemTerrainModel(rows, cols, resolution);
        double cx = cols / 2.0;
        double cy = rows / 2.0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double dx = (c - cx) / cx;
                double dy = (r - cy) / cy;
                double dist = Math.sqrt(dx * dx + dy * dy);
                model.elevations[r][c] = 2000 * Math.exp(-dist * dist * 3)
                        + 300 * Math.max(0, 1 - dist);
            }
        }
        return model;
    }

    public static DemTerrainModel syntheticPlain(int rows, int cols, double resolution) {
        DemTerrainModel model = new DemTerrainModel(rows, cols, resolution);
        Random rnd = new Random(7);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                model.elevations[r][c] = 10 + rnd.nextDouble() * 5;
            }
        }
        return model;
    }

    public static DemTerrainModel syntheticCanyon(int rows, int cols, double resolution) {
        DemTerrainModel model = new DemTerrainModel(rows, cols, resolution);
        double canyonWidth = cols * 0.15;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double distFromCenter = Math.abs(c - cols / 2.0);
                if (distFromCenter < canyonWidth) {
                    double depth = 500 * (1 - distFromCenter / canyonWidth);
                    model.elevations[r][c] = 1000 - depth;
                } else {
                    double slope = (distFromCenter - canyonWidth) / (cols / 2.0 - canyonWidth);
                    model.elevations[r][c] = 1000 + 200 * slope + 50 * Math.sin(r * 0.1);
                }
            }
        }
        return model;
    }
}