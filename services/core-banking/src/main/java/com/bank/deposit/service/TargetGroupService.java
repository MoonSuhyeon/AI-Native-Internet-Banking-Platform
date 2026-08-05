package com.bank.deposit.service;

import com.bank.deposit.domain.entity.TargetGroup;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.TargetGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TargetGroupService {

    private final TargetGroupRepository targetGroupRepository;

    public List<TargetGroup> findAll(Boolean isActive) {
        if (isActive != null) return targetGroupRepository.findByIsActive(isActive);
        return targetGroupRepository.findAll();
    }

    public TargetGroup findById(Long id) {
        return targetGroupRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "가입 대상 그룹을 찾을 수 없습니다."));
    }

    @Transactional
    public TargetGroup create(String targetGroupName, String description) {
        return targetGroupRepository.save(TargetGroup.builder()
                .targetGroupName(targetGroupName)
                .description(description)
                .build());
    }

    @Transactional
    public TargetGroup update(Long id, String targetGroupName, String description) {
        TargetGroup tg = findById(id);
        tg.update(targetGroupName, description);
        return tg;
    }
}
