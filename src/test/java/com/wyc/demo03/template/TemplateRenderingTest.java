package com.wyc.demo03.template;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板渲染测试：现有 MockMvc 测试使用 InternalResourceViewResolver，不解析 Thymeleaf，
 * 因此消息类型、签到表单显隐等模板改动需要单独验证。
 */
class TemplateRenderingTest {

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static final JakartaServletWebApplication WEB_APPLICATION =
            JakartaServletWebApplication.buildApplication(new MockServletContext());

    private static WebContext context(String role, Map<String, Object> variables) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.getSession().setAttribute("role", role);
        request.getSession().setAttribute("name", "测试用户");
        request.setAttribute("_csrf", "token");
        // Thymeleaf 3.1 起 WebContext 只接受 IWebExchange，不再有 (request, response, servletContext, ...) 构造器
        IWebExchange exchange = WEB_APPLICATION.buildExchange(request, response);
        return new WebContext(exchange, java.util.Locale.SIMPLIFIED_CHINESE, variables);
    }

    private static Map<String, Object> model(Object... pairs) {
        Map<String, Object> variables = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            variables.put((String) pairs[i], pairs[i + 1]);
        }
        return variables;
    }

    @Test
    void roomsAlertUsesErrorStyleForFailuresAndSuccessStyleForSuccess() {
        SpringTemplateEngine engine = engine();
        Map<String, Object> failure = model("info", "该时段已被预约", "infoType", "danger", "rooms", List.of());
        String failed = engine.process("rooms", context("STUDENT", failure));
        assertTrue(failed.contains("alert-danger"), "失败消息必须显示为错误样式");
        assertFalse(failed.contains("alert-success"), "失败消息不能显示为成功样式");

        Map<String, Object> success = model("info", "会议室添加成功", "infoType", "success", "rooms", List.of());
        assertTrue(engine.process("rooms", context("STUDENT", success)).contains("alert-success"));
    }

    @Test
    void roomsWithoutMessageTypeDoesNotCrash() {
        String html = engine().process("rooms", context("STUDENT", model("rooms", List.of())));
        assertFalse(html.contains("alert-danger"));
    }

    @Test
    void approveListRendersFailureAndSuccessStyles() {
        SpringTemplateEngine engine = engine();
        String failed = engine.process("approve-list",
                context("ADMIN", model("info", "该申请已被处理", "infoType", "danger", "approvals", List.of())));
        assertTrue(failed.contains("alert-danger"));
        String approved = engine.process("approve-list",
                context("ADMIN", model("info", "审批通过，签到码：1234", "infoType", "success", "approvals", List.of())));
        assertTrue(approved.contains("alert-success"));
    }

    @Test
    void myReservationsSurfacesCancellationMessageAndRules() {
        Page<Map<String, Object>> page = new Page<>(1, 10);
        String html = engine().process("my-reservations", context("STUDENT",
                model("info", "已签到，无法取消", "infoType", "danger", "reservationPage", page)));
        assertTrue(html.contains("alert-danger"), "取消失败必须有可见提示");
        assertTrue(html.contains("自动释放"), "需说明超时未签到的后果");
    }

    @Test
    void checkInKeepsFormWhileUnfinishedAndHidesItWhenFinished() {
        SpringTemplateEngine engine = engine();
        String retry = engine.process("check-in", context("STUDENT",
                model("reservationId", 42L, "info", "签到码错误！", "infoType", "danger", "checkInFinished", false)));
        assertTrue(retry.contains("check-in-action"), "失败后必须保留可重试的表单");
        assertTrue(retry.contains("value=\"42\""), "重试必须保留预约标识");

        String finished = engine.process("check-in", context("STUDENT",
                model("reservationId", 42L, "info", "签到成功！", "infoType", "success", "checkInFinished", true)));
        assertFalse(finished.contains("check-in-action"), "签到完成后不应再显示重复提交表单");
    }

    @Test
    void reservationPageRendersSelectionMessageAndStatusLegend() {
        String html = engine().process("reservation-new",
                context("STUDENT", model("roomId", 1L, "roomName", "会议室A")));
        assertTrue(html.contains("selectionMessage"), "需要可感知的选择状态提示区域");
        assertTrue(html.contains("待审批占用"), "图例必须说明待审批占用");
        assertTrue(html.contains("管理员预约"), "图例不能把管理员预约并入学生颜色");
    }
}
