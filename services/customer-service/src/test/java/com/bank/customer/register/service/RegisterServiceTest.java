package com.bank.customer.register.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.customer.repository.CustomerGradeHistoryRepository;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.customer.repository.CustomerStatusHistoryRepository;
import com.bank.customer.identity.domain.IdentityVerification;
import com.bank.customer.identity.repository.IdentityVerificationRepository;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.domain.PartyPerson;
import com.bank.customer.party.repository.ComplianceInfoRepository;
import com.bank.customer.party.repository.PartyPersonRepository;
import com.bank.customer.party.repository.PartyRepository;
import com.bank.customer.party.repository.PartyRoleRepository;
import com.bank.customer.register.dto.RegisterRequest;
import com.bank.customer.register.dto.RegisterResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegisterServiceTest {

    @Mock private PartyRepository                 partyRepository;
    @Mock private PartyPersonRepository           partyPersonRepository;
    @Mock private PartyRoleRepository             partyRoleRepository;
    @Mock private ComplianceInfoRepository        complianceInfoRepository;
    @Mock private CustomerRepository              customerRepository;
    @Mock private CredentialRepository            credentialRepository;
    @Mock private CustomerStatusHistoryRepository customerStatusHistoryRepository;
    @Mock private CustomerGradeHistoryRepository  customerGradeHistoryRepository;
    @Mock private IdentityVerificationRepository  identityVerificationRepository;
    @Mock private PasswordEncoder                 passwordEncoder;
    @Mock private com.bank.customer.metrics.AuthMetrics authMetrics;

    private RegisterService service() {
        return new RegisterService(partyRepository, partyPersonRepository, partyRoleRepository,
                complianceInfoRepository, customerRepository, credentialRepository,
                customerStatusHistoryRepository, customerGradeHistoryRepository,
                identityVerificationRepository, passwordEncoder, authMetrics);
    }

    private IdentityVerification verified() {
        return IdentityVerification.builder()
                .identityVerificationId(7L)
                .identityVerificationPurposeCode("SIGNUP")
                .identityVerificationCiValue("CI1")
                .identityVerificationName("홍길동")
                .identityVerificationBirthDate("19900101")
                .identityVerificationGenderCode("M")
                .identityVerificationNationalityTypeCode("DOMESTIC")
                .identityVerifiedAt(OffsetDateTime.now())
                .rrnEncrypted("enc")
                .build();
    }

    private RegisterRequest request() {
        return new RegisterRequest("user1", "password123!", 7L, "01012345678", "a@b.com");
    }

    private void commonOk(IdentityVerification iv) {
        when(credentialRepository.existsByLoginIdAndDeletedAtIsNull(anyString())).thenReturn(false);
        when(identityVerificationRepository.findById(7L)).thenReturn(Optional.of(iv));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(partyRepository.save(any())).thenReturn(Party.builder().partyId(1L).build());
        when(customerRepository.save(any())).thenReturn(Customer.builder().customerId(100L).build());
    }

    @Test
    @DisplayName("신규 신원 — party 새로 생성, 검증 신원으로 채우고 검증 소비")
    void newParty() {
        IdentityVerification iv = verified();
        commonOk(iv);
        when(partyPersonRepository.findByCiValueAndDeletedAtIsNull("CI1")).thenReturn(Optional.empty());

        RegisterResponse res = service().register(request());

        assertThat(res.customerId()).isEqualTo(100L);
        verify(partyRepository).save(any());      // 새 party 생성됨
        assertThat(iv.isConsumed()).isTrue();     // 검증 1회 소비
    }

    @Test
    @DisplayName("동일 CI party 존재 — 새 party 없이 역할·고객만 추가")
    void existingPartyDedup() {
        IdentityVerification iv = verified();
        commonOk(iv);
        when(partyPersonRepository.findByCiValueAndDeletedAtIsNull("CI1"))
                .thenReturn(Optional.of(PartyPerson.builder().partyId(9008L).build()));
        when(customerRepository.existsByPartyIdAndCustomerStatusCodeNotAndDeletedAtIsNull(anyLong(), anyString()))
                .thenReturn(false);
        when(partyRoleRepository.findByPartyIdAndRoleTypeCodeAndRoleStatusCodeAndDeletedAtIsNull(
                anyLong(), anyString(), anyString())).thenReturn(Optional.empty());
        when(complianceInfoRepository.findByPartyIdAndDeletedAtIsNull(anyLong())).thenReturn(Optional.empty());

        RegisterResponse res = service().register(request());

        assertThat(res.customerId()).isEqualTo(100L);
        verify(partyRepository, never()).save(any());   // 새 party 만들지 않음
        assertThat(iv.isConsumed()).isTrue();
    }

    @Test
    @DisplayName("동일 party 에 이미 활성 고객 — 중복 가입 CUST_003")
    void duplicateActiveCustomer() {
        IdentityVerification iv = verified();
        when(credentialRepository.existsByLoginIdAndDeletedAtIsNull(anyString())).thenReturn(false);
        when(identityVerificationRepository.findById(7L)).thenReturn(Optional.of(iv));
        when(partyPersonRepository.findByCiValueAndDeletedAtIsNull("CI1"))
                .thenReturn(Optional.of(PartyPerson.builder().partyId(9008L).build()));
        when(customerRepository.existsByPartyIdAndCustomerStatusCodeNotAndDeletedAtIsNull(anyLong(), anyString()))
                .thenReturn(true);

        assertThatThrownBy(() -> service().register(request()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("verificationId 없음 — CUST_094")
    void verificationNotFound() {
        when(credentialRepository.existsByLoginIdAndDeletedAtIsNull(anyString())).thenReturn(false);
        when(identityVerificationRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().register(request()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("이미 소비된 본인확인 — CUST_096")
    void verificationConsumed() {
        IdentityVerification iv = IdentityVerification.builder()
                .identityVerificationId(7L)
                .identityVerificationPurposeCode("SIGNUP")
                .identityVerificationCiValue("CI1")
                .identityVerifiedAt(OffsetDateTime.now())
                .consumedYn(true)
                .build();
        when(credentialRepository.existsByLoginIdAndDeletedAtIsNull(anyString())).thenReturn(false);
        when(identityVerificationRepository.findById(7L)).thenReturn(Optional.of(iv));

        assertThatThrownBy(() -> service().register(request()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("만료된 본인확인 — CUST_095")
    void verificationExpired() {
        IdentityVerification iv = IdentityVerification.builder()
                .identityVerificationId(7L)
                .identityVerificationPurposeCode("SIGNUP")
                .identityVerificationCiValue("CI1")
                .identityVerifiedAt(OffsetDateTime.now().minusHours(1))
                .build();
        when(credentialRepository.existsByLoginIdAndDeletedAtIsNull(anyString())).thenReturn(false);
        when(identityVerificationRepository.findById(7L)).thenReturn(Optional.of(iv));

        assertThatThrownBy(() -> service().register(request()))
                .isInstanceOf(BusinessException.class);
    }
}
