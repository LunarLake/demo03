package com.wyc.demo03.controller;

import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.entity.CreditAccount;
import com.wyc.demo03.entity.CreditLog;
import com.wyc.demo03.service.CreditService;
import com.wyc.demo03.service.RuleConfigService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 我的信用分。
 *
 * <p>页面同时展示"分数"与"依据"：分区间的三条阈值直接读当前规则版本，
 * 用户能看到"我还差多少分才能自动通过"，而不是只看到一个数字。
 */
@Controller
public class CreditController {

    private final CreditService creditService;
    private final RuleConfigService ruleConfigService;

    public CreditController(CreditService creditService, RuleConfigService ruleConfigService) {
        this.creditService = creditService;
        this.ruleConfigService = ruleConfigService;
    }

    @GetMapping("/credit")
    public String credit(Model model, HttpSession session) {
        Long userId = (Long) session.getAttribute("Id");
        CreditAccount account = creditService.accountOf(userId);
        RuleParams params = ruleConfigService.currentParams();

        model.addAttribute("account", account);
        model.addAttribute("logs", creditService.recentLogs(userId, 50));
        model.addAttribute("reasons", CreditLog.Reason.values());
        // 三个阈值单独传：模板里直接 ${autoCredit} 比穿透 record 属性稳妥
        model.addAttribute("autoCredit", params.autoApproveCredit());
        model.addAttribute("midCredit", params.midCreditMin());
        model.addAttribute("baseCredit", params.baseRuleMinCredit());
        model.addAttribute("cleanAward", CreditLog.Reason.CLEAN_30D.defaultDelta());
        return "credit";
    }
}
