package com.bank.loan.advisory.listener;

import com.bank.loan.advisory.event.AdvisoryReportPublishedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;

class AdvisoryReportPublishedListenerTest {

    AdvisoryReportPublishedListener listener = new AdvisoryReportPublishedListener();

    @Test
    void 리포트_발행_이벤트_수신_시_예외_없이_처리() {
        AdvisoryReportPublishedEvent event = new AdvisoryReportPublishedEvent(
                501L, 201L, "CRITICAL", "2026-05-27");

        assertThatNoException().isThrownBy(() -> listener.onReportPublished(event));
    }

    @Test
    void targetReviewerId_null_이어도_예외_없이_처리() {
        AdvisoryReportPublishedEvent event = new AdvisoryReportPublishedEvent(
                502L, null, "WARN", "2026-05-27");

        assertThatNoException().isThrownBy(() -> listener.onReportPublished(event));
    }

    @Test
    void INFO_WARN_CRITICAL_severity_모두_정상_처리() {
        for (String severity : new String[]{"INFO", "WARN", "CRITICAL"}) {
            AdvisoryReportPublishedEvent event = new AdvisoryReportPublishedEvent(
                    504L, 203L, severity, "2026-05-27");
            assertThatNoException().isThrownBy(() -> listener.onReportPublished(event));
        }
    }
}
