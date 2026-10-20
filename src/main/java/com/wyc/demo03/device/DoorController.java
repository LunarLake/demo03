package com.wyc.demo03.device;

import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;

/**
 * 门禁控制端口。
 *
 * <p>业务层只依赖这个接口，不关心底层是 MQTT、串口还是模拟器。
 * 两种实现：{@link LogDoorController}（只记录不真开锁）与
 * {@link com.wyc.demo03.mqtt.MqttDoorController}（走 MQTT 下发）。
 *
 * <p>实现方**不得**在方法里抛异常表示"设备失败"：设备离线、超时、回执错误
 * 都是正常业务结果，通过 {@link DispatchResult} 表达。异常只用于程序错误。
 */
public interface DoorController {

    /** 实现名，写入日志便于排障（如 log / mqtt）。 */
    String name();

    /** 设备当前是否可达（能接受指令）。 */
    boolean reachable(Device device);

    /**
     * 投递一条一次性指令。
     *
     * <p>返回 {@link DispatchResult#SENT} 时，调用方**不得**把指令结算为 ACKED ——
     * 真正的结算必须等设备回执。
     */
    DispatchResult dispatch(Device device, DeviceCommand command);
}
