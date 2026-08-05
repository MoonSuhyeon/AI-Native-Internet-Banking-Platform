package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 약관 적용 관리 (deposit_term_application_management)
 * ERD 기준 테이블 — 수신 상품/계약에 적용되는 약관 매핑 정보
 *
 * common_term_id : 공통 서비스 약관 ID (MVP: FK 미적용, 외부 서비스 참조)
 * term_target_id : 약관 적용 대상 ID   (MVP: FK 미적용)
 */
@Entity
@Table(name = "deposit_term_application_management")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TermApplicationManagement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "term_application_id")
    private Long termApplicationId;

    /** 공통 서비스 약관 ID (외부 서비스 참조 — FK 없음) */
    @Column(name = "common_term_id")
    private Long commonTermId;

    /** 약관 적용 대상 ID (FK 없음) */
    @Column(name = "term_target_id")
    private Long termTargetId;

    /** 업무 구분 코드: DEPOSIT / SAVINGS / SUBSCRIPTION */
    @Column(name = "business_type_code", length = 10)
    private String businessTypeCode;

    /** 필수 여부: Y = 필수 | N = 선택 */
    @Column(name = "is_required", columnDefinition = "CHAR(1)")
    @Builder.Default
    private String isRequired = "N";

    /** 등록일 (YYYYMMDD) */
    @Column(name = "registered_at", columnDefinition = "CHAR(8)")
    private String registeredAt;

    /** 수정일 (YYYYMMDD) */
    @Column(name = "modified_at", columnDefinition = "CHAR(8)")
    private String modifiedAt;
}
