package com.bank.loan.advisory.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 격리 처분 규칙.
 *
 * <p><b>무엇을 지키는 테스트인가.</b> "격리를 풀 수 있다" 가 아니라 <b>아무나·아무렇게나
 * 풀 수 없다</b> 를 지킨다. 격리는 AI 감사가 편향·위반을 의심해 걸어 둔 것이라, 푸는
 * 행위 자체가 감사 대상이다. 누가·왜 풀었는지가 남지 않으면 통제가 아니라 그냥 상태
 * 변경이다.
 *
 * <p>규칙을 서비스가 아니라 엔티티에 둔 이유도 여기서 확인된다 — 처분 경로가 나중에
 * 하나 더 생겨도(배치·관리자 도구) 같은 검사를 지난다.
 */
class QuarantineDispositionTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now();
    private static final Long ACTOR = 9001L;

    private ReviewAdvisoryReport quarantined() {
        ReviewAdvisoryReport r = new ReviewAdvisoryReport();
        r.markQuarantined(NOW.minusDays(1));
        return r;
    }

    @Nested
    @DisplayName("처분할 수 있는 조건")
    class Preconditions {

        @Test
        @DisplayName("격리 상태가 아니면 처분할 수 없다")
        void notQuarantined() {
            ReviewAdvisoryReport open = new ReviewAdvisoryReport();

            assertThatThrownBy(() -> open.dispose(
                    ReviewAdvisoryReport.DISPOSITION_RELEASED, ACTOR, "확인함", NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("격리 상태가 아니다");
        }

        @Test
        @DisplayName("두 번 처분할 수 없다")
        void disposeOnce() {
            ReviewAdvisoryReport r = quarantined();
            r.dispose(ReviewAdvisoryReport.DISPOSITION_AUDIT_REFERRED, ACTOR, "조사 의뢰", NOW);

            assertThatThrownBy(() -> r.dispose(
                    ReviewAdvisoryReport.DISPOSITION_RELEASED, 9002L, "역시 정상", NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("이미 처분됐다");
        }

        @Test
        @DisplayName("알 수 없는 처분은 거절한다")
        void unknownDisposition() {
            assertThatThrownBy(() -> quarantined().dispose("APPROVED", ACTOR, "사유", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("감사에 남아야 하는 것")
    class AuditTrail {

        @Test
        @DisplayName("행위자 없이는 풀 수 없다")
        void actorRequired() {
            // 누가 풀었는지 모르는 기록은 "풀렸다" 만 남는다. 그건 감사가 아니다.
            assertThatThrownBy(() -> quarantined().dispose(
                    ReviewAdvisoryReport.DISPOSITION_RELEASED, null, "정상", NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("행위자가 없다");
        }

        @Test
        @DisplayName("사유 없이는 풀 수 없다")
        void noteRequired() {
            assertThatThrownBy(() -> quarantined().dispose(
                    ReviewAdvisoryReport.DISPOSITION_RELEASED, ACTOR, "  ", NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("처분 사유가 없다");
        }

        @Test
        @DisplayName("누가·언제·왜가 모두 남는다")
        void recordsWhoWhenWhy() {
            ReviewAdvisoryReport r = quarantined();
            r.dispose(ReviewAdvisoryReport.DISPOSITION_RELEASED, ACTOR, "  표본 편향 아님  ", NOW);

            assertThat(r.getQuarantineDisposedBy()).isEqualTo(ACTOR);
            assertThat(r.getQuarantineDisposedAt()).isEqualTo(NOW);
            assertThat(r.getQuarantineDispositionNote()).isEqualTo("표본 편향 아님");
        }
    }

    @Nested
    @DisplayName("처분에 따라 상태가 달라진다")
    class StatusTransition {

        @Test
        @DisplayName("정상 판정만 격리를 끝낸다")
        void releasedResolves() {
            ReviewAdvisoryReport r = quarantined();
            r.dispose(ReviewAdvisoryReport.DISPOSITION_RELEASED, ACTOR, "정상", NOW);

            assertThat(r.getAdvrStatusCd()).isEqualTo(ReviewAdvisoryReport.STATUS_RESOLVED);
            assertThat(r.getResolvedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("재심사·조사 의뢰는 격리를 유지한다")
        void openDispositionsStayQuarantined() {
            // 아직 열려 있는 사안이다. RESOLVED 로 바꾸면 "처리됐다" 로 집계돼
            // 격리 목록에서 사라지고, 그러면 아무도 다시 안 본다.
            for (String d : new String[]{
                    ReviewAdvisoryReport.DISPOSITION_REVIEW_REASSIGNED,
                    ReviewAdvisoryReport.DISPOSITION_AUDIT_REFERRED}) {
                ReviewAdvisoryReport r = quarantined();
                r.dispose(d, ACTOR, "사유", NOW);

                assertThat(r.getAdvrStatusCd())
                        .as("%s 는 아직 끝난 사안이 아니다", d)
                        .isEqualTo(ReviewAdvisoryReport.STATUS_QUARANTINE);
                assertThat(r.getResolvedAt()).isNull();
                assertThat(r.isDisposed()).isTrue();
            }
        }
    }
}
