package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Contract;
import com.bank.deposit.domain.entity.ContractAppliedRate;
import com.bank.deposit.domain.entity.ContractSpecialTermAgreement;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.service.ContractService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContractController.class)
@Import(AuthenticatedCustomerValidator.class)
@DisplayName("ContractController")
class ContractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ContractService contractService;

    @Test
    @DisplayName("계약 목록을 조회한다")
    void list() throws Exception {
        given(contractService.findAll(null, null)).willReturn(List.of(
                contract("CTR-001", "CUST-001"),
                contract("CTR-002", "CUST-002")
        ));

        mockMvc.perform(get("/contracts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].contractNumber").value("CTR-001"));
    }

    @Test
    @DisplayName("고객 ID로 계약 목록을 필터링한다")
    void listByCustomer() throws Exception {
        given(contractService.findAll(eq("CUST-001"), isNull()))
                .willReturn(List.of(contract("CTR-001", "CUST-001")));

        mockMvc.perform(get("/contracts")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
                        .param("customerId", "CUST-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].customerId").value("CUST-001"));
    }

    @Test
    @DisplayName("계약 단건을 조회한다")
    void getById() throws Exception {
        given(contractService.findById(1L)).willReturn(contract("CTR-001", "CUST-001"));

        mockMvc.perform(get("/contracts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractNumber").value("CTR-001"));
    }

    @Test
    @DisplayName("존재하지 않는 계약 조회 시 404를 반환한다")
    void getNotFound() throws Exception {
        given(contractService.findById(999L))
                .willThrow(new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        mockMvc.perform(get("/contracts/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("계약을 생성하면 201을 반환한다")
    void create() throws Exception {
        given(contractService.createContract(
                eq("CUST-001"), eq(1L), any(), eq(12), any(),
                any(), any(), any(), any(), any(), any(),
                any(),
                any(), any(), any(), anyString()))
                .willReturn(contract("CTR-NEW", "CUST-001"));

        mockMvc.perform(post("/contracts")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-001",
                                  "productId": 1,
                                  "joinAmount": 1000000,
                                  "contractPeriodMonth": 12,
                                  "joinChannel": "WEB",
                                  "accountPassword": "1234"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractNumber").value("CTR-NEW"));
    }

    @Test
    @DisplayName("다른 고객 계약 목록 조회 시 403을 반환한다")
    void listByCustomerMismatch() throws Exception {
        mockMvc.perform(get("/contracts")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-999")
                        .param("customerId", "CUST-001"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("계약을 해지한다")
    void terminate() throws Exception {
        Contract terminated = Contract.builder()
                .contractNumber("CTR-001")
                .customerId("CUST-001")
                .productId(1L)
                .joinAmount(BigDecimal.valueOf(1_000_000))
                .contractInterestRate(BigDecimal.valueOf(3.0))
                .totalPreferentialRate(BigDecimal.ZERO)
                .finalInterestRate(BigDecimal.valueOf(3.0))
                .contractPeriodMonth(12)
                .startedAt(java.time.LocalDate.of(2026, 1, 1))
                .joinChannel(JoinChannel.WEB)
                .contractStatus(ContractStatus.TERMINATED)
                .build();
        given(contractService.terminate(eq(1L), any(), isNull())).willReturn(terminated);

        mockMvc.perform(patch("/contracts/1/terminate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terminationReason\": \"고객 요청\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("TERMINATED"));
    }

    @Test
    @DisplayName("계약 상태를 변경한다")
    void changeStatus() throws Exception {
        Contract suspended = contract("CTR-001", "CUST-001");
        given(contractService.changeStatus(1L, ContractStatus.SUSPENDED)).willReturn(suspended);

        mockMvc.perform(patch("/contracts/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractStatus\": \"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractNumber").value("CTR-001"));
    }

    @Test
    @DisplayName("계약을 만기 처리한다")
    void mature() throws Exception {
        Contract matured = Contract.builder()
                .contractNumber("CTR-001")
                .customerId("CUST-001")
                .productId(1L)
                .joinAmount(BigDecimal.valueOf(1_000_000))
                .contractInterestRate(BigDecimal.valueOf(3.0))
                .totalPreferentialRate(BigDecimal.ZERO)
                .finalInterestRate(BigDecimal.valueOf(3.0))
                .contractPeriodMonth(12)
                .startedAt(java.time.LocalDate.of(2026, 1, 1))
                .joinChannel(JoinChannel.WEB)
                .contractStatus(ContractStatus.MATURED)
                .build();
        given(contractService.mature(1L)).willReturn(matured);

        mockMvc.perform(patch("/contracts/1/maturity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("MATURED"));
    }

    @Test
    @DisplayName("자동이체일을 변경한다")
    void updateAutoTransferDay() throws Exception {
        mockMvc.perform(patch("/contracts/1/auto-transfer-day")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoTransferDay\": 25}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("수신 계약을 조회한다")
    void getDepositContract() throws Exception {
        given(contractService.findDepositContract(1L)).willReturn(contract("CTR-001", "CUST-001"));

        mockMvc.perform(get("/contracts/1/deposit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractNumber").value("CTR-001"));
    }

    @Test
    @DisplayName("수신 계약 설정을 생성한다")
    void setupDepositContract() throws Exception {
        given(contractService.updateDepositSettings(1L, true, 15))
                .willReturn(contract("CTR-001", "CUST-001"));

        mockMvc.perform(post("/contracts/1/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "autoTransferEnabled": true,
                                  "autoTransferDay": 15
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractNumber").value("CTR-001"));
    }

    @Test
    @DisplayName("수신 계약 설정을 수정한다")
    void updateDepositContract() throws Exception {
        given(contractService.updateDepositSettings(1L, false, 20))
                .willReturn(contract("CTR-001", "CUST-001"));

        mockMvc.perform(put("/contracts/1/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "autoTransferEnabled": false,
                                  "autoTransferDay": 20
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractNumber").value("CTR-001"));
    }

    @Test
    @DisplayName("계약 적용 금리 목록을 조회한다")
    void getAppliedRates() throws Exception {
        given(contractService.findAppliedRates(1L)).willReturn(List.of(appliedRate()));

        mockMvc.perform(get("/contracts/1/applied-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].appliedRate").value(0.3));
    }

    @Test
    @DisplayName("계약 적용 금리를 저장한다")
    void saveAppliedRate() throws Exception {
        given(contractService.saveAppliedRate(eq(1L), eq(2L), any(), eq(true)))
                .willReturn(appliedRate());

        mockMvc.perform(post("/contracts/1/applied-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rateId": 2,
                                  "appliedRate": 0.3,
                                  "conditionVerifiedYn": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rateId").value(2));
    }

    @Test
    @DisplayName("계약 특약 동의 목록을 조회한다")
    void getAgreements() throws Exception {
        given(contractService.findAgreements(1L)).willReturn(List.of(agreement()));

        mockMvc.perform(get("/contracts/1/special-terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].specialTermId").value(7));
    }

    @Test
    @DisplayName("계약 특약에 동의한다")
    void agree() throws Exception {
        given(contractService.agree(eq(1L), eq(7L), eq(true), eq("20260521"),
                eq("127.0.0.1"), eq("WEB"), eq(true)))
                .willReturn(agreement());

        mockMvc.perform(post("/contracts/1/special-terms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "specialTermId": 7,
                                  "isAgreed": true,
                                  "agreedAt": "20260521",
                                  "agreementIpAddress": "127.0.0.1",
                                  "agreementDeviceInfo": "WEB",
                                  "isElectronicSigned": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.specialTermId").value(7));
    }

    @Test
    @DisplayName("우대금리 목록을 조회한다")
    void getPreferentialRates() throws Exception {
        given(contractService.findAppliedRates(1L)).willReturn(List.of(appliedRate()));

        mockMvc.perform(get("/contracts/1/preferential-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("우대금리를 추가한다")
    void addPreferentialRate() throws Exception {
        given(contractService.savePreferentialRate(eq(1L), eq("급여이체"), any(), eq(true)))
                .willReturn(appliedRate());

        mockMvc.perform(post("/contracts/1/preferential-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "conditionName": "급여이체",
                                  "appliedRate": 0.3,
                                  "appliedYn": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appliedRate").value(0.3));
    }

    @Test
    @DisplayName("우대금리를 삭제한다")
    void deletePreferentialRate() throws Exception {
        mockMvc.perform(delete("/contracts/1/preferential-rates/2"))
                .andExpect(status().isNoContent());
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private Contract contract(String number, String customerId) {
        return Contract.builder()
                .contractNumber(number)
                .customerId(customerId)
                .productId(1L)
                .joinAmount(BigDecimal.valueOf(1_000_000))
                .contractInterestRate(BigDecimal.valueOf(3.0))
                .totalPreferentialRate(BigDecimal.ZERO)
                .finalInterestRate(BigDecimal.valueOf(3.0))
                .contractPeriodMonth(12)
                .startedAt(java.time.LocalDate.of(2026, 1, 1))
                .joinChannel(JoinChannel.WEB)
                .build();
    }

    private ContractAppliedRate appliedRate() {
        return ContractAppliedRate.builder()
                .contractId(1L)
                .rateId(2L)
                .appliedRate(BigDecimal.valueOf(0.3))
                .conditionVerifiedYn(true)
                .build();
    }

    private ContractSpecialTermAgreement agreement() {
        return ContractSpecialTermAgreement.builder()
                .contractId(1L)
                .specialTermId(7L)
                .isAgreed(true)
                .agreedAt("20260521")
                .agreementIpAddress("127.0.0.1")
                .agreementDeviceInfo("WEB")
                .isElectronicSigned(true)
                .build();
    }
}
