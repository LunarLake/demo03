package com.wyc.demo03.service.impl;

import com.wyc.demo03.config.DoorProperties;
import com.wyc.demo03.device.DispatchResult;
import com.wyc.demo03.device.DoorController;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.AccessLog;
import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.AccessLogMapper;
import com.wyc.demo03.mapper.DeviceMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.AccessService;
import com.wyc.demo03.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessServiceImpl implements AccessService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ReservationMapper reservationMapper;
    private final DeviceMapper deviceMapper;
    private final AccessLogMapper accessLogMapper;
    private final DeviceCommandService deviceCommandService;
    private final DoorController doorController;
    private final DoorProperties doorProperties;
    private final Clock clock;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UnlockResult reopen(Long reservationId, Long userId, String ip) {
        Reservation r = reservationMapper.selectById(reservationId);
        if (r == null || userId == null || !userId.equals(r.getUserId())) {
            // 预约不存在与不是本人，对外统一成"无权"，避免泄露预约号是否存在
            record(r, userId, null, null, "NOT_OWNER");
            return UnlockResult.deny("NOT_OWNER", "无权对该预约开门");
        }

        ReservationStatus status = r.statusOrNull();
        if (status != ReservationStatus.CHECKED_IN) {
            record(r, userId, null, null, "STATUS_INVALID");
            if (status == ReservationStatus.APPROVED) {
                return UnlockResult.deny("STATUS_INVALID", "请先完成首次签到，签到成功后会自动开门");
            }
            return UnlockResult.deny("STATUS_INVALID", "该预约当前状态不可开门");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(r.getStartTime())) {
            record(r, userId, null, null, "OUT_OF_WINDOW");
            return UnlockResult.deny("OUT_OF_WINDOW", "还未到预约开始时间");
        }
        if (!now.isBefore(r.getEndTime())) {
            record(r, userId, null, null, "OUT_OF_WINDOW");
            return UnlockResult.deny("OUT_OF_WINDOW", "预约已结束，门禁授权已撤销");
        }

        return unlock(r, userId, ip, "REOPEN");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UnlockResult openAfterCheckIn(Reservation reservation, Long userId, String ip) {
        if (reservation == null) {
            return UnlockResult.deny("STATUS_INVALID", "预约不存在");
        }
        // 签到刚刚校验过归属与状态，这里只补一次时段校验（签到窗口在开始之后，必然成立，
        // 但保留判断是为了防止"补签/手工改时间"等旁路绕过）
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(reservation.getStartTime()) || !now.isBefore(reservation.getEndTime())) {
            record(reservation, userId, null, null, "OUT_OF_WINDOW");
            return UnlockResult.deny("OUT_OF_WINDOW", "不在预约有效时段内，未下发开门指令");
        }
        return unlock(reservation, userId, ip, "FIRST_CHECKIN");
    }

    @Override
    public List<AccessLog> recentLogs(Long reservationId) {
        return reservationId == null ? List.of() : accessLogMapper.findByReservationId(reservationId);
    }

    // ====================================================================

    private UnlockResult unlock(Reservation r, Long userId, String ip, String reason) {
        Device device = deviceMapper.findByRoomId(r.getRoomId());
        if (device == null) {
            record(r, userId, null, null, "DEVICE_OFFLINE");
            return UnlockResult.deny("DEVICE_OFFLINE", "该房间尚未配置门禁设备，请联系管理员");
        }
        if (!doorController.reachable(device)) {
            record(r, userId, device.getId(), null, "DEVICE_OFFLINE");
            return UnlockResult.deny("DEVICE_OFFLINE", "门禁离线，请联系管理员或使用备用钥匙");
        }

        DeviceCommand command = deviceCommandService.issue(device.getId(), r.getId(),
                DeviceCommand.Type.UNLOCK, payload(r, userId, reason), doorProperties.getTtlSeconds());

        // 先置 SENT 再投递：回执只接受 SENT 状态的指令，否则 ack 会被状态条件静默挡住
        deviceCommandService.markSent(command.getMsgId());
        DispatchResult dispatch = doorController.dispatch(device, command);

        if (dispatch == DispatchResult.ACKED) {
            // 只有"设备已确认"才结算 ACKED；SENT 必须等回执，否则会把没开锁说成开锁
            deviceCommandService.ack(command.getMsgId(), DeviceCommand.Status.ACKED, "OK");
        } else if (dispatch == DispatchResult.SENT) {
            // 回执可能在 dispatch 返回前就到了（进程内总线、或设备响应极快）。
            // 回查一次库：状态确实变成 ACKED 才对外声称已开锁，否则如实说"等待确认"。
            DeviceCommand latest = deviceCommandService.findByMsgId(command.getMsgId());
            if (latest != null && DeviceCommand.Status.ACKED.name().equals(latest.getStatus())) {
                dispatch = DispatchResult.ACKED;
            }
        }

        // 已授权就记 granted=1：即使回执还没到，"服务端已放行"这个事实也要留痕
        record(r, userId, device.getId(), command.getId(), null);

        return switch (dispatch) {
            case ACKED -> UnlockResult.granted(command.getMsgId(), "ACKED", "门禁已开锁，请推门进入");
            case SENT -> UnlockResult.granted(command.getMsgId(), "SENT", "开门指令已下发，等待门禁确认");
            case FAILED -> UnlockResult.granted(command.getMsgId(), "FAILED",
                    "已授权，但指令未能下发到门禁，请联系管理员或使用机械释放杆");
        };
    }

    /**
     * 指令载荷：扁平 {@code k=v;k=v} 而不是 JSON。
     *
     * <p>这段字符串会原样参与 HMAC 的 canonical 计算，STM32 侧需要逐字节复现。
     * JSON 对象的键序与空格在不同序列化器下不一致，扁平串切分即可，最稳。
     */
    private String payload(Reservation r, Long userId, String reason) {
        return "reservation=" + r.getId()
                + ";room=" + r.getRoomId()
                + ";user=" + userId
                + ";start=" + r.getStartTime().format(ISO)
                + ";end=" + r.getEndTime().format(ISO)
                + ";reason=" + reason
                + ";pulseMs=3000";
    }

    /** 写一条开门记录；granted 由 denyReason 是否为空决定。 */
    private void record(Reservation r, Long userId, Long deviceId, Long commandId, String denyReason) {
        AccessLog entry = new AccessLog();
        entry.setReservationId(r == null ? null : r.getId());
        entry.setUserId(userId);
        entry.setDeviceId(deviceId);
        entry.setCommandId(commandId);
        entry.setGranted(denyReason == null ? 1 : 0);
        entry.setDenyReason(denyReason);
        entry.setCreatedAt(LocalDateTime.now(clock).withNano(0));
        accessLogMapper.insert(entry);
        if (denyReason != null) {
            log.info("开门被拒绝：reservation={} user={} reason={}",
                    r == null ? null : r.getId(), userId, denyReason);
        }
    }
}
