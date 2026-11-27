package com.wyc.demo03.mqtt;

/**
 * MQTT 连接就绪事件（首次连接或断线重连成功都会发）。
 *
 * <h3>为什么需要它</h3>
 * 后端启动时，{@code refreshAll()} 的第一次调度往往早于 MQTT 连接建立，
 * 于是首轮快照发布必然失败（"MQTT 未连接，无法发布"），而下一轮要等
 * {@code fixedDelay=60s}。结果就是：<b>后端重启后最多 60 秒没有 retained 快照</b>。
 *
 * <p>设备端的时间基准来自快照里的 {@code issuedAt}，这段时间里设备会一直
 * 停在 "NO TIME"、拒绝任何开锁指令。演示当天"先启后端再给板子上电"几乎
 * 必然撞进这个窗口，然后被误判成门禁坏了。
 *
 * <p>有了这个事件，连接一建立就立刻补发一轮，窗口从 60 秒压到 0。
 * 重连时同样需要 —— retained 消息在 Broker 重启后会丢失，必须重发。
 *
 * <p>用事件而不是直接调用，是为了避免 {@code mqtt} 包反向依赖 {@code device} 包：
 * 网关只负责"我连上了"，谁关心谁自己监听。
 *
 * @param serverUri 连接到的 Broker 地址
 * @param reconnect true = 断线后的自动重连；false = 首次连接建立
 */
public record MqttConnectedEvent(String serverUri, boolean reconnect) {
}
