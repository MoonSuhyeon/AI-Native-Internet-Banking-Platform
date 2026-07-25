package com.bank.customer.customer.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.domain.CustomerGradeHistory;
import com.bank.customer.customer.domain.CustomerStatusHistory;
import com.bank.customer.customer.repository.CustomerGradeHistoryRepository;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.customer.repository.CustomerStatusHistoryRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class CustomerLifecycleService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CustomerRepository              customerRepository;
    private final CustomerStatusHistoryRepository statusHistoryRepository;
    private final CustomerGradeHistoryRepository  gradeHistoryRepository;

    // ── 등급 변경 ─────────────────────────────────────────────────────────────

    @Transactional
    public void changeGrade(Long customerId, String newGradeCode,
                            String reasonCode, String reasonDetail, boolean systemTriggered,
                            Long actorEmployeeId) {
        Customer customer = findCustomer(customerId);
        String prevGrade  = customer.getCustomerGradeCode();
        OffsetDateTime now = OffsetDateTime.now();
        String today = now.format(DATE_FMT);

        CustomerGradeHistory prev = gradeHistoryRepository
                .findTopByCustomerIdOrderByCustomerGradeHistoryIdDesc(customerId)
                .orElse(null);

        customer.updateGrade(newGradeCode);

        gradeHistoryRepository.save(CustomerGradeHistory.ofTransition(
                customerId,
                prev != null ? prev.getCustomerGradeHistoryId() : null,
                prevGrade, newGradeCode,
                reasonCode, reasonDetail,
                today, now, systemTriggered, actorEmployeeId));
    }

    // ── 신용등급 업데이트 ──────────────────────────────────────────────────────

    @Transactional
    public void updateCreditRating(Long customerId, String ratingCode,
                                   String evaluationDate, String agencyCode) {
        Customer customer = findCustomer(customerId);
        customer.updateCreditRating(ratingCode, evaluationDate, agencyCode);
    }

    // ── 휴면 전환 ─────────────────────────────────────────────────────────────

    @Transactional
    public void makeDormant(Long customerId, String reasonDetail, boolean systemTriggered,
                            Long actorEmployeeId) {
        Customer customer = findCustomer(customerId);
        if (!customer.isActive()) {
            throw new BusinessException(CustomerErrorCode.CUST_012);
        }

        OffsetDateTime now = OffsetDateTime.now();
        CustomerStatusHistory prev = statusHistoryRepository
                .findTopByCustomerIdOrderByCustomerStatusHistoryIdDesc(customerId)
                .orElse(null);

        customer.dormant(now);

        statusHistoryRepository.save(CustomerStatusHistory.ofTransition(
                customerId,
                prev != null ? prev.getCustomerStatusHistoryId() : null,
                Customer.STATUS_ACTIVE, Customer.STATUS_DORMANT,
                CustomerStatusHistory.REASON_INACTIVITY,
                reasonDetail, now, systemTriggered, actorEmployeeId));
    }

    // ── 정지 ─────────────────────────────────────────────────────────────────

    /** 회원 정지(위험·규제 등에 의한 계정 동결). 해제는 {@link #reactivate}로 되돌린다. */
    @Transactional
    public void suspend(Long customerId, String reasonDetail, Long actorEmployeeId) {
        Customer customer = findCustomer(customerId);
        // 활성·휴면에서만 정지 가능 (이미 정지·해지 상태면 전이 불가)
        if (!customer.isActive() && !customer.isDormant()) {
            throw new BusinessException(CustomerErrorCode.CUST_012);
        }

        OffsetDateTime now = OffsetDateTime.now();
        String prevStatus  = customer.getCustomerStatusCode();
        CustomerStatusHistory prev = statusHistoryRepository
                .findTopByCustomerIdOrderByCustomerStatusHistoryIdDesc(customerId)
                .orElse(null);

        customer.suspend(now);

        statusHistoryRepository.save(CustomerStatusHistory.ofTransition(
                customerId,
                prev != null ? prev.getCustomerStatusHistoryId() : null,
                prevStatus, Customer.STATUS_SUSPENDED,
                CustomerStatusHistory.REASON_REGULATORY,
                reasonDetail, now, false, actorEmployeeId));
    }

    // ── 해지(탈퇴) ────────────────────────────────────────────────────────────

    /** 회원 해지(탈퇴). 개인정보 보유기간(해지일+5년)은 도메인에서 산정한다. */
    @Transactional
    public void close(Long customerId, String closeReasonCode, String reasonDetail,
                      Long actorEmployeeId) {
        Customer customer = findCustomer(customerId);
        if (customer.isClosed()) {
            throw new BusinessException(CustomerErrorCode.CUST_012);
        }

        OffsetDateTime now = OffsetDateTime.now();
        String prevStatus  = customer.getCustomerStatusCode();
        CustomerStatusHistory prev = statusHistoryRepository
                .findTopByCustomerIdOrderByCustomerStatusHistoryIdDesc(customerId)
                .orElse(null);

        customer.close(now, closeReasonCode);

        statusHistoryRepository.save(CustomerStatusHistory.ofTransition(
                customerId,
                prev != null ? prev.getCustomerStatusHistoryId() : null,
                prevStatus, Customer.STATUS_CLOSED,
                CustomerStatusHistory.REASON_CUST_REQ,
                reasonDetail, now, false, actorEmployeeId));
    }

    // ── 재활성화(휴면·정지 해제) ──────────────────────────────────────────────

    @Transactional
    public void reactivate(Long customerId, String reasonDetail, Long actorEmployeeId) {
        Customer customer = customerRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));
        if (!customer.isDormant() && !customer.isSuspended()) {
            throw new BusinessException(CustomerErrorCode.CUST_012);
        }

        OffsetDateTime now = OffsetDateTime.now();
        String prevStatus  = customer.getCustomerStatusCode();
        CustomerStatusHistory prev = statusHistoryRepository
                .findTopByCustomerIdOrderByCustomerStatusHistoryIdDesc(customerId)
                .orElse(null);

        customer.reactivate();

        statusHistoryRepository.save(CustomerStatusHistory.ofTransition(
                customerId,
                prev != null ? prev.getCustomerStatusHistoryId() : null,
                prevStatus, Customer.STATUS_ACTIVE,
                CustomerStatusHistory.REASON_REACTIVATE,
                reasonDetail, now, false, actorEmployeeId));
    }

    // ── 이력 조회 ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public java.util.List<CustomerStatusHistory> getStatusHistory(Long customerId) {
        return statusHistoryRepository.findByCustomerIdOrderByCustomerStatusHistoryIdDesc(customerId);
    }

    @Transactional(readOnly = true)
    public java.util.List<CustomerGradeHistory> getGradeHistory(Long customerId) {
        return gradeHistoryRepository.findByCustomerIdOrderByCustomerGradeHistoryIdDesc(customerId);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private Customer findCustomer(Long customerId) {
        return customerRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));
    }
}
