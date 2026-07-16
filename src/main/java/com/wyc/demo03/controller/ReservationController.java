package com.wyc.demo03.controller;

import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.ReservationService;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 预约控制器 —— 处理预约申请、审批、签到、取消、日程数据 API
 *
 * 本控制器包含系统最核心的业务接口，按功能可分为四组：
 *
 *   【预约组】
 *     GET  /reservation/new              — 可视化预约大屏（甘特图）
 *     POST /reservation/apply            — 提交预约申请（学生→待审批 / 教师→自动通过）
 *     GET  /api/room-schedule            — 返回 JSON 日程数据（供甘特图 AJAX 请求）
 *     GET  /my-reservations              — 我的预约列表
 *
 *   【审批组】（教师专属）
 *     GET  /reservation/approve-list     — 审批管理列表
 *     POST /reservation/approve          — 批准预约（生成签到码）
 *     POST /reservation/reject           — 拒绝预约
 *     GET  /reservation/detail/{id}      — 预约详情
 *
 *   【签到组】
 *     GET  /reservation/check-in/{id}    — 签到页面
 *     POST /reservation/check-in-action  — 执行签到核销
 *
 *   【取消组】
 *     POST /reservation/cancel/{id}      — 取消预约
 */
@Controller
public class ReservationController {
    private final ReservationService reservationService;
    private final MeetingRoomService meetingRoomService;

    public ReservationController(ReservationService reservationService, MeetingRoomService meetingRoomService) {
        this.reservationService = reservationService;
        this.meetingRoomService = meetingRoomService;
    }

    // ====================================================================
    // POST /reservation/apply —— 提交预约申请
    // ====================================================================
    // 学生和教师共用此接口，角色区分由 Service 层（ReservationServiceImpl.apply()）处理。
    //
    // JSR-380 校验（@Valid Reservation）保护哪些字段？
    //   Reservation 实体上的注解：
    //     @NotNull on roomId     → 会议室 ID 不能为空
    //     @NotNull on startTime  → 开始时间不能为空
    //     @NotNull on endTime    → 结束时间不能为空
    //     @Positive on attendeeCount → 参会人数必须 > 0
    //
    // BindingResult 拦截逻辑：
    //   如果前端 JS 校验被绕过（比如直接 curl 发请求），
    //   @Valid 注解在方法参数绑定阶段自动执行实体校验，
    //   校验失败的信息存入 BindingResult → hasErrors() 返回 true。
    //   此时 Controller 拒绝请求，带上错误提示重定向回 /rooms。
    //
    // 使用 RedirectAttributes（而非 Model）的原因：
    //   重定向（redirect:）会导致浏览器发起全新的 GET 请求，
    //   Model 中的属性会丢失。RedirectAttributes.addFlashAttribute()
    //   将数据存入 session 级别的闪存，重定向后自动取出并销毁。
    //
    // 返回值路由：
    //   Service 返回字符串结果码 → switch 匹配 → 设置对应的中文提示
    @PostMapping("/reservation/apply")
    public String apply(@Valid Reservation reservation, BindingResult bindingResult,
                        HttpSession session, RedirectAttributes ra) {
        // ★ BindingResult 拦截：前端表单校验失败 → 拒绝
        if (bindingResult.hasErrors()) {
            ra.addFlashAttribute("info", "请填写完整的预约信息！");
            return "redirect:/rooms";
        }

        // 调用核心业务方法（ReservationServiceImpl.apply()），
        // session 传入是为了从中读取 role 和 userId
        String result = reservationService.apply(reservation, session);

        // 根据 Service 返回的结果码设置对应的用户提示
        switch (result) {
            case "conflict"       -> ra.addFlashAttribute("info", "该时段已被预约，请选择其他时间！");
            case "over_capacity"  -> ra.addFlashAttribute("info", "参会人数超过会议室容量，无法预约！");
            case "room_not_found" -> ra.addFlashAttribute("info", "会议室不存在！");
            case "past_time"      -> ra.addFlashAttribute("info", "不能预约过去的时间！");
            default               -> ra.addFlashAttribute("info", "预约申请提交成功！");
        }
        return "redirect:/rooms";
    }

    // ====================================================================
    // GET /my-reservations —— 我的预约列表
    // ====================================================================
    // 从 session 取当前用户 ID，查询该用户的所有预约（联表查会议室名称 + 签到状态）。
    //
    // 路由不受 TeacherInterceptor 拦截 → 学生和教师都能访问。
    @GetMapping("/my-reservations")
    public String myReservations(Model model, HttpSession session) {
        Long userId = (Long) session.getAttribute("Id");
        // findByUserIdWithRoom：JOIN t_meeting_room + LEFT JOIN t_attendance_record
        model.addAttribute("reservations", reservationService.findByUserIdWithRoom(userId));
        return "my-reservations";
    }

    // ====================================================================
    // GET /reservation/approve-list —— 待审批列表（教师专属）
    // ====================================================================
    // 路由受 TeacherInterceptor 保护 → 学生访问会被重定向到首页。
    // 查询所有 status=0（待审批）的预约，联表展示申请人姓名和会议室名称。
    @GetMapping("/reservation/approve-list")
    public String approveList(Model model) {
        model.addAttribute("approvals", reservationService.findApprovals());
        return "approve-list";
    }

