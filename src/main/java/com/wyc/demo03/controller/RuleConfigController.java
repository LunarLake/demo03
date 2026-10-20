package com.wyc.demo03.controller;

import com.wyc.demo03.service.RuleConfigService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

/**
 * 附加规则配置（管理员）。
 *
 * <p>只开放 {@link com.wyc.demo03.domain.RuleParamKey} 里的 8 个数值参数，
 * 不能写表达式 —— 参数化才能穷举测试。
 * 每次保存生成**新版本**，历史判定不受影响。
 */
@Controller
public class RuleConfigController {

    private final RuleConfigService ruleConfigService;

    public RuleConfigController(RuleConfigService ruleConfigService) {
        this.ruleConfigService = ruleConfigService;
    }

    @GetMapping("/admin/rules")
    public String page(Model model) {
        model.addAttribute("params", ruleConfigService.currentParamsView());
        model.addAttribute("version", ruleConfigService.currentVersion());
        model.addAttribute("history", ruleConfigService.history());
        return "rule-config";
    }

    @PostMapping("/admin/rules/publish")
    public String publish(@RequestParam Map<String, String> form,
                          @RequestParam(required = false) String note,
                          HttpSession session,
                          RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute("Id");
        // 表单里还带着 _csrf 等非参数键，交给服务层用白名单过滤前先剔除
        Map<String, String> submitted = new HashMap<>();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!entry.getKey().startsWith("_")) {
                submitted.put(entry.getKey(), entry.getValue());
            }
        }
        submitted.remove("note");
        try {
            var version = ruleConfigService.publish(submitted, adminId, note);
            ra.addFlashAttribute("info", "已发布规则版本 v" + version.getVersionNo());
            ra.addFlashAttribute("infoType", "success");
        } catch (IllegalArgumentException ex) {
            ra.addFlashAttribute("info", "发布失败：" + ex.getMessage());
            ra.addFlashAttribute("infoType", "danger");
        }
        return "redirect:/admin/rules";
    }
}
