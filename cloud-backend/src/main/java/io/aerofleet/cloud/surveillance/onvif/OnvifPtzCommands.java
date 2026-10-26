package io.aerofleet.cloud.surveillance.onvif;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ONVIF PTZ 命令词表（单一事实源）。
 *
 * <p>{@code OnvifClient.PTZ_COMMANDS} 是历史公开常量，REST 层与测试都在用；
 * 它的内容改为由本类提供，避免"校验用一份、文档写一份、模拟实现再抄一份"。
 */
public final class OnvifPtzCommands {

    /** 支持的命令：{@code up/down/left/right/zoomIn/zoomOut/stop}。 */
    public static final Set<String> ALL = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "up", "down", "left", "right", "zoomIn", "zoomOut", "stop")));

    private OnvifPtzCommands() {
    }
}
