package io.aerofleet.cloud.surveillance.onvif;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OnvifXml} 的解析/拼装契约：命名空间无关匹配、安全基线、文本归一、转义。
 */
@DisplayName("OnvifXml 解析与安全基线")
class OnvifXmlTest {

    private static Document parse(String xml) {
        return OnvifXml.parse(xml.getBytes(StandardCharsets.UTF_8), "test xml");
    }

    @Test
    @DisplayName("内部子集 DOCTYPE 也拒绝（安全基线不依赖解析器去碰外部资源才报错）")
    void parse_doctypeWithInternalEntity_rejected() {
        // 实体引用出现在正文里：若 DOCTYPE 被放行，解析会成功并展开实体，
        // 本断言用「必须抛异常」把"拒 DOCTYPE"和"碰巧读不到外部文件也报错"区分开。
        String xml = "<!DOCTYPE foo [<!ENTITY boom \"boomvalue\">]>"
                + "<foo>&boom;</foo>";

        assertThatThrownBy(() -> parse(xml))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("test xml");
    }

    @Test
    @DisplayName("无命名空间与前缀命名空间的元素都按 local name 匹配")
    void descendants_namespaceAgnostic() {
        Document plain = parse("<root><child>alpha</child></root>");
        Document prefixed = parse("<r:root xmlns:r=\"urn:x\"><r:child>beta</r:child></r:root>");

        Element plainRoot = plain.getDocumentElement();
        assertThat(OnvifXml.children(plainRoot, "child")).hasSize(1);
        assertThat(OnvifXml.childText(plainRoot, "child")).isEqualTo("alpha");

        Element prefixedRoot = prefixed.getDocumentElement();
        List<Element> children = OnvifXml.children(prefixedRoot, "child");
        assertThat(children).hasSize(1);
        assertThat(OnvifXml.text(children.get(0))).isEqualTo("beta");
    }

    @Test
    @DisplayName("children 跳过文本/CDATA 等非元素节点，保持文档顺序")
    void children_skipsNonElementNodes() {
        Document doc = parse("<root>text<b>1</b><a>2</a><!--c--><b>3</b></root>");

        List<Element> bs = OnvifXml.children(doc.getDocumentElement(), "b");

        assertThat(bs).hasSize(2);
        assertThat(OnvifXml.text(bs.get(0))).isEqualTo("1");
        assertThat(OnvifXml.text(bs.get(1))).isEqualTo("3");
    }

    @Test
    @DisplayName("text 归一：null/空白为 null，首尾空白裁掉")
    void text_normalizesWhitespace() {
        Document doc = parse("<root><a>  value  </a><b>   </b></root>");
        Element root = doc.getDocumentElement();

        assertThat(OnvifXml.childText(root, "a")).isEqualTo("value");
        assertThat(OnvifXml.childText(root, "b")).isNull();
        assertThat(OnvifXml.childText(root, "missing")).isNull();
        assertThat(OnvifXml.text(null)).isNull();
    }

    @Test
    @DisplayName("escape 覆盖五个 XML 特殊字符；null 入参为空串")
    void escape_allSpecials() {
        assertThat(OnvifXml.escape("a&b<c>d\"e'f"))
                .isEqualTo("a&amp;b&lt;c&gt;d&quot;e&apos;f");
        assertThat(OnvifXml.escape(null)).isEmpty();
        assertThat(OnvifXml.escape("plain-123")).isEqualTo("plain-123");
    }

    @Test
    @DisplayName("空输入直接报错，错误消息里带上下文")
    void parse_emptyInput_throwsWithContext() {
        assertThatThrownBy(() -> OnvifXml.parse(new byte[0], "GetStreamUri response"))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("GetStreamUri response");
    }

    @Test
    @DisplayName("firstDescendant 含自身；descendants 保持文档顺序")
    void descendants_includesSelfInDocumentOrder() {
        Document doc = parse("<cap><svc><cap>x</cap></svc><cap>y</cap></cap>");

        List<Element> caps = OnvifXml.descendants(doc.getDocumentElement(), "cap");

        assertThat(caps).hasSize(3);
        assertThat(OnvifXml.firstDescendant(doc.getDocumentElement(), "cap"))
                .isSameAs(doc.getDocumentElement());
    }

    @Test
    @DisplayName("children / descendants 对 null 父节点返回空列表而不是 NPE")
    void nullParent_returnsEmpty() {
        assertThat(OnvifXml.children(null, "x")).isEmpty();
        assertThat(OnvifXml.descendants(null, "x")).isEmpty();
        assertThat(OnvifXml.firstChild(null, "x")).isNull();
        assertThat(OnvifXml.firstDescendant(null, "x")).isNull();
    }

    @Test
    @DisplayName("localName：命名空间感知取 local name；非感知 DOM 退回去前缀")
    void localName_handlesNamespacedAndNonNamespacedNodes() throws Exception {
        Document namespaced = parse("<tev:Root xmlns:tev=\"urn:t\"><Child/></tev:Root>");
        assertThat(OnvifXml.localName(namespaced.getDocumentElement())).isEqualTo("Root");

        // 非命名空间感知的 DOM：getLocalName() 为 null，只能按 nodeName 去前缀。
        // 设备固件/中间层若用非感知解析器重建过文档，就会走到这条路。
        DocumentBuilderFactory plain = DocumentBuilderFactory.newInstance();
        Document raw = plain.newDocumentBuilder().parse(new ByteArrayInputStream(
                "<a:b><c/></a:b>".getBytes(StandardCharsets.UTF_8)));
        assertThat(OnvifXml.localName(raw.getDocumentElement())).isEqualTo("b");
        assertThat(OnvifXml.localName(raw.getDocumentElement().getFirstChild())).isEqualTo("c");
    }

    @Test
    @DisplayName("serialize：保留命名空间与内容、不带 XML 声明；null 元素返回空串")
    void serialize_keepsNamespaceAndContent() {
        Document doc = parse("<tev:NotificationMessage xmlns:tev=\"urn:tev\">"
                + "<tev:Topic>motion</tev:Topic></tev:NotificationMessage>");

        String xml = OnvifXml.serialize(doc.getDocumentElement());
        assertThat(xml).contains("NotificationMessage").contains("motion");
        assertThat(xml).doesNotContain("<?xml");
        assertThat(OnvifXml.serialize(null)).isEmpty();
    }

    @Test
    @DisplayName("parse：null 与外部实体 DOCTYPE 都拒绝（错误消息带上下文）")
    void parse_nullAndExternalDoctype_rejected() {
        assertThatThrownBy(() -> OnvifXml.parse(null, "ProbeMatch"))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("empty ProbeMatch");
        assertThatThrownBy(() -> OnvifXml.parse(("<!DOCTYPE foo [<!ENTITY xxe SYSTEM "
                + "\"file:///etc/passwd\">]><a>&xxe;</a>").getBytes(StandardCharsets.UTF_8), "evil"))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("failed to parse evil");
    }
}
