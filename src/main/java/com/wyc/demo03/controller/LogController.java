package com.wyc.demo03.controller;

import com.wyc.demo03.common.ApiResponse;
import com.wyc.demo03.service.AttendanceRecordService;
import com.wyc.demo03.service.LogService;
import com.wyc.demo03.service.ReservationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

/**
 * 日志与管理看板控制器 —— 管理员专属
 *
 * 所有路由均受 AdminInterceptor 保护（拦截路径 /admin/** 和 /api/report-*），
 * 非管理员访问会被重定向到首页。与业务实体无 CRUD 操作的日志/统计类页面统一放在这里。
 *
 * 路由分两组：
 *   【页面路由】返回 Thymeleaf 视图
 *     GET /admin/logs      — 系统访问日志列表页
 *     GET /admin/dashboard — 管理数据大屏页面
 *
 *   【数据 API】返回 JSON（前端 ECharts 通过 AJAX 请求）
 *     GET /api/report-room-usage        — 会议室使用频次（饼图）
 *     GET /api/report-busy-hours        — 繁忙时段分布（柱状图）
 *     GET /api/report-attendance-rate   — 签到出勤率（饼图）
 */
@Controller
public class LogController {
    private final LogService logService;
    private final ReservationService reservationService;
    private final AttendanceRecordService attendanceRecordService;

    public LogController(LogService logService, ReservationService reservationService,
                         AttendanceRecordService attendanceRecordService) {
        this.logService = logService;
        this.reservationService = reservationService;
        this.attendanceRecordService = attendanceRecordService;
    }

    // ====================================================================
    // GET /admin/logs —— 系统访问日志列表
    // ====================================================================
    // 数据来源：LogInterceptor.preHandle() 在每次请求时异步写入 t_log 表。
    //
    // 日志记录的内容：
    //   username  — 登录用户名（未登录记为"匿名"）
    //   url       — 请求路径（request.getRequestURI()）
    //   ip        — 客户端 IP（request.getRemoteAddr()）
    //   timestamp — 请求时间（LocalDateTime.now()）
    //
    // 为什么日志在 Interceptor 中记录而不是在 Controller 中？
    //   1. 一处配置，全局生效 → 新增 Controller 不需要额外加日志代码
    //   2. 与业务逻辑解耦 → LogInterceptor 和 LogService 是独立的切面
    //   3. 异步执行 → saveAsync() 在独立线程中写库，不阻塞用户请求
    //
    // 注意：logService.list() 返回全部日志，没有分页。
    //        这在日志量大的情况下需要改进（如添加 PageHelper 分页）。
    @GetMapping("/admin/logs")
    public String logs(Model model) {
        model.addAttribute("logList", logService.list());
        return "logs";
    }

    // ====================================================================
    // GET /admin/dashboard —— 管理数据大屏
    // ====================================================================
    // 纯页面跳转，无业务逻辑。
    // 页面加载后，前端 ECharts 通过三个 AJAX 请求分别获取数据并渲染图表：
    //   /api/report-room-usage      → 饼图（各会议室使用频次）
    //   /api/report-busy-hours      → 柱状图（各时段预约数量）
    //   /api/report-attendance-rate → 饼图（出勤 vs 缺勤）
    @GetMapping("/admin/dashboard")
    public String dashboard() {
        return "dashboard";
    }

    // ====================================================================
    // GET /api/report-room-usage —— 会议室使用频次统计（JSON API）
    // ====================================================================
    // @ResponseBody：返回值直接序列化为 JSON，不经过 Thymeleaf 模板渲染。
    //
    // 返回格式示例：
    //   [{ "roomName": "大会议室", "count": 25 },
    //    { "roomName": "小会议室", "count": 12 }]
    //
    // 底层 SQL（ReservationMapper.countByRoom）：
    //   SELECT rm.room_name AS roomName, COUNT(*) AS count
    //   FROM t_reservation r JOIN t_meeting_room rm ON r.room_id = rm.id
    //   WHERE r.reservation_status = 1
    //   GROUP BY rm.room_name ORDER BY count DESC
    @ResponseBody
    @GetMapping("/api/report-room-usage")
    public ApiResponse<List<Map<String, Object>>> reportRoomUsage() {
        return ApiResponse.ok(reservationService.countByRoom());
    }

    // ====================================================================
    // GET /api/report-busy-hours —— 繁忙时段分布统计（JSON API）
    // ====================================================================
    // 按小时统计已批准预约的数量，用于展示"一天中哪个时段最热门"。
    //
    // 返回格式示例：
    //   [{ "hour": 9, "count": 8 },
    //    { "hour": 14, "count": 15 }]
    //
    // 底层 SQL（ReservationMapper.countByHour）：
    //   SELECT HOUR(r.start_time) AS hour, COUNT(*) AS count
    //   FROM t_reservation r WHERE r.reservation_status = 1
    //   GROUP BY HOUR(r.start_time) ORDER BY hour
    @ResponseBody
    @GetMapping("/api/report-busy-hours")
    public ApiResponse<List<Map<String, Object>>> reportBusyHours() {
        return ApiResponse.ok(reservationService.countByHour());
    }

    // ====================================================================
    // GET /api/report-attendance-rate —— 签到出勤率统计（JSON API）
    // ====================================================================
    // 统计所有已批准的预约中，已签到 vs 未签到的比例。
    //
    // 底层 SQL（AttendanceRecordMapper.countAttendanceRate）：
    //   按 attend_status 分组统计（0=未签到，1=已签到），
    //   前端用 ECharts 饼图渲染。
    @ResponseBody
    @GetMapping("/api/report-attendance-rate")
    public ApiResponse<List<Map<String, Object>>> reportAttendanceRate() {
        return ApiResponse.ok(attendanceRecordService.countAttendanceRate());
    }
}
