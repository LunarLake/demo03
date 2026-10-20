package com.wyc.demo03.controller;

import com.wyc.demo03.common.ApiResponse;
import com.wyc.demo03.common.SlotConflictException;
import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.ReservationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 预约控制器：申请 / 审批 / 签到 / 取消 / 日程数据。
 *
 * <p>v2 起失败原因不再用一串 switch 映射成硬编码文案 —— 规则引擎与时段校验
 * 已经给出面向用户的中文说明（{@code SubmitResult.message}），控制器直接展示，
 * 避免同一类错误在 Service 与 Controller 各写一套措辞。
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
    // 提交申请
    // ====================================================================
    @PostMapping("/reservation/apply")
    public String apply(@Valid Reservation reservation, BindingResult bindingResult,
                        HttpSession session, RedirectAttributes ra) {
        if (bindingResult.hasErrors()) {
            return fail(ra, "请填写完整的预约信息！", "/rooms");
        }

        Long userId = (Long) session.getAttribute("Id");
        String role = (String) session.getAttribute("role");

        ReservationService.SubmitResult result = reservationService.submit(reservation, userId, role);
        if (!result.accepted()) {
            return fail(ra, result.message(), "/rooms");
        }

        ra.addFlashAttribute("infoType", "success");
        if (result.approvedImmediately()) {
            String info = result.checkInCode() == null
                    ? "预约已生效"
                    : "预约已生效，签到码 " + result.checkInCode() + "，请在开始后 5 分钟内到现场屏幕输入";
            ra.addFlashAttribute("info", info);
        } else {
            ra.addFlashAttribute("info", "申请已提交（" + result.message() + "），等待管理员审核");
        }
        return "redirect:/my-reservations";
    }

    // ====================================================================
    // 我的预约
    // ====================================================================
    @GetMapping("/my-reservations")
    public String myReservations(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size,
                                 @RequestParam(required = false) Integer status,
                                 Model model, HttpSession session) {
        Long userId = (Long) session.getAttribute("Id");
        model.addAttribute("reservationPage", reservationService.findByUserIdWithRoomPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size),
                userId, status));
        model.addAttribute("status", status);
        return "my-reservations";
    }

    // ====================================================================
    // 审批（管理员）
    // ====================================================================
    @GetMapping("/reservation/approve-list")
    public String approveList(Model model) {
        model.addAttribute("approvals", reservationService.findApprovals());
        return "approve-list";
    }

    @PostMapping("/reservation/approve")
    public String approve(Long id, HttpSession session, RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute("Id");
        try {
            ReservationService.ApproveResult result = reservationService.approve(id, adminId);
            if (!result.ok()) {
                return fail(ra, result.message(), "/reservation/approve-list");
            }
            ra.addFlashAttribute("info", "审批通过，申请人签到码：" + result.checkInCode());
            ra.addFlashAttribute("infoType", "success");
        } catch (SlotConflictException e) {
            // 状态变更已随事务一起回滚，申请仍是待审批
            return fail(ra, "该时段已被其他预约占用，本次批准未生效，请让申请人重选时段",
                    "/reservation/approve-list");
        }
        return "redirect:/reservation/approve-list";
    }

    @PostMapping("/reservation/reject")
    public String reject(Long id, String reason, HttpSession session, RedirectAttributes ra) {
        // 长度前置校验：不必等进入事务再抛，页面能立刻拿到提示
        if (reason != null && reason.length() > 200) {
            return fail(ra, "拒绝原因不能超过200字", "/reservation/approve-list");
        }
        Long adminId = (Long) session.getAttribute("Id");
        try {
            reservationService.reject(id, reason, adminId);
            ra.addFlashAttribute("info", "已拒绝该预约申请");
            ra.addFlashAttribute("infoType", "success");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return fail(ra, ex.getMessage(), "/reservation/approve-list");
        }
        return "redirect:/reservation/approve-list";
    }

    // ====================================================================
    // 预约页与日程数据
    // ====================================================================
    @GetMapping("/reservation/new")
    public String newReservation(@RequestParam Long roomId, Model model, RedirectAttributes ra) {
        MeetingRoom room = meetingRoomService.getById(roomId);
        if (room == null) {
            return fail(ra, "房间不存在！", "/rooms");
        }
        if (Integer.valueOf(1).equals(room.getRoomStatus())) {
            return fail(ra, "该房间正在维护中，暂无法预约！", "/rooms");
        }
        model.addAttribute("roomId", roomId);
        model.addAttribute("roomName", room.getRoomName());
        return "reservation-new";
    }

    /**
     * 排期数据。{@code date} 用字符串接收再解析，避免非法日期直接抛 400。
     */
    @ResponseBody
    @GetMapping("/api/room-schedule")
    public ApiResponse<List<Map<String, Object>>> roomSchedule(@RequestParam Long roomId,
                                                               @RequestParam String date) {
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (RuntimeException e) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(reservationService.findScheduleByRoomAndDate(roomId, day));
    }

    // ====================================================================
    // 签到
    // ====================================================================
    @GetMapping("/reservation/check-in/{id}")
    public String checkIn(@PathVariable Long id, Model model) {
        model.addAttribute("reservationId", id);
        return "check-in";
    }

    @PostMapping("/reservation/check-in-action")
    public String checkInAction(Long reservationId, String code,
                                HttpServletRequest request, HttpSession session, Model model) {
        Long userId = (Long) session.getAttribute("Id");
        String result = reservationService.checkIn(reservationId, userId, code, request.getRemoteAddr());

        model.addAttribute("reservationId", reservationId);
        boolean finished = "success".equals(result) || "already_checked_in".equals(result)
                || "expired".equals(result) || "too_many_attempts".equals(result) || "error".equals(result);
        model.addAttribute("checkInFinished", finished);
        model.addAttribute("infoType", "success".equals(result) ? "success" : "danger");
        model.addAttribute("info", switch (result) {
            case "success" -> "签到成功！门禁授权已下发。";
            case "wrong_code" -> "签到码错误！";
            case "too_many_attempts" -> "签到码错误次数过多，本次签到已锁定，请联系管理员！";
            case "too_early" -> "尚未到签到时间（签到从预约开始时刻起开放）。";
            case "expired" -> "签到已截止（须在开始后 5 分钟内完成），本次预约已按爽约处理。";
            case "already_checked_in" -> "已签到，无需重复签到。";
            default -> "签到失败！";
        });
        return "check-in";
    }

    // ====================================================================
    // 取消 / 详情
    // ====================================================================
    @PostMapping("/reservation/cancel/{id}")
    public String cancel(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        Long userId = (Long) session.getAttribute("Id");
        String result = reservationService.cancel(id, userId);

        ra.addFlashAttribute("infoType", "success".equals(result) ? "success" : "danger");
        ra.addFlashAttribute("info", switch (result) {
            case "success" -> "预约已取消";
            case "not_owner" -> "无权取消该预约";
            case "already_started" -> "预约已开始，无法取消；未签到将按爽约处理";
            case "invalid_status" -> "该预约当前状态无法取消";
            case "status_changed" -> "预约状态已变化，请刷新后重试";
            default -> "取消失败";
        });
        return "redirect:/my-reservations";
    }

    @GetMapping("/reservation/detail/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Map<String, Object> detail = reservationService.findDetailById(id);
        if (detail == null) {
            return "redirect:/reservation/approve-list";
        }
        model.addAttribute("detail", detail);
        return "reservation-detail";
    }

    private String fail(RedirectAttributes ra, String message, String location) {
        ra.addFlashAttribute("info", message);
        ra.addFlashAttribute("infoType", "danger");
        return "redirect:" + location;
    }
}
