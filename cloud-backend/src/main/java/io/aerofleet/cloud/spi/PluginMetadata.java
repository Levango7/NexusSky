package io.aerofleet.cloud.spi;

/**
 * 插件元数据，描述插件的名称、版本、作者和功能说明。
 * <p>
 * 每个插件通过 {@code getMetadata()} 返回此对象，
 * 供注册中心和运维界面展示插件信息。
 */
public class PluginMetadata {

    /** 插件名称 */
    private final String name;

    /** 插件版本 */
    private final String version;

    /** 插件作者 */
    private final String author;

    /** 插件功能描述 */
    private final String description;

    public PluginMetadata(String name, String version, String author, String description) {
        this.name = name;
        this.version = version;
        this.author = author;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getAuthor() {
        return author;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "PluginMetadata{name='" + name + "', version='" + version
                + "', author='" + author + "', description='" + description + "'}";
    }
}