package com.bank.customer.banking.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.banking.domain.FavoriteTransfer;
import com.bank.customer.banking.domain.FavoriteType;
import com.bank.customer.banking.dto.FavoriteTransferResponse;
import com.bank.customer.banking.dto.RegisterFavoriteTransferRequest;
import com.bank.customer.banking.dto.UpdateOrderRequest;
import com.bank.customer.banking.repository.FavoriteTransferRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 이체 즐겨찾기 — 자주쓰는계좌·단축이체.
 *
 * <p>모든 조회·수정이 고객번호로 좁혀진다. 즐겨찾기 아이디만으로 찾는 메서드를 두지
 * 않는 이유다 — 아이디는 연속된 숫자라, 하나라도 그런 경로가 있으면 남의 이체 대상
 * 계좌번호를 세어 볼 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FavoriteTransferService {

    private final FavoriteTransferRepository favoriteTransferRepository;

    @Transactional(readOnly = true)
    public List<FavoriteTransferResponse> list(Long customerId, FavoriteType type) {
        return favoriteTransferRepository
                .findByCustomerIdAndFavoriteTypeOrderByDisplayOrderAscFavoriteIdAsc(customerId, type)
                .stream()
                .map(FavoriteTransferResponse::of)
                .toList();
    }

    public FavoriteTransferResponse register(Long customerId, RegisterFavoriteTransferRequest request) {
        if (favoriteTransferRepository.existsByCustomerIdAndFavoriteTypeAndBankCodeAndAccountNumber(
                customerId, request.favoriteType(), request.bankCode(), request.accountNumber())) {
            throw new BusinessException(CustomerErrorCode.CUST_161);
        }

        int order = favoriteTransferRepository.countByCustomerIdAndFavoriteType(
                customerId, request.favoriteType());

        FavoriteTransfer saved;
        try {
            saved = favoriteTransferRepository.save(FavoriteTransfer.register(
                    customerId, request.favoriteType(), request.alias(),
                    request.bankCode(), request.bankName(), request.accountNumber(),
                    request.accountHolderName(), request.amount(), order));
        } catch (IllegalArgumentException e) {
            // 엔티티가 금액 규칙을 막았다. 화면에 보이는 문구로 바꿔 준다.
            throw new BusinessException(CustomerErrorCode.CUST_162, e.getMessage());
        }
        return FavoriteTransferResponse.of(saved);
    }

    public void delete(Long customerId, Long favoriteId) {
        FavoriteTransfer favorite = favoriteTransferRepository
                .findByFavoriteIdAndCustomerId(favoriteId, customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_160));
        favoriteTransferRepository.delete(favorite);
    }

    public void updateOrder(Long customerId, FavoriteType type, UpdateOrderRequest request) {
        List<FavoriteTransfer> favorites = favoriteTransferRepository
                .findByCustomerIdAndFavoriteTypeOrderByDisplayOrderAscFavoriteIdAsc(customerId, type);

        for (int i = 0; i < request.orderedIds().size(); i++) {
            final int order = i;
            final Long id = request.orderedIds().get(i);
            favorites.stream()
                    .filter(f -> f.getFavoriteId().equals(id))
                    .findFirst()
                    .ifPresent(f -> f.changeOrder(order));
        }
    }
}
