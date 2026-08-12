package com.wyc.demo03.controller;

import com.wyc.demo03.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 个人中心控制器 —— 查看/更新个人资料、修改密码。
 * 全部路由受 RoleInterceptor 保护（需登录）。
 */
@Controller
public class ProfileController {

    private final UserService userService;

    public ProfileController(UserService userService) {
        this.userService = userService;
    }

    // ====================================================================
    // GET /profile —— 个人中心页面
    // ====================================================================
    @GetMapping("/profile")
    public String profile(Model model, HttpSession session) {
        // 从 session 展示登录快照；修改成功后由下方 POST 同步更新
        model.addAttribute("username", session.getAttribute("username"));
        model.addAttribute("name", session.getAttribute("name"));
        model.addAttribute("email", session.getAttribute("email"));
        return "profile";
    }

    // ====================================================================
    // POST /profile/update —— 更新姓名/邮箱
    // ====================================================================
    // 更新成功后同步刷新 session 中的 name/email，
    // 顶栏用户名立即生效，无需重新登录。
    @PostMapping("/profile/update")
    public String updateProfile(@RequestParam String name,
                                @RequestParam(required = false) String email,
                                HttpSession session, RedirectAttributes ra) {
        Long userId = (Long) session.getAttribute("Id");
        if (name == null || name.isBlank()) {
            ra.addFlashAttribute("info", "姓名不能为空！");
            return "redirect:/profile";
        }
        String result = userService.updateProfile(userId, name.trim(),
                email == null ? "" : email.trim());
        if ("success".equals(result)) {
            // 同步 session 快照，顶栏立即显示新姓名
            session.setAttribute("name", name.trim());
            session.setAttribute("email", email == null ? "" : email.trim());
            ra.addFlashAttribute("info", "个人资料已更新");
        } else {
            ra.addFlashAttribute("info", "更新失败，请重新登录后再试！");
        }
        return "redirect:/profile";
    }

    // ====================================================================
    // POST /profile/password —— 修改密码
    // ====================================================================
    // 校验：原密码正确 + 新密码长度 >= 6 + 两次输入一致
    @PostMapping("/profile/password")
    public String changePassword(@RequestParam String oldPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 HttpSession session, RedirectAttributes ra) {
        Long userId = (Long) session.getAttribute("Id");

        if (newPassword == null || newPassword.length() < 6) {
            ra.addFlashAttribute("info", "新密码长度至少 6 位！");
            return "redirect:/profile";
        }
        if (!newPassword.equals(confirmPassword)) {
            ra.addFlashAttribute("info", "两次输入的新密码不一致！");
            return "redirect:/profile";
        }

        String result = userService.changePassword(userId, oldPassword, newPassword);
        switch (result) {
            case "success" -> ra.addFlashAttribute("info", "密码修改成功");
            case "wrong_password" -> ra.addFlashAttribute("info", "原密码错误！");
            default -> ra.addFlashAttribute("info", "修改失败，请重新登录后再试！");
        }
        return "redirect:/profile";
    }
}
