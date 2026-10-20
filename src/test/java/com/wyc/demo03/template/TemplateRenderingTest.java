package com.wyc.demo03.template;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wyc.demo03.entity.CreditAccount;
import com.wyc.demo03.entity.CreditLog;
import com.wyc.demo03.entity.RuleVersion;
import com.wyc.demo03.service.RuleConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("排期板渲染：图例、网格容器、底部操作栏都在")
    void boardRendersShellAndLegend() {
        String html = engine().process("board", context("STUDENT",
                model("date", "2026-10-21", "today", false,
                        "prevDate", "2026-10-20", "nextDate", "2026-10-22")));

        assertTrue(html.contains("data-board"), "需要给前端一个挂载点");
        assertTrue(html.contains("id=\"boardGrid\""), "网格容器必须存在");
        assertTrue(html.contains("可预约"), "图例必须说明可预约颜色");
        assertTrue(html.contains("有人待审批"), "图例必须区分「有人待审」，否则用户以为选不了");
        assertTrue(html.contains("我的预约"), "我的预约要单独一个颜色");
        assertTrue(html.contains("提交预约"), "底部操作栏必须可直接提交");
        assertTrue(html.contains("board.js"), "排期逻辑在独立脚本里");
    }

    // ==================== P3 新增页面 ====================

    @Test
    @DisplayName("信用页：分数、三档阈值说明、流水行都要渲染出来")
    void creditPageRendersScoreAndRows() {
        CreditAccount account = new CreditAccount();
        account.setUserId(7L);
        account.setScore(88);
        account.setUpdatedAt(LocalDateTime.of(2026, 10, 20, 9, 30));

        Map<String, Object> row = new HashMap<>();
        row.put("created_at", LocalDateTime.of(2026, 10, 19, 14, 0));
        row.put("reason_code", "NO_SHOW");
        row.put("delta", -10);
        row.put("operator", "SYSTEM");
        row.put("note", "签到截止未签到");
        row.put("roomName", "研讨室A");

        String html = engine().process("credit", context("STUDENT", model(
                "account", account, "logs", List.of(row), "reasons", CreditLog.Reason.values(),
                "autoCredit", 90, "midCredit", 70, "baseCredit", 60, "cleanAward", 5)));

        assertTrue(html.contains("88"), "分数必须显示");
        assertTrue(html.contains("90") && html.contains("70") && html.contains("60"),
                "三档阈值来自当前规则版本，不能写死");
        assertTrue(html.contains("NO_SHOW"), "流水原因要显示");
        assertTrue(html.contains("-10"), "扣分数值要显示");
        assertFalse(html.contains("${"), "不能残留未解析的表达式");
    }

    @Test
    @DisplayName("申诉列表：空表时给引导文案，不报错")
    void appealListRendersEmptyState() {
        String html = engine().process("appeal", context("STUDENT", model("appeals", List.of())));
        assertTrue(html.contains("还没有申诉记录"));
        assertTrue(html.contains("不会把已经释放的时段"), "必须说清申诉不恢复房间");
    }

    @Test
    @DisplayName("申诉复核台：待办与已办结两张表都能渲染")
    void appealReviewRendersBothTables() {
        Map<String, Object> pending = new HashMap<>();
        pending.put("id", 1L);
        pending.put("target_type", "RESERVATION");
        pending.put("target_id", 42L);
        pending.put("reason", "时段其实是空的");
        pending.put("original_decision", "MANUAL_REJECTED");
        pending.put("created_at", LocalDateTime.of(2026, 10, 20, 9, 0));
        pending.put("due_at", LocalDateTime.of(2026, 10, 23, 9, 0));
        pending.put("applicantName", "学生一");

        String html = engine().process("appeal-review", context("ADMIN", model(
                "pending", List.of(pending), "decided", List.of(), "overdue", 1)));

        assertTrue(html.contains("学生一"));
        assertTrue(html.contains("申诉成立"), "结论下拉必须包含三种终态");
        assertTrue(html.contains("已超过应办结时间"));
    }

    @Test
    @DisplayName("规则配置页：8 个参数按白名单渲染，且带范围提示")
    void ruleConfigPageRendersWhitelist() {
        var params = java.util.Arrays.stream(com.wyc.demo03.domain.RuleParamKey.values())
                .map(k -> new RuleConfigService.ParamView(k.name(), k.label(), k.unit(),
                        k.min(), k.max(), k.defaultValue(), k.defaultValue(), k.description()))
                .toList();

        RuleVersion version = new RuleVersion();
        version.setVersionNo(1);
        version.setEffectiveFrom(LocalDateTime.of(2026, 10, 20, 9, 0));

        String html = engine().process("rule-config", context("ADMIN", model(
                "params", params, "version", version, "history", List.of())));

        assertEquals(com.wyc.demo03.domain.RuleParamKey.values().length,
                countOccurrences(html, "name=\"AUTO_APPROVE_CREDIT\"")
                        + countOccurrences(html, "name=\"MID_CREDIT_MIN\"")
                        + countOccurrences(html, "name=\"MID_MAX_DURATION_MINUTES\"")
                        + countOccurrences(html, "name=\"AUTO_APPROVE_DAILY_LIMIT\"")
                        + countOccurrences(html, "name=\"MAX_CONTINUOUS_MINUTES\"")
                        + countOccurrences(html, "name=\"MANUAL_REVIEW_HEADCOUNT\"")
                        + countOccurrences(html, "name=\"MANUAL_REVIEW_DUE_HOURS\"")
                        + countOccurrences(html, "name=\"BASE_RULE_MIN_CREDIT\""));
        assertTrue(html.contains("基础规则"), "必须说明基础规则只读");
        assertTrue(html.contains("新版本"), "必须说明每次保存会生成新版本");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = text.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = text.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
