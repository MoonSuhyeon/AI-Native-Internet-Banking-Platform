package com.bank.deposit.repository;

import com.bank.deposit.domain.entity.TargetGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TargetGroupRepository extends JpaRepository<TargetGroup, Long> {
    List<TargetGroup> findByIsActive(Boolean isActive);
    List<TargetGroup> findByTargetGroupIdIn(Collection<Long> ids);
}
