package com.bank.customer.branch.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.branch.domain.Branch;
import com.bank.customer.branch.domain.BranchConsultationReservation;
import com.bank.customer.branch.dto.BranchReservationResponse;
import com.bank.customer.branch.dto.BranchResponse;
import com.bank.customer.branch.dto.CreateBranchReservationRequest;
import com.bank.customer.branch.repository.BranchConsultationReservationRepository;
import com.bank.customer.branch.repository.BranchRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 영업점 조회와 지점 상담 예약.
 *
 * <p>예전에는 이 화면이 통째로 흉내였다 — 지점검색은 핸들러가 없었고 예약 버튼은
 * alert 만 띄웠다. 고객은 예약됐다고 믿고 지점에 갔을 것이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class BranchService {

    private final BranchRepository branchRepository;
    private final BranchConsultationReservationRepository reservationRepository;

    @Transactional(readOnly = true)
    public List<BranchResponse> search(String keyword) {
        String q = (keyword == null || keyword.isBlank()) ? null : keyword.strip();
        return branchRepository.search(q).stream().map(BranchResponse::of).toList();
    }

    public BranchReservationResponse reserve(Long customerId, CreateBranchReservationRequest request) {
        Branch branch = branchRepository.findById(request.branchId())
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_180));

        BranchConsultationReservation reservation;
        try {
            reservation = BranchConsultationReservation.reserve(
                    customerId, branch, request.reservedAt(), request.topicCd(),
                    request.memo(), request.contactPhone(), OffsetDateTime.now());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CustomerErrorCode.CUST_182, e.getMessage());
        }

        try {
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            // 유니크 인덱스가 같은 지점·같은 시각의 중복 예약을 막는다. 두 번 눌렀을
            // 때 자리가 둘 잡히면 지점이 한 자리를 헛되이 비워 둔다.
            throw new BusinessException(CustomerErrorCode.CUST_183);
        }
        return BranchReservationResponse.of(reservation, branch.getBranchName());
    }

    @Transactional(readOnly = true)
    public List<BranchReservationResponse> myReservations(Long customerId) {
        List<BranchConsultationReservation> rows =
                reservationRepository.findByCustomerIdOrderByReservedAtDesc(customerId);

        // 지점 이름을 한 번에 읽는다 — 행마다 조회하면 예약이 늘수록 쿼리가 늘어난다.
        Map<Long, String> names = branchRepository.findAllById(
                        rows.stream().map(BranchConsultationReservation::getBranchId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Branch::getBranchId, Branch::getBranchName, (a, b) -> a));

        return rows.stream()
                .map(r -> BranchReservationResponse.of(r, names.getOrDefault(r.getBranchId(), "")))
                .toList();
    }

    public void cancel(Long customerId, Long reservationId) {
        BranchConsultationReservation reservation = reservationRepository
                .findByReservationIdAndCustomerId(reservationId, customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_181));
        reservation.cancel(OffsetDateTime.now());
    }
}
