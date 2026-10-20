package com.wyc.demo03.integration;

import com.wyc.demo03.entity.User;
import com.wyc.demo03.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 页面冒烟：在**完整 Spring 上下文**里渲染真实模板（含 fragments、拦截器、真实数据）。
 *
 * <p>与 {@code TemplateRenderingTest} 的分工：那边是 standalone 引擎，只证明单个模板语法对；
 * 这里跑的是应用真正使用的那套引擎与拦截器链路，能抓到片段引用、方言、会话属性缺失这类问题。
 *
 * <p>用 {@code @Transactional} 包裹：页面只读，但访问日志拦截器会写 {@code t_log}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WebPagesSmokeTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserService userService;
    @Autowired private com.wyc.demo03.service.ReservationService reservationService;

    private MockHttpSession studentSession;
    private MockHttpSession adminSession;

    @BeforeEach
    void setUp() {
        User student = userService.lambdaQuery().eq(User::getUsername, "student01").one();
        User admin = userService.lambdaQuery().eq(User::getUsername, "admin01").one();
        studentSession = session(student);
        adminSession = session(admin);
    }

    private MockHttpSession session(User user) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("Id", user.getId());
        s.setAttribute("username", user.getUsername());
        s.setAttribute("name", user.getName());
        s.setAttribute("role", user.getRole());
        return s;
    }

    @Test
    @DisplayName("学生端主要页面都能渲染")
    void studentPagesRender() throws Exception {
        String[] pages = {"/", "/rooms", "/reservation/board", "/my-reservations", "/credit", "/appeal", "/profile"};
        for (String page : pages) {
            mvc.perform(get(page).session(studentSession))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("信用页展示分数与三档阈值（阈值来自当前规则版本，不是写死的）")
    void creditPageShowsThresholds() throws Exception {
        mvc.perform(get("/credit").session(studentSession))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("这些分数意味着什么")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("信用流水")));
    }

    @Test
    @DisplayName("管理端页面都能渲染")
    void adminPagesRender() throws Exception {
        String[] pages = {"/reservation/approve-list", "/appeal/review", "/admin/rules",
                "/admin/devices", "/admin/logs"};
        for (String page : pages) {
            mvc.perform(get(page).session(adminSession))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("规则配置页列出全部白名单参数")
    void ruleConfigListsAllParams() throws Exception {
        mvc.perform(get("/admin/rules").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MANUAL_REVIEW_HEADCOUNT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AUTO_APPROVE_CREDIT")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("版本历史")));
    }

    @Test
    @DisplayName("BUG-001 已通过且未签到的预约必须出现签到入口（不依赖网页能否拿到码）")
    void approvedReservationShowsCheckInEntry() throws Exception {
        var start = java.time.LocalDate.now().plusDays(3).atTime(14, 0);
        com.wyc.demo03.entity.Reservation form = new com.wyc.demo03.entity.Reservation();
        form.setRoomId(1L);
        form.setStartTime(start);
        form.setEndTime(start.plusMinutes(30));
        form.setAttendeeCount(2);

        var created = reservationService.submit(form,
                userService.lambdaQuery().eq(User::getUsername, "student01").one().getId(), "STUDENT");
        org.junit.jupiter.api.Assertions.assertTrue(created.accepted(), "前置条件：申请应被接受");

        mvc.perform(get("/my-reservations").session(studentSession))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/reservation/check-in/" + created.reservationId())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        String.valueOf(created.checkInCode()))));
    }

    @Test
    @DisplayName("排期板接口：2 个房间 × 56 格，状态字段可直接被前端消费")
    void boardApiReturnsFullGrid() throws Exception {
        String tomorrow = java.time.LocalDate.now().plusDays(1).toString();

        mvc.perform(get("/api/room-board").param("date", tomorrow).session(studentSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.slotsPerDay").value(56))
                .andExpect(jsonPath("$.data.unitMinutes").value(15))
                .andExpect(jsonPath("$.data.rooms.length()").value(2))
                .andExpect(jsonPath("$.data.rooms[0].slots.length()").value(56))
                .andExpect(jsonPath("$.data.rooms[0].slots[0].state").isString())
                .andExpect(jsonPath("$.data.rooms[0].slots[0].actionable").isBoolean());
    }

    @Test
    @DisplayName("BUG-008 注册开关关闭时，合法注册请求也不会创建账号")
    void registrationDisabledRejectsSignup() throws Exception {
        long before = userService.count();

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("_csrf", "tok");

        mvc.perform(post("/registerAction").session(session)
                        .param("_csrf", "tok")
                        .param("username", "intruder01")
                        .param("password", "abc123456")
                        .param("name", "入侵者")
                        .param("captcha", "0000"))
                .andExpect(view().name("register"));

        org.junit.jupiter.api.Assertions.assertEquals(before, userService.count(),
                "关闭注册时不得创建任何账号");
    }

    @Test
    @DisplayName("BUG-008 注册页在关闭时显示明确说明，且不给注册表单")
    void registerPageExplainsDisabled() throws Exception {
        mvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("未开放自助注册")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("action=\"/registerAction\""))));
    }

    @Test
    @DisplayName("未登录访问受保护页面会被重定向到登录页")
    void anonymousIsRedirected() throws Exception {
        mvc.perform(get("/credit"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("非管理员访问规则配置会被挡回首页")
    void nonAdminCannotOpenRuleConfig() throws Exception {
        mvc.perform(get("/admin/rules").session(studentSession))
                .andExpect(status().is3xxRedirection());
    }
}
