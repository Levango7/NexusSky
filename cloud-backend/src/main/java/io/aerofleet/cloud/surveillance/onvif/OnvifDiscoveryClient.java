package io.aerofleet.cloud.surveillance.onvif;

import io.aerofleet.cloud.surveillance.SurveillanceDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WS-Discovery（SOAP-over-UDP）设备发现。
 *
 * <h2>真实协议，不是扫描器</h2>
 *
 * <p>向 {@code 239.255.255.250:3702} 发 {@code Probe}（类型
 * {@code dn:NetworkVideoTransmitter}），收集设备回的 {@code ProbeMatch}。
 * 另外支持"单播探针"：直接向配置的 {@code host[:port]} 发同一个包，
 * 用于交换机会丢多播的现场。
 *
 * <h2>{@code subnet} 参数在 live 模式下是什么</h2>
 *
 * <p>WS-Discovery 是<b>链路本地</b>协议，没有"扫 192.168.1.0/24"这种能力
 * （那是模拟实现按第三段编设备的做法）。live 模式下该参数只作为<b>结果过滤</b>：
 * 是 IPv4 CIDR 时只保留落在网段内的应答；不是 CIDR 时记一条 WARN 并不过滤
 * （而不是假装按它扫过）。
 *
 * <h2>应答可以不可信</h2>
 *
 * <p>局域网里任何人都能回一个 ProbeMatch。这里只做结构解析与上限保护
 * （单包 ≤ 64 KB、最多 64 台），<b>不</b>据此信任设备身份——凭据仍由
 * {@link OnvifSoapClient} 在真实 SOAP 调用里验证。
 */
