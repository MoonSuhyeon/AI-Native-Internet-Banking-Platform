package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.ProductJoinChannel;
import com.bank.deposit.domain.enums.JoinChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductJoinChannelRepository extends JpaRepository<ProductJoinChannel, Long> {
    List<ProductJoinChannel> findByProductId(Long productId);
    boolean existsByProductIdAndJoinChannelCode(Long productId, JoinChannel joinChannelCode);
    void deleteByProductIdAndProductJoinChannelId(Long productId, Long channelId);
}
