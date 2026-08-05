package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.TermApplicationManagement;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.service.TermApplicationManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TermApplicationManagementController.class)
@DisplayName("TermApplicationManagementController")
class TermApplicationManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TermApplicationManagementService service;

    @Test
    @DisplayName("약관 적용 목록 전체를 조회한다")
    void listAll() throws Exception {
        given(service.findAll()).willReturn(List.of(entity("DEPOSIT", "Y")));

        mockMvc.perform(get("/term-applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].businessTypeCode").value("DEPOSIT"));
    }

    @Test
    @DisplayName("업무 구분 코드로 약관 적용 목록을 필터링한다")
    void listByBusinessTypeCode() throws Exception {
        given(service.findByBusinessTypeCode("SAVINGS"))
                .willReturn(List.of(entity("SAVINGS", "N")));

        mockMvc.perform(get("/term-applications").param("businessTypeCode", "SAVINGS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].businessTypeCode").value("SAVINGS"));
    }

    @Test
    @DisplayName("공통 약관 ID로 약관 적용 목록을 필터링한다")
    void listByCommonTermId() throws Exception {
        given(service.findByCommonTermId(100L)).willReturn(List.of(entity("DEPOSIT", "Y")));

        mockMvc.perform(get("/term-applications").param("commonTermId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("필수 여부로 약관 적용 목록을 필터링한다")
    void listByRequired() throws Exception {
        given(service.findByIsRequired("Y")).willReturn(List.of(entity("DEPOSIT", "Y")));

        mockMvc.perform(get("/term-applications").param("isRequired", "Y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isRequired").value("Y"));
    }

    @Test
    @DisplayName("약관 적용 단건을 조회한다")
    void getById() throws Exception {
        given(service.findById(1L)).willReturn(entity("DEPOSIT", "Y"));

        mockMvc.perform(get("/term-applications/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commonTermId").value(100));
    }

    @Test
    @DisplayName("존재하지 않는 약관 적용 조회 시 404를 반환한다")
    void getNotFound() throws Exception {
        given(service.findById(999L))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND, "약관 적용 정보를 찾을 수 없습니다."));

        mockMvc.perform(get("/term-applications/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("약관 적용 정보를 등록한다")
    void create() throws Exception {
        given(service.create(any(TermApplicationManagement.class))).willReturn(entity("DEPOSIT", "Y"));

        mockMvc.perform(post("/term-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commonTermId": 100,
                                  "termTargetId": 200,
                                  "businessTypeCode": "DEPOSIT",
                                  "isRequired": "Y",
                                  "registeredAt": "20260521"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessTypeCode").value("DEPOSIT"));
    }

    @Test
    @DisplayName("약관 적용 정보를 삭제한다")
    void deleteTermApplication() throws Exception {
        mockMvc.perform(delete("/term-applications/1"))
                .andExpect(status().isNoContent());
    }

    private TermApplicationManagement entity(String businessTypeCode, String required) {
        return TermApplicationManagement.builder()
                .commonTermId(100L)
                .termTargetId(200L)
                .businessTypeCode(businessTypeCode)
                .isRequired(required)
                .registeredAt("20260521")
                .build();
    }
}
