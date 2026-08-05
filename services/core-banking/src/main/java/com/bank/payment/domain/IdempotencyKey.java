package com.bank.payment.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 멱등키 (idempotency_key)
 *
 * 중복 요청 방지. 형식: {API코드}-{결제지시ID}-{시도번호}
 * - V2__create_idempotency_key.sql 정합
 * - 컬럼명세서 v12.2 본문 #176~#188 기반
 * - payment_instruction과 1:1 (UNIQUE FK, V2에서 ALTER로 박힘)
 * - idempotencyStatus: PROCESSING → COMPLETED / FAILED
 * - 비즈니스 메서드는 Stage 5에서 추가
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class IdempotencyKey {

    // 멱등키값
    private String idempotencyKey;

    // 클라이언트식별자
    private String clientId;

    // 요청내용해시
    private String requestHash;

    // 멱등키상태
    private String idempotencyStatus;

    // 첫응답스냅샷 (JSONB → String. 직렬화/역직렬화는 Service 또는 typeHandler에서 처리 — Stage 5)
    private String firstResponseSnap;

    // 재시도횟수
    private Integer retryCount;

    // 최초수신시각
    private LocalDateTime firstReceivedAt;

    // 마지막수신시각
    private LocalDateTime lastReceivedAt;

    // 만료시각
    private LocalDateTime expiresAt;

    // 최초등록일시
    private LocalDateTime firstRegisteredAt;

    // 최초등록자식별번호
    private String firstRegistrantId;

    // 최종수정일시
    private LocalDateTime lastModifiedAt;

    // 최종수정자식별번호
    private String lastModifierId;

    // ── 멱등키 생성 [공통] ───────────────────────────────
    /** 멱등키 생성. idempotencyStatus=PROCESSING 초기 */
    public static IdempotencyKey of(
            String idempotencyKey, String clientId, String requestHash,
            LocalDateTime firstReceivedAt, LocalDateTime lastReceivedAt,
            LocalDateTime expiresAt) {
        return IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey)
                .clientId(clientId)
                .requestHash(requestHash)
                .idempotencyStatus("PROCESSING")
                .retryCount(0)
                .firstReceivedAt(firstReceivedAt)
                .lastReceivedAt(lastReceivedAt)
                .expiresAt(expiresAt)
                .build();
    }

    // ── PROCESSING → COMPLETED ──────────────────────────
    /** 멱등키 성공 확정. 최초 성공 응답 박제 */
    public void markCompleted(String firstResponseSnap) {
        if (!"PROCESSING".equals(this.idempotencyStatus)) {
            throw new IllegalStateException("PROCESSING 상태에서만 COMPLETED 전이 가능: " + this.idempotencyStatus);
        }
        this.idempotencyStatus = "COMPLETED";
        this.firstResponseSnap = firstResponseSnap;
    }

    // ── PROCESSING → FAILED ─────────────────────────────
    /** 멱등키 실패 확정 */
    public void markFailed() {
        if (!"PROCESSING".equals(this.idempotencyStatus)) {
            throw new IllegalStateException("PROCESSING 상태에서만 FAILED 전이 가능: " + this.idempotencyStatus);
        }
        this.idempotencyStatus = "FAILED";
    }
}
