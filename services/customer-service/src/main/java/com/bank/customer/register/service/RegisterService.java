package com.bank.customer.register.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.customer.domain.Credential;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.domain.CustomerGradeHistory;
import com.bank.customer.customer.domain.CustomerStatusHistory;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.customer.repository.CustomerGradeHistoryRepository;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.customer.repository.CustomerStatusHistoryRepository;
import com.bank.customer.party.domain.ComplianceInfo;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.domain.PartyPerson;
import com.bank.customer.party.domain.PartyRole;
import com.bank.customer.party.repository.ComplianceInfoRepository;
import com.bank.customer.party.repository.PartyPersonRepository;
import com.bank.customer.party.repository.PartyRepository;
import com.bank.customer.party.repository.PartyRoleRepository;
import com.bank.customer.identity.domain.IdentityVerification;
import com.bank.customer.identity.repository.IdentityVerificationRepository;
import com.bank.customer.metrics.AuthMetrics;
import com.bank.customer.register.dto.RegisterRequest;
import com.bank.customer.register.dto.RegisterResponse;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Transactional
public class RegisterService {

    private static final DateTimeFormatter DATE_FMT      = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int               MAX_PWD_FAIL  = 5;
    /** 본인확인 후 가입까지 허용 시간(분) — 지나면 재인증 필요 */
    private static final long              VERIFICATION_VALIDITY_MINUTES = 30;

    private final PartyRepository                  partyRepository;
    private final PartyPersonRepository            partyPersonRepository;
    private final PartyRoleRepository              partyRoleRepository;
    private final ComplianceInfoRepository         complianceInfoRepository;
    private final CustomerRepository               customerRepository;
    private final CredentialRepository             credentialRepository;
    private final CustomerStatusHistoryRepository  customerStatusHistoryRepository;
    private final CustomerGradeHistoryRepository   customerGradeHistoryRepository;
    private final IdentityVerificationRepository   identityVerificationRepository;
    private final PasswordEncoder                  passwordEncoder;
    private final AuthMetrics                      authMetrics;

    public RegisterResponse register(RegisterRequest request) {

        if (credentialRepository.existsByLoginIdAndDeletedAtIsNull(request.loginId())) {
            authMetrics.registerFailure("duplicate_login_id");
            throw new BusinessException(CustomerErrorCode.CUST_001);
        }

        // 검증된 신원(주민번호 본인확인)이 권위 소스 — 이름·생년월일·성별·CI·RRN 은 여기서 가져온다.
        IdentityVerification identity = loadVerifiedIdentity(request.verificationId());

        OffsetDateTime now   = OffsetDateTime.now();
        String         today = now.format(DATE_FMT);

        // party 패턴: 한 사람(CI) = 한 party, 역할(CUSTOMER/EMPLOYEE…)은 N개.
        // CI 로 동일인 party 가 이미 있으면(예: 직원) 새 party 를 만들지 않고 역할·고객만 더한다.
        String ci = identity.getIdentityVerificationCiValue();
        RegisterResponse response = partyPersonRepository.findByCiValueAndDeletedAtIsNull(ci)
                .map(pp -> registerOnExistingParty(pp.getPartyId(), request, now, today))
                .orElseGet(() -> registerNewParty(request, identity, now, today));

        identity.consume(response.customerId());   // 검증 1건은 가입 1건에만 사용
        return response;
    }

    /** verificationId 로 본인확인 이력을 읽고 유효성(목적·소비·만료)을 검사한다. */
    private IdentityVerification loadVerifiedIdentity(Long verificationId) {
        IdentityVerification identity = identityVerificationRepository.findById(verificationId)
                .orElseThrow(() -> {
                    authMetrics.registerFailure("verification_not_found");
                    return new BusinessException(CustomerErrorCode.CUST_094);
                });

        if (!isSignupPurpose(identity.getIdentityVerificationPurposeCode())) {
            authMetrics.registerFailure("invalid_purpose");
            throw new BusinessException(CustomerErrorCode.CUST_094);
        }
        if (identity.isConsumed()) {
            authMetrics.registerFailure("verification_consumed");
            throw new BusinessException(CustomerErrorCode.CUST_096);
        }
        if (identity.getIdentityVerifiedAt().plusMinutes(VERIFICATION_VALIDITY_MINUTES)
                .isBefore(OffsetDateTime.now())) {
            authMetrics.registerFailure("verification_expired");
            throw new BusinessException(CustomerErrorCode.CUST_095);
        }
        return identity;
    }

    private boolean isSignupPurpose(String purposeCode) {
        return "SIGNUP".equals(purposeCode) || "IDENTITY_VERIFY".equals(purposeCode);
    }

