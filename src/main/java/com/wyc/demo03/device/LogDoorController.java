package com.wyc.demo03.device;

import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 日志版门禁：记录指令但不真正开锁。
 *
 * <p>硬件未到货、Mosquitto 未部署时使用（{@code app.door.mode=log}，默认）。
 * 它**假装设备一切正常**并立刻回执 OK —— 这样签到→开门的完整业务链路在演示时能跑通，
 * 同时又不会让人误以为真的控制了门锁：每次调用都打一条 WARN 说明当前是模拟。
 *
 * <p>换成真设备时把 {@code app.door.mode} 设为 {@code mqtt}，本实现自动不生效。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.door", name = "mode", havingValue = "log", matchIfMissing = true)
public class LogDoorController implements DoorController {

    @Override
    public String name() {
        return "log";
    }

    @Override
    public boolean reachable(Device device) {
        return device != null;
    }

    @Override
    public DispatchResult dispatch(Device device, DeviceCommand command) {
        log.warn("[模拟门禁] 未接入真实设备，指令仅记录不执行：device={} cmd={} msgId={}",
                device == null ? "?" : device.getDeviceCode(),
                command.getCmdType(),
                command.getMsgId());
        return DispatchResult.ACKED;
    }
}
