# 테스트 하네스 규약

백엔드 통합 테스트를 쓸 때 반복해서 밟은 지뢰들을 정리한 문서다.
loan-service 테스트 94건이 한 번에 무너졌다가 복구되는 과정에서 드러난 것들이라,
"이론적으로 좋은 관행"이 아니라 **실제로 여기서 깨진 것들**만 담았다.

---

## 1. 프로필 설정 파일은 서비스마다 반드시 있어야 한다

`src/test/resources/application-test.yml` 이 없으면 테스트 프로필이 운영용
`application.yml` 로 폴백한다. 운영 설정에는 기본값 없는 필수 환경변수가 있어서
(예: `${CRYPTO_KEY_BASE64}`) 빈 생성이 실패하고, **모든 `@SpringBootTest` 컨텍스트가
같이 죽는다.**

loan-service 는 이 파일 하나가 없어서 328건만 실행되고 나머지가 통째로 실패했다.
파일을 만들자 752건이 돌기 시작했다. 테스트가 "실패"로도 안 잡히고 그냥 실행되지 않는
형태라 오래 방치됐다.

```yaml
# 최소 형태
crypto:
  key-base64: <테스트용 더미>
server:
  port: 0
```

---

## 2. 기능 플래그를 `@DynamicPropertySource` 에 박지 말 것

`@DynamicPropertySource` 는 **우선순위가 가장 높아** `@TestPropertySource` 로도
덮을 수 없다. 공통 하네스에 플래그를 하드코딩하면 그 플래그를 켜야 하는 테스트가
영원히 켤 수 없게 된다.

- 컨테이너 주소·포트처럼 **런타임에만 알 수 있는 값** → `@DynamicPropertySource`
- 기능 on/off 같은 **테스트가 바꿔야 할 값** → `application-test.yml` 에 기본값을 두고
  개별 테스트가 `@TestPropertySource` 로 덮는다

---

## 3. 공유 DB 컨테이너 — 전역 건수를 단언하지 말 것

Testcontainers 싱글톤 컨테이너를 여러 테스트 클래스가 공유한다. 다른 클래스가 남긴
데이터가 그대로 보이므로 다음은 전부 깨진다.

```java
// 나쁨 — 다른 클래스가 남긴 BIAS_REVIEWING 까지 세어 1 대신 10 이 나왔다
.andExpect(jsonPath("$.data.processed").value(1));
```

```java
// 좋음 — "내가 만든 것이 처리됐는가"
.andExpect(jsonPath("$.data.expiredRevIds[?(@ == %d)]".formatted(revId)).exists());
```

같은 이유로 목록 조회에서 `totalCount` 를 고정값으로 단언하는 것도 위험하다.
자기 계약(cntrId)·자기 신청(applId)으로 스코프가 좁혀지는지 먼저 확인한다.

---

## 4. jsonb 컬럼은 문자열로 비교하지 말 것

PostgreSQL `jsonb` 는 저장 시 **키 순서를 재정렬하고 공백을 정규화**한다.
직렬화한 그대로 돌아오지 않는다.

```java
// 나쁨 — 실제 저장값은 {"applId": 260} (콜론 뒤 공백)
assertThat(payload).contains("\"applId\":" + applId);

// 좋음
assertThat(objectMapper.readTree(payload).path("applId").asLong()).isEqualTo(applId);
```

---

## 5. 실제 시각에 묶인 고정 날짜를 쓰지 말 것

고정 날짜를 박아두면 시간이 흐르면서 전제가 뒤집힌다. 작성 시점에는 통과하다가
몇 달 뒤 조용히 깨지는데, 원인이 코드 변경이 아니라 달력이라 추적이 오래 걸린다.

실제로 겪은 것:

- 계약 시작 `20230101` + 12개월 → 만기가 지나 잔여개월 0 → **중도상환 수수료가 0** 이 되어
  `assertThat(fee).isPositive()` 실패. 프로덕션이 맞았고 테스트 전제가 낡은 것이었다.
- "미래" 로 잡은 `20270101` 도 그 날짜가 지나면 회차가 연체로 바뀌어 같은 문제가 된다.

```java
// 좋음 — 오늘 기준 상대값
private static final String CNTR_START =
        LocalDate.now().minusMonths(3).withDayOfMonth(1).format(YMD);
```

