package com.bank.deposit.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 병합으로 Spring Security 가 클래스패스에 들어오면서 슬라이스에 기본 보안이 걸린다.
// 컨트롤러 슬라이스의 관심사는 매핑·검증이지 인증이 아니므로 필터를 끈다.
// (실제 인증은 게이트웨이가 JWT 로 처리하고, 백엔드 체인은 permitAll 이다.)
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(HomeController.class)
@DisplayName("HomeController")
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("루트 헬스 정보를 반환한다")
    void home() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("deposit-service"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.endpoints", hasItem("/products")));
    }
}
