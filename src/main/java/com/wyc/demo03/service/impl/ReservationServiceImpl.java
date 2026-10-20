package com.wyc.demo03.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wyc.demo03.common.SlotConflictException;
import com.wyc.demo03.config.SlotProperties;
import com.wyc.demo03.domain.DecisionPath;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.domain.RuleContext;
import com.wyc.demo03.domain.RuleOutcome;
import com.wyc.demo03.domain.SlotCheck;
import com.wyc.demo03.domain.SlotSchema;
import com.wyc.demo03.domain.SlotUnit;
import com.wyc.demo03.entity.AttendanceRecord;
import com.wyc.demo03.entity.AuditLog;
import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.entity.ReservationSlot;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.AuditLogMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.rule.RuleEngine;
import com.wyc.demo03.service.AttendanceRecordService;
import com.wyc.demo03.service.CheckinCodeService;
import com.wyc.demo03.service.CreditService;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.service.SlotService;
import com.wyc.demo03.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 预约业务实现（v2）。
 *
 * <h3>事务与并发</h3>
 * <ul>
 *   <li>所有状态变更走 {@code mapper.transit(...)} 的条件更新，靠 {@code WHERE status = 旧值}
 *       判断谁抢到；不做 {@code getById → set → updateById}。</li>
 *   <li>占用仲裁交给数据库唯一键：{@code insertUnits} 是一条多值 INSERT，撞键整条回滚，
 *       因此不会出现"插了一半"的占用。</li>
 *   <li>冲突时抛 {@link SlotConflictException} 让整个事务回滚 ——
 *       此时状态已经被改过，必须一起撤销，不能只回滚占用。</li>
 * </ul>
 *
 * <h3>取消的时间限制（与 v1 不同）</h3>
 * 已开始的预约不允许用户取消。v1 允许"只要没签到就能取消"，这会给爽约留后门：
 * 学生 14:05 发现赶不上，直接取消就能躲掉 −10 分的爽约认定。
 */
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl extends ServiceImpl<ReservationMapper, Reservation>
        implements ReservationService {

    /** 签到码连续输错上限。 */
    private static final int MAX_CHECKIN_ATTEMPTS = 5;

    private final AttendanceRecordService attendanceRecordService;
    private final MeetingRoomService meetingRoomService;
    private final UserService userService;
    private final SlotService slotService;
    private final CheckinCodeService checkinCodeService;
    private final CreditService creditService;
    private final RuleEngine ruleEngine;
    private final SlotSchema slotSchema;
    private final SlotProperties slotProperties;
    private final AuditLogMapper auditLogMapper;
    private final Clock clock;

    /** 预约 ID → 签到码输错次数（内存版，单实例部署适用）。 */
    private final ConcurrentMap<Long, AtomicInteger> checkInFailures = new ConcurrentHashMap<>();

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ====================================================================
    // 提交申请
    // ====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubmitResult submit(Reservation form, Long userId, String role) {
        LocalDateTime now = now();

        MeetingRoom room = meetingRoomService.getById(form.getRoomId());
        if (room == null) {
            return SubmitResult.fail("room_not_found", "房间不存在");
        }
        if (Integer.valueOf(1).equals(room.getRoomStatus())) {
            return SubmitResult.fail("room_maintenance", "该房间正在维护中，暂无法预约");
        }

        SlotCheck slotCheck = slotSchema.check(form.getStartTime(), form.getEndTime(), now);
        if (slotCheck.invalid()) {
            return SubmitResult.fail(slotCheck.code(), slotCheck.message());
        }

        List<SlotUnit> units = slotService.unitsOf(form.getStartTime(), form.getEndTime());
        // 只读预检：真正仲裁在 occupy() 的唯一键
        List<ReservationSlot> occupied = slotService.findOccupied(form.getRoomId(), units);

        if ("TEACHER".equals(role)) {
            return submitAsTeacher(form, userId, room, units, occupied, now);
        }
        return submitAsStudent(form, userId, role, room, slotCheck, units, occupied, now);
    }

    /** 学生（及管理员）路径：走三层规则。 */
    private SubmitResult submitAsStudent(Reservation form, Long userId, String role, MeetingRoom room,
                                         SlotCheck slotCheck, List<SlotUnit> units,
                                         List<ReservationSlot> occupied, LocalDateTime now) {
        int attendee = form.getAttendeeCount() == null ? 1 : form.getAttendeeCount();
        int capacity = room.getCapacity() == null ? 0 : room.getCapacity();

        RuleContext ctx = new RuleContext(
                role,
                true,
                slotCheck,
                !occupied.isEmpty(),
                attendee,
                capacity,
                form.getPurpose(),
                creditService.scoreOf(userId),
                baseMapper.countActiveViolation(userId) > 0,
                baseMapper.countAutoPassOnDate(userId, form.getStartTime().toLocalDate()),
                units.size());

        RuleOutcome outcome = ruleEngine.evaluate(ctx);
        Reservation r = buildReservation(form, userId, attendee);
        r.setHitRuleCodes(joinCodes(outcome.hitRuleCodes()));

        switch (outcome.kind()) {
            case REJECT -> {
                r.setReservationStatus(ReservationStatus.REJECTED.code());
                r.setDecisionPath(DecisionPath.REJECTED_BASIC.name());
                r.setDecisionReason(outcome.message());
                r.setClosedAt(now);
                save(r);
                audit("STUDENT", AuditLog.Action.RESERVE, r.getId(),
                        "规则拒绝：" + outcome.firstRuleCode());
                return SubmitResult.fail(outcome.firstRuleCode(), outcome.message());
            }
            case FORCE_MANUAL -> {
                r.setReservationStatus(ReservationStatus.PENDING.code());
                r.setDecisionPath(DecisionPath.MANUAL_PENDING.name());
                save(r);
                audit("STUDENT", AuditLog.Action.RESERVE, r.getId(),
                        "转人工：" + outcome.firstRuleCode());
                return SubmitResult.ok(r.getId(), ReservationStatus.PENDING, outcome.message(), null);
            }
            default -> {
                r.setReservationStatus(ReservationStatus.APPROVED.code());
                r.setDecisionPath(DecisionPath.AUTO_PASS.name());
                r.setApprovedAt(now);
                save(r);
                // 先落库拿到 ID，再占位；占用失败会抛异常把这条记录一起回滚
                slotService.occupy(r.getId(), r.getRoomId(), units);
                String code = checkinCodeService.issue(r.getId());
                audit("STUDENT", AuditLog.Action.RESERVE, r.getId(),
                        "自动通过：" + outcome.firstRuleCode());
                return SubmitResult.ok(r.getId(), ReservationStatus.APPROVED, outcome.message(), code);
            }
        }
    }

    /** 教师路径：可覆盖尚未开始的学生预约，教师之间冲突直接拒绝（文档 3.2.1）。 */
    private SubmitResult submitAsTeacher(Reservation form, Long userId, MeetingRoom room,
                                         List<SlotUnit> units, List<ReservationSlot> occupied,
                                         LocalDateTime now) {
        int attendee = form.getAttendeeCount() == null ? 1 : form.getAttendeeCount();
        if (attendee > (room.getCapacity() == null ? attendee : room.getCapacity())) {
            return SubmitResult.fail("over_capacity",
                    "参会人数超过房间容量（" + room.getCapacity() + " 人）");
        }

        List<Reservation> owners = loadOwners(occupied);
        Map<Long, String> ownerRoles = loadRoles(owners);

        // 1) 教师之间不可覆盖
        for (Reservation owner : owners) {
            if ("TEACHER".equals(ownerRoles.get(owner.getUserId()))) {
                return SubmitResult.fail("teacher_conflict", "该时段已有其他教师的预约，无法覆盖");
            }
        }
        // 2) 已开始的预约不可覆盖（含已进入签到窗口）
        for (Reservation owner : owners) {
            if (owner.hasStarted(now)) {
                return SubmitResult.fail("override_started",
                        "该时段已有已开始的预约，不可覆盖；请选择其他时间");
            }
        }
        // 3) 覆盖尚未开始的学生预约：整笔取消 + 释放单元 + 撤销码
        String overrideReason = "被教师的预约覆盖，请重新选择时段";
        for (Reservation owner : owners) {
            int moved = baseMapper.transit(owner.getId(),
                    owner.getReservationStatus(), ReservationStatus.CANCELED.code(),
                    null, overrideReason, now);
            if (moved != 1) {
                // 状态在这期间被别人改过：不能"取消了却没释放单元"，必须整体回滚
                throw new IllegalStateException("覆盖失败：目标预约状态已变化，请刷新后重试");
            }
            baseMapper.setCancelSource(owner.getId(), "TEACHER_OVERRIDE");
            slotService.release(owner.getId());
            checkinCodeService.revoke(owner.getId());
            audit("SYSTEM", AuditLog.Action.CANCEL, owner.getId(), "教师覆盖取消");
        }

        // 4) 建立教师自己的预约
        Reservation r = buildReservation(form, userId, attendee);
        r.setReservationStatus(ReservationStatus.APPROVED.code());
        r.setDecisionPath(DecisionPath.TEACHER_AUTO.name());
        r.setHitRuleCodes(owners.isEmpty() ? null : "TEACHER_OVERRIDE:" + owners.size());
        r.setApprovedAt(now);
        save(r);
        slotService.occupy(r.getId(), r.getRoomId(), units);
        String code = checkinCodeService.issue(r.getId());
        audit("TEACHER", AuditLog.Action.RESERVE, r.getId(),
                owners.isEmpty() ? "教师预约直接通过" : "教师覆盖 " + owners.size() + " 笔预约");
        return SubmitResult.ok(r.getId(), ReservationStatus.APPROVED, "预约已生效", code);
    }

    // ====================================================================
    // 人工批准 / 拒绝
    // ====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApproveResult approve(Long id, Long adminId) {
        Reservation r = getById(id);
        if (r == null) {
            return ApproveResult.fail("not_found", "预约不存在");
        }
        if (r.statusOrNull() != ReservationStatus.PENDING) {
            return ApproveResult.fail("state_changed", "该申请已被处理，请刷新后重试");
        }
        LocalDateTime now = now();
        if (!now.isBefore(r.getStartTime())) {
            return ApproveResult.fail("expired", "预约开始时间已过，无法批准");
        }

        int claimed = baseMapper.transit(id, ReservationStatus.PENDING.code(),
                ReservationStatus.APPROVED.code(), DecisionPath.MANUAL_APPROVED.name(), null, null);
        if (claimed != 1) {
            return ApproveResult.fail("state_changed", "该申请已被其他人处理，请刷新后重试");
        }
        baseMapper.markApprover(id, adminId, now);

        // 重检并占用；冲突抛 SlotConflictException → 上面那次状态变更一起回滚
        List<SlotUnit> units = slotService.unitsOf(r.getStartTime(), r.getEndTime());
        slotService.occupy(id, r.getRoomId(), units);

        String code = checkinCodeService.issue(id);
        audit("ADMIN", AuditLog.Action.APPROVE, id, "人工批准，签到码已签发");
        return ApproveResult.ok(code);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason, Long adminId) {
        if (reason != null && reason.length() > 200) {
            throw new IllegalArgumentException("拒绝原因不能超过200字");
        }
        Reservation r = getById(id);
        if (r == null || r.statusOrNull() != ReservationStatus.PENDING) {
            throw new IllegalStateException("该申请不存在或已被处理，本次未执行拒绝");
        }
        String message = (reason == null || reason.isBlank())
                ? "该时段无法安排，如有疑问请联系管理员"
                : reason.trim();
        LocalDateTime now = now();

        int moved = baseMapper.transit(id, ReservationStatus.PENDING.code(),
                ReservationStatus.REJECTED.code(), DecisionPath.MANUAL_REJECTED.name(), message, now);
        if (moved != 1) {
            throw new IllegalStateException("该申请已被处理，本次未执行拒绝");
        }
        baseMapper.markApprover(id, adminId, now);
        // 待审批理论上没有占用与码，这里做成幂等清理，防止历史脏数据残留
        slotService.release(id);
        checkinCodeService.revoke(id);
        audit("ADMIN", AuditLog.Action.REJECT, id, "人工拒绝：" + message);
    }

    // ====================================================================
    // 取消
    // ====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String cancel(Long reservationId, Long userId) {
        Reservation r = getById(reservationId);
        if (r == null || !r.getUserId().equals(userId)) {
            return "not_owner";
        }
        ReservationStatus from = r.statusOrNull();
        if (from != ReservationStatus.PENDING && from != ReservationStatus.APPROVED) {
            return "invalid_status";
        }
        LocalDateTime now = now();
        if (!now.isBefore(r.getStartTime())) {
            // 已开始就不给取消入口：否则可以用"取消"规避爽约扣分
            return "already_started";
        }

        int moved = baseMapper.transit(reservationId, from.code(),
                ReservationStatus.CANCELED.code(), null, "用户取消", now);
        if (moved != 1) {
            return "status_changed";
        }
        baseMapper.setCancelSource(reservationId, "USER");
        slotService.release(reservationId);
        checkinCodeService.revoke(reservationId);
        audit("USER", AuditLog.Action.CANCEL, reservationId, "用户取消");
        return "success";
    }

    // ====================================================================
    // 签到
    // ====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String checkIn(Long reservationId, Long userId, String code, String ip) {
        Reservation r = getById(reservationId);
        if (r == null || !r.getUserId().equals(userId)) {
            return "error";
        }
        ReservationStatus status = r.statusOrNull();
        if (status == ReservationStatus.CHECKED_IN) {
            return "already_checked_in";
        }
        if (status != ReservationStatus.APPROVED) {
            return "error";
        }

        LocalDateTime now = now();
        // 窗口 [开始, min(开始+5分钟, 结束))：开始包含、截止不包含
        LocalDateTime windowStart = r.getStartTime();
        LocalDateTime windowEnd = r.getStartTime().plusMinutes(slotProperties.getCheckinWindowMinutes());
        if (windowEnd.isAfter(r.getEndTime())) {
            windowEnd = r.getEndTime();
        }

        if (now.isBefore(windowStart)) {
            return "too_early";
        }
        if (!now.isBefore(windowEnd)) {
            checkInFailures.remove(reservationId);
            return "expired";
        }

        AtomicInteger failures = checkInFailures.get(reservationId);
        if (failures != null && failures.get() >= MAX_CHECKIN_ATTEMPTS) {
            return "too_many_attempts";
        }
        if (!checkinCodeService.verify(reservationId, code)) {
            checkInFailures.computeIfAbsent(reservationId, k -> new AtomicInteger()).incrementAndGet();
            return "wrong_code";
        }
        checkInFailures.remove(reservationId);

        // 抢占状态：并发下只有一笔能成功
        int moved = baseMapper.transit(reservationId, ReservationStatus.APPROVED.code(),
                ReservationStatus.CHECKED_IN.code(), null, null, null);
        if (moved != 1) {
            return "already_checked_in";
        }

        AttendanceRecord record = new AttendanceRecord();
        record.setReservationId(reservationId);
        record.setUserId(userId);
        record.setAttendStatus(1);
        record.setCheckInTime(now);
        record.setMethod("SCREEN_CODE");
        record.setIp(ip);
        attendanceRecordService.save(record);

        audit("USER", AuditLog.Action.CHECKIN, reservationId, "首次签到成功");
        return "success";
    }

    /** 签到窗口长度（分钟），来自 {@code app.slot.checkin-window-minutes}。 */
    private int checkinWindowMinutes() {
        // SlotSchema 只负责单元规则，窗口长度单独从配置读取，避免把两件事揉在一起
        return slotProperties.getCheckinWindowMinutes();
    }

    // ====================================================================
    // 定时任务
    // ====================================================================
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releasePendingStarted(LocalDateTime now) {
        // 待审批不占单元也没有签到码，批量条件更新即可
        int released = baseMapper.releaseExpiredPending(now);
        if (released > 0) {
            audit("SYSTEM", AuditLog.Action.RELEASE, null,
                    "待审批到开始时刻，批量释放 " + released + " 笔");
        }
        return released;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int releaseNoShow(LocalDateTime now) {
        LocalDateTime cutoff = now.minusMinutes(checkinWindowMinutes());
        List<Reservation> overdue = baseMapper.findCheckinOverdue(cutoff);
        int released = 0;
        for (Reservation r : overdue) {
            int moved = baseMapper.transit(r.getId(), ReservationStatus.APPROVED.code(),
                    ReservationStatus.TIMEOUT_RELEASED.code(), null,
                    "签到截止仍未签到，已自动释放", now);
            if (moved != 1) {
                continue;
            }
            slotService.release(r.getId());
            checkinCodeService.revoke(r.getId());
            audit("SYSTEM", AuditLog.Action.RELEASE, r.getId(), "爽约释放");
            released++;
        }
        return released;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int finishExpired(LocalDateTime now) {
        List<Reservation> ending = baseMapper.findToFinish(now);
        int finished = 0;
        for (Reservation r : ending) {
            ReservationStatus from = r.statusOrNull();
            if (from == null) {
                continue;
            }
            int moved = baseMapper.transit(r.getId(), from.code(),
                    ReservationStatus.FINISHED.code(), null, null, now);
            if (moved != 1) {
                continue;
            }
            slotService.release(r.getId());
            checkinCodeService.revoke(r.getId());
            audit("SYSTEM", AuditLog.Action.RELEASE, r.getId(), "到期结束");
            finished++;
        }
        return finished;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Reservation> remindEnding(LocalDateTime now) {
        LocalDateTime until = now.plusMinutes(slotProperties.getRemindBeforeMinutes());
        List<Reservation> upcoming = baseMapper.findEndingSoon(now, until);
        List<Reservation> reminded = new ArrayList<>();
        for (Reservation r : upcoming) {
            if (baseMapper.markReminded(r.getId(), now) == 1) {
                reminded.add(r);
            }
        }
        return reminded;
    }

    // ====================================================================
    // 查询
    // ====================================================================
    @Override
    public List<Map<String, Object>> findApprovals() {
        return baseMapper.findApprovals();
    }

    @Override
    public List<Map<String, Object>> countByRoom() {
        return baseMapper.countByRoom();
    }

    @Override
    public List<Map<String, Object>> countByHour() {
        return baseMapper.countByHour();
    }

    @Override
    public IPage<Map<String, Object>> findByUserIdWithRoomPage(Page<?> page, Long userId, Integer statusGroup) {
        return baseMapper.findByUserIdWithRoomPage(page, userId, statusGroup);
    }

    @Override
    public Map<String, Object> findDetailById(Long id) {
        return baseMapper.findDetailById(id);
    }

    @Override
    public List<Map<String, Object>> findScheduleByRoomAndDate(Long roomId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        return baseMapper.findByRoomAndDate(roomId, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
    }

    // ====================================================================
    // 私有辅助
    // ====================================================================

    private Reservation buildReservation(Reservation form, Long userId, int attendee) {
        Reservation r = new Reservation();
        r.setUserId(userId);
        r.setRoomId(form.getRoomId());
        r.setStartTime(form.getStartTime());
        r.setEndTime(form.getEndTime());
        r.setAttendeeCount(attendee);
        String purpose = form.getPurpose();
        r.setPurpose(purpose == null || purpose.isBlank() ? null : purpose.trim());
        return r;
    }

    /** 由占用行反查占位者，去重后按 ID 取回预约实体。 */
    private List<Reservation> loadOwners(List<ReservationSlot> occupied) {
        if (occupied.isEmpty()) {
            return List.of();
        }
        List<Long> ids = occupied.stream().map(ReservationSlot::getReservationId).distinct().toList();
        List<Reservation> owners = baseMapper.findByIds(ids);
        return owners == null ? List.of() : owners;
    }

    /** userId → role，用于判断冲突方是否教师。 */
    private Map<Long, String> loadRoles(List<Reservation> owners) {
        if (owners.isEmpty()) {
            return Map.of();
        }
        List<Long> userIds = owners.stream().map(Reservation::getUserId).distinct().toList();
        List<User> users = userService.listByIds(userIds);
        Map<Long, String> roles = new HashMap<>();
        if (users != null) {
            for (User u : users) {
                roles.put(u.getId(), u.getRole());
            }
        }
        return roles;
    }

    private static String joinCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        String joined = String.join(",", codes);
        return joined.length() > 200 ? joined.substring(0, 200) : joined;
    }

    private void audit(String actor, AuditLog.Action action, Long targetId, String detail) {
        AuditLog log = new AuditLog();
        log.setActor(actor);
        log.setAction(action.name());
        log.setTargetType("RESERVATION");
        log.setTargetId(targetId);
        log.setDetail(detail != null && detail.length() > 500 ? detail.substring(0, 500) : detail);
        log.setCreatedAt(now());
        auditLogMapper.insert(log);
    }
}
