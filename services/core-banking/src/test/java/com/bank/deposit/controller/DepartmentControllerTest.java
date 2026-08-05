package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Department;
import com.bank.deposit.domain.enums.DepartmentType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.service.DepartmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

// 병합으로 Spring Security 가 클래스패스에 들어오면서 슬라이스에 기본 보안이 걸린다.
// 컨트롤러 슬라이스의 관심사는 매핑·검증이지 인증이 아니므로 필터를 끈다.
// (실제 인증은 게이트웨이가 JWT 로 처리하고, 백엔드 체인은 permitAll 이다.)
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(DepartmentController.class)
@DisplayName("DepartmentController")
class DepartmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DepartmentService departmentService;

    @Test
    @DisplayName("부서 목록을 조회한다")
    void list() throws Exception {
        given(departmentService.findAll()).willReturn(List.of(department("DEP")));

        mockMvc.perform(get("/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].departmentCode").value("DEP"));
    }

    @Test
    @DisplayName("부서를 생성한다")
    void create() throws Exception {
        given(departmentService.create(eq("DEP"), eq("수신상품부"), eq(DepartmentType.PRODUCT), isNull()))
                .willReturn(department("DEP"));

        mockMvc.perform(post("/departments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "departmentCode": "DEP",
                                  "departmentName": "수신상품부",
                                  "departmentType": "PRODUCT"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.departmentName").value("수신상품부"));
    }

    @Test
    @DisplayName("부서 단건을 조회한다")
    void getById() throws Exception {
        given(departmentService.findById(1L)).willReturn(department("DEP"));

        mockMvc.perform(get("/departments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentCode").value("DEP"));
    }

    @Test
    @DisplayName("존재하지 않는 부서 조회 시 404를 반환한다")
    void getNotFound() throws Exception {
        given(departmentService.findById(999L))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND, "부서를 찾을 수 없습니다."));

        mockMvc.perform(get("/departments/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("부서를 수정한다")
    void update() throws Exception {
        given(departmentService.update(1L, "수신운영부", DepartmentType.OPERATION))
                .willReturn(Department.builder()
                        .departmentCode("DEP")
                        .departmentName("수신운영부")
                        .departmentType(DepartmentType.OPERATION)
                        .build());

        mockMvc.perform(put("/departments/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "departmentCode": "DEP",
                                  "departmentName": "수신운영부",
                                  "departmentType": "OPERATION"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentName").value("수신운영부"));
    }

    @Test
    @DisplayName("부서를 비활성화한다")
    void deactivate() throws Exception {
        mockMvc.perform(delete("/departments/1"))
                .andExpect(status().isNoContent());
    }

    private Department department(String code) {
        return Department.builder()
                .departmentCode(code)
                .departmentName("수신상품부")
                .departmentType(DepartmentType.PRODUCT)
                .build();
    }
}
