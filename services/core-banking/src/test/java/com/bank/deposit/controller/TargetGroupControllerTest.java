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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 병합으로 Spring Security 가 클래스패스에 들어오면서 슬라이스에 기본 보안이 걸린다.
// 컨트롤러 슬라이스의 관심사는 매핑·검증이지 인증이 아니므로 필터를 끈다.
// (실제 인증은 게이트웨이가 JWT 로 처리하고, 백엔드 체인은 permitAll 이다.)
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(TargetGroupController.class)
@DisplayName("TargetGroupController")
class TargetGroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TargetGroupService targetGroupService;

    @Test
    @DisplayName("가입 대상 그룹 목록을 조회한다")
    void list() throws Exception {
        given(targetGroupService.findAll(null)).willReturn(List.of(
                targetGroup("직장인", "급여소득 고객"),
                targetGroup("청년", "청년 우대 고객")
        ));

        mockMvc.perform(get("/target-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].targetGroupName").value("직장인"))
                .andExpect(jsonPath("$[1].targetGroupName").value("청년"));

        then(targetGroupService).should().findAll(null);
    }

    @Test
    @DisplayName("활성 여부로 가입 대상 그룹 목록을 필터링한다")
    void listByActiveStatus() throws Exception {
        given(targetGroupService.findAll(true)).willReturn(List.of(targetGroup("직장인", "급여소득 고객")));

        mockMvc.perform(get("/target-groups").param("isActive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].targetGroupName").value("직장인"));

        then(targetGroupService).should().findAll(true);
    }

    @Test
    @DisplayName("가입 대상 그룹을 생성한다")
    void create() throws Exception {
        given(targetGroupService.create("직장인", "급여소득 고객"))
                .willReturn(targetGroup("직장인", "급여소득 고객"));

        mockMvc.perform(post("/target-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetGroupName": "직장인",
                                  "description": "급여소득 고객"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetGroupName").value("직장인"))
                .andExpect(jsonPath("$.description").value("급여소득 고객"));

        then(targetGroupService).should().create("직장인", "급여소득 고객");
    }

    @Test
    @DisplayName("가입 대상 그룹명은 필수다")
    void createWithoutName() throws Exception {
        mockMvc.perform(post("/target-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetGroupName": "",
                                  "description": "급여소득 고객"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private TargetGroup targetGroup(String name, String description) {
        return TargetGroup.builder()
                .targetGroupName(name)
                .description(description)
                .build();
    }
}
