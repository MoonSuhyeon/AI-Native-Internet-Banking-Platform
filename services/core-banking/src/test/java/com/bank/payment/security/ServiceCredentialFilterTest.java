package com.bank.payment.security;

import com.bank.common.security.Sha256;
import com.bank.deposit.exception.BusinessException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 내부 API 주체 인증의 전환 단계 동작을 고정한다.
 *
 * <p>여기서 지키려는 것은 셋이다.
 * <ul>
 *   <li>전환 단계에는 <b>막지 않는다</b> — 호출자를 다 찾기 전에 켜면 FDS 탐지와
 *       AI 상담이 즉시 깨진다. 승인 게이트를 목록 확인 없이 켰다가 여신 넷을
 *       빠뜨린 일이 있었다.</li>
 *   <li>그래도 <b>남긴다</b> — 조용히 통과시키면 이 상태가 언제 끝나는지 아무도
 *       모르게 된다. 이 기록이 전환 절차의 입력이다.</li>
 *   <li>{@code enforce} 를 켜면 <b>막는다</b> — 허용이 정책으로 굳지 않게 하는
 *       스위치가 코드에 있어야 한다.</li>
 * </ul>
 */
class ServiceCredentialFilterTest {

    private static final String CREDENTIAL = "dev-secret-loan-service-credential";
    private static final String INTERNAL_PATH = "/api/v1/internal/payments";

    private ServiceAuthorizationMapper mapper;
    private List<ServiceAuthorizationLogEntry> audited;
    private ServiceAuthorizationAuditWriter auditWriter;

    @BeforeEach
    void setUp() {
        mapper = mock(ServiceAuthorizationMapper.class);
        audited = new ArrayList<>();
        auditWriter = new ServiceAuthorizationAuditWriter(mapper) {
            @Override
            public void write(ServiceAuthorizationLogEntry entry) {
                audited.add(entry);
            }
        };
    }

    private ServiceCredentialFilter filter(boolean enforce) {
        return new ServiceCredentialFilter(mapper, auditWriter, enforce);
    }

    private MockHttpServletRequest request(String path, String credential) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRequestURI(path);
        if (credential != null) {
            req.addHeader("X-Internal-Token", credential);
        }
        return req;
    }

    private void knownService() {
        org.mockito.Mockito.when(mapper.findPrincipalByCredentialHash(Sha256.hex(CREDENTIAL)))
                .thenReturn(new ServicePrincipal("LOAN_SERVICE", "여신 서비스", "ACTIVE"));
    }

    @Test
    @DisplayName("아는 자격증명이면 신원을 세워 넘긴다")
    void establishesIdentity() throws Exception {
        knownService();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = request(INTERNAL_PATH, CREDENTIAL);

        filter(false).doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(req.getAttribute(ServiceCredentialFilter.SERVICE_ID_ATTRIBUTE))
                .isEqualTo("LOAN_SERVICE");
        verify(chain, times(1)).doFilter(any(), any());
        // 인가는 컨트롤러가 자기 작업으로 판정한다. 여기서 또 남기면 한 요청에
        // 두 줄이 생겨 감사가 부풀어 오른다.
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("전환 단계에는 미인증도 통과시키되 반드시 남긴다")
    void allowsButAuditsDuringMigration() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        filter(false).doFilter(request(INTERNAL_PATH, null), new MockHttpServletResponse(), chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(audited).singleElement().satisfies(e -> {
            assertThat(e.serviceId()).isNull();
            assertThat(e.decision()).isEqualTo(ServiceAuthorizationLogEntry.DECISION_DENY);
            assertThat(e.denyReason()).isEqualTo("UNKNOWN_CREDENTIAL");
            assertThat(e.requestPath()).isEqualTo(INTERNAL_PATH);
        });
    }

    @Test
    @DisplayName("모르는 자격증명도 통과시키되 남긴다")
    void allowsButAuditsUnknownCredential() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        filter(false).doFilter(request(INTERNAL_PATH, "아무거나"), new MockHttpServletResponse(), chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(audited).hasSize(1);
    }

    @Test
    @DisplayName("enforce 를 켜면 미인증을 막는다")
    void rejectsWhenEnforced() {
        FilterChain chain = mock(FilterChain.class);

        assertThatThrownBy(() -> filter(true)
                .doFilter(request(INTERNAL_PATH, null), new MockHttpServletResponse(), chain))
                .isInstanceOf(BusinessException.class);

        // 막을 때도 남긴다. 막힌 시도가 가장 봐야 할 기록이다.
        assertThat(audited).hasSize(1);
    }

    @Test
    @DisplayName("정지된 서비스는 신원을 세우지 않는다")
    void doesNotEstablishSuspendedService() throws Exception {
        org.mockito.Mockito.when(mapper.findPrincipalByCredentialHash(Sha256.hex(CREDENTIAL)))
                .thenReturn(new ServicePrincipal("LOAN_SERVICE", "여신 서비스", "SUSPENDED"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest req = request(INTERNAL_PATH, CREDENTIAL);

        filter(false).doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(req.getAttribute(ServiceCredentialFilter.SERVICE_ID_ATTRIBUTE)).isNull();
        assertThat(audited).singleElement()
                .extracting(ServiceAuthorizationLogEntry::denyReason)
                .isEqualTo("SERVICE_SUSPENDED");
    }

    @Test
    @DisplayName("직원 운영 경로는 이 필터의 대상이 아니다")
    void skipsEmployeeOperatedPath() throws Exception {
        // 확인되지 않은 호출자를 위해 OPERATOR 서비스 신원을 만들면 그 신원이
        // 곧 우회로가 된다. 서비스가 아닌 것을 서비스로 등록하지 않는다.
        ServiceCredentialFilter f = filter(true);
        MockHttpServletRequest req = request("/api/v1/internal/reconciliation/run", null);

        assertThat(f.shouldNotFilter(req)).isTrue();
        assertThat(audited).isEmpty();
    }

    @Test
    @DisplayName("내부 경로가 아니면 건드리지 않는다")
    void skipsNonInternalPath() {
        assertThat(filter(true).shouldNotFilter(request("/api/v1/payments", null))).isTrue();
        verify(mapper, never()).findPrincipalByCredentialHash(any());
    }
}