    /** 신규 신원 — party 부터 새로 만든다. 인적사항·CI·RRN 은 검증 이력에서 박제한다. */
    private RegisterResponse registerNewParty(RegisterRequest request, IdentityVerification identity,
                                              OffsetDateTime now, String today) {
        Party party = partyRepository.save(Party.builder()
                .partyTypeCode(Party.TYPE_PERSONAL)
                .partyName(identity.getIdentityVerificationName())
                .partyStatusCode(Party.STATUS_ACTIVE)
                .build());

        partyPersonRepository.save(PartyPerson.builder()
                .partyId(party.getPartyId())
                .birthDate(identity.getIdentityVerificationBirthDate())
                .genderCode(identity.getIdentityVerificationGenderCode())
                .ciValue(identity.getIdentityVerificationCiValue())
                .rrnEncrypted(identity.getRrnEncrypted())
                .isPepYn(false)
                .build());

        saveCustomerRole(party.getPartyId(), today);
        saveComplianceInfo(party.getPartyId());

        return createCustomerWithCredential(party.getPartyId(), request, now, today);
    }

    /**
     * 기존 party 재사용 — 고객 역할이 없던 party(예: 직원)에 CUSTOMER 역할과 고객 레코드를 더한다.
     * 이미 활성 고객이 있으면 중복 가입(uq_customer_active_per_party 와도 정합)이므로 거부한다.
     */
    private RegisterResponse registerOnExistingParty(Long partyId, RegisterRequest request,
                                                     OffsetDateTime now, String today) {
        if (customerRepository.existsByPartyIdAndCustomerStatusCodeNotAndDeletedAtIsNull(
                partyId, Customer.STATUS_CLOSED)) {
            authMetrics.registerFailure("duplicate_customer");
            throw new BusinessException(CustomerErrorCode.CUST_003);
        }

        // 고객 역할이 없으면 추가 (직원 등 다른 역할만 보유하던 party)
        if (partyRoleRepository.findByPartyIdAndRoleTypeCodeAndRoleStatusCodeAndDeletedAtIsNull(
                partyId, PartyRole.TYPE_CUSTOMER, PartyRole.STATUS_ACTIVE).isEmpty()) {
            saveCustomerRole(partyId, today);
        }

        // 컴플라이언스 정보가 없으면 초기화 (직원 시드 등은 미보유)
        if (complianceInfoRepository.findByPartyIdAndDeletedAtIsNull(partyId).isEmpty()) {
            saveComplianceInfo(partyId);
        }

        return createCustomerWithCredential(partyId, request, now, today);
    }

    /** CUSTOMER 역할 부여 (법인과 동일) */
    private void saveCustomerRole(Long partyId, String today) {
        partyRoleRepository.save(PartyRole.builder()
                .partyId(partyId)
                .roleTypeCode(PartyRole.TYPE_CUSTOMER)
                .roleStatusCode(PartyRole.STATUS_ACTIVE)
                .roleStartDate(today)
                .build());
    }

    /** ComplianceInfo — AML/KYC 초기화 (법인과 동일) */
    private void saveComplianceInfo(Long partyId) {
        complianceInfoRepository.save(ComplianceInfo.builder()
                .partyId(partyId)
                .amlRiskLevelCode(ComplianceInfo.AML_LOW)
                .isOfacSanctionedYn(false)
                .isUnSanctionedYn(false)
                .isEuSanctionedYn(false)
                .isKrSanctionedYn(false)
                .kycStatusCode(ComplianceInfo.KYC_PENDING)
                .cddLevelCode(ComplianceInfo.CDD_STANDARD)
                .eddRequiredYn(false)
                .fatcaStatusCode(ComplianceInfo.FATCA_PENDING)
                .fatcaReportableYn(false)
                .crsStatusCode(ComplianceInfo.CRS_PENDING)
                .crsReportableYn(false)
                .build());
    }

    /** 고객·자격증명·초기 이력 생성 (신규/기존 party 공통) */
    private RegisterResponse createCustomerWithCredential(Long partyId, RegisterRequest request,
                                                          OffsetDateTime now, String today) {
        Customer customer = customerRepository.save(Customer.builder()
                .partyId(partyId)
                .customerStatusCode(Customer.STATUS_ACTIVE)
                .customerGradeCode(Customer.GRADE_NORMAL)
                .mainCustomerYn(false)
                .smsReceiveYn(false)
                .emailReceiveYn(false)
                .postalReceiveYn(false)
                .joinChannelCode("ONLINE")
                .firstJoinDate(today)
                .joinedAt(now)
                .phone(request.phone())
                .email(request.email())
                .build());

        credentialRepository.save(Credential.builder()
                .customerId(customer.getCustomerId())
                .loginId(request.loginId())
                .passwordHash(passwordEncoder.encode(request.password()))
                .accountStatusCode(Credential.STATUS_ACTIVE)
                .passwordLoginFailureCount(0)
                .maxPasswordLoginFailureCount(MAX_PWD_FAIL)
                .passwordChangedAt(now)
                .build());

        customerStatusHistoryRepository.save(
                CustomerStatusHistory.ofInitial(customer.getCustomerId(), now));
        customerGradeHistoryRepository.save(
                CustomerGradeHistory.ofInitial(customer.getCustomerId(), Customer.GRADE_NORMAL, today, now));

        authMetrics.registerSuccess();
        return new RegisterResponse(customer.getCustomerId(), request.loginId());
    }
}
