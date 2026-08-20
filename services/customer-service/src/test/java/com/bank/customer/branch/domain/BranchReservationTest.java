package com.bank.customer.branch.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 지점 상담 예약 규칙.
 *
 * <p><b>무엇을 지키는가.</b> 예약이 <b>실제로 갈 수 있는 시각</b>으로만 잡히게 한다.
 * 예전에는 이 화면이 통째로 흉내였다 — 버튼이 alert 만 띄우고 아무것도 저장하지
 * 않았다. 저장하기 시작한 지금 더 나쁜 실패가 가능해졌다: 영업시간 밖으로 잡힌
 * 예약은 저장되고, 고객은 예약됐다고 믿고 헛걸음한다.
 *
 * <p>검사를 화면이 아니라 엔티티에 두는 이유도 그것이다. 화면만 막으면 API 를 직접
 * 부르는 경로가 뚫린다.
 */
class BranchReservationTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 21, 10, 0, 0, 0, ZoneOffset.ofHours(9));

    private static Branch branch() {
        return Branch.builder()
                .branchId(1L)
                .branchCode("B001")
                .branchName("강남중앙지점")
                .branchType("BRANCH")
                .region("서울 강남구")
                .address("서울특별시 강남구 테헤란로 152")
                .openTime("09:00")
                .closeTime("16:00")
                .active(true)
                .build();
    }

    private static OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(2026, 8, 25, hour, minute, 0, 0, ZoneOffset.ofHours(9));
    }

    private static BranchConsultationReservation reserve(OffsetDateTime when) {
        return BranchConsultationReservation.reserve(
                1L, branch(), when, "DEPOSIT", "적금 상담", "010-1111-2222", NOW);
    }

    @Test
    @DisplayName("영업시간 안이면 예약된다")
    void reservesWithinHours() {
        assertThat(reserve(at(14, 0)).getStatusCd())
                .isEqualTo(BranchConsultationReservation.STATUS_RESERVED);
    }

    @Test
    @DisplayName("영업 시작 전에는 예약할 수 없다")
    void rejectsBeforeOpen() {
        assertThatThrownBy(() -> reserve(at(8, 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("영업시간");
    }

    @Test
    @DisplayName("마감 시각 자체도 받지 않는다")
    void rejectsAtClosingTime() {
        // 16:00 마감인데 16:00 상담은 앉을 시간이 없다.
        assertThat(branch().isOpenAt(LocalTime.of(16, 0))).isFalse();
        assertThatThrownBy(() -> reserve(at(16, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("지난 시각으로는 예약할 수 없다")
    void rejectsPast() {
        OffsetDateTime yesterday = NOW.minusDays(1).withHour(14).withMinute(0);

        assertThatThrownBy(() -> reserve(yesterday))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지난 시각");
    }

    @Test
    @DisplayName("연락처 없이는 예약할 수 없다")
    void requiresContact() {
        // 화면이 "문자로 안내드리겠습니다" 라고 약속한다. 보낼 곳이 없으면 그 약속이
        // 지켜지지 않고, 고객은 안내가 안 왔다는 사실만 겪는다.
        assertThatThrownBy(() -> BranchConsultationReservation.reserve(
                1L, branch(), at(14, 0), "DEPOSIT", null, "  ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("연락처가 없다");
    }

    @Test
    @DisplayName("상담 내용 없이는 예약할 수 없다")
    void requiresTopic() {
        assertThatThrownBy(() -> BranchConsultationReservation.reserve(
                1L, branch(), at(14, 0), null, null, "010-1111-2222", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("취소하면 시각이 남는다")
    void cancelRecordsTime() {
        BranchConsultationReservation r = reserve(at(14, 0));
        r.cancel(NOW);

        assertThat(r.getStatusCd()).isEqualTo(BranchConsultationReservation.STATUS_CANCELLED);
        assertThat(r.getCancelledAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("두 번 취소할 수 없다")
    void cancelOnce() {
        BranchConsultationReservation r = reserve(at(14, 0));
        r.cancel(NOW);

        assertThatThrownBy(() -> r.cancel(NOW.plusHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
