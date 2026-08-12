package com.wyc.demo03.controller;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.service.AttendanceRecordService;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.IOException;

/**
 * 主控制器 —— 处理首页、登录、注册、验证码、注销
 *
 * 路由映射关系（Controller ↔ Thymeleaf 模板）：
 *   GET  /, /index           → 返回 "home"  → templates/home.html
 *   GET  /login              → 返回 "login" → templates/login.html
 *   GET  /register           → 返回 "register" → templates/register.html
 *   GET  /verityImg          → 输出图片流，不返回视图
 *   POST /loginAction        → 登录成功重定向 /，失败返回 login 视图
 *   POST /registerAction     → 注册成功跳转 login 视图，失败返回 register 视图
 *   GET  /logout             → 使 session 失效，重定向 /login
 */
@Controller
public class MainController {
    private final UserService userService;
    private final MeetingRoomService meetingRoomService;
    private final AttendanceRecordService attendanceRecordService;
    private final ReservationService reservationService;

    /**
     * 构造器注入（推荐方式，取代 @Autowired 字段注入）
     * 优点：依赖不可变（final）、方便单元测试（可以手动 new 并传入 mock）
     */
    public MainController(UserService userService, MeetingRoomService meetingRoomService,
                          AttendanceRecordService attendanceRecordService,
                          ReservationService reservationService) {
        this.userService = userService;
        this.meetingRoomService = meetingRoomService;
        this.attendanceRecordService = attendanceRecordService;
        this.reservationService = reservationService;
    }

    // ====================================================================
    // GET / 或 /index —— 首页看板
    // ====================================================================
    // 渲染首页，向模型注入四个统计数据，前端用 Bootstrap 卡片展示。
    @GetMapping({"/", "/index"})
    public String home(Model model, HttpSession session) {
        // 从 session 获取当前登录用户 ID（在 loginAction 中写入）
        Long userId = (Long) session.getAttribute("Id");

        model.addAttribute("rooms", meetingRoomService.list());

        // 今日签到人数：AttendanceRecordMapper 执行 COUNT + DATE 过滤
        model.addAttribute("todayCheckIn", attendanceRecordService.countTodayCheckIn());

        // 我的预约数：MyBatis-Plus 的 lambdaQuery 等价于
        //   SELECT COUNT(*) FROM t_reservation WHERE user_id = #{userId}
        model.addAttribute("myReservationCount",
            userId != null ? reservationService.lambdaQuery()
                .eq(Reservation::getUserId, userId).count() : 0);

        // 待审批数：status=0 的预约（所有学生的待审批申请）
        model.addAttribute("pendingCount",
            reservationService.lambdaQuery()
                .eq(Reservation::getReservationStatus, 0).count());

        return "home";
    }

    // ====================================================================
    // GET /login —— 登录页面
    // ====================================================================
    // 页面中的验证码图片通过 <img src="/verityImg"> 触发另一个请求来生成。
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    // ====================================================================
    // GET /register —— 注册页面
    // ====================================================================
    @GetMapping("/register")
    public String register() {
        return "register";
    }

    // ====================================================================
    // GET /verityImg —— 生成图形验证码
    // ====================================================================
    // 使用 Hutool 的 LineCaptcha（线段干扰验证码）生成 200×100 像素的 PNG 图片。
    //   1. 生成随机验证码文本（如 "A3B9"）
    //   2. 把文本存入 session.verityCode（供 loginAction 对比）
    //   3. 把 PNG 图片直接写入 response 输出流（浏览器 <img> 标签自动渲染）
    @GetMapping("/verityImg")
    public void verityImg(HttpServletRequest request, HttpServletResponse response, HttpSession session) throws IOException {
        // 创建 200×100 像素的线段干扰验证码
        LineCaptcha lineCaptcha = CaptchaUtil.createLineCaptcha(200, 100);

        // 验证码文本存入 session，后续登录时比对
        session.setAttribute("verityCode", lineCaptcha.getCode());

        // 直接将 PNG 图片流写入 HTTP 响应，浏览器会把它当作 <img> 的 src 来渲染
        lineCaptcha.write(response.getOutputStream());
    }

