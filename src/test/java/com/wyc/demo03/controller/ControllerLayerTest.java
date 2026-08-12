package com.wyc.demo03.controller;

import com.wyc.demo03.entity.User;
import com.wyc.demo03.service.AttendanceRecordService;
import com.wyc.demo03.service.LoginAttemptService;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Controller 层集成测试（standalone MockMvc，不启动 Spring 容器）。
 * 覆盖 MainController / ReservationController / RoomController / LogController 的主要路由分支。
 */
@ExtendWith(MockitoExtension.class)
class ControllerLayerTest {

    @Mock
    private UserService userService;
    @Mock
    private MeetingRoomService meetingRoomService;
    @Mock
    private AttendanceRecordService attendanceRecordService;
    @Mock
    private ReservationService reservationService;
    @Mock
    private LoginAttemptService loginAttemptService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MainController mainController = new MainController(userService, meetingRoomService,
                attendanceRecordService, reservationService, loginAttemptService);
        ReservationController reservationController = new ReservationController(reservationService, meetingRoomService);
        RoomController roomController = new RoomController(meetingRoomService, reservationService);
        com.wyc.demo03.service.LogService logService = org.mockito.Mockito.mock(com.wyc.demo03.service.LogService.class);
        org.mockito.Mockito.lenient().when(logService.list()).thenReturn(java.util.List.of());
        LogController logController = new LogController(logService, reservationService, attendanceRecordService);
        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver();
        viewResolver.setPrefix("/WEB-INF/views/");
        viewResolver.setSuffix(".html");
        mockMvc = MockMvcBuilders.standaloneSetup(mainController, reservationController, roomController, logController)
                .setViewResolvers(viewResolver)
                .build();
    }

    // ============ 页面路由 ============

    @Test
    void loginPageRenders() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }

    @Test
    void registerPageRenders() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"));
    }

    @Test
    void logoutRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/logout"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void roomsPageRenders() throws Exception {
        mockMvc.perform(get("/rooms"))
                .andExpect(status().isOk())
                .andExpect(view().name("rooms"));
    }

    @Test
    void checkInPageRenders() throws Exception {
        mockMvc.perform(get("/reservation/check-in/42"))
                .andExpect(status().isOk())
                .andExpect(view().name("check-in"))
                .andExpect(model().attribute("reservationId", 42L));
    }

    @Test
    void dashboardPageRenders() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"));
    }

    // ============ 登录流程 ============

    @Test
    void loginActionRejectsMissingCaptcha() throws Exception {
        mockMvc.perform(post("/loginAction")
                        .param("username", "admin")
                        .param("password", "admin123")
                        .param("captcha", "AAAA"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("info", "验证码错误或已失效！"));
    }

    @Test
    void loginActionRejectsBlockedAccount() throws Exception {
        // Arrange：验证码正确但账号处于锁定状态
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("verityCode", "ABCD");
        when(loginAttemptService.isBlocked(anyString(), anyString())).thenReturn(true);

        // Act & Assert
        mockMvc.perform(post("/loginAction")
                        .session(session)
                        .param("username", "admin")
                        .param("password", "admin123")
                        .param("captcha", "abcd"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("info", "登录失败次数过多，请15分钟后再试！"));
    }

    @Test
    void loginActionSuccessRedirectsHome() throws Exception {
        // Arrange：验证码正确、未锁定、密码正确
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("verityCode", "ABCD");
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setName("系统管理员");
        user.setRole("ADMIN");
        user.setEmail("admin@example.com");
        when(loginAttemptService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(userService.login("admin", "admin123")).thenReturn(user);

        // Act & Assert
        mockMvc.perform(post("/loginAction")
                        .session(session)
                        .param("username", "admin")
                        .param("password", "admin123")
                        .param("captcha", "abcd"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void loginActionWrongPasswordShowsError() throws Exception {
        // Arrange：验证码正确但密码错误
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("verityCode", "ABCD");
        when(loginAttemptService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(userService.login("admin", "wrong")).thenReturn(null);

        // Act & Assert
        mockMvc.perform(post("/loginAction")
                        .session(session)
                        .param("username", "admin")
                        .param("password", "wrong")
                        .param("captcha", "abcd"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("info", "用户名或密码错误！"));
    }

    // ============ 注册流程 ============

    @Test
    void registerActionRejectsMissingCaptcha() throws Exception {
        mockMvc.perform(post("/registerAction")
                        .param("username", "newuser")
                        .param("password", "123456")
                        .param("name", "新用户")
                        .param("captcha", "AAAA"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attribute("info", "验证码错误或已失效！"));
    }

    @Test
    void registerActionRejectsBlankFields() throws Exception {
        mockMvc.perform(post("/registerAction")
                        .param("username", "")
                        .param("password", "")
                        .param("name", "")
                        .param("captcha", "AAAA"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attribute("info", "请填写完整的注册信息！"));
    }

    // ============ 预约/审批流程 ============

    @Test
    void applyWithInvalidDataRedirectsToRooms() throws Exception {
        mockMvc.perform(post("/reservation/apply"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rooms"));
    }

    @Test
    void applyWithConflictShowsFlashMessage() throws Exception {
        // Arrange
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 1L);
        session.setAttribute("role", "STUDENT");
        when(reservationService.apply(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("conflict");

        // Act & Assert
        mockMvc.perform(post("/reservation/apply")
                        .session(session)
                        .param("roomId", "1")
                        .param("startTime", "2026-08-13T10:00:00")
                        .param("endTime", "2026-08-13T11:00:00")
                        .param("attendeeCount", "5")
                        .param("_csrf", "token"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rooms"));
    }

    @Test
    void approveListRenders() throws Exception {
        mockMvc.perform(get("/reservation/approve-list"))
                .andExpect(status().isOk())
                .andExpect(view().name("approve-list"));
    }

    @Test
    void myReservationsRenders() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 1L);

        mockMvc.perform(get("/my-reservations").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("my-reservations"));
    }

    @Test
    void checkInActionWithWrongCodeShowsError() throws Exception {
        // Arrange
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 1L);
        when(reservationService.checkIn(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("wrong_code");

        // Act & Assert
        mockMvc.perform(post("/reservation/check-in-action")
                        .session(session)
                        .param("reservationId", "1")
                        .param("code", "0000"))
                .andExpect(status().isOk())
                .andExpect(view().name("check-in"))
                .andExpect(model().attribute("info", "签到码错误！"));
    }

    @Test
    void detailRedirectsWhenReservationMissing() throws Exception {
        // Arrange
        when(reservationService.findDetailById(99L)).thenReturn(null);

        // Act & Assert
        mockMvc.perform(get("/reservation/detail/99"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservation/approve-list"));
    }

    @Test
    void homeRendersWithCounts() throws Exception {
        // Arrange
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("Id", 1L);
        when(meetingRoomService.list()).thenReturn(java.util.List.of());
        when(attendanceRecordService.countTodayCheckIn()).thenReturn(2);

        com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<com.wyc.demo03.entity.Reservation> chain =
                org.mockito.Mockito.mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        when(reservationService.lambdaQuery()).thenReturn(chain);
        when(chain.eq(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(chain);
        when(chain.count()).thenReturn(0L);

        // Act & Assert
        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("home"));
    }

    @Test
    void approveSuccessRedirectsWithCode() throws Exception {
        // Arrange
        when(reservationService.approve(1L)).thenReturn("1234");

        // Act & Assert
        mockMvc.perform(post("/reservation/approve").param("id", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservation/approve-list"));
    }

    @Test
    void approveFailureRedirectsWithError() throws Exception {
        // Arrange
        when(reservationService.approve(1L)).thenReturn("error");

        // Act & Assert
        mockMvc.perform(post("/reservation/approve").param("id", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservation/approve-list"));
    }

    @Test
    void rejectRedirectsToApproveList() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/reservation/reject").param("id", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservation/approve-list"));
    }

    @Test
    void newReservationRedirectsWhenRoomMissing() throws Exception {
        // Arrange
        when(meetingRoomService.getById(99L)).thenReturn(null);

        // Act & Assert
        mockMvc.perform(get("/reservation/new").param("roomId", "99"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rooms"));
    }

    @Test
    void newReservationRedirectsWhenRoomUnderMaintenance() throws Exception {
        // Arrange
        com.wyc.demo03.entity.MeetingRoom room = new com.wyc.demo03.entity.MeetingRoom();
        room.setId(1L);
        room.setRoomStatus(1);
        when(meetingRoomService.getById(1L)).thenReturn(room);

        // Act & Assert
        mockMvc.perform(get("/reservation/new").param("roomId", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rooms"));
    }

    @Test
    void newReservationRendersWhenRoomAvailable() throws Exception {
        // Arrange
        com.wyc.demo03.entity.MeetingRoom room = new com.wyc.demo03.entity.MeetingRoom();
        room.setId(1L);
        room.setRoomName("大会议室");
        room.setRoomStatus(0);
        when(meetingRoomService.getById(1L)).thenReturn(room);

        // Act & Assert
        mockMvc.perform(get("/reservation/new").param("roomId", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("reservation-new"));
    }

    @Test
    void roomScheduleReturnsJson() throws Exception {
        // Arrange
        when(reservationService.findScheduleByRoomAndDate(1L, "2026-08-13"))
                .thenReturn(java.util.List.of());

        // Act & Assert
        mockMvc.perform(get("/api/room-schedule")
                        .param("roomId", "1")
                        .param("date", "2026-08-13"))
                .andExpect(status().isOk());
    }

    @Test
    void logsPageRenders() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/admin/logs"))
                .andExpect(status().isOk())
                .andExpect(view().name("logs"));
    }
}
