package com.bank.customer.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 운영(prod) 환경에서 데모 직원 계정을 로그인 불가로 비활성화한다.
 *
 * <p>V11/V12 가 시드한 관리자 데모 직원 계정(login_id audit01…ops01, password Employee1234!)은
 * Flyway 마이그레이션이라 프로파일 게이팅이 불가해 staging/prod 에도 그대로 생성된다
 * (login 가능한 공개 자격증명). 인증서 PIN(V10)을 시드 시리얼로 한정해 운영 no-op 으로 둔 것과
 * 동일한 수준의 환경 격리를 직원 계정에도 적용하기 위해, prod 기동 시 알려진 데모 login_id 의
 * 자격증명을 {@code CLOSED} 로 전환한다(LoginService 가 {@code isActive()} 검사로 차단 → CUST_012).
 *
 * <p>대상은 알려진 데모 login_id 로 한정(V10 의 시드 시리얼 한정과 동형)하고, local/test 에서는
 * {@code @Profile("prod")} 로 동작하지 않아 데모·테스트 로그인은 그대로 유지된다.
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class DemoEmployeeAccountGuard implements ApplicationRunner {

    /** V11/V12 가 시드한 데모 직원 login_id (관리자 콘솔 7역할 + 심사역·운영). */
    private static final List<String> DEMO_LOGIN_IDS = List.of(
            "audit01", "review01", "risk01", "mkt01", "owner01",
            "staff01", "other01", "deputy01", "ops01");

    private final JdbcTemplate jdbcTemplate;

    /**
     * 닫지 <b>않을</b> 데모 계정. 쉼표로 구분한다. 기본은 비어 있어 전부 닫는다.
     *
     * <p><b>이것을 채우는 것은 의도적으로 구멍을 내는 일이다.</b> 이 계정들의
     * 비밀번호는 저장소에 공개돼 있어, 열어 두면 주소를 아는 누구나 그 권한으로
     * 들어올 수 있다. 그래서 기본값이 비어 있고, 열려면 배포 설정에 명시해야 한다.
     *
     * <p>공모전 심사처럼 <b>정해진 계정으로 화면을 보여 줘야 하는</b> 배포에서만
     * 쓴다. 그 경우에도 필요한 하나만 적고 나머지는 닫힌 채로 둔다.
     */
    @Value("${demo.employee.keep-open:}")
    private String keepOpenRaw;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<String> keepOpen = parseKeepOpen();
        List<String> targets = DEMO_LOGIN_IDS.stream()
                .filter(id -> !keepOpen.contains(id))
                .toList();

        if (!keepOpen.isEmpty()) {
            // 조용히 열어 두지 않는다. 로그에 남겨야 운영자가 이 배포의 상태를 안다.
            log.warn("[DemoEmployeeAccountGuard] 데모 직원 계정 {}를 **열어 둔 채** 기동합니다. " +
                    "이 계정들의 비밀번호는 저장소에 공개돼 있습니다 — 시연용 배포에서만 쓰세요.", keepOpen);

            // 닫지 않는 것으로는 부족하다. 이 가드가 이전 기동에서 이미 닫아 둔 계정은
            // 그대로 남아, 설정을 켜도 로그인이 안 된다 — 사람이 SQL 로 되살려야 하고
            // 다음 재기동 때 또 닫힌다. 예외로 지정했으면 열어 주는 것까지가 그 뜻이다.
            String reopenIn = keepOpen.stream().map(id -> "?").collect(Collectors.joining(","));
            int reopened = jdbcTemplate.update(
                    "UPDATE credential SET account_status_code = 'ACTIVE', " +
                            "password_login_failure_count = 0, updated_at = NOW() " +
                            "WHERE login_id IN (" + reopenIn + ") AND account_status_code = 'CLOSED'",
                    keepOpen.toArray());
            if (reopened > 0) {
                log.warn("[DemoEmployeeAccountGuard] 그중 {}건은 닫혀 있어 다시 열었습니다.", reopened);
            }
        }
        if (targets.isEmpty()) {
            return;
        }

        String placeholders = targets.stream().map(id -> "?").collect(Collectors.joining(","));
        int closed = jdbcTemplate.update(
                "UPDATE credential SET account_status_code = 'CLOSED', updated_at = NOW() " +
                        "WHERE login_id IN (" + placeholders + ") AND account_status_code <> 'CLOSED'",
                targets.toArray());

        if (closed > 0) {
            log.warn("[DemoEmployeeAccountGuard] prod 환경 — 데모 직원 계정 {}건을 CLOSED 로 비활성화했습니다. " +
                    "운영 직원 계정은 별도 발급 절차로 생성하세요.", closed);
        }
    }

    /** 알려진 데모 계정만 열 수 있다. 목록에 없는 이름을 적어도 아무 일도 하지 않는다. */
    private List<String> parseKeepOpen() {
        if (keepOpenRaw == null || keepOpenRaw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(keepOpenRaw.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .filter(DEMO_LOGIN_IDS::contains)
                .toList();
    }
}
