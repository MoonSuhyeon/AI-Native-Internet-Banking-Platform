package com.bank.customer.login;

import com.bank.common.security.BankRole;
import com.bank.common.security.jwt.JwtClaims;
import com.bank.common.security.jwt.JwtProvider;
import com.bank.common.security.jwt.TokenType;
import com.bank.customer.customer.domain.Credential;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.login.dto.LoginRequest;
import com.bank.customer.login.dto.RefreshRequest;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.repository.PartyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인/리프레시 E2E — JWT 발급·검증·로테이션 경로를 실제 HTTP 스택으로 검증한다.
 *
 * <p>HTTP({@code /api/v1/auth/login}, {@code /refresh}) → LoginService → AuthEventService
 * → JwtProvider → Redis(RT:) → H2 DB 전 구간을 태운다. 단위테스트(mock)와 달리 토큰이
 * 실제로 발급되고 서명·클레임이 검증되며, refresh 로테이션·재사용 차단이 동작하는지 확인한다.
 *
 * <p>test 프로파일은 Redis 자동설정을 제외하므로, 토큰 회전을 실제로 검증할 수 있도록
 * 맵 기반 동작형 {@link StringRedisTemplate} 를 주입한다(외부 Redis·Docker 불필요).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LoginRefreshE2ETest.FakeRedisConfig.class)
@DisplayName("로그인/리프레시 E2E — 토큰 발급·검증·로테이션")
class LoginRefreshE2ETest {

    /** 맵 기반 인메모리 Redis — set/get/delete 만 실제 동작시켜 refresh 토큰 회전을 검증 가능하게 한다. */
    @TestConfiguration
    static class FakeRedisConfig {
        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return Mockito.mock(RedisConnectionFactory.class);
        }

        @Bean
        @SuppressWarnings("unchecked")
        StringRedisTemplate stringRedisTemplate() {
            Map<String, String> store = new ConcurrentHashMap<>();
            StringRedisTemplate template = Mockito.mock(StringRedisTemplate.class);
            ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
            Mockito.when(template.opsForValue()).thenReturn(ops);
            Mockito.doAnswer(i -> { store.put(i.getArgument(0), i.getArgument(1)); return null; })
                    .when(ops).set(Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class));
            Mockito.when(ops.get(Mockito.anyString())).thenAnswer(i -> store.get(i.getArgument(0)));
            Mockito.when(template.delete(Mockito.anyString()))
                    .thenAnswer(i -> store.remove(i.getArgument(0)) != null);
            return template;
        }
    }

    private static final String PASSWORD = "Pass1234!";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtProvider jwtProvider;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired PartyRepository partyRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired CredentialRepository credentialRepository;

    private String loginId;
    private Long customerId;

    /** 테스트마다 고유 login_id 로 개인 고객 1명을 시드한다(커밋되어 HTTP 호출에서 조회됨). */
    @BeforeEach
    void seedCustomer() {
        Party party = partyRepository.save(Party.builder()
                .partyTypeCode(Party.TYPE_PERSONAL)
                .partyName("E2E사용자")
                .partyStatusCode(Party.STATUS_ACTIVE)
                .build());

        Customer customer = customerRepository.save(Customer.builder()
                .partyId(party.getPartyId())
                .customerStatusCode(Customer.STATUS_ACTIVE)
                .customerGradeCode(Customer.GRADE_NORMAL)
                .mainCustomerYn(true)
                .smsReceiveYn(false).emailReceiveYn(false).postalReceiveYn(false)
                .email("e2e@bank.com").phone("01000000000")
                .joinedAt(OffsetDateTime.now())
                .build());
        this.customerId = customer.getCustomerId();

        this.loginId = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        credentialRepository.save(Credential.builder()
                .customerId(customerId)
                .loginId(loginId)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .passwordChangedAt(OffsetDateTime.now())
                .accountStatusCode(Credential.STATUS_ACTIVE)
                .passwordLoginFailureCount(0)
                .maxPasswordLoginFailureCount(5)
                .build());
    }

    // ── 로그인: 토큰 발급·검증 ────────────────────────────────────────────────

    @Test
    @DisplayName("로그인 성공 → access/refresh 발급, JwtProvider 로 서명·클레임 검증(ROLE_CUSTOMER)")
    void login_issuesValidTokens() throws Exception {
        JsonNode data = login(loginId, PASSWORD, status().isOk());

        String accessToken  = data.get("accessToken").asText();
        String refreshToken = data.get("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();

        // 서명·만료 검증 통과
        jwtProvider.validate(accessToken);

        JwtClaims access = jwtProvider.parseClaims(accessToken);
        assertThat(access.customerId()).isEqualTo(customerId);
        assertThat(access.tokenType()).isEqualTo(TokenType.ACCESS);
        assertThat(access.roles()).containsExactly(BankRole.CUSTOMER.authority()); // ROLE_CUSTOMER
        assertThat(access.employeeId()).isNull(); // 일반 고객은 직원 claim 없음

        assertThat(jwtProvider.parseClaims(refreshToken).tokenType()).isEqualTo(TokenType.REFRESH);
    }

    @Test
    @DisplayName("잘못된 비밀번호 → 인증 실패(토큰 미발급)")
    void login_wrongPassword_rejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(loginId, "WrongPass!"))))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("OK")));
    }

    // ── 리프레시: 로테이션·재사용 차단 ────────────────────────────────────────

    @Test
    @DisplayName("리프레시 성공 → 새 토큰 쌍 발급(이전 refresh 와 다름), 새 access 유효")
    void refresh_rotatesTokens() throws Exception {
        String oldRefresh = login(loginId, PASSWORD, status().isOk()).get("refreshToken").asText();

        JsonNode data = refresh(oldRefresh, status().isOk());
        String newAccess  = data.get("accessToken").asText();
        String newRefresh = data.get("refreshToken").asText();

        assertThat(newRefresh).isNotBlank().isNotEqualTo(oldRefresh);
        JwtClaims access = jwtProvider.parseClaims(newAccess);
        assertThat(access.customerId()).isEqualTo(customerId);
        assertThat(access.tokenType()).isEqualTo(TokenType.ACCESS);
    }

    @Test
    @DisplayName("리프레시 토큰 재사용 차단 → 401 TOKEN_INVALID")
    void refresh_reuse_rejected() throws Exception {
        String oldRefresh = login(loginId, PASSWORD, status().isOk()).get("refreshToken").asText();

        refresh(oldRefresh, status().isOk());            // 1회차: 회전 후 RT 교체
        mockMvc.perform(post("/api/v1/auth/refresh")     // 2회차: 같은 토큰 재사용
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(oldRefresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("access 토큰으로 refresh 시도 → 401 TOKEN_INVALID(토큰 타입 검증)")
    void refresh_withAccessToken_rejected() throws Exception {
        String accessToken = login(loginId, PASSWORD, status().isOk()).get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(accessToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private JsonNode login(String id, String pw,
                           org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(id, pw))))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }

    private JsonNode refresh(String token,
                             org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RefreshRequest(token))))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("data");
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }
}
