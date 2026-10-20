package com.wyc.demo03.mqtt;

import com.wyc.demo03.config.MqttProperties;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paho 网关的订阅与连接恢复（BUG-003）。
 *
 * <p>真 Broker 才能覆盖的场景（AC-28 断网重连）留待联调；这里用
 * {@code mockConstruction} 拦下 {@link MqttClient} 的构造，把"订阅先于连接"
 * "Broker 重启后补订阅" "首次连不上要退避重试" 这三条**不依赖网络**的行为钉死。
 *
 * <p>顺序很关键：旧实现在 {@code isConnected()==false} 时直接 return，订阅永久丢失；
 * 且 {@code cleanSession=true} 让 Broker 不保留订阅，重连后不补发就等于收不到任何回执。
 */
class PahoMqttGatewayTest {

    private static final String TOPIC = "room/+/ack";

    private MqttProperties props() {
        MqttProperties p = new MqttProperties();
        p.setMode("paho");
        p.setBrokerUrl("tcp://127.0.0.1:1883");
        p.setClientId("demo03-test");
        p.setQos(1);
        return p;
    }

    /** 构造被拦下的客户端：默认"已连接"，{@code connect()} 不抛。 */
    private MockedConstruction.MockInitializer<MqttClient> connected() {
        return (mock, ctx) -> when(mock.isConnected()).thenReturn(true);
    }

    // ====================================================================

    @Test
    @DisplayName("订阅早于连接：意图被登记，连接成功后自动补发（不再丢弃）")
    void subscriptionBeforeConnectIsAppliedOnConnect() throws Exception {
        try (MockedConstruction<MqttClient> mocked =
                     mockConstruction(MqttClient.class, connected())) {
            PahoMqttGateway gateway = new PahoMqttGateway(props());

            gateway.subscribe(TOPIC, 1, payload -> { });

            assertEquals(1, gateway.subscriptionCount(), "未连接时也要记住订阅意图");
            assertTrue(mocked.constructed().isEmpty(), "订阅本身不应触发建连");

            gateway.connectWithRetry();

            assertEquals(1, mocked.constructed().size());
            verify(mocked.constructed().get(0))
                    .subscribe(eq(TOPIC), eq(1), any(IMqttMessageListener.class));
        }
    }

    @Test
    @DisplayName("Broker 重启：重连回调收到 connectComplete(true) 后重新订阅，并能继续收到消息")
    void subscriptionsRestoredAfterReconnect() throws Exception {
        try (MockedConstruction<MqttClient> mocked =
                     mockConstruction(MqttClient.class, connected())) {
            PahoMqttGateway gateway = new PahoMqttGateway(props());
            List<String> received = new ArrayList<>();
            gateway.subscribe(TOPIC, 1, received::add);
            gateway.connectWithRetry();

            MqttClient client = mocked.constructed().get(0);
            ArgumentCaptor<IMqttMessageListener> listener =
                    ArgumentCaptor.forClass(IMqttMessageListener.class);
            verify(client).subscribe(eq(TOPIC), eq(1), listener.capture());

            // Broker 重启 → Paho 自动重连成功
            ArgumentCaptor<MqttCallbackExtended> callback =
                    ArgumentCaptor.forClass(MqttCallbackExtended.class);
            verify(client).setCallback(callback.capture());
            callback.getValue().connectComplete(true, "tcp://127.0.0.1:1883");

            verify(client, times(2)).subscribe(eq(TOPIC), eq(1), any(IMqttMessageListener.class));

            // 补发的订阅必须是可用的：向监听器投一条消息，处理器要能收到
            listener.getValue().messageArrived("room/ROOM-A-01/ack",
                    new MqttMessage("ok".getBytes(StandardCharsets.UTF_8)));
            assertEquals(List.of("ok"), received, "恢复后的订阅要能真的收到心跳/回执");
        }
    }

    @Test
    @DisplayName("Broker 比应用晚启动：首次连接失败后按退避重试，第二次连上时订阅照常恢复")
    void retriesWhenBrokerStartsLater() throws Exception {
        AtomicInteger created = new AtomicInteger();
        try (MockedConstruction<MqttClient> mocked = mockConstruction(MqttClient.class, (mock, ctx) -> {
            when(mock.isConnected()).thenReturn(true);
            if (created.getAndIncrement() == 0) {
                doThrow(new MqttException(MqttException.REASON_CODE_CONNECTION_LOST))
                        .when(mock).connect(any(MqttConnectOptions.class));
            }
        })) {
            PahoMqttGateway gateway = new PahoMqttGateway(props());
            gateway.subscribe(TOPIC, 1, payload -> { });

            gateway.connectWithRetry();

            assertEquals(2, mocked.constructed().size(), "首次失败后应退避再试一次");
            assertTrue(gateway.isConnected());
            verify(mocked.constructed().get(1))
                    .subscribe(eq(TOPIC), eq(1), any(IMqttMessageListener.class));
        }
    }

    @Test
    @DisplayName("退避阶梯封顶：1→2→4→8→15→30 秒，之后维持 30 秒")
    void backoffIsCapped() {
        PahoMqttGateway gateway = new PahoMqttGateway(props());

        assertEquals(1_000, gateway.backoffOf(1));
        assertEquals(2_000, gateway.backoffOf(2));
        assertEquals(4_000, gateway.backoffOf(3));
        assertEquals(8_000, gateway.backoffOf(4));
        assertEquals(15_000, gateway.backoffOf(5));
        assertEquals(30_000, gateway.backoffOf(6));
        assertEquals(30_000, gateway.backoffOf(50), "不能无限指数增长，否则重启一次要等几分钟");
        assertEquals(1_000, gateway.backoffOf(0), "非法入参按第一次处理");
    }

    @Test
    @DisplayName("应用关闭后停止重试：不再新建客户端")
    void shutdownStopsRetrying() throws Exception {
        try (MockedConstruction<MqttClient> mocked = mockConstruction(MqttClient.class, (mock, ctx) -> {
            when(mock.isConnected()).thenReturn(false);
            doThrow(new MqttException(MqttException.REASON_CODE_CONNECTION_LOST))
                    .when(mock).connect(any(MqttConnectOptions.class));
        })) {
            PahoMqttGateway gateway = new PahoMqttGateway(props());
            gateway.close();

            gateway.connectAsync();

            assertTrue(mocked.constructed().isEmpty(), "关闭后不应再发起连接");
        }
    }
}
