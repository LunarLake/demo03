package com.wyc.demo03.controller;

import com.wyc.demo03.common.SlotConflictException;
import com.wyc.demo03.config.DoorProperties;
import com.wyc.demo03.config.SlotProperties;
import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.service.AccessService;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.service.RoomBoardService;
import com.wyc.demo03.service.RuleConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-006：提交时撞上占用唯一键，必须给"时段刚被占用"这种可操作提示，
 * 而不是"服务器内部错误"。
 *
 * <p>关键点：冲突由数据库唯一键仲裁产生，事务在 Service 边界已经回滚干净，
 * 控制器这一层只负责翻译，不需要也不应该再做补偿。
 */
class ReservationApplyConflictTest {

    private ReservationService reservationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reservationService = mock(ReservationService.class);
        RoomBoardService board = mock(RoomBoardService.class);
        RuleConfigService ruleConfigService = mock(RuleConfigService.class);
        when(ruleConfigService.currentParams()).thenReturn(RuleParams.defaults());

        ReservationController controller = new ReservationController(
                reservationService,
                mock(MeetingRoomService.class),
                mock(AccessService.class),
                board,
                ruleConfigService,
                new SlotProperties(),
                new DoorProperties());

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/views/", ".html"))
                .build();
    }

    private MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("Id", 7L);
        s.setAttribute("role", "STUDENT");
        s.setAttribute("_csrf", "tok");
        return s;
    }

    private Reservation form() {
        Reservation r = new Reservation();
        r.setRoomId(1L);
        r.setStartTime(java.time.LocalDate.now().plusDays(2).atTime(10, 0));
        r.setEndTime(java.time.LocalDate.now().plusDays(2).atTime(11, 0));
        r.setAttendeeCount(3);
        return r;
    }

    @Test
    @DisplayName("JSON 提交：冲突返回 success=false + 可操作提示")
    void jsonApplyTranslatesConflict() throws Exception {
        when(reservationService.submit(any(Reservation.class), anyLong(), anyString()))
                .thenThrow(new SlotConflictException(java.util.List.of(
                        com.wyc.demo03.entity.ReservationSlot.of(99L, 1L, java.time.LocalDate.now(), 8))));

        mvc.perform(post("/api/reservation/apply").session(session())
                        .param("_csrf", "tok")
                        .param("roomId", "1")
                        .param("startTime", "2026-10-25T10:00")
                        .param("endTime", "2026-10-25T11:00")
                        .param("attendeeCount", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("该时段刚被其他人占用，请重新选择"));
    }

    @Test
    @DisplayName("表单提交：冲突回到排期板并给出提示，不停留在空白首页")
    void formApplyTranslatesConflict() throws Exception {
        when(reservationService.submit(any(Reservation.class), anyLong(), anyString()))
                .thenThrow(new SlotConflictException(java.util.List.of(
                        com.wyc.demo03.entity.ReservationSlot.of(99L, 1L, java.time.LocalDate.now(), 8))));

        mvc.perform(post("/reservation/apply").session(session())
                        .param("_csrf", "tok")
                        .param("roomId", "1")
                        .param("startTime", "2026-10-25T10:00")
                        .param("endTime", "2026-10-25T11:00")
                        .param("attendeeCount", "3"))
                .andExpect(redirectedUrl("/reservation/board"))
                .andExpect(flash().attribute("infoType", "danger"))
                .andExpect(flash().attribute("info",
                        org.hamcrest.Matchers.containsString("刚被其他人占用")));
    }
}