public class OnvifDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(OnvifDiscoveryClient.class);

    private static final String MULTICAST_ADDRESS = "239.255.255.250";
    private static final int DISCOVERY_PORT = 3702;
    private static final int MAX_DATAGRAM = 64 * 1024;
    private static final int MAX_DEVICES = 64;

    private static final Pattern NAME_SCOPE =
            Pattern.compile("onvif://www\\.onvif\\.org/name/(\\S+)");
    private static final Pattern HARDWARE_SCOPE =
            Pattern.compile("onvif://www\\.onvif\\.org/hardware/(\\S+)");

    private final OnvifSettings settings;

    public OnvifDiscoveryClient(OnvifSettings settings) {
        this.settings = settings;
    }

    /**
     * 发探针并收集应答。
     *
     * @param subnet 结果过滤用 CIDR（可为 null/非 CIDR，见类注释）
     * @return 发现的设备；无应答时为空列表（不是错误）
     */
    public List<SurveillanceDevice> discover(String subnet) {
        OnvifValidation.requireSubnet(subnet);

        if (settings.getDiscoveryUsername() == null || settings.getDiscoveryUsername().isBlank()
                || settings.getDiscoveryPassword() == null || settings.getDiscoveryPassword().isEmpty()) {
            // ProbeMatch 里没有凭据字段；没有配置就没有可登记的设备账号。
            // 用占位账号登记会让后续 SOAP 调用必然 401，比这里直接说清楚更糟。
            throw new OnvifException("live discovery requires aerofleet.onvif.discovery-username and "
                    + "aerofleet.onvif.discovery-password (WS-Discovery replies carry no credentials)");
        }

        Ipv4Cidr filter = parseCidrOrNull(subnet);
        byte[] probe = probeMessage().getBytes(StandardCharsets.UTF_8);
        List<SurveillanceDevice> discovered = new ArrayList<>();
        Set<String> seenIps = new HashSet<>();

        try (MulticastSocket socket = new MulticastSocket(0)) {
            socket.setTimeToLive(1);
            if (!sendProbes(socket, probe)) {
                throw new OnvifException("WS-Discovery could not send a Probe: multicast is "
                        + (settings.isDiscoveryMulticastEnabled() ? "enabled but failed" : "disabled")
                        + " and no unicast target was reachable");
            }

            long deadlineNanos = System.nanoTime()
                    + Math.max(1, settings.getDiscoveryTimeoutMs()) * 1_000_000L;
            byte[] buffer = new byte[MAX_DATAGRAM];
            while (System.nanoTime() < deadlineNanos && discovered.size() < MAX_DEVICES) {
                long remainingMs = Math.max(1,
                        (deadlineNanos - System.nanoTime()) / 1_000_000L);
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMs));
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    break;
                } catch (PortUnreachableException e) {
                    // 单播目标不在线时 Windows 会把 ICMP 端口不可达回灌到 receive
                    log.debug("WS-Discovery unicast target unreachable: {}", e.getMessage());
                    continue;
                }
                handleDatagram(packet, filter, seenIps, discovered);
            }
        } catch (IOException e) {
            throw new OnvifException("WS-Discovery failed: " + e.getMessage(), e);
        }

        log.info("WS-Discovery found {} device(s) (subnet={})", discovered.size(), subnet);
        return discovered;
    }

    // ------------------------------------------------------------------

    /** 至少成功发出一个探针（多播或任一单播目标）才返回 {@code true}。 */
    private boolean sendProbes(MulticastSocket socket, byte[] probe) {
        boolean anySent = false;
        if (settings.isDiscoveryMulticastEnabled()) {
            try {
                InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
                socket.send(new DatagramPacket(probe, probe.length, group, DISCOVERY_PORT));
                anySent = true;
            } catch (IOException e) {
                // 多播发送失败不是致命：单播目标仍可能工作
                log.warn("WS-Discovery multicast probe failed: {}", e.getMessage());
            }
        }
        List<String> targets = settings.getDiscoveryUnicastTargets();
        if (targets != null) {
            for (String target : targets) {
                if (target == null || target.isBlank()) {
                    continue;
                }
                try {
                    InetSocketAddress address = parseTarget(target.trim());
                    socket.send(new DatagramPacket(probe, probe.length,
                            address.getAddress(), address.getPort()));
                    anySent = true;
                } catch (IOException | IllegalArgumentException e) {
                    log.warn("WS-Discovery unicast probe to {} failed: {}", target, e.getMessage());
                }
            }
        }
        return anySent;
    }

    private static InetSocketAddress parseTarget(String target) {
        String host = target;
        int port = DISCOVERY_PORT;
        int colon = target.lastIndexOf(':');
        if (colon > 0 && colon < target.length() - 1) {
            host = target.substring(0, colon);
            port = Integer.parseInt(target.substring(colon + 1));
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
        }
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (address.isUnresolved() || address.getAddress() == null) {
            throw new IllegalArgumentException("cannot resolve host: " + host);
        }
        return address;
    }

    private void handleDatagram(DatagramPacket packet, Ipv4Cidr filter,
                                Set<String> seenIps, List<SurveillanceDevice> out) {
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
        Document document;
        try {
            document = OnvifXml.parse(data, "ProbeMatch");
        } catch (OnvifException e) {
            // 一个畸形应答不该让整次发现失败
            log.debug("Ignoring malformed ProbeMatch from {}: {}",
                    packet.getAddress(), e.getMessage());
            return;
        }
        for (Element match : OnvifXml.descendants(document.getDocumentElement(), "ProbeMatch")) {
            SurveillanceDevice device = toDevice(match, filter);
            if (device == null) {
                continue;
            }
            if (seenIps.add(device.ip)) {
                out.add(device);
                log.debug("WS-Discovery matched device id={} ip={} vendor={}",
                        device.id, device.ip, device.vendor);
            }
        }
    }

    private SurveillanceDevice toDevice(Element match, Ipv4Cidr filter) {
        String xaddrs = OnvifXml.childText(match, "XAddrs");
        if (xaddrs == null) {
            return null;
        }
        String firstAddr = xaddrs.split("\\s+")[0];
        URI uri;
        try {
            uri = URI.create(firstAddr);
        } catch (IllegalArgumentException e) {
            log.debug("Ignoring ProbeMatch with invalid XAddr {}: {}", firstAddr, e.getMessage());
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme)) {
            // 本客户端只实现明文 HTTP 的 ONVIF 服务；https 设备需要 TLS 配置，
            // 不能把它当成 http 去连（端口对不上，且请求会明文发出）
            log.warn("Skipping ONVIF device with unsupported XAddr scheme: {}", firstAddr);
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 80;

        if (filter != null && isIpv4Literal(host) && !filter.contains(host)) {
            return null;
        }

        String scopes = OnvifXml.childText(match, "Scopes");
        // text() 已把空串归一成 null，这里只需判 null。
        String endpoint = OnvifXml.descendants(match, "Address").stream()
                .map(OnvifXml::text)
                .filter(v -> v != null)
                .findFirst()
                .orElse(null);

        String name = scopeValue(NAME_SCOPE, scopes);
        if (name == null || name.isBlank()) {
            name = scopeValue(HARDWARE_SCOPE, scopes);
        }
        if (name == null || name.isBlank()) {
            name = "ONVIF 设备 " + host;
        }

        String id = endpoint == null ? "onvif-" + host : "onvif-" + endpoint.substring(endpoint.lastIndexOf(':') + 1);
        id = id.replaceAll("[^A-Za-z0-9._-]", "-");

        return new SurveillanceDevice(id, name, vendorOf(scopes, name), host, port,
                settings.getDiscoveryUsername(), settings.getDiscoveryPassword());
    }

    private static String scopeValue(Pattern pattern, String scopes) {
        if (scopes == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(scopes);
        if (!matcher.find()) {
            return null;
        }
        // 用 Spring 的 RFC 3986 解码：URLDecoder 会把字面 '+' 解成空格（表单语义）
        return UriUtils.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private static SurveillanceDevice.Vendor vendorOf(String scopes, String name) {
        String probe = ((scopes == null ? "" : scopes) + " " + (name == null ? "" : name))
                .toLowerCase(Locale.ROOT);
        if (probe.contains("hikvision")) {
            return SurveillanceDevice.Vendor.HIKVISION;
        }
        if (probe.contains("dahua")) {
            return SurveillanceDevice.Vendor.DAHUA;
        }
        if (probe.contains("uniview")) {
            return SurveillanceDevice.Vendor.UNIVIEW;
        }
        return SurveillanceDevice.Vendor.ONVIF;
    }

    private static String probeMessage() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\""
                + " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                + "<e:Header>"
                + "<w:MessageID>uuid:" + UUID.randomUUID() + "</w:MessageID>"
                + "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
                + "<w:Action e:mustUnderstand=\"true\">"
                + "http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>"
                + "</e:Header>"
                + "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body>"
                + "</e:Envelope>";
    }

    private Ipv4Cidr parseCidrOrNull(String subnet) {
        Ipv4Cidr cidr = Ipv4Cidr.parse(subnet.trim());
        if (cidr == null) {
            log.warn("subnet '{}' is not an IPv4 CIDR; live WS-Discovery is link-local, "
                    + "so it will not be used to direct the probe (no filtering applied)", subnet);
        }
        return cidr;
    }

    private static boolean isIpv4Literal(String host) {
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** 极简 IPv4 CIDR（只用于结果过滤，不做 DNS 解析）。 */
    static final class Ipv4Cidr {
        private final long network;
        private final long mask;

        private Ipv4Cidr(long network, long mask) {
            this.network = network;
            this.mask = mask;
        }

        static Ipv4Cidr parse(String cidr) {
            if (cidr == null) {
                return null;
            }
            int slash = cidr.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            String[] parts = cidr.substring(0, slash).split("\\.");
            if (parts.length != 4) {
                return null;
            }
            long value = 0;
            for (String part : parts) {
                int octet;
                try {
                    octet = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (octet < 0 || octet > 255) {
                    return null;
                }
                value = (value << 8) | octet;
            }
            int bits;
            try {
                bits = Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (bits < 0 || bits > 32) {
                return null;
            }
            long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            return new Ipv4Cidr(value & mask, mask);
        }

        boolean contains(String ip) {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                return false;
            }
            long value = 0;
            for (String part : parts) {
                int octet;
                try {
                    octet = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    return false;
                }
                if (octet < 0 || octet > 255) {
                    return false;
                }
                value = (value << 8) | octet;
            }
            return (value & mask) == network;
        }
    }
}
