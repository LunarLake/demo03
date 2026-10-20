package com.wyc.demo03.service;

import com.wyc.demo03.config.DoorProperties;
import com.wyc.demo03.device.DispatchResult;
import com.wyc.demo03.device.DoorController;
import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;
import com.wyc.demo03.mapper.DeviceCommandMapper;
import com.wyc.demo03.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 设备管理（管理端）。
 *
 * <p>只做两件事：看状态、发测试指令。刻意<strong>不</strong>提供"把设备置为在线"之类的按钮 ——
 * 在线状态必须由心跳决定，人工改状态只会让排障时看到假象。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAdminService {

    /** 测试开锁的结果。 */
    public record TestResult(boolean ok, String message) {
        public static TestResult ok(String message) {
            return new TestResult(true, message);
        }

        public static TestResult fail(String message) {
            return new TestResult(false, message);
        }
    }

    private final DeviceMapper deviceMapper;
    private final DeviceCommandMapper deviceCommandMapper;
    private final DeviceCommandService deviceCommandService;
    private final DoorController doorController;
    private final DoorProperties doorProperties;

    /** 设备列表（带房间名与在线状态）。 */
    public List<Map<String, Object>> listWithRoom() {
        return deviceMapper.findAllWithRoom();
    }

    /** 某设备最近的指令。 */
    public List<DeviceCommand> recentCommands(Long deviceId, int limit) {
        return deviceCommandMapper.findRecentByDevice(deviceId, limit);
    }

    /** 门禁通道当前实现（log / mqtt:inprocess / mqtt:paho），页面上要如实显示。 */
    public String channelName() {
        return doorController.name();
    }

    /**
     * 下发一次测试开锁。
     *
     * <p>不关联预约（reservationId 为空），因此不受"预约归属/时段"约束 ——
     * 它是给管理员排查门锁硬件用的，不是给用户开门的入口。
     * 设备端仍会执行 HMAC 与过期校验，所以不会绕过安全机制。
     */
    public TestResult testUnlock(Long deviceId) {
        Device device = deviceMapper.selectById(deviceId);
        if (device == null) {
            return TestResult.fail("设备不存在");
        }
        if (!doorController.reachable(device)) {
            return TestResult.fail("设备离线或 MQTT 未连接，无法下发");
        }

        DeviceCommand command = deviceCommandService.issue(device.getId(), null,
                DeviceCommand.Type.UNLOCK, "reason=ADMIN_TEST;pulseMs=1000",
                doorProperties.getTtlSeconds());
        deviceCommandService.markSent(command.getMsgId());

        DispatchResult result = doorController.dispatch(device, command);
        if (result == DispatchResult.ACKED) {
            deviceCommandService.ack(command.getMsgId(), DeviceCommand.Status.ACKED, "OK");
            return TestResult.ok("测试开锁已执行（设备已回执）");
        }
        if (result == DispatchResult.SENT) {
            return TestResult.ok("指令已下发，等待设备回执");
        }
        log.warn("测试开锁下发失败：device={} msgId={}", device.getDeviceCode(), command.getMsgId());
        return TestResult.fail("指令下发失败");
    }
}
