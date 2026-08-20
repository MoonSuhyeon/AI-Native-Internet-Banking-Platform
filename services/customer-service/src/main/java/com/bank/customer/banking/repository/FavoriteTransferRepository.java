package com.bank.customer.banking.repository;

import com.bank.customer.banking.domain.FavoriteTransfer;
import com.bank.customer.banking.domain.FavoriteType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteTransferRepository extends JpaRepository<FavoriteTransfer, Long> {

    List<FavoriteTransfer> findByCustomerIdAndFavoriteTypeOrderByDisplayOrderAscFavoriteIdAsc(
            Long customerId, FavoriteType favoriteType);

    Optional<FavoriteTransfer> findByFavoriteIdAndCustomerId(Long favoriteId, Long customerId);

    boolean existsByCustomerIdAndFavoriteTypeAndBankCodeAndAccountNumber(
            Long customerId, FavoriteType favoriteType, String bankCode, String accountNumber);

    /** 새 항목을 맨 아래에 둔다. 등록한 것이 목록 위로 튀어 오르면 순서가 흔들린다. */
    int countByCustomerIdAndFavoriteType(Long customerId, FavoriteType favoriteType);
}
