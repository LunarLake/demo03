package com.wyc.demo03.controller;

import com.wyc.demo03.service.AccessService;
import com.wyc.demo03.service.RoomBoardService;
import com.wyc.demo03.service.RuleConfigService;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReservationFeedbackTest {
    private ReservationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ReservationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReservationController(service, mock(MeetingRoomService.class), mock(AccessService.class), mock(RoomBoardService.class), mock(RuleConfigService.class)))
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/views/", ".html")).build();
    }

    @Test
    void checkInRetryRetainsIdAndAllowsInput() throws Exception {
        when(service.checkIn(anyLong(), anyLong(), anyString(), anyString())).thenReturn("wrong_code");
        MockHttpSession session = new MockHttpSession(); session.setAttribute("Id", 100L);
        mvc.perform(post("/reservation/check-in-action").session(session).param("reservationId", "42").param("code", "0000"))
                .andExpect(view().name("check-in"))
                .andExpect(model().attribute("reservationId", 42L))
                .andExpect(model().attribute("checkInFinished", false))
                .andExpect(model().attribute("infoType", "danger"));
    }

    @Test
    void checkInSuccessHidesRepeatForm() throws Exception {
        when(service.checkIn(anyLong(), anyLong(), anyString(), anyString())).thenReturn("success");
        MockHttpSession session = new MockHttpSession(); session.setAttribute("Id", 100L);
        mvc.perform(post("/reservation/check-in-action").session(session).param("reservationId", "42").param("code", "1234"))
                .andExpect(model().attribute("checkInFinished", true))
                .andExpect(model().attribute("infoType", "success"));
    }

    @Test
    void rejectTooLongReasonNeverCallsService() throws Exception {
        mvc.perform(post("/reservation/reject").param("id", "1").param("reason", "拒".repeat(201)))
                .andExpect(redirectedUrl("/reservation/approve-list"))
                .andExpect(flash().attribute("info", "拒绝原因不能超过200字"))
                .andExpect(flash().attribute("infoType", "danger"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectChangedStatusIsNotReportedAsSuccess() throws Exception {
        doThrow(new IllegalStateException("状态已变化")).when(service).reject(any(), any(), any());
        mvc.perform(post("/reservation/reject").param("id", "1").param("reason", "原因"))
                .andExpect(redirectedUrl("/reservation/approve-list"))
                .andExpect(flash().attribute("info", "状态已变化"))
                .andExpect(flash().attribute("infoType", "danger"));
    }

    @Test
    void expiredApprovalDoesNotDisplayResultAsCode() throws Exception {
        when(service.approve(any(), any()))
                .thenReturn(com.wyc.demo03.service.ReservationService.ApproveResult
                        .fail("expired", "预约开始时间已过，无法批准"));
        mvc.perform(post("/reservation/approve").param("id", "1"))
                .andExpect(flash().attribute("info", "预约开始时间已过，无法批准"))
                .andExpect(flash().attribute("infoType", "danger"));
    }

    @Test
    void cancellationProvidesVisibleMessageType() throws Exception {
        when(service.cancel(1L, 100L)).thenReturn("invalid_status");
        MockHttpSession session = new MockHttpSession(); session.setAttribute("Id", 100L);
        mvc.perform(post("/reservation/cancel/1").session(session))
                .andExpect(redirectedUrl("/my-reservations"))
                .andExpect(flash().attribute("infoType", "danger"));
    }

    @Test
    void conflictIsAnErrorNotASuccessMessage() throws Exception {
        when(service.submit(any(), any(), any()))
                .thenReturn(com.wyc.demo03.service.ReservationService.SubmitResult
                        .fail("B04_OCCUPIED", "所选时段已被占用，请选择其他时间"));
        mvc.perform(post("/reservation/apply").param("roomId", "1")
                        .param("startTime", "2099-06-09T10:00:00").param("endTime", "2099-06-09T11:00:00")
                        .param("attendeeCount", "2"))
                .andExpect(flash().attribute("infoType", "danger"));
    }
}
