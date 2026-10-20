package com.wyc.demo03.service.impl;

import com.wyc.demo03.entity.Appeal;
import com.wyc.demo03.entity.AuditLog;
import com.wyc.demo03.entity.CreditLog;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.AppealMapper;
import com.wyc.demo03.mapper.AuditLogMapper;
import com.wyc.demo03.mapper.CreditLogMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.AppealService;
import com.wyc.demo03.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppealServiceImpl implements AppealService {

    /** 申诉期限：自原处理结论起 7 日内。 */
    private static final int APPEAL_WINDOW_DAYS = 7;

    /** 办结时限：3 个工作日。 */
    private static final int DUE_WORKING_DAYS = 3;

    private final AppealMapper appealMapper;
    private final ReservationMapper reservationMapper;
    private final CreditLogMapper creditLogMapper;
    private final CreditService creditService;
    private final AuditLogMapper auditLogMapper;
    private final Clock clock;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Appeal submit(Long userId, Appeal.TargetType type, Long targetId, String reason, String evidence) {
        if (userId == null || type == null || targetId == null) {
            throw new IllegalArgumentException("申诉对象不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("请填写申诉说明");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("申诉说明不能超过 500 字");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String originalDecision = resolveOriginalDecision(userId, type, targetId, now);

        Appeal appeal = new Appeal();
        appeal.setUserId(userId);
        appeal.setTargetType(type.name());
        appeal.setTargetId(targetId);
        appeal.setReason(reason.trim());
        appeal.setEvidence(evidence == null || evidence.isBlank() ? null : evidence.trim());
        appeal.setStatus(Appeal.Status.SUBMITTED.name());
        appeal.setOriginalDecision(originalDecision);
        appeal.setCreatedAt(now);
        appeal.setDueAt(addWorkingDays(now, DUE_WORKING_DAYS));

        try {
            appealMapper.insert(appeal);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该预约或扣分已提交过申诉，每项只能申诉一次");
        }

        audit(userId, "APPEAL_DECIDE", appeal.getId(), "提交申诉：" + type + "#" + targetId);
        return appeal;
    }

    @Override
    public List<Map<String, Object>> listMine(Long userId) {
        return appealMapper.findByUser(userId);
    }

    @Override
    public List<Map<String, Object>> listPending() {
        return appealMapper.findByStatus(Appeal.Status.SUBMITTED.name());
    }

    @Override
    public List<Map<String, Object>> listDecided() {
        return appealMapper.findDecided();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Appeal decide(Long appealId, Long reviewerId, Appeal.Status status, String note) {
        if (status == null || !status.closed()) {
            throw new IllegalArgumentException("请选择复核结论");
        }
        Appeal appeal = appealMapper.selectById(appealId);
        if (appeal == null) {
            throw new IllegalArgumentException("申诉工单不存在");
        }
        if (appeal.isDecided()) {
            throw new IllegalStateException("该工单已复核，不能重复处理");
        }
        if (reviewerId == null) {
            throw new IllegalArgumentException("未获取到复核人");
        }
        if (reviewerId.equals(appeal.getUserId())) {
            throw new IllegalStateException("不能复核自己提交的申诉");
        }
        if (isOriginalApprover(appeal, reviewerId)) {
            throw new IllegalStateException("复核人不得是原审核人，请换人处理");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String reviewNote = note == null || note.isBlank() ? status.label() : note.trim();

        // ★ 抢占式更新：只有第一个把 SUBMITTED 改掉的人算赢家，后面的人拿到 0 行直接退出，
        //   不会出现两个复核都成功、信用被对冲两次（BUG-011）
        if (appealMapper.decideIfSubmitted(appealId, status.name(), reviewerId, reviewNote, now) != 1) {
            throw new IllegalStateException("该工单已被其他管理员处理，请刷新后查看");
        }
        appeal.setStatus(status.name());
        appeal.setReviewerId(reviewerId);
        appeal.setReviewNote(reviewNote);
        appeal.setDecidedAt(now);

        // 申诉成立且对象是信用扣分 → 对冲原扣分（注意：不恢复房间单元）
        if (status == Appeal.Status.REVOKED && Appeal.TargetType.CREDIT.name().equals(appeal.getTargetType())) {
            // 对冲幂等：同一申诉只能产生一笔撤销流水
            if (creditLogMapper.countByAppealId(appealId) > 0) {
                log.warn("申诉 {} 已存在对冲流水，跳过重复撤销", appealId);
            } else {
            CreditLog original = creditLogMapper.selectById(appeal.getTargetId());
            if (original != null && original.getDelta() != null && original.getDelta() != 0) {
                // ★ 原因码必须用 APPEAL_REVOKE，不能沿用原原因：
                //   信用流水有唯一键 (reservation_id, reason_code)，若对冲行还写 NO_SHOW，
                //   会和原扣分行撞键，导致撤销静默失败（分数降不下来）。
                //   reservation_id 传 null：同一预约可能存在多笔扣分各自被撤销，
                //   靠 appealId 与 note 保留可追溯性。
                creditService.adjust(original.getUserId(), CreditLog.Reason.APPEAL_REVOKE,
                        -original.getDelta(), null, appeal.getId(), "REVIEWER",
                        "申诉成立，撤销第 " + original.getId() + " 条扣分（原 "
                                + original.getReasonCode() + " " + original.getDelta() + " 分）");
            }
            }
        }

        audit(reviewerId, "APPEAL_DECIDE", appealId,
                "复核结论：" + status.name() + "，" + appeal.getReviewNote());
        return appeal;
    }

    @Override
    public int countOverdue(LocalDateTime now) {
        return appealMapper.countOverdue(now);
    }

    // ==================== 私有 ====================

    /**
     * 校验目标存在、归属本人、未超 7 日，并返回原处理结论快照。
     */
    private String resolveOriginalDecision(Long userId, Appeal.TargetType type, Long targetId, LocalDateTime now) {
        if (type == Appeal.TargetType.RESERVATION) {
            Reservation r = reservationMapper.selectById(targetId);
            if (r == null) {
                throw new IllegalArgumentException("预约不存在");
            }
            if (!userId.equals(r.getUserId())) {
                throw new IllegalArgumentException("只能对自己的预约提交申诉");
            }
            // 只允许对"有不利结论"的预约申诉：已拒绝 / 已取消 / 超时释放。
            // 已通过、已签到、已结束的预约没有可诉的结论，允许申诉只会产生无效工单（BUG-011）
            ReservationStatus status = r.statusOrNull();
            if (status != ReservationStatus.REJECTED
                    && status != ReservationStatus.CANCELED
                    && status != ReservationStatus.TIMEOUT_RELEASED) {
                throw new IllegalArgumentException("只有被拒绝、已取消或超时释放的预约可以申诉");
            }
            LocalDateTime base = r.getClosedAt() != null ? r.getClosedAt() : r.getEndTime();
            if (base != null && now.isAfter(base.plusDays(APPEAL_WINDOW_DAYS))) {
                throw new IllegalArgumentException("已超过 7 日申诉期限");
            }
            return "预约状态 " + r.getReservationStatus()
                    + "；判定路径 " + r.getDecisionPath()
                    + "；命中规则 " + r.getHitRuleCodes()
                    + "；原因 " + (r.getDecisionReason() == null ? "无" : r.getDecisionReason());
        }

        CreditLog entry = creditLogMapper.selectById(targetId);
        if (entry == null) {
            throw new IllegalArgumentException("信用流水不存在");
        }
        if (!userId.equals(entry.getUserId())) {
            throw new IllegalArgumentException("只能对自己的扣分提交申诉");
        }
        // 只接受负向扣分：对加分申诉，成立后反而会把分数扣回去
        if (entry.getDelta() == null || entry.getDelta() >= 0) {
            throw new IllegalArgumentException("只能对扣分记录提交申诉");
        }
        if (entry.getCreatedAt() != null && now.isAfter(entry.getCreatedAt().plusDays(APPEAL_WINDOW_DAYS))) {
            throw new IllegalArgumentException("已超过 7 日申诉期限");
        }
        return "信用变动 " + entry.getReasonCode() + "（" + entry.getDelta() + " 分），"
                + (entry.getNote() == null ? "" : entry.getNote());
    }

    /** 复核人是否是原审核人（预约的 approvedBy）。 */
    private boolean isOriginalApprover(Appeal appeal, Long reviewerId) {
        Long reservationId = null;
        if (Appeal.TargetType.RESERVATION.name().equals(appeal.getTargetType())) {
            reservationId = appeal.getTargetId();
        } else {
            CreditLog entry = creditLogMapper.selectById(appeal.getTargetId());
            if (entry != null) {
                reservationId = entry.getReservationId();
            }
        }
        if (reservationId == null) {
            return false;
        }
        Reservation r = reservationMapper.selectById(reservationId);
        return r != null && reviewerId.equals(r.getApprovedBy());
    }

    /** 加 N 个工作日（跳过周六日；不做节假日表）。 */
    static LocalDateTime addWorkingDays(LocalDateTime from, int workingDays) {
        LocalDate date = from.toLocalDate();
        int added = 0;
        while (added < workingDays) {
            date = date.plusDays(1);
            DayOfWeek dow = date.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return date.atTime(from.toLocalTime());
    }

    private void audit(Long actorId, String action, Long targetId, String detail) {
        AuditLog audit = new AuditLog();
        audit.setActor(actorId == null ? "SYSTEM" : String.valueOf(actorId));
        audit.setAction(action);
        audit.setTargetType("APPEAL");
        audit.setTargetId(targetId);
        audit.setDetail(detail != null && detail.length() > 500 ? detail.substring(0, 500) : detail);
        audit.setCreatedAt(LocalDateTime.now(clock));
        auditLogMapper.insert(audit);
    }
}
