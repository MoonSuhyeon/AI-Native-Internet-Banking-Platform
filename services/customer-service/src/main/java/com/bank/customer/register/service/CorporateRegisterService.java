package com.bank.customer.register.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.domain.CustomerGradeHistory;
import com.bank.customer.customer.domain.CustomerStatusHistory;
import com.bank.customer.customer.domain.Credential;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.customer.repository.CustomerGradeHistoryRepository;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.customer.repository.CustomerStatusHistoryRepository;
import com.bank.customer.party.domain.BusinessInfo;
import com.bank.customer.party.domain.ComplianceInfo;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.domain.PartyOrganization;
import com.bank.customer.party.domain.PartyRole;
import com.bank.customer.party.repository.BusinessInfoRepository;
import com.bank.customer.party.repository.ComplianceInfoRepository;
import com.bank.customer.party.repository.PartyOrganizationRepository;
import com.bank.customer.party.repository.PartyRepository;
import com.bank.customer.party.repository.PartyRoleRepository;
import com.bank.customer.register.dto.CorporateRegisterRequest;
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
public class CorporateRegisterService {

    private static final DateTimeFormatter DATE_FMT    = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int               MAX_FAILURE = 5;

    private final PartyRepository                  partyRepository;
    private final PartyOrganizationRepository      partyOrgRepository;
    private final BusinessInfoRepository           businessInfoRepository;
    private final ComplianceInfoRepository         complianceInfoRepository;
    private final PartyRoleRepository              partyRoleRepository;
    private final CustomerRepository               customerRepository;
    private final CredentialRepository             credentialRepository;
    private final CustomerStatusHistoryRepository  statusHistoryRepository;
    private final CustomerGradeHistoryRepository   gradeHistoryRepository;
    private final PasswordEncoder                  passwordEncoder;

    public RegisterResponse register(CorporateRegisterRequest req) {

        if (credentialRepository.existsByLoginIdAndDeletedAtIsNull(req.loginId())) {
            throw new BusinessException(CustomerErrorCode.CUST_001);
        }
        if (partyOrgRepository.findByCorpRegNoAndDeletedAtIsNull(req.corpRegNo()).isPresent()) {
            throw new BusinessException(CustomerErrorCode.CUST_003);
        }
        if (businessInfoRepository.existsByBizRegNoAndDeletedAtIsNull(req.bizRegNo())) {
            throw new BusinessException(CustomerErrorCode.CUST_003);
        }

        OffsetDateTime now   = OffsetDateTime.now();
        String         today = now.format(DATE_FMT);

        // 1) Party (기업)
        Party party = partyRepository.save(Party.builder()
                .partyTypeCode(Party.TYPE_ORGANIZATION)
                .partyName(req.corpName())
                .partyEnglishName(req.corpEnglishName())
                .partyStatusCode(Party.STATUS_ACTIVE)
                .build());

        // 2) PartyOrganization
        partyOrgRepository.save(PartyOrganization.builder()
                .partyId(party.getPartyId())
                .orgSubtypeCode(PartyOrganization.SUBTYPE_CORPORATION)
                .corpTypeCode("STOCK")   // 주식회사 기본값 — chk_party_org_subtype: CORPORATION 은 corp_type_code NOT NULL 요구
                .corpRegNo(req.corpRegNo().replaceAll("-", ""))
                .corpFormalName(req.corpName())
                .corpFormalEnglishName(req.corpEnglishName())
                .hqCountryCode("KOR")
                .build());

        // 3) BusinessInfo
        businessInfoRepository.save(BusinessInfo.builder()
                .partyId(party.getPartyId())
                .bizRegNo(req.bizRegNo().replaceAll("-", ""))
                .bizStatusCode("ACTIVE")
                .tradeName(req.tradeName())
                .openingDate(req.openingDate())
                .ntsIndustryCode(req.ntsIndustryCode())
                .ksicCode(req.ksicCode())
                .bizItemCode(req.bizItemCode())
                .taxTypeCode(req.taxTypeCode())
                .build());

        // 4) ComplianceInfo (기본값으로 초기화)
        complianceInfoRepository.save(ComplianceInfo.builder()
                .partyId(party.getPartyId())
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

        // 5) PartyRole — CUSTOMER 역할 부여
        partyRoleRepository.save(PartyRole.builder()
                .partyId(party.getPartyId())
                .roleTypeCode(PartyRole.TYPE_CUSTOMER)
                .roleStatusCode(PartyRole.STATUS_ACTIVE)
                .roleStartDate(today)
                .build());

        // 6) Customer
        Customer customer = customerRepository.save(Customer.builder()
                .partyId(party.getPartyId())
                .customerStatusCode(Customer.STATUS_ACTIVE)
                .customerGradeCode(Customer.GRADE_NORMAL)
                .mainCustomerYn(false)
                .smsReceiveYn(false)
                .emailReceiveYn(false)
                .postalReceiveYn(false)
                .joinChannelCode("ONLINE")
                .firstJoinDate(today)
                .joinedAt(now)
                .email(req.email())
                .phone(req.phone())
                .build());

        // 7) Credential
        credentialRepository.save(Credential.builder()
                .customerId(customer.getCustomerId())
                .loginId(req.loginId())
                .passwordHash(passwordEncoder.encode(req.password()))
                .accountStatusCode(Credential.STATUS_ACTIVE)
                .passwordLoginFailureCount(0)
                .maxPasswordLoginFailureCount(MAX_FAILURE)
                .passwordChangedAt(now)
                .build());

        // 8) 이력
        statusHistoryRepository.save(CustomerStatusHistory.ofInitial(customer.getCustomerId(), now));
        gradeHistoryRepository.save(CustomerGradeHistory.ofInitial(
                customer.getCustomerId(), Customer.GRADE_NORMAL, today, now));

        return new RegisterResponse(customer.getCustomerId(), req.loginId());
    }
}
