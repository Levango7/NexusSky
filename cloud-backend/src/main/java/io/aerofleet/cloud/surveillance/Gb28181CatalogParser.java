package io.aerofleet.cloud.surveillance;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GB28181 Catalog 响应解析器（E5，spec D4）。
 * <p>
 * 标准 Catalog 响应（MANSCDP+xml）：
 * <pre>{@code
 * <DeviceList Num="2">
 *   <Item><DeviceID>...20位...</DeviceID><Name>东门</Name>
 *         <Manufacturer>..</Manufacturer><Status>ON</Status></Item>
 *   ...
 * </DeviceList>
 * }</pre>
 * 逐 Item 提取 DeviceID/Name/Status/Manufacturer。**逐字段容错但不静默**：
 * DeviceID 缺失/非法的 Item 跳过并计数（解析结果附 skipped 说明）；
 * 整体不是 Catalog 结构抛明确异常。
 */
@Component
public class Gb28181CatalogParser {

    /** 单个 Catalog 条目（发现层轻量形状；DeviceInfo/SurveillanceDevice 由 adapter 按需转换）。 */
    public record CatalogItem(String deviceId, String name, String status, String manufacturer) {
    }

    /** 解析结果：提取的条目 + 跳过的 Item 数（可观测，不静默）。 */
    public record CatalogResult(List<CatalogItem> items, int skippedItems) {
    }

    private static final Pattern ITEM = Pattern.compile("<Item>(.*?)</Item>", Pattern.DOTALL);
    private static final Pattern FIELD = Pattern.compile("<(\\w+)>([^<]*)</\\1>");

    /** 解析 Catalog 响应 XML。 */
    public CatalogResult parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("catalog xml is empty");
        }
        if (!xml.contains("<DeviceList")) {
            throw new IllegalArgumentException(
                    "not a GB28181 Catalog response (missing <DeviceList>): "
                            + xml.substring(0, Math.min(60, xml.length())) + "...");
        }
        List<CatalogItem> out = new ArrayList<>();
        int skipped = 0;
        Matcher items = ITEM.matcher(xml);
        while (items.find()) {
            String body = items.group(1);
            String deviceId = null;
            String name = null;
            String status = null;
            String manufacturer = null;
            Matcher fields = FIELD.matcher(body);
            while (fields.find()) {
                switch (fields.group(1)) {
                    case "DeviceID" -> deviceId = fields.group(2).trim();
                    case "Name" -> name = fields.group(2).trim();
                    case "Status" -> status = fields.group(2).trim();
                    case "Manufacturer" -> manufacturer = fields.group(2).trim();
                    default -> { /* 其他字段不提取 */ }
                }
            }
            if (deviceId == null || deviceId.length() != 20) {
                skipped++;
                continue;
            }
            try {
                Gb28181DeviceId.parse(deviceId);
            } catch (IllegalArgumentException e) {
                skipped++;   // 编码非法：跳过但计数（Status 面向接入方可观测）
                continue;
            }
            out.add(new CatalogItem(deviceId,
                    name == null ? deviceId : name,
                    "ON".equalsIgnoreCase(status) ? "ON" : "OFF",
                    manufacturer == null ? "GB28181" : manufacturer));
        }
        return new CatalogResult(out, skipped);
    }
}
