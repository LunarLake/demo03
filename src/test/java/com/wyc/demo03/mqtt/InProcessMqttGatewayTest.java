package com.wyc.demo03.mqtt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内总线的主题匹配。
 *
 * <p>匹配规则必须与 MQTT 一致，否则"后端订阅 room/+/ack"与"设备发 room/A/ack"
 * 在演示时能通、换成真 Broker 后不通（或更糟：通到不该通的地方）。
 */
class InProcessMqttGatewayTest {

    @Test
    @DisplayName("通配符匹配与 MQTT 语义一致")
    void topicMatching() {
        assertTrue(InProcessMqttGateway.matches("room/+/ack", "room/A/ack"));
        assertTrue(InProcessMqttGateway.matches("room/+/#", "room/A/cmd"));
        assertTrue(InProcessMqttGateway.matches("room/#", "room/A/ack"));
        assertTrue(InProcessMqttGateway.matches("room/A/ack", "room/A/ack"));

        assertFalse(InProcessMqttGateway.matches("room/+/ack", "room/A/cmd"), "后缀不同不能匹配");
        assertFalse(InProcessMqttGateway.matches("room/+", "room/A/ack"), "单层通配不能跨层");
        assertFalse(InProcessMqttGateway.matches("room/A/ack", "room/B/ack"), "设备之间互不串消息");
        assertFalse(InProcessMqttGateway.matches("room/#", "hall/A/ack"), "根主题不同不能匹配");
    }

    @Test
    @DisplayName("发布只投递给匹配的订阅者，且一个订阅者出错不影响其它订阅者")
    void publishDispatchesToMatchedSubscribersOnly() {
        InProcessMqttGateway gateway = new InProcessMqttGateway();
        List<String> acks = new ArrayList<>();
        List<String> cmds = new ArrayList<>();

        gateway.subscribe("room/+/ack", 1, acks::add);
        gateway.subscribe("room/+/cmd", 1, cmds::add);
        gateway.subscribe("room/+/ack", 1, p -> {
            throw new IllegalStateException("故意失败");
        });

        gateway.publish("room/A/ack", "{\"msgId\":\"1\"}", 1, false);

        assertEquals(1, acks.size(), "正常订阅者必须收到");
        assertTrue(cmds.isEmpty(), "不匹配的订阅者不能收到");
    }

    @Test
    @DisplayName("进程内总线默认已连接")
    void alwaysConnected() {
        assertTrue(new InProcessMqttGateway().isConnected());
        assertEquals("inprocess", new InProcessMqttGateway().mode());
    }
}
