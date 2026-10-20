package com.wyc.demo03.mqtt;

/**
 * MQTT 主题约定（计划 6.1）。
 *
 * <p>主题只按 {@code deviceCode} 分层，不拼预约号或其他动态内容 ——
 * 这样设备侧的 ACL 可以写成 {@code room/{自己的 code}/#}，一条规则覆盖全部读写，
 * 不必为每笔预约改 ACL。
 */
public final class MqttTopics {

    /** 业务快照（带 version 与 expiresAt），retained。 */
    public static final String SUFFIX_SNAPSHOT = "snapshot";

    /** 一次性指令（UNLOCK/BUZZ/CLEAR），不 retained。 */
    public static final String SUFFIX_CMD = "cmd";

    /** 指令回执。 */
    public static final String SUFFIX_ACK = "ack";

    /** 心跳，30 秒一次。 */
    public static final String SUFFIX_HEARTBEAT = "heartbeat";

    /** 设备事件（手动释放、故障、掉电恢复）。 */
    public static final String SUFFIX_EVENT = "event";

    /** 后端订阅的回执主题。 */
    public static final String SUBSCRIBE_ACK = "room/+/ack";

    /** 后端订阅的心跳主题。 */
    public static final String SUBSCRIBE_HEARTBEAT = "room/+/heartbeat";

    /** 后端订阅的设备事件主题。 */
    public static final String SUBSCRIBE_EVENT = "room/+/event";

    private MqttTopics() {
    }

    public static String snapshot(String deviceCode) {
        return "room/" + deviceCode + "/" + SUFFIX_SNAPSHOT;
    }

    public static String cmd(String deviceCode) {
        return "room/" + deviceCode + "/" + SUFFIX_CMD;
    }

    public static String ack(String deviceCode) {
        return "room/" + deviceCode + "/" + SUFFIX_ACK;
    }

    public static String heartbeat(String deviceCode) {
        return "room/" + deviceCode + "/" + SUFFIX_HEARTBEAT;
    }

    public static String event(String deviceCode) {
        return "room/" + deviceCode + "/" + SUFFIX_EVENT;
    }

    /**
     * 从 {@code room/{deviceCode}/{suffix}} 里取 deviceCode；格式不符返回 {@code null}。
     */
    public static String deviceCodeOf(String topic) {
        if (topic == null) {
            return null;
        }
        String[] parts = topic.split("/");
        if (parts.length != 3 || !"room".equals(parts[0]) || parts[1].isEmpty()) {
            return null;
        }
        return parts[1];
    }
}