    // ====================================================================
    // POST /reservation/approve —— 批准预约（教师专属）
    // ====================================================================
    // 点击"批准"按钮后调用。
    //
    // 参数 id 来自表单隐藏字段 <input type="hidden" name="id" th:value="${r.id}">
    // 不需要 @RequestParam，因为 Spring MVC 会自动绑定同名表单字段。
    //
    // Service 返回签到码 → Controller 拼入提示信息展示给教师。
    // 失败时返回 "error" → 显示"审批失败"（通常因为该预约已被其他人审批/取消）。
    @PostMapping("/reservation/approve")
    public String approve(Long id, RedirectAttributes ra) {
        String code = reservationService.approve(id);
        if ("error".equals(code)) {
            ra.addFlashAttribute("info", "审批失败！");
        } else {
            // 签到码展示给教师，教师需要告知学生（或投屏展示）
            ra.addFlashAttribute("info", "审批通过，签到码：" + code);
        }
        return "redirect:/reservation/approve-list";
    }

    // ====================================================================
    // POST /reservation/reject —— 拒绝预约（教师专属）
    // ====================================================================
    // Service 层有防御：只有 status=0 的预约才能被拒绝，
    // 已通过/已拒绝/被覆盖/已取消的预约即使点了拒绝也会被静默忽略。
    @PostMapping("/reservation/reject")
    public String reject(Long id, RedirectAttributes ra) {
        reservationService.reject(id);
        ra.addFlashAttribute("info", "已拒绝该预约申请");
        return "redirect:/reservation/approve-list";
    }

    // ====================================================================
    // GET /reservation/new?roomId=X —— 可视化预约大屏（甘特图）
    // ====================================================================
    // 这是预约功能的"门面"—— 进入甘特图页面前的最后一道防线。
    //
    // 两层防御：
    //   第一层：room == null → 会议室不存在（通常因为手动改 URL 的 roomId）
    //   第二层：room.getRoomStatus() == 1 → 会议室维护中，禁止进入预约页
    //
    // roomStatus 的含义：
    //   0 = 正常（可以预约）
    //   1 = 维护中（不可预约，前端 rooms.html 中按钮已置灰，这里做后端兜底）
    //
    // 为什么 roomName 要传到 model？
    //   reservation-new.html 中会议室名是只读的文本展示（不是下拉框），
    //   需要后端把名称传过来直接渲染。同时防止用户通过改 URL 的 roomId
    //   来预约一个不存在的会议室。
    @GetMapping("/reservation/new")
    public String newReservation(@RequestParam Long roomId, Model model, RedirectAttributes ra) {
        MeetingRoom room = meetingRoomService.getById(roomId);
        if (room == null) {
            ra.addFlashAttribute("info", "会议室不存在！");
            return "redirect:/rooms";
        }
        // 维护状态检查：如果会议室标记为"维护中"，禁止进入预约页
        if (room.getRoomStatus() == 1) {
            ra.addFlashAttribute("info", "该会议室正在维护中，暂无法预约！");
            return "redirect:/rooms";
        }
        model.addAttribute("roomId", roomId);
        model.addAttribute("roomName", room.getRoomName());
        return "reservation-new";
    }

    // ====================================================================
    // GET /api/room-schedule —— 日程数据 API（返回 JSON）
    // ====================================================================
    // 这是前端甘特图的数据源，被 reservation-new.js 的 loadSchedule() 通过 axios 调用。
    //
    // @ResponseBody 的作用：
    //   告诉 Spring MVC 不要去找 Thymeleaf 模板，而是把返回值（List<Map>）
    //   直接通过 Jackson 序列化为 JSON 写入 HTTP 响应体。
    //
    // 返回格式示例：
    //   [
    //     { "start_time": "2026-06-09T09:00:00", "end_time": "2026-06-09T10:00:00",
    //       "userName": "张三", "role": "STUDENT" },
    //     { "start_time": "2026-06-09T14:00:00", "end_time": "2026-06-09T15:30:00",
    //       "userName": "李老师", "role": "TEACHER" }
    //   ]
    //
    // 注意：只返回 status=1（已批准）的预约。待审批(status=0)的预约不在甘特图上显示。
    @ResponseBody
    @GetMapping("/api/room-schedule")
    public List<Map<String, Object>> roomSchedule(@RequestParam Long roomId, @RequestParam String date) {
        return reservationService.findScheduleByRoomAndDate(roomId, date);
    }

    // ====================================================================
    // GET /reservation/check-in/{id} —— 签到页面入口
    // ====================================================================
    // {id} 是路径变量（@PathVariable），来自 URL 路径 /reservation/check-in/42
    //
    // 这个接口只负责渲染签到页面，不校验任何权限。
    // 实际的校验（预约归属、时间窗口、签到码）在 POST checkInAction 中完成。
    // 这是"先渲染，提交时再校验"的设计 —— 即使恶意用户访问了别人的签到页，
    // 他不知道签到码也无法完成签到。
    @GetMapping("/reservation/check-in/{id}")
    public String checkIn(@PathVariable Long id, Model model) {
        model.addAttribute("reservationId", id);
        return "check-in";
    }

