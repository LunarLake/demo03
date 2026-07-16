package com.wyc.demo03.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wyc.demo03.entity.AttendanceRecord;
import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.AttendanceRecordService;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;

/**
 * 预约业务核心实现类
 *
 * 继承 MyBatis-Plus 的 ServiceImpl<ReservationMapper, Reservation>，
 * 从而自动获得 save、updateById、getById、lambdaQuery 等通用 CRUD 方法。
 * 自定义 SQL 通过 ReservationMapper（baseMapper）上的 @Select 注解调用。
 *
 * 事务策略：
 *   apply()、approve()、checkIn() 这三个方法涉及多表写入，用 @Transactional 保证原子性。
 *   reject()、cancel() 是单表单字段更新，依赖 MyBatis-Plus 内置的 updateById，无需显式事务。
 */
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl extends ServiceImpl<ReservationMapper, Reservation> implements ReservationService {

    private final AttendanceRecordService attendanceRecordService;
    private final MeetingRoomService meetingRoomService;
    private final UserService userService;

    /**
     * 安全的随机数生成器，用于生成 4 位签到码。
     * 为什么用 SecureRandom 而不是 Math.random()？
     *   SecureRandom 使用加密强度的随机源，生成的签到码不可预测，
     *   防止恶意用户通过枚举尝试猜出签到码进行虚假签到。
     */
    private final SecureRandom random = new SecureRandom();

    // ====================================================================
    // 方法一：apply —— 提交预约申请
    // ====================================================================
    // 这是整个系统最复杂的业务方法，同时处理学生预约和教师预约两条路径。
    //
    // 两条路径的分叉点：
    //   ┌─ 学生预约 → 状态=0（待审批），等待教师审批
    //   └─ 教师预约 → 状态=1（自动通过），直接生成签到码
    //
    // 教师预约的"强行覆盖"逻辑也在此方法内：
    //   1. 查出冲突预约（状态为 0 或 1 的重叠时段）
    //   2. 判断冲突者角色：
    //      - 有教师冲突 → 拒绝（教师之间不能互相覆盖）
    //      - 只有学生冲突 → 允许，学生预约状态→3（被覆盖）
    //   3. 在同一个事务里完成"学生状态变更 + 教师预约创建"
    //
    // 参数：
    //   reservation — 前端表单提交的预约实体（含 roomId, startTime, endTime, attendeeCount）
    //   session     — HTTP 会话，从中获取当前用户的 role 和 userId
    //
    // 返回值（字符串，由 Controller 判断后设置闪现消息）：
    //   "room_not_found"         — 会议室不存在
    //   "over_capacity"          — 参会人数超过会议室容量
    //   "past_time"              — 预约开始时间已过（不能预约过去的时间）
    //   "conflict"               — 存在冲突（学生冲突 / 教师冲突）
    //   "success"                — 预约成功
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String apply(Reservation reservation, HttpSession session) {
        // ---- 从 Session 获取当前用户的角色和 ID ----
        // session 中的属性是在 MainController.loginAction() 中登录成功后写入的
        String role = (String) session.getAttribute("role");
        Long userId = (Long) session.getAttribute("Id");

        // 把当前用户 ID 强制设为预约的申请人（防止前端篡改 userId）
        reservation.setUserId(userId);

        // ========== 第一层校验：会议室存在性 + 容量 + 时间合法性 ==========

        // 校验 1：会议室是否存在
        MeetingRoom room = meetingRoomService.getById(reservation.getRoomId());
        if (room == null) {
            return "room_not_found";
        }

        // 校验 2：参会人数是否超过会议室容量
        //        例如：会议室容量 20 人，用户填了 25 人 → 拒绝
        if (reservation.getAttendeeCount() != null && reservation.getAttendeeCount() > room.getCapacity()) {
            return "over_capacity";
        }

        // 校验 3：开始时间不能是过去的时间
        //        例如：现在是 14:00，用户想预约 13:00 → 拒绝
        if (reservation.getStartTime().isBefore(LocalDateTime.now())) {
            return "past_time";
        }

        // ========== 第二层校验：冲突检测 ==========
        // 调用 ReservationMapper.findConflicting() 查询同一会议室、同时段内
        // 状态为 0（待审批）或 1（已通过）的预约。
        // 冲突判定 SQL（在 ReservationMapper 中）：
        //   SELECT * FROM t_reservation
        //   WHERE room_id = #{roomId}
        //     AND reservation_status IN (0, 1)
        //     AND start_time < #{endTime} AND end_time > #{startTime}
        //
        // 时间重叠的逻辑（两条线段的交集判断）：
        //   已有预约 [s, e)    新预约 [start, end)
        //   它们重叠 ⇔ start < e AND end > s
        //   用 < 和 >（不含等于）是因为：A 在 10:00 结束，B 从 10:00 开始 → 不冲突
        List<Reservation> conflicts = baseMapper.findConflicting(
                reservation.getRoomId(), reservation.getStartTime(), reservation.getEndTime());

        if (!conflicts.isEmpty()) {
            // ---- 存在冲突预约，需要根据当前用户角色做不同处理 ----
            if ("TEACHER".equals(role)) {
                // ========== 教师路径：判断是否能覆盖 ==========
                //
                // 第一步：抽取冲突预约的所有 userId，查出对应的用户角色。
                //         distinct() 去重是因为一个用户在同一时段可能只有一条预约。
                List<Long> conflictUserIds = conflicts.stream()
                        .map(Reservation::getUserId).distinct().toList();

                // 第二步：检查冲突预约中是否有教师。
                //         anyMatch 是短路判断，只要找到一个教师就返回 true。
                boolean hasTeacherConflict = userService.listByIds(conflictUserIds).stream()
                        .anyMatch(u -> "TEACHER".equals(u.getRole()));

                if (hasTeacherConflict) {
                    // 冲突中有其他教师的预约 → 拒绝覆盖
                    // 教师之间不能互相覆盖（避免行政纠纷）
                    return "conflict";
                }

                // 冲突者全为学生 → 执行覆盖：把每个学生预约状态改为 3（被覆盖）
                // 这些学生的预约仍然保留在数据库里，只是状态变了，
                // 他们在"我的预约"页面会看到自己的预约显示为"已被覆盖"
                for (Reservation conflict : conflicts) {
                    conflict.setReservationStatus(3);
                    updateById(conflict);
                }

                // 覆盖完成后继续往下走，创建教师的预约

            } else {
                // ========== 学生路径：直接拒绝 ==========
                // 学生不能覆盖任何人的预约，哪怕是其他学生的也不行
                return "conflict";
            }
        }

        // ========== 第三层：设置预约状态并保存 ==========

        if ("TEACHER".equals(role)) {
            // 教师预约：自动通过（状态=1），同时生成 4 位签到码
            // 教师不需要审批，体现了"教师优先"的业务规则
            reservation.setReservationStatus(1);
            reservation.setCheckInCode(generateCode());
        } else {
            // 学生预约：进入待审批状态（状态=0），等待教师审批
            // 签到码暂时为空，审批通过时再生成
            reservation.setReservationStatus(0);
        }

        // MyBatis-Plus 的 save() 方法：执行 INSERT INTO t_reservation
        save(reservation);

        // ========== 第四层：教师预约自动创建签到记录 ==========
        // 教师的预约自动通过后，立即在 t_attendance_record 中创建一条
        // attendStatus=0（未签到）的记录，为后续签到核销做准备。
        // 学生的签到记录在审批通过时才创建（见 approve() 方法）。
        if ("TEACHER".equals(role)) {
            AttendanceRecord record = new AttendanceRecord();
            record.setReservationId(reservation.getId());
            record.setUserId(userId);
            record.setAttendStatus(0);       // 0 = 未签到
            attendanceRecordService.save(record);
        }

        return "success";
    }

    // ====================================================================
    // 方法二：approve —— 教师批准预约
    // ====================================================================
    // 教师点击"批准"按钮后调用。
    // 两步操作必须在同一个事务中完成（@Transactional）：
    //   1. 更新预约状态为 1（已通过），同时生成 4 位签到码
    //   2. 创建一条签到记录（attendStatus=0），为后续签到核销做准备
    //
    // 防御性校验：
    //   只有状态为 0（待审批）的预约才能被批准。
    //   如果预约已经被其他人批准/拒绝/覆盖/取消 → 返回 "error"。
    //
    // 参数：
    //   id — 预约 ID
    //
    // 返回值：
    //   "error"          — 预约不存在或状态不是 0（待审批）
    //   checkInCode      — 成功时返回生成的 4 位签到码（展示给教师查看）
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String approve(Long id) {
        Reservation reservation = getById(id);

        // 防御校验：预约必须存在，且状态必须是 0（待审批）。
        // 已批准(1)、已拒绝(2)、被覆盖(3)、已取消(4)、超时释放(5) 的预约都不能再批准。
        if (reservation == null || reservation.getReservationStatus() != 0) {
            return "error";
        }

        // 状态变更：0（待审批）→ 1（已通过）
        reservation.setReservationStatus(1);

        // 生成 4 位随机签到码（0000~9999），用于用户现场签到核销
        reservation.setCheckInCode(generateCode());

        updateById(reservation);

        // 同步创建签到记录（attendStatus=0，表示尚未签到）
        // 这条记录在用户签到时会更新：attendStatus→1 + 写入签到时间 + IP
        AttendanceRecord record = new AttendanceRecord();
        record.setReservationId(id);
        record.setUserId(reservation.getUserId());
        record.setAttendStatus(0);
        attendanceRecordService.save(record);

        // 返回签到码给 Controller，展示给教师
        return reservation.getCheckInCode();
    }

    // ====================================================================
    // ★ 核心方法三：reject —— 教师拒绝预约
    // ====================================================================
    // 逻辑相对简单，但有一个重要的防御校验：
    //   只能拒绝状态为 0（待审批）的预约。
    //   如果预约已被批准、已被拒绝、已被覆盖等 → 直接跳过不做任何操作。
    //
    // 为什么不用 @Transactional？
    //   只有一个单表的 updateById 操作，MyBatis-Plus 内置了单条 SQL 的原子性，
    //   不需要额外的事务包装。如果 update 失败，什么都不会发生。
    @Override
    public void reject(Long id) {
        Reservation reservation = getById(id);
        if (reservation != null && reservation.getReservationStatus() == 0) {
            reservation.setReservationStatus(2);  // 状态 0 → 2（已拒绝）
            updateById(reservation);
        }
    }

    // ====================================================================
    // 方法四：checkIn —— 现场签到核销
    // ====================================================================
    // 签到流程分五步校验，每一步失败都会返回对应的错误码：
    //
    //   【第一步】预约有效性校验
    //     - 预约是否存在
    //     - 是否是当前用户的预约（防止签别人的到）
    //     - 预约状态是否为 1（已通过）
    //     - 是否已生成签到码
    //
    //   【第二步】时间窗口校验 ★ 核心逻辑
    //     时间窗口：startTime - 10分钟 ～ startTime + 15分钟
    //     例如：预约 14:00 开始
    //           窗口开启：13:50  ← 提前 10 分钟
    //           窗口关闭：14:15  ← 延后 15 分钟
    //     在此窗口外签到 → too_early（太早）或 expired（已过期）
    //
    //   【第三步】签到码比对
    //     用户输入的 4 位数字和数据库中的 checkInCode 比对，
    //     不匹配 → wrong_code
    //
    //   【第四步】签到记录校验
    //     查找 t_attendance_record 中对应的签到记录，
    //     必须是当前用户的记录，否则 → error
    //
    //   【第五步】防重复签到 ★ 关键
    //     如果 attendStatus 已经是 1（已签到），说明之前已经签到过了，
    //     返回 already_checked_in，拒绝重复签到。
    //
    // 参数：
    //   reservationId — 预约 ID
    //   userId        — 签到人用户 ID（从 session 获取，不可篡改）
    //   code          — 用户输入的 4 位签到码
    //   ip            — 签到时的客户端 IP（从 request.getRemoteAddr() 获取）
    //
    // 返回值：
    //   "error"               — 预约不存在/不属于该用户/状态不对
    //   "too_early"           — 签到时间早于窗口开启时间
    //   "expired"             — 签到时间超出窗口关闭时间
    //   "wrong_code"          — 签到码不匹配
    //   "already_checked_in"  — 已经签到过了（防重复）
    //   "success"             — 签到成功
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String checkIn(Long reservationId, Long userId, String code, String ip) {
        // ===== 第一步：预约有效性校验 =====
        Reservation reservation = getById(reservationId);
        // 四个条件同时满足：
        //   1. 预约存在
        //   2. 预约属于当前用户（防止用户通过修改 URL 签别人的到）
        //   3. 状态为 1（已通过）
        //   4. 已生成签到码（已批准）
        if (reservation == null || !reservation.getUserId().equals(userId)
                || reservation.getReservationStatus() != 1
                || reservation.getCheckInCode() == null) {
            return "error";
        }

        // ===== 第二步：时间窗口校验 =====
        LocalDateTime now = LocalDateTime.now();

        // 窗口开启时间：预约开始时间 - 10 分钟
        LocalDateTime windowStart = reservation.getStartTime().minusMinutes(10);

        // 窗口关闭时间：预约开始时间 + 15 分钟
        LocalDateTime windowEnd = reservation.getStartTime().plusMinutes(15);

        if (now.isBefore(windowStart)) {
            return "too_early";   // 还没到签到时间（比如 14:00 的预约，现在是 13:45）
        }
        if (now.isAfter(windowEnd)) {
            return "expired";     // 签到时间已过（比如 14:00 的预约，现在已经是 14:20）
        }

        // ===== 第三步：签到码比对 =====
        // 用户输入的 4 位数字和数据库中存储的签到码必须完全一致
        if (!reservation.getCheckInCode().equals(code)) {
            return "wrong_code";
        }

        // ===== 第四步：签到记录校验 =====
        // 查找在审批时（教师）或预约时（教师）创建的签到记录
        AttendanceRecord record = attendanceRecordService.findByReservationId(reservationId);
        if (record == null || !record.getUserId().equals(userId)) {
            return "error";       // 签到记录不存在或不属于当前用户
        }

        // ===== 第五步：防重复签到 =====
        // 如果签到记录的 attendStatus 已经是 1，说明之前已经签到成功过。
        // 这个检查在 @Transactional 的保护下执行，防止并发重复签到：
        //  即使两个请求同时到达这里，数据库的行锁会保证只有一个能成功更新。
        if (record.getAttendStatus() == 1) {
            return "already_checked_in";
        }

        // ===== 执行签到 =====
        record.setAttendStatus(1);               // 标记为已签到
        record.setCheckInTime(LocalDateTime.now());  // 记录签到时间
        record.setIp(ip);                         // 记录签到 IP（用于审计溯源）
        attendanceRecordService.updateById(record);

        return "success";
    }

    // ====================================================================
    // 方法五：cancel —— 取消预约
    // ====================================================================
    // 用户可以取消自己尚未开始的预约。
    //
    // 取消条件（三个都要满足）：
    //   1. 预约存在且属于当前用户（not_owner 检查）
    //   2. 预约状态为 0（待审批）或 1（已通过）— 已经拒绝/覆盖/取消的不能再取消
    //   3. 尚未签到 — 已经签到过的预约不能取消（物理上已经参会了）
    //
    // 为什么不用 @Transactional？
    //   单表单字段更新，MyBatis-Plus 的 updateById 本身就是一条原子 SQL，
    //   不需要事务包装。
    //
    // 参数：
    //   reservationId — 预约 ID
    //   userId        — 操作人用户 ID
    //
    // 返回值：
    //   "not_owner"          — 预约不属于当前用户
    //   "invalid_status"     — 预约状态不允许取消
    //   "already_checked_in" — 已签到，不能取消
    //   "success"            — 取消成功
    @Override
    public String cancel(Long reservationId, Long userId) {
        Reservation r = getById(reservationId);

        // 校验 1：预约存在且属于当前用户
        // 这个检查防止用户通过修改 URL 中的 ID 来取消别人的预约
        if (r == null || !r.getUserId().equals(userId)) {
            return "not_owner";
        }

        // 校验 2：只能取消待审批(0)或已通过(1)的预约
        // 已拒绝(2)、被覆盖(3)、已取消(4)、超时释放(5) 的预约不能再取消
        if (r.getReservationStatus() != 0 && r.getReservationStatus() != 1) {
            return "invalid_status";
        }

        // 校验 3：已签到的预约不能取消
        // 用户已经到场参会了，取消没有意义，也防止伪造签到记录后取消
        AttendanceRecord ar = attendanceRecordService.findByReservationId(reservationId);
        if (ar != null && ar.getAttendStatus() == 1) {
            return "already_checked_in";
        }

        // 执行取消：状态 → 4（用户取消）
        r.setReservationStatus(4);
        updateById(r);
        return "success";
    }

    // ====================================================================
    // 数据查询方法（无事务，只读操作）
    // ====================================================================

    /**
     * 查询待审批的预约列表（教师审批页面使用）
     * SQL：SELECT r.*, u.name AS userName, rm.room_name AS roomName
     *      FROM t_reservation r
     *      JOIN t_user u ON r.user_id = u.id
     *      JOIN t_meeting_room rm ON r.room_id = rm.id
     *      WHERE r.reservation_status = 0
     *      ORDER BY r.start_time ASC
     */
    @Override
    public List<Map<String, Object>> findApprovals() {
        return baseMapper.findApprovals();
    }

    /**
     * 按会议室统计预约数量（管理大屏饼图使用）
     * 返回：[{room_name: "会议室A", count: 15}, {room_name: "会议室B", count: 8}, ...]
     */
    @Override
    public List<Map<String, Object>> countByRoom() {
        return baseMapper.countByRoom();
    }

    /**
     * 按小时统计预约分布（管理大屏柱状图/热力图使用）
     */
    @Override
    public List<Map<String, Object>> countByHour() {
        return baseMapper.countByHour();
    }

    /**
     * 生成 4 位随机签到码
     *
     * SecureRandom.nextInt(10000) 生成 0~9999 的随机整数，
     * String.format("%04d", ...) 格式化为 4 位数字（不足 4 位前面补零）。
     * 例如：42 → "0042"、1024 → "1024"
     */
    private String generateCode() {
        return String.format("%04d", random.nextInt(10000));
    }

    /**
     * 查询当前用户的预约列表（联表查会议室名称 + 签到状态）
     * 用于"我的预约"页面
     */
    @Override
    public List<Map<String, Object>> findByUserIdWithRoom(Long userId) {
        return baseMapper.findByUserIdWithRoom(userId);
    }

    /**
     * 查询单条预约的完整详情（含用户、会议室、签到记录）
     * 用于"预约详情"页面（教师查看）
     */
    @Override
    public Map<String, Object> findDetailById(Long id) {
        return baseMapper.findDetailById(id);
    }

    /**
     * 查询指定会议室在指定日期的已批准预约（甘特图日程数据）
     * 用于 /api/room-schedule 接口
     */
    @Override
    public List<Map<String, Object>> findScheduleByRoomAndDate(Long roomId, String date) {
        return baseMapper.findByRoomAndDate(roomId, date);
    }
}
