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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 开门授权。
 *
 * <p><b>事务边界只包"写库"，不包 MQTT 投递。</b>
 * 发布一条开锁指令是不可回滚的物理动作：如果把它放在
 * {@code @Transactional} 里，一旦随后写 {@code t_access_log} 失败或事务提交失败，
 * 就会出现"门已经开了、系统里查无记录"。这与签到侧 BUG-004 是同一条教训。
 */
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
    private final TransactionTemplate transactionTemplate;

    @Override
    public UnlockResult reopen(Long reservationId, Long userId, String ip) {
        Reservation r = reservationMapper.selectById(reservationId);
        if (r == null || userId == null || !userId.equals(r.getUserId())) {
            // 预约不存在与不是本人，对外统一成"无权"，避免泄露预约号是否存在
            record(r, userId, null, null, ip, "NOT_OWNER");
            return UnlockResult.deny("NOT_OWNER", "无权对该预约开门");
        }

        ReservationStatus status = r.statusOrNull();
        if (status != ReservationStatus.CHECKED_IN) {
            record(r, userId, null, null, ip, "STATUS_INVALID");
            if (status == ReservationStatus.APPROVED) {
                return UnlockResult.deny("STATUS_INVALID", "请先完成首次签到，签到成功后会自动开门");
            }
            return UnlockResult.deny("STATUS_INVALID", "该预约当前状态不可开门");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(r.getStartTime())) {
            record(r, userId, null, null, ip, "OUT_OF_WINDOW");
            return UnlockResult.deny("OUT_OF_WINDOW", "还未到预约开始时间");
        }
        if (!now.isBefore(r.getEndTime())) {
            record(r, userId, null, null, ip, "OUT_OF_WINDOW");
            return UnlockResult.deny("OUT_OF_WINDOW", "预约已结束，门禁授权已撤销");
        }

        return unlock(r, userId, ip, "REOPEN");
    }

    @Override
    public UnlockResult openAfterCheckIn(Long reservationId, Long userId, String ip) {
        Reservation reservation = reservationId == null ? null : reservationMapper.selectById(reservationId);
        if (reservation == null) {
            record(null, userId, null, null, ip, "STATUS_INVALID");
            return UnlockResult.deny("STATUS_INVALID", "预约不存在");
        }
        if (userId == null || !userId.equals(reservation.getUserId())) {
            record(reservation, userId, null, null, ip, "NOT_OWNER");
            return UnlockResult.deny("NOT_OWNER", "无权对该预约开门");
        }
        // 与 reopen 对齐：只有"已签到"才允许自动开门。
        // 不能只依赖"唯一调用方是签到成功后"——任何补签/重试/管理端复用都会变成"未签到可开门"。
        if (reservation.statusOrNull() != ReservationStatus.CHECKED_IN) {
            record(reservation, userId, null, null, ip, "STATUS_INVALID");
            return UnlockResult.deny("STATUS_INVALID", "签到状态异常，未下发开门指令");
        }
        // 时段校验保留：防止"补签/手工改时间"等旁路绕过
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(reservation.getStartTime()) || !now.isBefore(reservation.getEndTime())) {
            record(reservation, userId, null, null, ip, "OUT_OF_WINDOW");
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
            record(r, userId, null, null, ip, "DEVICE_OFFLINE");
            return UnlockResult.deny("DEVICE_OFFLINE", "该房间尚未配置门禁设备，请联系管理员");
        }
        if (!doorController.reachable(device)) {
            record(r, userId, device.getId(), null, ip, "DEVICE_OFFLINE");
            return UnlockResult.deny("DEVICE_OFFLINE", "门禁离线，请联系管理员或使用备用钥匙");
        }

        // 1) 授权：只写库。事务边界到这里为止（入库指令 + 记录"服务端已放行"）
        DeviceCommand command = transactionTemplate.execute(status -> {
            DeviceCommand cmd = deviceCommandService.issue(device.getId(), r.getId(),
                    DeviceCommand.Type.UNLOCK, payload(r, userId, reason), doorProperties.getTtlSeconds());
            // 已授权就记 granted=1：即使回执还没到，"服务端已放行"这个事实也要留痕
            record(r, userId, device.getId(), cmd.getId(), ip, null);
            return cmd;
        });

        // 2) 投递：MQTT 发布不可回滚，必须放在事务提交之后
        DispatchResult dispatch = deliver(device, command);

        return switch (dispatch) {
            case ACKED -> UnlockResult.granted(command.getMsgId(), "ACKED", "门禁已开锁，请推门进入");
            case SENT -> UnlockResult.granted(command.getMsgId(), "SENT", "开门指令已下发，等待门禁确认");
            case FAILED -> UnlockResult.granted(command.getMsgId(), "FAILED",
                    "已授权，但指令未能下发到门禁，请联系管理员或使用机械释放杆");
        };
    }

    /**
     * 投递并结算。**必须在授权事务提交之后调用。**
     *
     * <p>{@code markSent} 只接受 PENDING 状态：返回 false 说明这条指令已经投递过或已过期，
     * 此时不得再驱动一次电控锁，否则一次点击可能在设备侧执行两次。
     */
    private DispatchResult deliver(Device device, DeviceCommand command) {
        if (!deviceCommandService.markSent(command.getMsgId())) {
            log.info("开门指令重复投递被忽略：msgId={}", command.getMsgId());
            return DispatchResult.SENT;
        }

        DispatchResult dispatch = doorController.dispatch(device, command);

        if (dispatch == DispatchResult.ACKED) {
            // 只有"设备已确认"才结算 ACKED；SENT 必须等回执，否则会把没开锁说成开锁
            deviceCommandService.ack(command.getMsgId(), DeviceCommand.Status.ACKED, "OK");
        } else if (dispatch == DispatchResult.FAILED) {
            // 根本没发出去：立刻结算为 FAILED，不留一条"假 SENT"等到过期
            deviceCommandService.ack(command.getMsgId(), DeviceCommand.Status.FAILED, "DISPATCH_FAILED");
        } else if (dispatch == DispatchResult.SENT) {
            // 回执可能在 dispatch 返回前就到了（进程内总线、或设备响应极快）。
            // 回查一次库：状态确实变成 ACKED 才对外声称已开锁，否则如实说"等待确认"。
            DeviceCommand latest = deviceCommandService.findByMsgId(command.getMsgId());
            if (latest != null && DeviceCommand.Status.ACKED.name().equals(latest.getStatus())) {
                dispatch = DispatchResult.ACKED;
            }
        }
        return dispatch;
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

    /** 写一条开门记录；granted 由 denyReason 是否为空决定。IP 一律落库，便于事后追溯是谁在哪个终端点的。 */
    private void record(Reservation r, Long userId, Long deviceId, Long commandId,
                        String ip, String denyReason) {
        AccessLog entry = new AccessLog();
        entry.setReservationId(r == null ? null : r.getId());
        entry.setUserId(userId);
        entry.setIp(ip);
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
