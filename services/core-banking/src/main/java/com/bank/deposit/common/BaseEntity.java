package com.bank.deposit.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /**
     * 낙관적 락. 계약·상품·특약을 두 곳에서 동시에 고칠 때 나중 쓰기가 앞의 것을 조용히
     * 덮는 것을 막는다. 덮였다는 사실조차 남지 않는 것이 이 결함의 성질이라, 잔액처럼
     * 비관적 락으로 감싸는 경로가 아니어도 필요하다.
     *
     * <p>여기 두는 이유: 이 클래스를 상속하는 엔티티가 17개다. 개별로 붙이면 새 엔티티에서
     * 빠지고, 빠진 것을 알아채기 어렵다. 컬럼은 db-deposit/V19 가 채운다.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
