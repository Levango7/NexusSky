package io.aerofleet.cloud.surveillance.onvif;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * ONVIF XML 解析与拼装的小工具。
 *
 * <h2>为什么自己写解析而不是用 JAXB</h2>
 *
 * <p>ONVIF 设备对命名空间前缀的写法极不统一（{@code tds:} / {@code ns1:} / 默认命名空间
 * 混用），按前缀匹配的代码在不同厂商固件上会时好时坏。这里统一按
 * <b>local name</b> 匹配，前缀完全无关。
 *
 * <h2>安全基线</h2>
 *
 * <p>解析器禁用 DOCTYPE 与外部实体。设备响应是<b>不可信输入</b>：
 * 一个被中间人替换的 SOAP 应答如果带 {@code <!DOCTYPE ... SYSTEM "file:///...">}，
 * 启用外部实体的解析器会把本机文件内容读进内存并可能回显到错误信息里。
 * 这里直接拒绝 DOCTYPE（SAX 报错 → {@link OnvifException}），不做"解析后再检查"。
 */
public final class OnvifXml {

    private OnvifXml() {
    }

    /**
     * 解析一段 XML（安全配置）。
     *
     * @param xml  原始字节
     * @param what 用于错误消息的上下文，如 "GetStreamUri response"
     * @return DOM 文档
     * @throws OnvifException XML 非法、或含 DOCTYPE/外部实体
     */
    public static Document parse(byte[] xml, String what) {
        if (xml == null || xml.length == 0) {
            throw new OnvifException("empty " + what);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // 关闭一切外部实体/DTD 路径；JDK 自带解析器支持这三项
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException e) {
            // 解析器不支持上述安全开关：宁可失败也不要在不安全配置下解析设备响应
            throw new OnvifException("XML parser does not support the required security features", e);
        } catch (Exception e) {
            throw new OnvifException("failed to parse " + what + ": " + e.getMessage(), e);
        }
    }

    /** 取节点的 local name（命名空间无关）；无 local name 时退回去掉前缀的 nodeName。 */
    public static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) {
            return local;
        }
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /** 直接子元素（按 local name 过滤，保持文档顺序）。 */
    public static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(localName(node))) {
                out.add((Element) node);
            }
        }
        return out;
    }

    /** 第一个直接子元素。 */
    public static Element firstChild(Element parent, String localName) {
        List<Element> list = children(parent, localName);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 直接子元素的文本（trim 后）；不存在或空文本返回 {@code null}。 */
    public static String childText(Element parent, String localName) {
        Element child = firstChild(parent, localName);
        return child == null ? null : text(child);
    }

    /** 元素文本（trim 后）；空文本返回 {@code null}。 */
    public static String text(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 后代元素（含自身），按 local name 过滤。
     *
     * <p>用于解析 {@code ProbeMatch} / {@code Profiles} 这类"可能被包在
     * 多层信封里"的元素——按前缀写死路径在不同固件上会漏。
     */
    public static List<Element> descendants(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        collect(root, localName, out);
        return out;
    }

    /** 第一个匹配的后代元素（含自身）；没有时返回 {@code null}。 */
    public static Element firstDescendant(Element root, String localName) {
        List<Element> list = descendants(root, localName);
        return list.isEmpty() ? null : list.get(0);
    }

    private static void collect(Element element, String localName, List<Element> out) {
        if (localName.equals(localName(element))) {
            out.add(element);
        }
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                collect((Element) node, localName, out);
            }
        }
    }

    /**
     * 把元素序列化成 XML 字符串（带回命名空间声明）。
     *
     * <p>用于事件订阅：设备推来的 {@code NotificationMessage} 是任意结构的 XML，
     * 解析成强类型只会把厂商私有的字段丢掉；这里原样序列化整段交给上层展示。
     * 只做输出，不加载任何外部资源。
     *
     * @param element 待序列化的元素
     * @return XML 文本（无 XML 声明）
     */
    public static String serialize(Element element) {
        if (element == null) {
            return "";
        }
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(element), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            throw new OnvifException("failed to serialize XML: " + e.getMessage(), e);
        }
    }

    /** XML 文本转义（用于把 token/命令拼进请求体）。 */
    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
