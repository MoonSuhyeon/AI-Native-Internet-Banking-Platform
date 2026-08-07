package com.bank.customer.party.dto;

import java.time.OffsetDateTime;

/**
 * 제재대상 스크리닝 행 — 제재대상 Hit 검토 화면(/admin/screening)의 진입점.
 *
 * <p>compliance_info에서 OFAC·UN·EU·KR 중 하나라도 제재(is_sanctioned_yn은 이 넷의 OR GENERATED)인
 * party를 이름·인적사항과 함께 반환한다. 각 제재목록 매칭 여부(Y/N)와 마지막 스크리닝 시점을 담는다.
 *
 * <p>일치율·Hit 상태(검토대기/승인/거절)·검토자는 스크리닝 hit 단위 데이터로, 이를 담는 도메인 테이블이
 * 아직 없어 본 응답에서 제외한다 — 검토 워크플로는 별도 스키마(sanction_screening_hit) 신설이 필요하다.
 */
public record SanctionedPartyResponse(
        Long           partyId,
        String         partyName,
        String         birthDate,
        String         nationalityCode,
        Boolean         ofacSanctionedYn,
        Boolean         unSanctionedYn,
        Boolean         euSanctionedYn,
        Boolean         krSanctionedYn,
        String         amlRiskLevelCode,
        OffsetDateTime sanctionLastScreenedAt
) {
}
