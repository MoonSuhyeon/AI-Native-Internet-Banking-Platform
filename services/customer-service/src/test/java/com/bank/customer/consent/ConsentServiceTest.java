package com.bank.customer.consent;

import com.bank.customer.consent.domain.TermsConsent;
import com.bank.customer.consent.domain.TermsTemplate;
import com.bank.customer.consent.dto.ConsentRecordRequest;
import com.bank.customer.consent.dto.ConsentResponse;
import com.bank.customer.consent.repository.TermsConsentRepository;
import com.bank.customer.consent.repository.TermsTemplateRepository;
import com.bank.customer.consent.service.ConsentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 동의 기록의 규칙을 고정한다.
 *
 * <p>여기서 묻는 것은 "저장이 되는가" 가 아니다. 동의 이력은 뒤에 분쟁의 근거가 되는
 * 기록이라, <b>어떤 상태가 남으면 안 되는가</b>가 요점이다 — 반만 남은 동의,
 * 거절했는데 진행된 기록, 철회했는데 유효로 보이는 값.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("약관 동의 — 반만 남는 기록을 만들지 않는다")
class ConsentServiceTest {

    @Mock TermsConsentRepository consentRepository;
    @Mock TermsTemplateRepository templateRepository;
    @InjectMocks ConsentService service;

    private static final Long CUSTOMER = 9111L;

