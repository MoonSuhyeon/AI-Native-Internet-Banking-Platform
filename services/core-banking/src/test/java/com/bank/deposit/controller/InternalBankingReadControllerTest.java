package com.bank.deposit.controller;

import com.bank.deposit.audit.AccessActor;
import com.bank.deposit.audit.AccessActorResolver;
import com.bank.deposit.audit.ResourceAccessGuard;
import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.service.AccountService;
import com.bank.deposit.repository.ProductRepository;
import com.bank.deposit.service.ContractService;
import com.bank.deposit.service.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 내부 읽기 API 세 종이 <b>같은 관문</b>을 지나는지 확인한다.
 *
 * <p>새 엔드포인트를 추가할 때 가드를 빼먹는 것이 가장 흔한 사고다. 자원마다
 * 관문을 따로 구현하면 어느 하나가 조용히 비어도 드러나지 않는다. 그래서
 * 여기서 묻는 것은 응답 모양이 아니라 <b>가드를 통과했는가, 그리고 막히면
 * 자원을 건드리지 않는가</b> 다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("내부 읽기 API — 세 자원이 같은 관문을 쓴다")
class InternalBankingReadControllerTest {

    @Mock AccountService accountService;
    @Mock TransactionService transactionService;
    @Mock ContractService contractService;
    @Mock ProductRepository productRepository;
    @Mock AccessActorResolver actorResolver;
    @Mock ResourceAccessGuard accessGuard;

    @InjectMocks InternalBankingReadController controller;

    private static final String CUSTOMER = "1";

    @Test
    @DisplayName("계좌 조회는 DEPOSIT_ACCOUNT 자원으로 가드를 지난다")
    void accounts_pass_through_guard() {
        givenActor();
        given(accountService.findByCustomer(CUSTOMER)).willReturn(List.of(account()));

        controller.accounts(CUSTOMER, "9001", null, null, "민원 확인", "t1");

        verify(accessGuard).authorizeRead(any(), eq("DEPOSIT_ACCOUNT"), eq("READ"), eq(CUSTOMER));
    }

    @Test
    @DisplayName("거래 조회는 DEPOSIT_TRANSACTION 자원으로 가드를 지난다")
    void transactions_pass_through_guard() {
        givenActor();
        given(transactionService.findByCustomer(eq(CUSTOMER), any(Pageable.class)))
                .willReturn(Page.empty());

        controller.transactions(CUSTOMER, 50, "9001", null, null, "민원 확인", "t1");

        verify(accessGuard).authorizeRead(any(), eq("DEPOSIT_TRANSACTION"), eq("READ"), eq(CUSTOMER));
    }

    @Test
    @DisplayName("계약 조회는 DEPOSIT_CONTRACT 자원으로 가드를 지난다")
    void contracts_pass_through_guard() {
        givenActor();
        given(contractService.findAll(eq(CUSTOMER), any())).willReturn(List.of());

        controller.contracts(CUSTOMER, null, "9001", null, null, "민원 확인", "t1");

        verify(accessGuard).authorizeRead(any(), eq("DEPOSIT_CONTRACT"), eq("READ"), eq(CUSTOMER));
    }

    @Test
    @DisplayName("가드가 막으면 자원을 아예 조회하지 않는다")
    void denied_request_never_touches_the_resource() {
        givenActor();
        willThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .given(accessGuard).authorizeRead(any(), anyString(), anyString(), anyString());

        assertThatThrownBy(() ->
                controller.accounts(CUSTOMER, "9001", null, null, null, "t1"))
                .isInstanceOf(BusinessException.class);

        verify(accountService, never()).findByCustomer(anyString());
    }

    @Test
    @DisplayName("거래 조회 건수에 상한이 있다")
    void transaction_page_size_is_capped() {
        givenActor();
        given(transactionService.findByCustomer(eq(CUSTOMER), any(Pageable.class)))
                .willReturn(Page.empty());

        controller.transactions(CUSTOMER, 100_000, "9001", null, null, "민원 확인", "t1");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionService).findByCustomer(eq(CUSTOMER), captor.capture());
        assertThat(captor.getValue().getPageSize())
                .as("한 번의 조회로 전 이력을 끌어가면 그것 자체가 유출 경로다")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("건수를 0 이하로 줘도 최소 1건으로 올린다")
    void transaction_page_size_has_floor() {
        givenActor();
        given(transactionService.findByCustomer(eq(CUSTOMER), any(Pageable.class)))
                .willReturn(Page.empty());

        controller.transactions(CUSTOMER, 0, "9001", null, null, "민원 확인", "t1");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionService).findByCustomer(eq(CUSTOMER), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }

    private void givenActor() {
        given(actorResolver.resolve(any(), any(), any(), any(), any(), anyString(), anyString()))
                .willReturn(new AccessActor(AccessActor.EMPLOYEE, 9001L, null, null, "민원 확인", "t1"));
    }

    private Account account() {
        return Account.builder()
                .accountNumber("110-1111-0001")
                .customerId(CUSTOMER)
                .contractId(1L)
                .accountType(ProductType.DEPOSIT)
                .accountPassword("$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy")
                .openedAt(LocalDate.of(2026, 1, 1))
                .balance(1_000_000L)
                .build();
    }

    @SuppressWarnings("unused")
    private static Pageable unused() {
        return PageRequest.of(0, 1);
    }
}
