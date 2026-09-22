package io.aerofleet.cloud.spi;

import java.util.Map;

/**
 * 插件上下文，在插件初始化时传递给插件，提供配置属性和运行环境信息。
 * <p>
 * 支持通过 {@code getProperty} / {@code getBoolean} / {@code getInt} 等便捷方法
 * 从属性 Map 中获取类型化的配置值。
 */
public class PluginContext {

    /** 属性映射 */
    private final Map<String, Object> properties;

    public PluginContext(Map<String, Object> properties) {
        this.properties = properties;
    }

    /**
     * 获取原始属性 Map。
     *
     * @return 属性映射（可能为空，不为 null）
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    /**
     * 获取字符串属性。
     *
     * @param key 属性键
     * @return 属性值；不存在时返回 null
     */
    public String getProperty(String key) {
        Object val = properties.get(key);
        return val != null ? val.toString() : null;
    }

    /**
     * 获取字符串属性，带默认值。
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 属性值；不存在时返回默认值
     */
    public String getProperty(String key, String defaultValue) {
        String val = getProperty(key);
        return val != null ? val : defaultValue;
    }

    /**
     * 获取布尔属性。
     *
     * @param key 属性键
     * @return 属性值；不存在或类型不匹配时返回 false
     */
    public boolean getBoolean(String key) {
        Object val = properties.get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return false;
    }

    /**
     * 获取布尔属性，带默认值。
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 属性值；不存在或类型不匹配时返回默认值
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object val = properties.get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    /**
     * 获取整数属性。
     *
     * @param key 属性键
     * @return 属性值；不存在或类型不匹配时返回 0
     */
    public int getInt(String key) {
        Object val = properties.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 获取整数属性，带默认值。
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 属性值；不存在或类型不匹配时返回默认值
     */
    public int getInt(String key, int defaultValue) {
        Object val = properties.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 获取长整数属性。
     *
     * @param key 属性键
     * @return 属性值；不存在或类型不匹配时返回 0
     */
    public long getLong(String key) {
        Object val = properties.get(key);
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * 获取长整数属性，带默认值。
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 属性值；不存在或类型不匹配时返回默认值
     */
    public long getLong(String key, long defaultValue) {
        Object val = properties.get(key);
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 获取双精度浮点属性。
     *
     * @param key 属性键
     * @return 属性值；不存在或类型不匹配时返回 0.0
     */
    public double getDouble(String key) {
        Object val = properties.get(key);
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        if (val instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /**
     * 获取双精度浮点属性，带默认值。
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @return 属性值；不存在或类型不匹配时返回默认值
     */
    public double getDouble(String key, double defaultValue) {
        Object val = properties.get(key);
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        if (val instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 判断是否包含指定属性。
     *
     * @param key 属性键
     * @return 包含时返回 true
     */
    public boolean containsKey(String key) {
        return properties.containsKey(key);
    }
}