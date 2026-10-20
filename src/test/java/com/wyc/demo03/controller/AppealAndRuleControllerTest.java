package com.wyc.demo03.controller;

import com.wyc.demo03.entity.CreditAccount;
import com.wyc.demo03.entity.RuleVersion;
import com.wyc.demo03.service.AppealService;
import com.wyc.demo03.service.CreditService;
import com.wyc.demo03.service.RuleConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * P3 新增页面与接口的控制器测试。
 *
 * <p>模板渲染由 {@link com.wyc.demo03.template.TemplateRenderingTest} 覆盖，
 * 这里只验证路由、视图名、参数透传与失败分支的用户提示——两者合起来才算"页面能打开"。
 */
@ExtendWith(MockitoExtension.class)
class AppealAndRuleControllerTest {

    @Mock private CreditService creditService;
    @Mock private RuleConfigService ruleConfigService;
    @Mock private AppealService appealService;

    private MockMvc mvc;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-10-20T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @BeforeEach
    void setUp() {
        InternalResourceViewResolver resolver = new InternalResourceViewResolver("/WEB-INF/views/", ".html");

        // 三个控制器挂在同一个 MockMvc 上：分别 standaloneSetup 会各建一个 ServletContext，
        // MockHttpSession 跨上下文使用时 Spring 会报 "Cannot reinitialize with different application context"
        mvc = MockMvcBuilders.standaloneSetup(
                        new CreditController(creditService, ruleConfigService),
                        new AppealController(appealService, clock),
                        new RuleConfigController(ruleConfigService))
                .setViewResolvers(resolver)
                .build();
    }

    // ==================== 信用页 ====================

    @Test
    void creditPageLoads() throws Exception {
        CreditAccount account = new CreditAccount();
        account.setUserId(7L);
        account.setScore(88);
        account.setUpdatedAt(LocalDateTime.now());
        when(creditService.accountOf(7L)).thenReturn(account);
        when(creditService.recentLogs(7L, 50)).thenReturn(List.of());
        when(ruleConfigService.currentParams())
                .thenReturn(com.wyc.demo03.domain.RuleParams.defaults());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 7L);

        mvc.perform(get("/credit").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("credit"));
    }

    // ==================== 申诉 ====================

    @Test
    void appealListLoads() throws Exception {
        when(appealService.listMine(7L)).thenReturn(List.of());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 7L);

        mvc.perform(get("/appeal").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("appeal"));
    }

    @Test
    void appealFormPrefillsTarget() throws Exception {
        mvc.perform(get("/appeal/new").param("type", "CREDIT").param("targetId", "12"))
                .andExpect(status().isOk())
                .andExpect(view().name("appeal-new"));
    }

    @Test
    @DisplayName("提交成功回到列表并提示 3 个工作日")
    void submitRedirectsWithDueHint() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 7L);

        mvc.perform(post("/appeal/submit").session(session)
                        .param("type", "CREDIT").param("targetId", "12").param("reason", "签到记录有误"))
                .andExpect(redirectedUrl("/appeal"))
                .andExpect(flash().attribute("infoType", "success"));
    }

    @Test
    @DisplayName("提交失败（超期/重复）回到列表并给出原因")
    void submitFailureShowsReason() throws Exception {
        when(appealService.submit(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("已超过 7 日申诉期限"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 7L);

        mvc.perform(post("/appeal/submit").session(session)
                        .param("type", "CREDIT").param("targetId", "12").param("reason", "x"))
                .andExpect(flash().attribute("info", "已超过 7 日申诉期限"))
                .andExpect(flash().attribute("infoType", "danger"));
    }

    @Test
    void reviewPageLoads() throws Exception {
        when(appealService.listPending()).thenReturn(List.of());
        when(appealService.listDecided()).thenReturn(List.of());

        mvc.perform(get("/appeal/review"))
                .andExpect(status().isOk())
                .andExpect(view().name("appeal-review"));
    }

    @Test
    @DisplayName("自审被拦时提示原因，不静默成功")
    void selfReviewIsReported() throws Exception {
        when(appealService.decide(eq(1L), any(), any(), any()))
                .thenThrow(new IllegalStateException("复核人不得是原审核人，请换人处理"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 9L);

        mvc.perform(post("/appeal/decide").session(session)
                        .param("id", "1").param("status", "REVOKED").param("note", "改判"))
                .andExpect(flash().attribute("infoType", "danger"));
    }

    // ==================== 规则配置 ====================

    @Test
    void rulePageLoads() throws Exception {
        when(ruleConfigService.currentParamsView()).thenReturn(List.of());
        when(ruleConfigService.history()).thenReturn(List.of());

        mvc.perform(get("/admin/rules"))
                .andExpect(status().isOk())
                .andExpect(view().name("rule-config"));
    }

    @Test
    @DisplayName("发布成功提示版本号；_csrf 等非参数键不会被当成规则参数")
    void publishFiltersNonParamKeys() throws Exception {
        RuleVersion version = new RuleVersion();
        version.setVersionNo(3);
        version.setEffectiveFrom(LocalDateTime.now());
        when(ruleConfigService.publish(anyMap(), eq(9L), eq("调低阈值"))).thenReturn(version);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 9L);

        mvc.perform(post("/admin/rules/publish").session(session)
                        .param("AUTO_APPROVE_CREDIT", "95")
                        .param("note", "调低阈值")
                        .param("_csrf", "token"))
                .andExpect(redirectedUrl("/admin/rules"))
                .andExpect(flash().attribute("info", "已发布规则版本 v3"));

        verify(ruleConfigService).publish(
                eq(Map.of("AUTO_APPROVE_CREDIT", "95")), eq(9L), eq("调低阈值"));
    }

    @Test
    @DisplayName("发布失败（越界/倒置）把原因带回页面")
    void publishFailureShowsReason() throws Exception {
        when(ruleConfigService.publish(anyMap(), any(), any()))
                .thenThrow(new IllegalArgumentException("自动通过线 超出允许范围（0–100 分），当前值：999"));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 9L);

        mvc.perform(post("/admin/rules/publish").session(session)
                        .param("AUTO_APPROVE_CREDIT", "999"))
                .andExpect(flash().attribute("infoType", "danger"));
    }
}
