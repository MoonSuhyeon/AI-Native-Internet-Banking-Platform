package com.bank.loan.support;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.collateral.domain.Collateral;
import com.bank.loan.collateral.repository.CollateralRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;


/**
 * 통합 테스트 베이스. 컨테이너(Postgres / Redis) 와 MockMvc / ObjectMapper 를 공유한다.
 *
 * 싱글톤 컨테이너 패턴 — @Testcontainers / @Container 미사용.
 * 컨테이너 lifecycle 을 JVM 전체에 맞춰 두어, 여러 테스트 클래스가 캐시된 Spring 컨텍스트를
 * 재사용해도 동일한 컨테이너에 계속 접속할 수 있게 한다.
 * Ryuk 데몬이 JVM 종료 시 자동 정리한다.
 *
 *  - JPA ddl-auto = create-drop (Spring 컨텍스트 초기화 시점에 스키마 신규 생성)
 *  - 서류 스토리지 = OS 임시 디렉터리
 *  - MockMvc 기본 요청에 전 역할 JWT 포함 — 테스트용 슈퍼 토큰
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractLoanIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final GenericContainer<?> REDIS;
    static final KafkaContainer KAFKA;
    protected static final WireMockServer DOC_AGENT_MOCK;
    protected static final WireMockServer AUTO_REVIEW_MOCK;
    protected static final WireMockServer PAYMENT_MOCK;

    static {
        POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
        POSTGRES.start();

        REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        REDIS.start();

        KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        KAFKA.start();

        DOC_AGENT_MOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        DOC_AGENT_MOCK.start();

        AUTO_REVIEW_MOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        AUTO_REVIEW_MOCK.start();
        AUTO_REVIEW_MOCK.stubFor(WireMock.post(WireMock.urlEqualTo("/api/ai/auto-review/evaluate"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        // auto-review 컨트롤러는 ApiResponse 봉투로 감싸 반환한다
                        // (AutoReviewEvaluateClient 가 ApiResponse<T> 로 역직렬화).
                        // 맨몸 객체를 주면 data 가 null 이 되어 결과가 null 로 떨어진다.
                        .withBody("{\"code\":\"OK\",\"message\":\"OK\",\"data\":"
                                + "{\"track\":\"TRACK_3\",\"pd\":0.120000,"
                                + "\"rationale\":\"통합테스트 기본 stub\"}}")));

        // advisory 스텁을 없앴다. 어드바이저리는 별도 프로세스가 아니라 이 서비스
        // 안에 있으므로 HTTP 로 부르지 않는다.
        //
        // 이 기본 스텁이 오래 해를 끼쳤다. 늘 빈 배열을 돌려주는 바람에 CRITICAL
        // 미확인 약정 차단(LOAN_201)이 한 번도 발화하지 않았고, 그런데도 전 테스트가
        // 초록이었다. 통제가 없는 것과 통제가 통과하는 것이 구별되지 않았다.

        // 기본 stub: POST /api/v1/internal/payments → COMPLETED
        // 개별 테스트에서 priority=1 스텁으로 특정 X-Idempotency-Key 에 대해 FAILED 등을 오버라이드 가능
        PAYMENT_MOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        PAYMENT_MOCK.start();
        PAYMENT_MOCK.stubFor(WireMock.post(WireMock.urlEqualTo("/api/v1/internal/payments"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentInstructionId\":\"PI-TEST-001\"," +
                                  "\"transactionNo\":\"TXN-TEST-001\"," +
                                  "\"status\":\"COMPLETED\"," +
                                  "\"failureCategory\":null}")));
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");

        // common_db 는 같은 테스트 컨테이너를 공유한다. common Flyway 는 전용 이력 테이블을 쓰므로
        // loan Flyway 와 충돌하지 않는다(CommonDataSourceConfig 참고).
        r.add("common.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("common.datasource.username", POSTGRES::getUsername);
        r.add("common.datasource.password", POSTGRES::getPassword);

        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());

        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // loan.review.bias-check.enabled 는 여기서 고정하지 않는다.
        // @DynamicPropertySource 는 우선순위가 최상이라 개별 테스트 클래스가
        // @TestPropertySource 로 덮을 수 없어, 편향 검증 플로우를 검증하는 테스트가
        // 통과 불가능한 상태가 됐었다(#25 에서 작성 → #47 에서 전역 비활성화).
        // 기본값 false 는 application-test.yml 에 두고, 편향 플로우 테스트는
        // @TestPropertySource 로 true 를 지정한다.

        // 가심사→ceval→DSR 자동 트리거(비동기)는 테스트의 수동 ceval/DSR 호출과 같은 appl_id 에
        // 충돌(unique 위반)하므로 통합테스트에서는 끈다. 각 플로우는 값을 직접 통제한다.
        r.add("loan.auto-trigger.enabled", () -> "false");
        r.add("doc-agent.base-url", () -> "http://localhost:" + DOC_AGENT_MOCK.port());
        r.add("auto-review.base-url", () -> "http://localhost:" + AUTO_REVIEW_MOCK.port());
        r.add("payment.url", () -> "http://localhost:" + PAYMENT_MOCK.port());
    }

    @Autowired private WebApplicationContext wac;

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper om;

    @BeforeAll
    void initTestAuth() {
        mockMvc = buildAuthMockMvc();
    }

    @BeforeEach
    void resetTestAuth() {
        mockMvc = buildAuthMockMvc();
    }

    private MockMvc buildAuthMockMvc() {
        // GatewayHeaderAuthFilter 가 X-User-Id / X-User-Role 헤더를 읽어 SecurityContext 를 설정한다.
        String roles = "ROLE_STAFF,ROLE_OPS,ROLE_SENIOR_REVIEWER,ROLE_INTERNAL,"
                + "ROLE_TELLER,ROLE_DEPUTY_MANAGER,ROLE_BRANCH_MANAGER,"
                + "ROLE_HQ_REVIEWER,ROLE_COMPLIANCE,ROLE_ADMIN";
        // 헤더는 RequestPostProcessor(.with) 가 아니라 빌더의 .header() 로 지정한다.
        //
        // .with(request -> request.addHeader(...)) 는 defaultRequest 병합이 끝난 뒤
        // 실제 요청 객체에 직접 append 하므로, 테스트가 자기 X-User-Id 를 지정해도
        // 헤더가 두 개가 되고 GatewayHeaderAuthFilter 는 첫 번째(기본값)를 읽는다.
        // 그 결과 호출자 신원을 바꿀 수 없어 4-eye 위반·권한 거부 같은 네거티브
        // 테스트가 전부 200 으로 통과해 버렸다.
        //
        // .header() 로 지정하면 MockHttpServletRequestBuilder 병합 규칙이 적용되어
        // 테스트가 같은 헤더를 지정한 경우 테스트 값이 우선한다.
        return MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(MockMvcRequestBuilders.get("/")
                        .header("X-User-Id", "1")
                        .header("X-User-Role", roles))
                .build();
    }

    protected JsonNode extractData(MvcResult result) throws Exception {
        return om.readTree(result.getResponse().getContentAsString()).get("data");
    }

    // ====================================================================
    // FK 를 만족하는 최소 픽스처
    //
    // loan_application.prod_id → loan_product(prod_id),
    // loan_review.appl_id      → loan_application(appl_id) 로 FK 가 걸려 있다(V1).
    // 임의의 랜덤 id 로 자식 row 를 넣으면 DataIntegrityViolationException 이 난다.
    // 실제 부모 row 를 만들어 쓰도록 공용 헬퍼를 둔다.
    // ====================================================================

    @Autowired protected LoanApplicationRepository applicationRepository;
    @Autowired protected CollateralRepository collateralRepository;
    @Autowired protected LoanReviewRepository reviewRepository;

    private Long cachedProdId;

    /**
     * FK 를 만족하는 테스트용 상품 1건. 클래스(=컨텍스트) 당 한 번만 만든다.
     *
     * <p>리포지토리로 직접 만들지 않고 생성 API 를 쓴다. JPA 는 모든 컬럼을 명시
     * INSERT 하므로 DB DEFAULT 가 적용되지 않아, NOT NULL 컬럼이 마이그레이션으로
     * 늘어날 때마다(V10 min_guarantor_count 등) 픽스처를 따라 고쳐야 한다.
     * 서비스 계층을 태우면 기본값 채움이 한 곳에서 유지된다.
     */
    protected Long ensureTestProduct() {
        if (cachedProdId != null) {
            return cachedProdId;
        }
        try {
            MvcResult r = mockMvc.perform(MockMvcRequestBuilders.post("/api/loan-products")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "prodCd":"%s","prodName":"테스트 상품","loanTypeCd":"CREDIT",
                                      "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED","baseRateBps":500,
                                      "minAmount":1000000,"maxAmount":500000000,
                                      "minPeriodMo":6,"maxPeriodMo":360,
                                      "collateralRequiredYn":false,"guarantorRequiredYn":false
                                    }
                                    """.formatted("TESTPROD_" + UUID.randomUUID().toString().substring(0, 12))))
                    .andReturn();
            cachedProdId = extractData(r).get("prodId").asLong();
            return cachedProdId;
        } catch (Exception e) {
            throw new IllegalStateException("테스트 상품 생성 실패", e);
        }
    }

    /** FK 를 만족하는 신청 1건을 저장하고 applId 를 돌려준다. */
    protected Long saveTestApplication() {
        return saveTestApplication(LoanApplication.STATUS_REJECTED, null);
    }

    /**
     * FK 를 만족하는 본심사 1건(신청 포함)을 저장하고 revId 를 돌려준다.
     * review_advisory_report.rev_id → loan_review(rev_id) FK 용.
     *
     * <p>loan_review.appl_id 는 UNIQUE 이므로 호출할 때마다 새 신청을 만든다.
     */
    protected Long saveTestReview() {
        return saveTestReview(LoanReview.DECISION_APPROVED);
    }

    /** 결정 코드를 지정해 본심사 1건을 저장하고 revId 를 돌려준다. */
    protected Long saveTestReview(String decisionCd) {
        boolean approved = LoanReview.DECISION_APPROVED.equals(decisionCd);
        return reviewRepository.save(LoanReview.builder()
                .applId(saveTestApplication())
                .revTypeCd(LoanReview.TYPE_MANUAL)
                .revStatusCd(LoanReview.STATUS_COMPLETED)
                .revDecisionCd(decisionCd)
                .approvedAmount(approved ? 30_000_000L : null)
                .approvedRateBps(approved ? 500 : null)
                .approvedPeriodMo(approved ? 24 : null)
                .reviewerId(1L)
                .reviewedAt(OffsetDateTime.now())
                .approvedAt(approved ? OffsetDateTime.now() : null)
                .build()).getRevId();
    }

    /**
     * FK 를 만족하는 담보 1건을 저장하고 colId 를 돌려준다.
     * ltv_calculation.col_id → collateral(col_id) FK 용.
     */
    protected Long saveTestCollateral(Long applId) {
        return collateralRepository.save(Collateral.builder()
                .applId(applId)
                .colTypeCd("APARTMENT")
                .colStatusCd(Collateral.STATUS_REGISTERED)
                .colNo("TESTCOL_" + UUID.randomUUID().toString().substring(0, 12))
                .declaredValue(300_000_000L)
                .currencyCd("KRW")
                .seniorLienYn(false)
                .seniorLienAmount(0L)
                .build()).getColId();
    }

    /** 상태·자금용도를 지정해 신청 1건을 저장하고 applId 를 돌려준다. */
    protected Long saveTestApplication(String applStatusCd, String loanPurposeCd) {
        return applicationRepository.save(LoanApplication.builder()
                .applNo("TESTAPP_" + UUID.randomUUID().toString().substring(0, 12))
                .customerId(2_040_000L + ThreadLocalRandom.current().nextLong(99_999))
                .prodId(ensureTestProduct())
                .channelCd("TEST")
                .requestedAmount(10_000_000L)
                .requestedPeriodMo(24)
                .loanPurposeCd(loanPurposeCd)
                .repaymentMethodCd("EQUAL")
                .applStatusCd(applStatusCd)
                .appliedAt(OffsetDateTime.now())
                .build()).getApplId();
    }
}
