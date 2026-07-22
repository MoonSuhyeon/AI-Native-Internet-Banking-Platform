# Deposit Service Review Fix Plan

## 범위

수신계 리뷰에서 지적된 거래 정합성, 보안, 동시성, 추천 성능, 마이그레이션, 문서화 이슈를 수정한다.

## 진행 상태

- [x] `TransactionService.transfer` 수신 계좌 검증 강화
  - 수신 계좌가 없으면 출금하지 않도록 `orElseThrow` 흐름으로 변경
  - `toAccountNo`와 수신 계좌의 `accountNumber` 일치 검증
  - 외부 이체는 내부 수신 거래를 생성하지 않도록 분리
- [x] 계좌 비밀번호 저장 보안 강화
  - 계좌 생성 및 계약 생성 경로에서 BCrypt 해시 적용
  - `Account` 엔티티 저장 직전 평문/빈 비밀번호 차단 가드 추가
- [x] 잔액 변경 동시성 보호
  - `Account`에 `@Version` 추가
  - 입금, 출금, 이체 계좌 조회에 `PESSIMISTIC_WRITE` 락 적용
- [x] 추천 서비스 명칭 정리
  - 구현체를 `CashflowBasedRecommendService`로 분리
  - 기존 API 경로는 프론트 호환을 위해 유지
- [x] 추천 조회 N+1 및 전체 메모리 로딩 개선
  - 가입금액 조건을 DB 쿼리로 푸시다운
  - 상품 금리 조건을 상품 ID 목록 기준으로 일괄 조회
- [x] `periodMonth` 서비스 레벨 가드 추가
  - 컨트롤러 외 호출에서도 1 이상만 허용
- [x] Flyway 버전 갭 및 시드 정책 정리
  - `V3`, `V4` 실제 파일 존재 확인
  - `V2`, `V7`, `V8`은 no-op으로 전환
  - 로컬 시드는 `LocalDataSeeder`로 제한
- [x] 날짜 문자열 저장 개선
  - `Account`, `Contract` 날짜를 `LocalDate` / SQL `DATE`로 전환
  - `V10__account_dates_and_number_sequence.sql` 추가
- [x] 엔티티 직접 노출 제거
  - 상품 조회 API에 `ProductResponse` DTO 적용
- [x] 계좌번호 발급 방식 개선
  - UUID 8자리 대신 DB sequence + check digit 사용
- [x] 설정 정리
  - 미사용 Redis 의존성 제거
  - default 설정의 DEBUG 로그 제거
  - 로컬 DB 기본값은 local profile로 제한
- [x] IDOR 방어
  - `X-Customer-Id`와 요청 `customerId` 일치 검증 추가
  - 추천, 계좌, 계약 컨트롤러에 적용
- [x] 직접 시간 호출 제거
  - 주요 서비스에 `Clock` 주입
  - 테스트에서 고정 `Clock` 사용
- [x] 문서화
  - 루트 README와 deposit-service README에 보안·정합성 변경 사항 반영

## 검증

- 2026-05-27: `.\gradlew.bat :services:deposit-service:test` 성공

## 남은 사항

- 운영 환경에 이미 적용된 Flyway checksum이 있다면 배포 전 마이그레이션 이력 정책 확인 필요
