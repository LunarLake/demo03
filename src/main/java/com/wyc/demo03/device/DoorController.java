package com.wyc.demo03.device;

import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;

/**
 * 门禁控制端口。
 *
 * <p>业务层只依赖这个接口，不关心底层是 MQTT、串口还是模拟器。
 * P4 提供 {@link LogDoorController}（只记录不真开锁）；P5 接入 Mosquitto 后
 * 换成 MQTT 实现即可，{@code AccessService} 一行都不用改。
 *
 * <p>实现方**不得**在方法里抛异常表示"设备失败"：设备离线、超时、回执错误
 * 都是正常业务结果，通过返回值表达。异常只用于程序错误。
 */
public interface DoorController {

    /** 实现名，写入日志便于排障（如 log / mqtt）。 */
    String name();

    /** 设备当前是否可达（能接受指令）。 */
    boolean reachable(Device device);

    /**
     * 投递一条一次性指令。
     *
     * @return true 表示已送达并收到成功回执；false 表示投递失败（指令保留为 SENT，等待过期）
     */
    boolean dispatch(Device device, DeviceCommand command);
}
