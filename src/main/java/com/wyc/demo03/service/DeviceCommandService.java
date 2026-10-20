package com.wyc.demo03.service;

import com.wyc.demo03.entity.DeviceCommand;

import java.time.LocalDateTime;

/**
 * 设备指令的持久化与回执。
 *
 * <p>每条开锁指令都是一次性的：入库即生成唯一 {@code msgId}，带 {@code expiresAt}。
 * 设备端按 {@code msgId} 幂等去重、按 {@code expiresAt} 丢弃过期指令 ——
 * 这样 QoS1 的重复投递不会开两次锁，MQTT 保留消息也不会在下一笔预约上"复活"。
 */
public interface DeviceCommandService {

    /**
     * 入库一条待下发指令。
     *
     * @param ttlSeconds 有效期；超过后设备必须丢弃，服务端也会置为 EXPIRED
     */
    DeviceCommand issue(Long deviceId, Long reservationId, DeviceCommand.Type type,
                        String payload, int ttlSeconds);

    /** 标记已投递（PENDING → SENT）。 */
    boolean markSent(String msgId);

    /** 设备回执；只有仍处于 SENT 的指令才接受，迟到回执不会覆盖已结算状态。 */
    boolean ack(String msgId, DeviceCommand.Status status, String resultCode);

    /** 超时未回执的置为 EXPIRED，返回处理条数。 */
    int expireOverdue(LocalDateTime now);

    /** 按 msgId 查（测试与排障用）。 */
    DeviceCommand findByMsgId(String msgId);
}
