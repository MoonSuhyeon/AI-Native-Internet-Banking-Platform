package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.SpecialTerm;
import com.bank.deposit.domain.enums.SpecialTermStatus;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.service.SpecialTermService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SpecialTermController.class)
@DisplayName("SpecialTermController")
class SpecialTermControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SpecialTermService specialTermService;

    @Test
    @DisplayName("특약 목록을 조회한다")
    void list() throws Exception {
        given(specialTermService.findAll(null)).willReturn(List.of(term("우대금리 특약", SpecialTermStatus.ACTIVE)));

        mockMvc.perform(get("/special-terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].specialTermName").value("우대금리 특약"));
    }

    @Test
    @DisplayName("활성 여부로 특약 목록을 필터링한다")
    void listByActive() throws Exception {
        given(specialTermService.findAll(true)).willReturn(List.of(term("우대금리 특약", SpecialTermStatus.ACTIVE)));

        mockMvc.perform(get("/special-terms").param("isActive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("특약을 생성한다")
    void create() throws Exception {
        given(specialTermService.create(eq("우대금리 특약"), eq("내용"), eq("요약"),
                eq(true), eq("v1"), eq("20260101"), isNull()))
                .willReturn(term("우대금리 특약", SpecialTermStatus.ACTIVE));

        mockMvc.perform(post("/special-terms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "specialTermName": "우대금리 특약",
                                  "specialTermContent": "내용",
                                  "specialTermSummary": "요약",
                                  "isRequired": true,
                                  "specialTermVersion": "v1",
                                  "startedAt": "20260101"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.specialTermName").value("우대금리 특약"));
    }

    @Test
    @DisplayName("특약 단건을 조회한다")
    void getById() throws Exception {
        given(specialTermService.findById(1L)).willReturn(term("우대금리 특약", SpecialTermStatus.ACTIVE));

        mockMvc.perform(get("/special-terms/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specialTermVersion").value("v1"));
    }

    @Test
    @DisplayName("존재하지 않는 특약 조회 시 404를 반환한다")
    void getNotFound() throws Exception {
        given(specialTermService.findById(999L))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND, "특약을 찾을 수 없습니다."));

        mockMvc.perform(get("/special-terms/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("특약을 수정한다")
    void update() throws Exception {
        given(specialTermService.update(1L, "수정 특약", "수정 내용", "v2"))
                .willReturn(term("수정 특약", SpecialTermStatus.ACTIVE));

        mockMvc.perform(put("/special-terms/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "specialTermName": "수정 특약",
                                  "specialTermContent": "수정 내용",
                                  "specialTermVersion": "v2"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specialTermName").value("수정 특약"));
    }

    @Test
    @DisplayName("특약 상태를 변경한다")
    void changeStatus() throws Exception {
        given(specialTermService.changeStatus(1L, SpecialTermStatus.INACTIVE))
                .willReturn(term("우대금리 특약", SpecialTermStatus.INACTIVE));

        mockMvc.perform(patch("/special-terms/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    private SpecialTerm term(String name, SpecialTermStatus status) {
        return SpecialTerm.builder()
                .specialTermName(name)
                .specialTermContent("내용")
                .specialTermSummary("요약")
                .isRequired(true)
                .specialTermVersion("v1")
                .startedAt("20260101")
                .status(status)
                .build();
    }
}