    // ====================================================================
    // POST /loginAction —— 处理登录表单提交
    // ====================================================================
    //【第一步】验证码校验 — session 中取 verityCode 与用户输入比对
    //【第二步】用户名密码校验 — 调用 UserServiceImpl.login()
    @PostMapping("/loginAction")
    public String loginAction(String username, String password, String captcha,
                              HttpSession session, HttpServletRequest request, Model model) {
        // ---- 第一步：验证码校验 ----
        // 取出 session 中的验证码文本（由 verityImg() 写入）
        String code = (String) session.getAttribute("verityCode");
        // ★ 立即删除，防止同一个验证码被多次使用（重放攻击）
        session.removeAttribute("verityCode");

        // 验证码为空（session 过期）或不匹配 → 拒绝登录
        if (code == null || !code.equalsIgnoreCase(captcha)) {
            model.addAttribute("info", "验证码错误或已失效！");
            return "login";  // 回到登录页，不创建 session
        }

        // ---- 第二步：用户名密码校验 ----
        // UserServiceImpl.login() 内部调用 BCrypt.checkpw() 比对密码哈希
        User user = userService.login(username, password);

        if (user != null) {
            // ===== 登录成功：防 Session Fixation → 重建 session =====
            // 为什么要重建 session？
            //   攻击者可能预先在浏览器中设置一个已知的 JSESSIONID，
            //   如果服务端复用这个 session，攻击者就可以劫持登录态。
            //   销毁旧 session + 创建新 session = 旧的 JSESSIONID 彻底失效。
            session.invalidate();                          // 销毁旧 session
            HttpSession newSession = request.getSession(true);  // 创建全新 session

            // 向新 session 写入用户信息（后续所有请求都从这里读取）
            newSession.setAttribute("username", user.getUsername());  // RoleInterceptor 认证检查
            newSession.setAttribute("Id", user.getId());              // 预约操作的身份标识
            newSession.setAttribute("name", user.getName());          // header.html 显示用户名
            newSession.setAttribute("role", user.getRole());          // Teacher/AdminInterceptor + 侧边栏
            newSession.setAttribute("email", user.getEmail());        // 预留

            return "redirect:/";  // POST-Redirect-GET 模式，防止刷新页面重复提交表单
        } else {
            // 登录失败：用户名或密码错误（具体是哪个错不透露，防止撞库）
            model.addAttribute("info", "用户名或密码错误！");
            return "login";
        }
    }

    // ====================================================================
    // POST /registerAction —— 处理注册表单提交
    // ====================================================================
    @PostMapping("/registerAction")
    public String registerAction(@Valid User user, BindingResult bindingResult, Model model) {
        // ★ BindingResult 拦截逻辑：
        //   hasErrors() → 说明 @NotBlank 等注解校验未通过（用户留空了必填字段）
        if (bindingResult.hasErrors()) {
            model.addAttribute("info", "请填写完整的注册信息！");
            return "register";  // 回到注册页，保留用户已填的数据（Thymeleaf 自动回填）
        }

        try {
            // UserServiceImpl.register() 内部：
            //   1. BCrypt.hashpw() 哈希密码
            //   2. super.save(user) 写入数据库
            userService.register(user);
        } catch (DuplicateKeyException e) {
            // username 唯一约束冲突 → 用户名已被占用
            model.addAttribute("info", "用户名已存在，请更换！");
            return "register";
        }

        // 注册成功 → 跳到登录页，提示用户登录
        model.addAttribute("info", "注册成功，请登录！");
        return "login";
    }

    // ====================================================================
    // GET /logout —— 注销登录
    // ====================================================================
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }
}
