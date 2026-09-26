package io.aerofleet.cloud.voicecmd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语音指令解析器。
 * <p>
 * 基于关键词匹配的自然语言解析，将语音文本转换为结构化的 {@link ParsedCommand}。
 * 支持中英文关键词，模拟实现用于应急场景下的快速指令下达。
 * <p>
 * 解析规则：
 * <ul>
 *   <li>"起飞"/"takeoff" → action=TAKEOFF</li>
 *   <li>"降落"/"land" → action=LAND</li>
 *   <li>"返航"/"return"/"rtl" → action=RETURN</li>
 *   <li>"悬停"/"hover" → action=HOVER</li>
 *   <li>"高度XX米" → altitude=XX</li>
 *   <li>"前往XX" → target=XX</li>
 *   <li>"速度XX" → speed=XX</li>
 *   <li>"拍照"/"photo" → action=PHOTO</li>
 *   <li>"录像"/"record" → action=RECORD</li>
 *   <li>"无人机X号"/"drone X" → sysid=X</li>
 *   <li>"紧急"/"emergency" → priority=HIGH</li>
 * </ul>
 */
@Service
public class VoiceCommandParser {

    private static final Logger log = LoggerFactory.getLogger(VoiceCommandParser.class);

    // --- 动作关键词匹配模式 ---
    private static final Pattern TAKEOFF_PATTERN =
            Pattern.compile("起飞|takeoff", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAND_PATTERN =
            Pattern.compile("降落|land", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETURN_PATTERN =
            Pattern.compile("返航|return|rtl", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOVER_PATTERN =
            Pattern.compile("悬停|hover", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHOTO_PATTERN =
            Pattern.compile("拍照|photo", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORD_PATTERN =
            Pattern.compile("录像|record", Pattern.CASE_INSENSITIVE);

    // --- 参数提取模式 ---
    private static final Pattern ALTITUDE_CN_PATTERN =
            Pattern.compile("高度(\\d+(?:\\.\\d+)?)米?");
    private static final Pattern ALTITUDE_EN_PATTERN =
            Pattern.compile("altitude\\s+(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEED_CN_PATTERN =
            Pattern.compile("速度(\\d+(?:\\.\\d+)?)");
    private static final Pattern SPEED_EN_PATTERN =
            Pattern.compile("speed\\s+(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_CN_PATTERN =
            Pattern.compile("前往(.+?)(?:[,，。;；]|$)");
    private static final Pattern TARGET_EN_PATTERN =
            Pattern.compile("go\\s+to\\s+(.+?)(?:[,，。;；]|$)", Pattern.CASE_INSENSITIVE);

    // --- 设备与优先级模式 ---
    private static final Pattern SYSID_CN_PATTERN =
            Pattern.compile("无人机(\\d+)号");
    private static final Pattern SYSID_EN_PATTERN =
            Pattern.compile("drone\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMERGENCY_PATTERN =
            Pattern.compile("紧急|emergency", Pattern.CASE_INSENSITIVE);

    /**
     * 解析语音文本为结构化指令。
     *
     * @param text 语音识别文本（中英文混合）
     * @return 解析后的 {@link ParsedCommand}，未识别时 action=UNKNOWN
     */
    public ParsedCommand parse(String text) {
        if (text == null || text.isBlank()) {
            ParsedCommand cmd = new ParsedCommand("");
            cmd.setAction(ParsedCommand.Action.UNKNOWN);
            cmd.setConfidencePct(0);
            return cmd;
        }

        String normalized = text.trim();
        ParsedCommand cmd = new ParsedCommand(normalized);
        int matchedRules = 0;

        // --- 解析动作 ---
        if (TAKEOFF_PATTERN.matcher(normalized).find()) {
            cmd.setAction(ParsedCommand.Action.TAKEOFF);
            matchedRules++;
        } else if (LAND_PATTERN.matcher(normalized).find()) {
            cmd.setAction(ParsedCommand.Action.LAND);
            matchedRules++;
        } else if (RETURN_PATTERN.matcher(normalized).find()) {
            cmd.setAction(ParsedCommand.Action.RETURN);
            matchedRules++;
        } else if (HOVER_PATTERN.matcher(normalized).find()) {
            cmd.setAction(ParsedCommand.Action.HOVER);
            matchedRules++;
        } else if (PHOTO_PATTERN.matcher(normalized).find()) {
            cmd.setAction(ParsedCommand.Action.PHOTO);
            matchedRules++;
        } else if (RECORD_PATTERN.matcher(normalized).find()) {
            cmd.setAction(ParsedCommand.Action.RECORD);
            matchedRules++;
        }

        // --- 解析高度 ---
        Double altitude = extractDouble(normalized, ALTITUDE_CN_PATTERN);
        if (altitude == null) {
            altitude = extractDouble(normalized, ALTITUDE_EN_PATTERN);
        }
        if (altitude != null) {
            cmd.setAltitudeM(altitude);
            matchedRules++;
            // 如果没有明确动作但设置了高度，推断为 SET_ALTITUDE
            if (cmd.getAction() == ParsedCommand.Action.UNKNOWN) {
                cmd.setAction(ParsedCommand.Action.SET_ALTITUDE);
            }
        }

        // --- 解析速度 ---
        Double speed = extractDouble(normalized, SPEED_CN_PATTERN);
        if (speed == null) {
            speed = extractDouble(normalized, SPEED_EN_PATTERN);
        }
        if (speed != null) {
            cmd.setSpeedMps(speed);
            matchedRules++;
            if (cmd.getAction() == ParsedCommand.Action.UNKNOWN) {
                cmd.setAction(ParsedCommand.Action.SET_SPEED);
            }
        }

        // --- 解析目标位置 ---
        String target = extractString(normalized, TARGET_CN_PATTERN);
        if (target == null) {
            target = extractString(normalized, TARGET_EN_PATTERN);
        }
        if (target != null && !target.isBlank()) {
            cmd.setTargetName(target.trim());
            matchedRules++;
            // 如果有目标位置但没有明确动作，推断为 FLY_TO
            if (cmd.getAction() == ParsedCommand.Action.UNKNOWN) {
                cmd.setAction(ParsedCommand.Action.FLY_TO);
            }
        }

        // --- 解析无人机编号 ---
        Integer sysid = extractInteger(normalized, SYSID_CN_PATTERN);
        if (sysid == null) {
            sysid = extractInteger(normalized, SYSID_EN_PATTERN);
        }
        if (sysid != null) {
            cmd.setSysid(sysid);
            matchedRules++;
        }

        // --- 解析紧急优先级 ---
        if (EMERGENCY_PATTERN.matcher(normalized).find()) {
            cmd.setPriority(ParsedCommand.Priority.HIGH);
            matchedRules++;
        }

        // --- 计算置信度 ---
        // 每条匹配规则贡献一定置信度，最高 100%
        int confidence = Math.min(100, matchedRules * 30);
        if (matchedRules == 0) {
            confidence = 0;
        } else if (cmd.getAction() != ParsedCommand.Action.UNKNOWN) {
            // 有明确动作时置信度至少 60%
            confidence = Math.max(confidence, 60);
        }
        cmd.setConfidencePct(confidence);

        log.debug("Parsed voice command: '{}' → {}", normalized, cmd);
        return cmd;
    }

    // --- 内部提取工具方法 ---

    private Double extractDouble(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Integer extractInteger(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String extractString(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}