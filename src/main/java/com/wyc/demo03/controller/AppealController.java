package com.wyc.demo03.controller;

import com.wyc.demo03.entity.Appeal;
import com.wyc.demo03.service.AppealService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 申诉：提交、查看、复核。
 *
 * <p>注意"申诉不恢复房间单元"这条语义在页面文案里必须说清楚，
 * 否则用户会以为申诉赢了就能拿回房间。
 */
@Controller
public class AppealController {

    private final AppealService appealService;
    private final Clock clock;

    public AppealController(AppealService appealService, Clock clock) {
        this.appealService = appealService;
        this.clock = clock;
    }

    @GetMapping("/appeal")
    public String list(Model model, HttpSession session) {
        Long userId = (Long) session.getAttribute("Id");
        model.addAttribute("appeals", appealService.listMine(userId));
        return "appeal";
    }

    @GetMapping("/appeal/new")
    public String form(@RequestParam(defaultValue = "RESERVATION") String type,
                       @RequestParam Long targetId,
                       Model model) {
        model.addAttribute("type", type);
        model.addAttribute("targetId", targetId);
        return "appeal-new";
    }

    @PostMapping("/appeal/submit")
    public String submit(@RequestParam String type,
                         @RequestParam Long targetId,
                         @RequestParam String reason,
                         @RequestParam(required = false) String evidence,
                         HttpSession session,
                         RedirectAttributes ra) {
        Long userId = (Long) session.getAttribute("Id");
        try {
            Appeal.TargetType targetType = Appeal.TargetType.valueOf(type);
            appealService.submit(userId, targetType, targetId, reason, evidence);
            ra.addFlashAttribute("info", "申诉已提交，我们将在 3 个工作日内给出书面结论");
            ra.addFlashAttribute("infoType", "success");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return fail(ra, ex.getMessage());
        }
        return "redirect:/appeal";
    }

    @GetMapping("/appeal/review")
    public String review(Model model) {
        model.addAttribute("pending", appealService.listPending());
        model.addAttribute("decided", appealService.listDecided());
        model.addAttribute("overdue", appealService.countOverdue(LocalDateTime.now(clock)));
        return "appeal-review";
    }

    @PostMapping("/appeal/decide")
    public String decide(@RequestParam Long id,
                         @RequestParam String status,
                         @RequestParam(required = false) String note,
                         HttpSession session,
                         RedirectAttributes ra) {
        Long reviewerId = (Long) session.getAttribute("Id");
        try {
            appealService.decide(id, reviewerId, Appeal.Status.valueOf(status), note);
            ra.addFlashAttribute("info", "复核结论已记录");
            ra.addFlashAttribute("infoType", "success");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return fail(ra, ex.getMessage());
        }
        return "redirect:/appeal/review";
    }

    private String fail(RedirectAttributes ra, String message) {
        ra.addFlashAttribute("info", message);
        ra.addFlashAttribute("infoType", "danger");
        return "redirect:/appeal";
    }
}
