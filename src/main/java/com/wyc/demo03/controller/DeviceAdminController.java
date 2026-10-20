package com.wyc.demo03.controller;

import com.wyc.demo03.service.DeviceAdminService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备管理（管理端）。挂载在 {@code /admin/**} 下，由 AdminInterceptor 按角色拦截。
 */
@Controller
public class DeviceAdminController {

    private final DeviceAdminService deviceAdminService;

    public DeviceAdminController(DeviceAdminService deviceAdminService) {
        this.deviceAdminService = deviceAdminService;
    }

    @GetMapping("/admin/devices")
    public String page(Model model) {
        List<Map<String, Object>> devices = deviceAdminService.listWithRoom();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> device : devices) {
            Map<String, Object> row = new HashMap<>(device);
            Number id = (Number) device.get("id");
            row.put("commands", id == null ? List.of()
                    : deviceAdminService.recentCommands(id.longValue(), 5));
            rows.add(row);
        }

        model.addAttribute("devices", rows);
        model.addAttribute("channel", deviceAdminService.channelName());
        return "device-admin";
    }

    @PostMapping("/admin/devices/{id}/test-unlock")
    public String testUnlock(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        DeviceAdminService.TestResult result = deviceAdminService.testUnlock(id);
        ra.addFlashAttribute("info", result.message());
        ra.addFlashAttribute("infoType", result.ok() ? "success" : "danger");
        return "redirect:/admin/devices";
    }
}