    // ====================================================================
    // POST /reservation/cancel/{id} —— 取消预约
    // ====================================================================
    // {id} 是路径变量，来自 RESTful 风格的 URL /reservation/cancel/42
    //
    // 安全设计：
    //   从 session 获取当前登录用户的 userId，
    //   传给 Service 层的 cancel(reservationId, userId)，
    //   Service 内部校验 reservation.userId == userId → 确保只能取消自己的预约。
    //   这与 checkIn 的设计一致：身份来自 session，不可由前端篡改。
    //
    // NOT 使用 @Valid + BindingResult，因为这里不接收实体对象，
    // 只有 id 和 session 数据，无需 Bean Validation。
    @PostMapping("/reservation/cancel/{id}")
    public String cancel(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        // 从 session 获取用户 ID（不可篡改的身份来源）
        Long userId = (Long) session.getAttribute("Id");

        // 调用 Service 执行取消逻辑，返回结果码
        String result = reservationService.cancel(id, userId);

        // 根据结果码给出对应的中文提示
        switch (result) {
            case "success"            -> ra.addFlashAttribute("info", "预约已取消");
            case "not_owner"          -> ra.addFlashAttribute("info", "无权取消该预约");
            case "already_checked_in" -> ra.addFlashAttribute("info", "已签到，无法取消");
            case "invalid_status"     -> ra.addFlashAttribute("info", "该预约当前状态无法取消");
            default                   -> ra.addFlashAttribute("info", "取消失败");
        }
        return "redirect:/my-reservations";
    }

    // ====================================================================
    // GET /reservation/detail/{id} —— 预约详情查看（教师专属）
    // ====================================================================
    // 路由受 TeacherInterceptor 保护。
    // 联表查询预约、用户、会议室、签到记录的完整信息。
    // 如果预约不存在（detail==null），重定向回审批列表。
    @GetMapping("/reservation/detail/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Map<String, Object> detail = reservationService.findDetailById(id);
        if (detail == null) {
            return "redirect:/reservation/approve-list";
        }
        model.addAttribute("detail", detail);
        return "reservation-detail";
    }

    // ====================================================================
    // POST /reservation/check-in-action —— 执行签到核销
    // ====================================================================
    // 这是签到流程的"最后一公里"，从签到页提交签到码后调用。
    //
    // 参数来源：
    //   reservationId — 签到页隐藏字段 <input type="hidden" name="reservationId">
    //   code          — 用户输入的 4 位签到码
    //   session       — 获取当前用户 ID（不可篡改的身份来源）
    //   request       — 获取客户端 IP（request.getRemoteAddr()），用于审计溯源
    //
    // 为什么 userId 从 session 取而不是从表单提交？
    //   防止恶意用户修改表单中的 userId 字段，以他人身份签到。
    //   session 中的 Id 是登录时服务端写入的，前端无法篡改。
    //
    // NOT 使用 RedirectAttributes 而是 Model：
    //   签到失败时不重定向，而是返回同一个 check-in 页面并显示错误提示，
    //   这样用户可以修改签到码后重新提交，不用重新进入签到页。
    //
    // Service 返回结果码含义：
    //   "success"            — 签到成功（attendStatus 0→1）
    //   "wrong_code"         — 签到码不匹配
    //   "too_early"          — 早于窗口开启时间（startTime - 10min 之前）
    //   "expired"            — 晚于窗口关闭时间（startTime + 15min 之后）
    //   "already_checked_in" — 已签到（防止重复签到）
    @PostMapping("/reservation/check-in-action")
    public String checkInAction(Long reservationId, String code,
                                 HttpServletRequest request, HttpSession session, Model model) {
        // 从 session 获取当前用户 ID（不可篡改）
        Long userId = (Long) session.getAttribute("Id");

        // 获取客户端 IP，存入签到记录用于审计
        String ip = request.getRemoteAddr();

        // 调用 Service 的五步校验链路：
        //   预约有效性 → 时间窗口 → 签到码比对 → 签到记录校验 → 防重复
        String result = reservationService.checkIn(reservationId, userId, code, ip);

        // 根据结果码返回对应的中文提示，失败时不重定向（用户在同一页面看到提示后可以重试）
        switch (result) {
            case "success"            -> model.addAttribute("info", "签到成功！");
            case "wrong_code"         -> model.addAttribute("info", "签到码错误！");
            case "too_early"          -> model.addAttribute("info",
                                            "尚未到签到时间（会议开始前10分钟开放）");
            case "expired"            -> model.addAttribute("info",
                                            "签到已过期（仅限会议开始前10分钟至开始后15分钟内签到）");
            case "already_checked_in" -> model.addAttribute("info", "已签到，无需重复签到");
            default                   -> model.addAttribute("info", "签到失败！");
        }
        return "check-in";  // 返回签到页（而非重定向），保留 reservationId 以便用户重试
    }
}
