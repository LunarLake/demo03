package com.wyc.demo03.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.ReservationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * 会议室控制器 —— 处理会议室列表、新增、编辑、删除
 *
 * 权限控制：
 *   GET  /rooms           — 所有角色可访问（RoleInterceptor 只检查登录态）
 *   POST /room/add         — TeacherInterceptor 拦截，只有教师能访问
 *   POST /room/update      — TeacherInterceptor 拦截
 *   POST /room/delete/{id} — TeacherInterceptor 拦截
 *
 * RoomController 不接收 @Valid 实体 → 不使用 BindingResult。
 * 新增和修改的 MeetingRoom 参数直接绑定表单字段（roomName, capacity, equipment, roomStatus），
 * 校验在 Service 层和数据库层完成。
 */
@Controller
public class RoomController {
    private final MeetingRoomService meetingRoomService;
    private final ReservationService reservationService;

    public RoomController(MeetingRoomService meetingRoomService, ReservationService reservationService) {
        this.meetingRoomService = meetingRoomService;
        this.reservationService = reservationService;
    }

    // ====================================================================
    // GET /rooms —— 会议室列表（支持关键词搜索）
    // ====================================================================
    // 两种查询模式：
    //   【有搜索关键词】→ 用 MyBatis-Plus 的 LambdaQueryWrapper 构造 LIKE 查询，
    //                    在 roomName 和 equipment 两个字段中模糊匹配
    //   【无搜索关键词】→ meetingRoomService.list() 返回全部会议室
    //
    // LambdaQueryWrapper 的优势：
    //   - 用 Java Lambda 表达式引用字段（MeetingRoom::getRoomName），
    //     避免手写字符串字段名导致的拼写错误和重构遗漏
    //   - 等价 SQL：
    //       SELECT * FROM t_meeting_room
    //       WHERE room_name LIKE '%keyword%' OR equipment LIKE '%keyword%'
    //
    // 角色信息传递：
    //   将 session.role 放入 model，前端 Thymeleaf 用 th:if 判断是否渲染
    //   "新增会议室"、"编辑"、"删除"等教师专属按钮。
    @GetMapping("/rooms")
    public String list(String keyword, Model model, HttpSession session) {
        // 从 session 获取当前用户角色，传给前端用于条件渲染
        String role = (String) session.getAttribute("role");

        List<MeetingRoom> rooms;
        if (keyword != null && !keyword.isBlank()) {
            // ===== 有搜索关键词：模糊查询 =====
            // 构建查询条件：roomName LIKE '%keyword%' OR equipment LIKE '%keyword%'
            LambdaQueryWrapper<MeetingRoom> wrapper = new LambdaQueryWrapper<>();
            wrapper.like(MeetingRoom::getRoomName, keyword)    // 会议室名称模糊匹配
                    .or()                                       // OR 连接
                    .like(MeetingRoom::getEquipment, keyword);  // 设备列表模糊匹配
            rooms = meetingRoomService.list(wrapper);
        } else {
            // ===== 无搜索关键词：查询全部 =====
            rooms = meetingRoomService.list();
        }

        model.addAttribute("rooms", rooms);
        model.addAttribute("role", role);  // 前端用 th:if="${role == 'TEACHER'}" 控制按钮显示
        return "rooms";
    }

    // ====================================================================
    // POST /room/add —— 新增会议室（教师专属）
    // ====================================================================
    // MeetingRoom 实体参数由 Spring MVC 自动绑定表单字段：
    //   <input name="roomName">     → room.setRoomName()
    //   <input name="capacity">     → room.setCapacity()
    //   <input name="equipment">    → room.setEquipment()
    //   <input name="roomStatus">   → room.setRoomStatus()  (0=正常, 1=维护中)
    //
    // 不使用 @Valid + BindingResult 的原因：
    //   会议室的新增/编辑字段在表单中是简单的文本输入，
    //   MeetingRoom 实体上没有加严格的 @NotNull 注解（名称可为空字符串），
    //   主要的约束在数据库层（NOT NULL、UNIQUE 等）。
    //
    // 使用 RedirectAttributes 的原因：
    //   新增成功后重定向到 /rooms 列表页，用 addFlashAttribute 传递成功提示。
    @PostMapping("/room/add")
    public String add(MeetingRoom room, RedirectAttributes ra) {
        // MyBatis-Plus 的 save()：INSERT INTO t_meeting_room
        meetingRoomService.save(room);
        ra.addFlashAttribute("info", "会议室添加成功");
        return "redirect:/rooms";
    }

    // ====================================================================
    // POST /room/update —— 修改会议室信息（教师专属）
    // ====================================================================
    // MeetingRoom 实体必须包含 id 字段（来自表单隐藏字段 <input type="hidden" name="id">），
    // MyBatis-Plus 的 updateById() 根据 id 执行 UPDATE。
    //
    // 与 POST /room/add 的区别：
    //   add    用的是 save()  → INSERT
    //   update 用的是 updateById() → UPDATE ... WHERE id = ?
    @PostMapping("/room/update")
    public String update(MeetingRoom room, RedirectAttributes ra) {
        String result = meetingRoomService.updateRoom(room);
        if (result.startsWith("has_active:")) {
            ra.addFlashAttribute("info", "该会议室有" + result.substring(11) + "个活跃预约，无法设为维护中");
        } else {
            ra.addFlashAttribute("info", "会议室信息更新成功");
        }
        return "redirect:/rooms";
    }

    // ====================================================================
    // POST /room/delete/{id} —— 删除会议室（教师专属）
    // ====================================================================
    // {id} 是路径变量（@PathVariable），来自 RESTful 风格的 URL /room/delete/5
    //
    // 删除的安全性由 MeetingRoomServiceImpl.deleteRoom() 保证：
    //   1. 先查该会议室是否有活跃预约（status=0 或 1）
    //   2. 如果有 → 返回 "has_active:N"，拒绝删除
    //   3. 如果没有 → 执行 removeById()
    //
    // 结果处理：
    //   result.startsWith("has_active:") → 提取活跃预约数量，告知用户无法删除的原因
    //   否则 → 删除成功
    //
    // 为什么不在 Controller 里做这个检查？
    //   Controller 只负责路由和参数传递，业务规则（如"有活跃预约不能删"）
    //   属于 Service 层的职责。这样即使将来有其他入口（如定时任务批量清理），
    //   也能复用同一个安全检查。
    @PostMapping("/room/delete/{id}")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        String result = meetingRoomService.deleteRoom(id);

        // "has_active:3" → substring(11) 得到 "3" → 提示"有 3 个活跃预约，无法删除"
        if (result.startsWith("has_active:")) {
            ra.addFlashAttribute("info", "该会议室有" + result.substring(11) + "个活跃预约，无法删除");
        } else {
            ra.addFlashAttribute("info", "会议室已删除");
        }
        return "redirect:/rooms";
    }
}
