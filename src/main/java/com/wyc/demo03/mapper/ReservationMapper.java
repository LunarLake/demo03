package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wyc.demo03.entity.Reservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ReservationMapper extends BaseMapper<Reservation> {

    // ====================================================================
    // 状态迁移：守卫式更新
    // ====================================================================
    /**
     * 带前置状态的原子迁移。返回 1 表示抢到，0 表示状态已被别人改过。
     *
     * <p>所有状态变更都必须走这里，禁止 {@code getById → setStatus → updateById} 的三段式：
     * 后者在并发下会互相覆盖，而且无法判断"是谁赢了"。
     *
     * <p>用 {@code COALESCE} 而不是直接赋值，是为了让"只设置一次"的字段
     * （终止时刻、原因）在后续迁移中不被 null 冲掉。
     */
    @Update("UPDATE t_reservation SET "
            + "reservation_status = #{to}, "
            + "decision_path = COALESCE(#{decisionPath}, decision_path), "
            + "decision_reason = COALESCE(#{reason}, decision_reason), "
            + "closed_at = COALESCE(#{closedAt}, closed_at), "
            + "version = version + 1 "
            + "WHERE id = #{id} AND reservation_status = #{from}")
    int transit(@Param("id") Long id,
                @Param("from") int from,
                @Param("to") int to,
                @Param("decisionPath") String decisionPath,
                @Param("reason") String reason,
                @Param("closedAt") LocalDateTime closedAt);

    /** 记录取消来源（用户/教师覆盖/管理员）。只在取消路径上调用一次。 */
    @Update("UPDATE t_reservation SET cancel_source = #{cancelSource} "
            + "WHERE id = #{id} AND cancel_source IS NULL")
    int setCancelSource(@Param("id") Long id, @Param("cancelSource") String cancelSource);

    /** 记录人工审核人与时刻（仅在管理员批准/拒绝后调用）。 */
    @Update("UPDATE t_reservation SET approved_by = #{approvedBy}, approved_at = #{approvedAt} "
            + "WHERE id = #{id} AND approved_by IS NULL")
    int markApprover(@Param("id") Long id,
                     @Param("approvedBy") Long approvedBy,
                     @Param("approvedAt") LocalDateTime approvedAt);

    /** 登记"已提醒"，条件更新保证只提醒一次。 */
    @Update("UPDATE t_reservation SET reminded_at = #{at} "
            + "WHERE id = #{id} AND reminded_at IS NULL")
    int markReminded(@Param("id") Long id, @Param("at") LocalDateTime at);

    // ====================================================================
    // 我的预约
    // ====================================================================
    /**
     * 当前用户的预约分页，附带房间名与签到状态。
     *
     * <p>{@code statusGroup} 语义（与页面 Tab 对应）：
     * null=全部；0=待审批；1=已通过；6=已签到；2=终止（已拒绝/已取消/超时释放/已结束）。
     */
    @Select("<script>" +
            "SELECT r.*, m.room_name AS roomName, ar.attend_status, ar.check_in_time, " +
            "       cc.code AS checkInCode, cc.revoked_at AS codeRevokedAt " +
            "FROM t_reservation r " +
            "JOIN t_meeting_room m ON r.room_id = m.id " +
            "LEFT JOIN t_attendance_record ar ON r.id = ar.reservation_id " +
            "LEFT JOIN t_checkin_code cc ON r.id = cc.reservation_id " +
            "WHERE r.user_id = #{userId} " +
            "<choose>" +
            "<when test='statusGroup == 2'>AND r.reservation_status IN (2, 3, 5, 7)</when>" +
            "<when test='statusGroup != null'>AND r.reservation_status = #{statusGroup}</when>" +
            "</choose>" +
            " ORDER BY r.start_time DESC" +
            "</script>")
    IPage<Map<String, Object>> findByUserIdWithRoomPage(Page<?> page,
                                                        @Param("userId") Long userId,
                                                        @Param("statusGroup") Integer statusGroup);

    // ====================================================================
    // 审批
    // ====================================================================
    /** 待审批列表：带判定路径与命中规则，供管理员看到"为什么进了人工"。 */
    @Select("SELECT r.*, u.name AS userName, u.role AS userRole, m.room_name AS roomName, m.capacity AS capacity "
            + "FROM t_reservation r "
            + "JOIN t_user u ON r.user_id = u.id "
            + "JOIN t_meeting_room m ON r.room_id = m.id "
            + "WHERE r.reservation_status = 0 ORDER BY r.start_time ASC")
    List<Map<String, Object>> findApprovals();

    /** 某用户某日已自动通过的笔数（附加规则"每日不超过 3 笔"用）。 */
    @Select("SELECT COUNT(*) FROM t_reservation "
            + "WHERE user_id = #{userId} AND decision_path = 'AUTO_PASS' AND DATE(start_time) = #{date}")
    int countAutoPassOnDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    // ====================================================================
    // 定时任务扫描
    // ====================================================================
    /**
     * 签到截止仍未签到的「已通过」预约。
     *
     * @param cutoff 现在 － 签到窗口长度；{@code start_time <= cutoff} 即已过签到截止
     */
    @Select("SELECT r.* FROM t_reservation r "
            + "WHERE r.reservation_status = 1 AND r.start_time <= #{cutoff} "
            + "  AND NOT EXISTS (SELECT 1 FROM t_attendance_record a "
            + "                  WHERE a.reservation_id = r.id AND a.attend_status = 1)")
    List<Reservation> findCheckinOverdue(@Param("cutoff") LocalDateTime cutoff);

    /** 已到开始时刻仍未处理的待审批申请：批量置为超时释放。 */
    @Update("UPDATE t_reservation SET reservation_status = 5, closed_at = #{now}, version = version + 1 "
            + "WHERE reservation_status = 0 AND start_time <= #{now}")
    int releaseExpiredPending(@Param("now") LocalDateTime now);

    /** 到达结束时刻的有效预约（已通过 / 已签到）。 */
    @Select("SELECT * FROM t_reservation "
            + "WHERE reservation_status IN (1, 6) AND end_time <= #{now}")
    List<Reservation> findToFinish(@Param("now") LocalDateTime now);

    /** 即将结束且尚未提醒过的预约。 */
    @Select("SELECT * FROM t_reservation "
            + "WHERE reservation_status IN (1, 6) AND reminded_at IS NULL "
            + "  AND end_time <= #{until} AND end_time > #{now}")
    List<Reservation> findEndingSoon(@Param("now") LocalDateTime now,
                                     @Param("until") LocalDateTime until);

    // ====================================================================
    // 查询 / 统计
    // ====================================================================
    /** 某房间某日的有效预约（已通过/已签到），用于日程与排期板。 */
    @Select("SELECT r.*, u.name AS userName, u.role AS role FROM t_reservation r "
            + "JOIN t_user u ON r.user_id = u.id "
            + "WHERE r.room_id = #{roomId} AND r.reservation_status IN (1, 6) "
            + "  AND r.start_time < #{dayEnd} AND r.end_time > #{dayStart} "
            + "ORDER BY r.start_time")
    List<Map<String, Object>> findByRoomAndDate(@Param("roomId") Long roomId,
                                                @Param("dayStart") LocalDateTime dayStart,
                                                @Param("dayEnd") LocalDateTime dayEnd);

    /** 某房间某日仍待审批的申请，供排期板标注"仅待审、仍可选"。 */
    @Select("SELECT r.room_id AS roomId, r.start_time AS startTime, r.end_time AS endTime "
            + "FROM t_reservation r "
            + "WHERE r.reservation_status = 0 "
            + "  AND r.start_time < #{dayEnd} AND r.end_time > #{dayStart}")
    List<Map<String, Object>> findPendingOnDate(@Param("dayStart") LocalDateTime dayStart,
                                                @Param("dayEnd") LocalDateTime dayEnd);

    /**
     * 某日仍在进行中的预约（待审批 / 已通过 / 已签到），按开始时间升序。
     *
     * <p>排期板用它区分"我的"与"别人的"：占用表里只有 reservationId，
     * 要判断归属还得回到预约表。一次取全天而不是逐格查，避免 112 次查询。
     */
    @Select("SELECT * FROM t_reservation "
            + "WHERE reservation_status IN (0, 1, 6) "
            + "  AND start_time < #{dayEnd} AND end_time > #{dayStart} "
            + "ORDER BY start_time")
    List<Reservation> findActiveOnDate(@Param("dayStart") LocalDateTime dayStart,
                                       @Param("dayEnd") LocalDateTime dayEnd);

    /** 预约详情：含房间、申请人、签到记录、签到码。 */
    @Select("SELECT r.*, u.name AS userName, u.role AS userRole, m.room_name AS roomName, m.capacity AS capacity, "
            + "       ar.attend_status, ar.check_in_time AS checkInTime, ar.ip AS checkInIp, "
            + "       cc.code AS checkInCode, cc.revoked_at AS codeRevokedAt "
            + "FROM t_reservation r "
            + "JOIN t_user u ON r.user_id = u.id "
            + "JOIN t_meeting_room m ON r.room_id = m.id "
            + "LEFT JOIN t_attendance_record ar ON r.id = ar.reservation_id "
            + "LEFT JOIN t_checkin_code cc ON r.id = cc.reservation_id "
            + "WHERE r.id = #{id}")
    Map<String, Object> findDetailById(@Param("id") Long id);

    /** 按房间统计预约量（管理端图表）。 */
    @Select("SELECT m.room_name AS label, COUNT(*) AS count FROM t_reservation r "
            + "JOIN t_meeting_room m ON r.room_id = m.id "
            + "WHERE r.reservation_status IN (1, 6, 7) GROUP BY m.room_name ORDER BY count DESC")
    List<Map<String, Object>> countByRoom();

    /** 按小时统计预约分布。 */
    @Select("SELECT HOUR(start_time) AS label, COUNT(*) AS count FROM t_reservation "
            + "WHERE reservation_status IN (1, 6, 7) GROUP BY HOUR(start_time) ORDER BY label ASC")
    List<Map<String, Object>> countByHour();

    /** 房间是否还有有效预约（房间删除前校验）。 */
    @Select("SELECT COUNT(*) FROM t_reservation "
            + "WHERE room_id = #{roomId} AND reservation_status IN (0, 1, 6)")
    int countActiveByRoom(@Param("roomId") Long roomId);

    /** 某用户是否存在未被申诉撤销的违规记录（基础规则 B06）。 */
    @Select("SELECT COUNT(*) FROM t_credit_log c "
            + "WHERE c.user_id = #{userId} AND c.reason_code = 'VIOLATION' "
            + "  AND NOT EXISTS (SELECT 1 FROM t_appeal a "
            + "                  WHERE a.target_type = 'CREDIT' AND a.target_id = c.id "
            + "                    AND a.status = 'REVOKED')")
    int countActiveViolation(@Param("userId") Long userId);

    /**
     * 按 ID 批量取预约实体。
     *
     * <p>不用 MyBatis-Plus 的 {@code selectByIds}：那是个内部方法，随版本改签名，
     * 单测里 stub 起来脆。这里写成显式 SQL，行为固定、可测。
     */
    @Select("<script>"
            + "SELECT * FROM t_reservation WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Reservation> findByIds(@Param("ids") List<Long> ids);

    /** 在指定时间之后有过"已结束"预约的用户（"30 日无爽约"加分的候选集）。 */
    @Select("SELECT DISTINCT user_id FROM t_reservation "
            + "WHERE reservation_status = 7 AND closed_at >= #{since}")
    List<Long> findUsersFinishedSince(@Param("since") LocalDateTime since);
}
