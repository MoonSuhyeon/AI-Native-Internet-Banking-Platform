package com.bank.deposit.service;

import com.bank.deposit.domain.entity.TermApplicationManagement;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.TermApplicationManagementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TermApplicationManagementService {

    private final TermApplicationManagementRepository repository;

    public List<TermApplicationManagement> findAll() {
        return repository.findAll();
    }

    public List<TermApplicationManagement> findByBusinessTypeCode(String businessTypeCode) {
        return repository.findByBusinessTypeCode(businessTypeCode);
    }

    public List<TermApplicationManagement> findByCommonTermId(Long commonTermId) {
        return repository.findByCommonTermId(commonTermId);
    }

    public List<TermApplicationManagement> findByIsRequired(String isRequired) {
        return repository.findByIsRequired(isRequired);
    }

    public TermApplicationManagement findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "약관 적용 관리 항목을 찾을 수 없습니다. id=" + id));
    }

    @Transactional
    public TermApplicationManagement create(TermApplicationManagement entity) {
        return repository.save(entity);
    }

    @Transactional
    public void delete(Long id) {
        TermApplicationManagement entity = findById(id);
        repository.delete(entity);
    }
}
