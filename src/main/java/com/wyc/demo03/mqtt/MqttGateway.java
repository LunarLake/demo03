package com.wyc.demo03.mqtt;

import java.util.function.Consumer;

/**
 * MQTT 传输端口。
 *
 * <p>抽出接口是为了让"业务怎么发消息"与"消息怎么走网络"解耦：
 * 没有 Broker 时用进程内总线跑通全链路，接上 Mosquitto 只换实现。
 * 主题、信封、HMAC、幂等这些**协议约定不属于传输层**，两种实现共用。
 */
public interface MqttGateway {

    /** 实现名（inprocess / paho），写入日志。 */
    String mode();

    /** 是否已连接。未连接时不得假装投递成功。 */
    boolean isConnected();

    /**
     * 发布消息。
     *
     * @throws IllegalStateException 未连接（调用方应据此返回"下发失败"，而不是吞掉）
     */
    void publish(String topic, String payload, int qos, boolean retained);

    /**
     * 订阅。处理器里抛异常不得影响其它订阅者。
     *
     * @param topicFilter 支持 {@code +} 单层与 {@code #} 多层通配
     */
    void subscribe(String topicFilter, int qos, Consumer<String> handler);
}
