package io.aerofleet.cloud.surveillance;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 厂商设备能力 DTO。
 * <p>
 * 描述设备支持的功能集合，包括服务类型、PTZ 能力、流协议、事件类型等。
 * 各厂商适配器通过 {@link VendorAdapter#getCapabilities} 返回此对象，
 * 上层业务可据此判断设备是否支持特定功能。
 * <p>
 * 与简单的 {@code Set<String>} 相比，VendorCapabilities 提供结构化的能力描述，
 * 便于上层业务进行功能判断和 UI 适配。
 */
public class VendorCapabilities {

    /** 设备支持的服务类型集合（如 "Device", "Media", "PTZ", "Events", "Imaging"）。 */
    public final Set<String> services;
    /** 是否支持 PTZ 云台控制。 */
    public final boolean ptzSupported;
    /** 是否支持事件订阅。 */
    public final boolean eventsSupported;
    /** 是否支持视频流获取。 */
    public final boolean streamingSupported;
    /** 是否支持设备发现。 */
    public final boolean discoverySupported;
    /** 支持的流协议列表（如 "rtsp", "hikvision", "dahua", "uniview"）。 */
    public final Set<String> streamProtocols;
    /** 支持的事件类型列表（如 "Motion", "Intrusion", "Fire", "Door"）。 */
    public final Set<String> eventTypes;

    private VendorCapabilities(Builder builder) {
        this.services = Collections.unmodifiableSet(new LinkedHashSet<>(builder.services));
        this.ptzSupported = builder.ptzSupported;
        this.eventsSupported = builder.eventsSupported;
        this.streamingSupported = builder.streamingSupported;
        this.discoverySupported = builder.discoverySupported;
        this.streamProtocols = Collections.unmodifiableSet(new LinkedHashSet<>(builder.streamProtocols));
        this.eventTypes = Collections.unmodifiableSet(new LinkedHashSet<>(builder.eventTypes));
    }

    /**
     * 检查设备是否支持指定服务。
     *
     * @param service 服务名称
     * @return true 若设备支持该服务
     */
    public boolean hasService(String service) {
        return services.contains(service);
    }

    /**
     * 检查设备是否支持指定流协议。
     *
     * @param protocol 协议名称
     * @return true 若设备支持该流协议
     */
    public boolean supportsStreamProtocol(String protocol) {
        return streamProtocols.contains(protocol);
    }

    /**
     * 从简单的服务类型集合创建 VendorCapabilities（兼容旧接口）。
     * <p>
     * 根据服务类型集合推断 PTZ/Events/Streaming/Discovery 支持状态。
     *
     * @param serviceSet 服务类型集合
     * @return 推断的 VendorCapabilities
     */
    public static VendorCapabilities fromServiceSet(Set<String> serviceSet) {
        if (serviceSet == null || serviceSet.isEmpty()) {
            return new Builder().build();
        }
        Builder builder = new Builder();
        builder.services(serviceSet);
        builder.ptzSupported(serviceSet.contains("PTZ"));
        builder.eventsSupported(serviceSet.contains("Events"));
        builder.streamingSupported(serviceSet.contains("Media"));
        builder.discoverySupported(serviceSet.contains("Device"));
        if (serviceSet.contains("Media")) {
            builder.streamProtocol("rtsp");
        }
        if (serviceSet.contains("Events")) {
            builder.eventType("Motion");
        }
        return builder.build();
    }

    /**
     * 转换为简单的服务类型集合（兼容旧接口）。
     *
     * @return 服务类型集合
     */
    public Set<String> toServiceSet() {
        return services;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VendorCapabilities)) return false;
        VendorCapabilities that = (VendorCapabilities) o;
        return ptzSupported == that.ptzSupported
                && eventsSupported == that.eventsSupported
                && streamingSupported == that.streamingSupported
                && discoverySupported == that.discoverySupported
                && Objects.equals(services, that.services)
                && Objects.equals(streamProtocols, that.streamProtocols)
                && Objects.equals(eventTypes, that.eventTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(services, ptzSupported, eventsSupported,
                streamingSupported, discoverySupported, streamProtocols, eventTypes);
    }

    @Override
    public String toString() {
        return "VendorCapabilities{services=" + services
                + ", ptz=" + ptzSupported
                + ", events=" + eventsSupported
                + ", streaming=" + streamingSupported
                + ", discovery=" + discoverySupported
                + ", protocols=" + streamProtocols
                + ", eventTypes=" + eventTypes + "}";
    }

    /**
     * VendorCapabilities 构建器。
     */
    public static class Builder {

        private final Set<String> services = new LinkedHashSet<>();
        private boolean ptzSupported = false;
        private boolean eventsSupported = false;
        private boolean streamingSupported = false;
        private boolean discoverySupported = false;
        private final Set<String> streamProtocols = new LinkedHashSet<>();
        private final Set<String> eventTypes = new LinkedHashSet<>();

        public Builder services(Set<String> services) {
            this.services.clear();
            if (services != null) {
                this.services.addAll(services);
            }
            return this;
        }

        public Builder service(String service) {
            this.services.add(service);
            return this;
        }

        public Builder ptzSupported(boolean ptzSupported) {
            this.ptzSupported = ptzSupported;
            return this;
        }

        public Builder eventsSupported(boolean eventsSupported) {
            this.eventsSupported = eventsSupported;
            return this;
        }

        public Builder streamingSupported(boolean streamingSupported) {
            this.streamingSupported = streamingSupported;
            return this;
        }

        public Builder discoverySupported(boolean discoverySupported) {
            this.discoverySupported = discoverySupported;
            return this;
        }

        public Builder streamProtocols(Set<String> protocols) {
            this.streamProtocols.clear();
            if (protocols != null) {
                this.streamProtocols.addAll(protocols);
            }
            return this;
        }

        public Builder streamProtocol(String protocol) {
            this.streamProtocols.add(protocol);
            return this;
        }

        public Builder eventTypes(Set<String> types) {
            this.eventTypes.clear();
            if (types != null) {
                this.eventTypes.addAll(types);
            }
            return this;
        }

        public Builder eventType(String type) {
            this.eventTypes.add(type);
            return this;
        }

        public VendorCapabilities build() {
            return new VendorCapabilities(this);
        }
    }
}