**영업일 보정도 같이 고려한다.** 만기일·회차일은 휴일 보정으로 하루 이틀 밀린다
(`20230401` → `20230403`). 경계값을 정확히 맞추려 하지 말고 여유를 둔다.

---

## 6. 외부 연동 stub 의 기본값이 "아무 일도 안 일어남" 이면 규칙은 검증되지 않는다

하네스의 advisory stub 이 항상 빈 배열(`[]`)을 돌려주고 있었다. 그 결과
**CRITICAL 권고 미확인 시 약정을 막는 4-eye 게이트가 한 번도 발화하지 않았다.**
전 테스트가 초록인데 규칙은 아무것도 지켜지지 않는 상태였다.

기본 stub 은 "정상 경로가 흐르게" 하는 용도일 뿐이다.
**차단 규칙이 있으면 그 규칙을 발화시키는 테스트를 따로 둔다.**
stub 을 갈아끼웠다면 `@AfterAll` 에서 기본값을 복구한다 — WireMock 서버가 공유 자원이라
복구하지 않으면 뒤에 도는 클래스가 그 stub 을 물려받는다.

---

## 7. `MockMvc` 기본 헤더는 `.header()` 로 준다

`defaultRequest()` 에 `RequestPostProcessor(.with)` 로 헤더를 붙이면 개별 요청과
병합되지 않는다. 빌더의 `.header()` 를 쓴다.

```java
MockMvcBuilders.webAppContextSetup(wac)
        .apply(SecurityMockMvcConfigurers.springSecurity())
        .defaultRequest(MockMvcRequestBuilders.get("/")
                .header("X-User-Id", "1")
                .header("X-User-Role", roles))
        .build();
```

---

## 8. 예외는 메시지가 아니라 에러코드로 단언한다

`BusinessException` 은 코드를 `getErrorCode()` 로만 노출하고 메시지에는 담지 않는다.
`hasMessageContaining("LOAN_151")` 은 **구조적으로 통과할 수 없는 단언**이다.

```java
assertThatThrownBy(() -> service.ack(id, req))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(LoanErrorCode.LOAN_151);
```

---

## 9. 비동기 후처리는 조건 대기로 확인한다

`@Async` + `@TransactionalEventListener(AFTER_COMMIT)` 로 붙은 후처리는 도착 시점이
고정되지 않는다. 예를 들어 계약 체결 시 신용정보 신고가 자동발행된다.

```java
await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> ...);
```

도착 **순서**도 보장되지 않으므로 위치(`items[0]`)가 아니라 구성으로 검증한다
(`containsInAnyOrder`).

---

## 10. 새 의존성을 추가하면 단위 테스트의 `@Mock` 도 같이 늘린다

서비스에 생성자 의존성을 추가하면 `@InjectMocks` 단위 테스트는 컴파일은 통과하고
**런타임 NPE** 로 죽는다. 통합 테스트만 돌려보고 넘어가면 놓친다.

---

## 11. 미구현 기능을 비활성화로 덮지 않는다

테스트가 실패할 때 `@Disabled` 로 사유를 남기는 것은 손쉬운 탈출구지만,
그 사유가 "미구현" 이라면 **테스트가 옳고 코드가 빠진 것**이다.
서류 다운로드·doc-agent 이벤트 컨슈머가 그랬고, 둘 다 구현해서 되살렸다.

비활성화가 정당한 경우는 외부 자원이 필요할 때 정도다. 이때도 `@Disabled` 가 아니라
조건부 활성화를 쓴다.

```java
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
```

---

## 12. 원인을 두 번 틀리면 추측을 멈추고 상태를 찍는다

자동발행 타임아웃을 "타임아웃이 짧다" → "커넥션 풀 고갈" 로 두 번 잘못 짚었다.
세 번째로 **실제 스케줄 행을 그대로 출력**했더니 원인이 한 번에 나왔다
(휴일 보정으로 만기일이 하루 밀려 있었다). 그 한 번의 출력으로 9개 날짜의 영업일 여부를
동시에 확인해 세 개 클래스를 연달아 고쳤다.

임시 `System.out` 은 Gradle 콘솔에 안 보인다. `build/test-results/test/TEST-*.xml` 의
`<system-out>` 에서 읽는다.
