package io.aerofleet.cloud.mapping;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * MappingArea ↔ String 转换器，用于 JPA 持久化。
 * <p>
 * 序列化格式：
 * <ul>
 *   <li>POLYGON: {@code "POLYGON:lat1,lon1;lat2,lon2;..."}</li>
 *   <li>CIRCLE: {@code "CIRCLE:centerLat,centerLon,radiusM"}</li>
 * </ul>
 */
@Converter(autoApply = false)
public class MappingAreaConverter implements AttributeConverter<MappingArea, String> {

    @Override
    public String convertToDatabaseColumn(MappingArea area) {
        if (area == null) {
            return null;
        }
        if (area.kind() == MappingArea.Kind.CIRCLE) {
            return "CIRCLE:" + area.centerLat() + "," + area.centerLon() + "," + area.radiusM();
        }
        // POLYGON
        StringBuilder sb = new StringBuilder("POLYGON:");
        List<double[]> pts = area.points();
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(pts.get(i)[0]).append(',').append(pts.get(i)[1]);
        }
        return sb.toString();
    }

    @Override
    public MappingArea convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        if (dbData.startsWith("CIRCLE:")) {
            String body = dbData.substring("CIRCLE:".length());
            String[] parts = body.split(",");
            double centerLat = Double.parseDouble(parts[0]);
            double centerLon = Double.parseDouble(parts[1]);
            double radiusM = Double.parseDouble(parts[2]);
            return MappingArea.circle(centerLat, centerLon, radiusM);
        }
        if (dbData.startsWith("POLYGON:")) {
            String body = dbData.substring("POLYGON:".length());
            String[] pointStrs = body.split(";");
            List<double[]> points = new ArrayList<>(pointStrs.length);
            for (String ps : pointStrs) {
                String[] coords = ps.split(",");
                double lat = Double.parseDouble(coords[0]);
                double lon = Double.parseDouble(coords[1]);
                points.add(new double[]{lat, lon});
            }
            return MappingArea.polygon(points);
        }
        throw new IllegalArgumentException("unknown MappingArea format: " + dbData);
    }
}