    // ── 기록 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("모르는 약관이 하나라도 있으면 아무것도 저장하지 않는다")
    void unknown_terms_rejects_everything() {
        // 아는 것만 저장하면 화면은 다 받았다고 여기는데 기록은 일부만 남는다.
        // 조용히 반만 남는 것이 가장 나쁘다.
        given(templateRepository.findByTermsNoIn(anyList()))
                .willReturn(List.of(template(1L, "CERT-001", true)));

        assertThatThrownBy(() -> service.record(CUSTOMER, request(
                new ConsentRecordRequest.Item("CERT-001", true),
                new ConsentRecordRequest.Item("CERT-999", true)), "203.0.113.5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CERT-999");

        verify(consentRepository, never()).save(any());
    }

    @Test
    @DisplayName("필수 약관을 거절하면 거절한다 — 진행된 기록을 남기지 않는다")
    void rejecting_required_terms_is_refused() {
        // "거절했는데 처리된" 기록이 가장 설명하기 어려운 상태다.
        given(templateRepository.findByTermsNoIn(anyList()))
                .willReturn(List.of(template(1L, "CERT-001", true)));

        assertThatThrownBy(() -> service.record(CUSTOMER,
                request(new ConsentRecordRequest.Item("CERT-001", false)), "203.0.113.5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CERT-001");

        verify(consentRepository, never()).save(any());
    }

    @Test
    @DisplayName("선택 약관 거절은 거절로 기록한다 — 안 받은 것과 다르다")
    void rejecting_optional_terms_is_recorded() {
        // "안 받았다" 와 "거절했다" 는 다른 사실이고, 분쟁이 되는 것은 그 차이다.
        given(templateRepository.findByTermsNoIn(anyList()))
                .willReturn(List.of(template(9L, "MKTG-001", false)));
        given(consentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        List<ConsentResponse> saved = service.record(CUSTOMER,
                request(new ConsentRecordRequest.Item("MKTG-001", false)), "203.0.113.5");

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).agreed()).isFalse();
        assertThat(saved.get(0).effective())
                .as("거절은 유효한 동의가 아니다")
                .isFalse();
    }

    @Test
    @DisplayName("기록에 행위자·경로·시점이 함께 남는다")
    void records_who_how_when() {
        // 뒤에 분쟁이 되면 묻는 것이 이 셋이다.
        given(templateRepository.findByTermsNoIn(anyList()))
                .willReturn(List.of(template(1L, "CERT-001", true)));
        given(consentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.record(CUSTOMER, request(new ConsentRecordRequest.Item("CERT-001", true)),
                "203.0.113.5");

        ArgumentCaptor<TermsConsent> captor = ArgumentCaptor.forClass(TermsConsent.class);
        verify(consentRepository).save(captor.capture());
        TermsConsent saved = captor.getValue();

        assertThat(saved.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(saved.getConsentMethodCd()).isEqualTo(TermsConsent.METHOD_WEB);
        assertThat(saved.getClientIp()).isEqualTo("203.0.113.5");
        assertThat(saved.getConsentTargetId()).isEqualTo(77L);
        assertThat(saved.getAgreedAt()).isNotNull();
    }

    // ── 철회 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("철회해도 동의했던 사실은 남는다")
    void withdrawal_keeps_the_fact() {
        given(consentRepository.findByConsentIdAndCustomerId(1L, CUSTOMER))
                .willReturn(Optional.of(agreedConsent()));
        given(templateRepository.findAllById(anyList()))
                .willReturn(List.of(template(1L, "CERT-001", true)));

        ConsentResponse out = service.withdraw(CUSTOMER, 1L, "고객 요청");

        assertThat(out.agreed()).as("동의했던 사실은 지워지지 않는다").isTrue();
        assertThat(out.withdrawn()).isTrue();
        assertThat(out.withdrawnAt()).isNotNull();
        assertThat(out.effective()).as("철회했으므로 지금은 효력이 없다").isFalse();
    }

    @Test
    @DisplayName("두 번 철회할 수 없다")
    void cannot_withdraw_twice() {
        TermsConsent consent = agreedConsent();
        consent.withdraw("첫 철회", OffsetDateTime.now(), CUSTOMER);
        given(consentRepository.findByConsentIdAndCustomerId(1L, CUSTOMER))
                .willReturn(Optional.of(consent));

        assertThatThrownBy(() -> service.withdraw(CUSTOMER, 1L, "또"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("남의 동의는 찾지 못한다 — 존재 여부도 알려주지 않는다")
    void cannot_withdraw_someone_elses() {
        // 아이디로 찾은 뒤 소유자를 검사하면 없는 건과 남의 건이 다른 응답을 내어
        // 존재 여부가 새어 나간다. 조건에 고객번호를 함께 넣는다.
        given(consentRepository.findByConsentIdAndCustomerId(1L, CUSTOMER))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(CUSTOMER, 1L, "사유"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 유효성 판단 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("철회한 동의는 유효하지 않다")
    void withdrawn_consent_is_not_effective() {
        // 이력에 행이 있다는 사실만 보면 철회한 고객에게 계속 마케팅이 나간다.
        given(templateRepository.findByTermsNo("MKTG-001"))
                .willReturn(Optional.of(template(9L, "MKTG-001", false)));
        given(consentRepository
                .findFirstByCustomerIdAndTermsTemplateIdAndAgreedYnTrueAndWithdrawnYnFalseOrderByAgreedAtDesc(
                        CUSTOMER, 9L))
                .willReturn(Optional.empty());

        assertThat(service.hasEffectiveConsent(CUSTOMER, "MKTG-001")).isFalse();
    }

    @Test
    @DisplayName("등록되지 않은 약관은 유효한 동의가 없다")
    void unknown_terms_has_no_consent() {
        given(templateRepository.findByTermsNo("NOPE")).willReturn(Optional.empty());
        assertThat(service.hasEffectiveConsent(CUSTOMER, "NOPE")).isFalse();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private static ConsentRecordRequest request(ConsentRecordRequest.Item... items) {
        return new ConsentRecordRequest("CERT", 77L, TermsConsent.METHOD_WEB, "web/1.0",
                List.of(items));
    }

    private static TermsConsent agreedConsent() {
        return TermsConsent.builder()
                .consentId(1L)
                .customerId(CUSTOMER)
                .termsTemplateId(1L)
                .bizDivCd("CERT")
                .agreedYn(true)
                .agreedAt(OffsetDateTime.now().minusDays(1))
                .consentMethodCd(TermsConsent.METHOD_WEB)
                .withdrawnYn(false)
                .createdAt(OffsetDateTime.now().minusDays(1))
                .updatedAt(OffsetDateTime.now().minusDays(1))
                .build();
    }

    private static TermsTemplate template(long id, String no, boolean required) {
        return TermsTemplate.builder()
                .termsTemplateId(id)
                .termsNo(no)
                .termsName(no + " 약관")
                .termsCategoryCd(TermsTemplate.CATEGORY_PRIVACY)
                .termsVersion("1.0")
                .requiredYn(required)
                .bizDivCd("CERT")
                .activeYn(true)
                .build();
    }
}
