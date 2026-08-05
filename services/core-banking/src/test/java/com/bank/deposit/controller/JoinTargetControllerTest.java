package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.TargetGroup;
import com.bank.deposit.service.TargetGroupService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 병합으로 Spring Security 가 클래스패스에 들어오면서 슬라이스에 기본 보안이 걸린다.
// 컨트롤러 슬라이스의 관심사는 매핑·검증이지 인증이 아니므로 필터를 끈다.
// (실제 인증은 게이트웨이가 JWT 로 처리하고, 백엔드 체인은 permitAll 이다.)
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(JoinTargetController.class)
@DisplayName("JoinTargetController")
class JoinTargetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TargetGroupService targetGroupService;

    @Test
    @DisplayName("가입 대상 목록을 조회한다")
    void list() throws Exception {
        given(targetGroupService.findAll(null)).willReturn(List.of(targetGroup()));

        mockMvc.perform(get("/join-targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].targetGroupName").value("직장인"));
    }

    @Test
    @DisplayName("가입 대상을 생성한다")
    void create() throws Exception {
        given(targetGroupService.create(eq("직장인"), eq("급여소득자"))).willReturn(targetGroup());

        mockMvc.perform(post("/join-targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetGroupName": "직장인",
                                  "description": "급여소득자"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetGroupName").value("직장인"));
    }

    private TargetGroup targetGroup() {
        return TargetGroup.builder()
                .targetGroupName("직장인")
                .description("급여소득자")
                .build();
    }
}
