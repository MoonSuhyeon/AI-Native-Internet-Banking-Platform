package com.bank.deposit.controller;

import com.bank.deposit.audit.AccessActor;
import com.bank.deposit.audit.AccessActorResolver;
import com.bank.deposit.audit.ResourceAccessGuard;
import com.bank.deposit.domain.entity.Product;
import com.bank.deposit.domain.enums.ContractStatus;
import com.bank.deposit.dto.internal.InternalAccountSummary;
import com.bank.deposit.dto.internal.InternalContractSummary;
import com.bank.deposit.dto.internal.InternalInterestHistory;
import com.bank.deposit.dto.internal.InternalProductCatalogEntry;
import com.bank.deposit.dto.internal.InternalSpecialTerm;
import com.bank.deposit.dto.internal.InternalTransactionSummary;
import com.bank.deposit.service.AccountService;
import com.bank.deposit.service.ContractService;
import com.bank.deposit.service.InternalProductCatalogService;
import com.bank.deposit.repository.InterestHistoryRepository;
import com.bank.deposit.repository.ProductRepository;
import com.bank.deposit.repository.SpecialTermRepository;
import com.bank.deposit.service.TransactionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.bank.deposit.audit.AccessActorResolver.*;

/**
 * 수신 데이터 열람 — 감사가 붙은 내부 읽기 API.
 *
 * <p><b>왜 기존 {@code /accounts} 를 그대로 쓰지 않는가.</b> 기존 조회는 고객 본인
 * 소유권만 본다({@code X-Customer-Id} 일치). AI 상담처럼 <b>직원을 대신해</b> 남의 계좌를
 * 읽는 경로에는 맞지 않는다. 여기서는 행위자와 사유를 요구하고 전부 기록한다.
 *
 * <p><b>왜 감사가 API 와 같이 와야 하는가.</b> DB 직접 접근을 API 로 바꾸면서 감사를
 * 빼면 <b>"DB 는 안 보지만 감사도 안 남는 API"</b> 가 된다. 경계만 옮기고 통제는
 * 그대로 비는 것이라, 둘은 한 작업이다.
 *
 * <p>context-path 가 이미 {@code /api} 다. 여기에 또 {@code /api} 를 쓰면 외부 URL 이
 * {@code /api/api/...} 가 된다.
 */
@RestController
@RequestMapping("/v1/internal/banking")
@RequiredArgsConstructor
public class InternalBankingReadController {

    private static final String RESOURCE_ACCOUNT     = "DEPOSIT_ACCOUNT";
    private static final String RESOURCE_TRANSACTION = "DEPOSIT_TRANSACTION";
    private static final String RESOURCE_CONTRACT    = "DEPOSIT_CONTRACT";
    private static final String RESOURCE_INTEREST    = "DEPOSIT_TRANSACTION";
    private static final String ACTION_READ = "READ";

    /** 상담 한 번이 훑는 양의 상한. 없으면 한 번의 조회가 전 이력을 끌어간다. */
    private static final int MAX_PAGE_SIZE = 200;

    private final AccountService accountService;
    private final TransactionService transactionService;
    private final ContractService contractService;
    private final ProductRepository productRepository;
    private final InternalProductCatalogService productCatalogService;
    private final SpecialTermRepository specialTermRepository;
    private final InterestHistoryRepository interestHistoryRepository;
    private final AccessActorResolver actorResolver;
    private final ResourceAccessGuard accessGuard;

    /**
     * 고객의 계좌 요약 목록.
     *
     * <p>잔액이 포함되므로 열람 자체가 감사 대상이다. 비밀번호 해시·내부 식별자는
     * 내보내지 않는다 — 상담이 필요한 것은 잔액과 상태까지다.
     */
    @GetMapping("/customers/{customerId}/accounts")
    public List<InternalAccountSummary> accounts(
            @PathVariable String customerId,
            @RequestHeader(value = EMPLOYEE_ID_HEADER, required = false) String employeeId,
            @RequestHeader(value = CUSTOMER_ID_HEADER, required = false) String callerCustomerId,
            @RequestHeader(value = SERVICE_HEADER,     required = false) String service,
            @RequestHeader(value = REASON_HEADER,      required = false) String reason,
            @RequestHeader(value = TRACE_HEADER,       required = false) String traceId) {

        AccessActor actor = actorResolver.resolve(
                employeeId, callerCustomerId, service, reason, traceId,
                RESOURCE_ACCOUNT + "_" + ACTION_READ, customerId);

        // 두 경계를 모두 통과해야 한다 — 상류 인가와 자원 쪽 최종 판단.
        // 통과 여부와 무관하게 요청·판단·결과가 한 건으로 기록된다.
        accessGuard.authorizeRead(actor, RESOURCE_ACCOUNT, ACTION_READ, customerId);

        return accountService.findByCustomer(customerId).stream()
                .map(InternalAccountSummary::from)
                .toList();
    }

    /**
     * 고객의 최근 거래.
     *
     * <p>기간·건수를 좁힌다. 상담에 필요한 것은 최근 흐름이지 전 이력이 아니고,
     * 한 번의 조회로 전부 끌어가면 그것 자체가 유출 경로가 된다.
     */
    @GetMapping("/customers/{customerId}/transactions")
    public List<InternalTransactionSummary> transactions(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "50") int size,
            @RequestHeader(value = EMPLOYEE_ID_HEADER, required = false) String employeeId,
            @RequestHeader(value = CUSTOMER_ID_HEADER, required = false) String callerCustomerId,
            @RequestHeader(value = SERVICE_HEADER,     required = false) String service,
            @RequestHeader(value = REASON_HEADER,      required = false) String reason,
            @RequestHeader(value = TRACE_HEADER,       required = false) String traceId) {

        AccessActor actor = actorResolver.resolve(
                employeeId, callerCustomerId, service, reason, traceId,
                RESOURCE_TRANSACTION + "_" + ACTION_READ, customerId);
        accessGuard.authorizeRead(actor, RESOURCE_TRANSACTION, ACTION_READ, customerId);

        var pageable = PageRequest.of(0, Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "transactionAt"));

        // 본인 계좌 사이의 이체를 표시해 주기 위해 계좌 목록을 먼저 읽는다.
        // 상대 계좌 자체는 내보내지 않고, 본인 계좌인지 여부만 계산해 담는다.
        Set<Long> ownAccountIds = accountService.findByCustomer(customerId).stream()
                .map(a -> a.getAccountId())
                .collect(Collectors.toSet());

        return transactionService.findByCustomer(customerId, pageable).getContent().stream()
                .map(t -> InternalTransactionSummary.from(t, ownAccountIds))
                .toList();
    }

    /** 고객의 수신 계약. 만기 안내·상담에서 쓴다. */
    @GetMapping("/customers/{customerId}/contracts")
    public List<InternalContractSummary> contracts(
            @PathVariable String customerId,
            @RequestParam(required = false) ContractStatus contractStatus,
            @RequestHeader(value = EMPLOYEE_ID_HEADER, required = false) String employeeId,
            @RequestHeader(value = CUSTOMER_ID_HEADER, required = false) String callerCustomerId,
            @RequestHeader(value = SERVICE_HEADER,     required = false) String service,
            @RequestHeader(value = REASON_HEADER,      required = false) String reason,
            @RequestHeader(value = TRACE_HEADER,       required = false) String traceId) {

        AccessActor actor = actorResolver.resolve(
                employeeId, callerCustomerId, service, reason, traceId,
                RESOURCE_CONTRACT + "_" + ACTION_READ, customerId);
        accessGuard.authorizeRead(actor, RESOURCE_CONTRACT, ACTION_READ, customerId);

        var contracts = contractService.findAll(customerId, contractStatus);
        // 상품은 공개 카탈로그다. 계약 수만큼 개별 조회하지 않도록 한 번에 읽어 붙인다.
        Map<Long, Product> products = productRepository.findAllById(
                        contracts.stream().map(c -> c.getProductId())
                                .filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getProductId, java.util.function.Function.identity(), (a, b) -> a));

        return contracts.stream()
                .map(c -> InternalContractSummary.from(c, products.get(c.getProductId())))
                .toList();
    }

    /**
     * 판매 중인 상품 카탈로그 — 대상·금리·예금상세를 묶어 한 번에.
     *
     * <p><b>여기만 관문을 지나지 않는다.</b> 상품은 공개 카탈로그이지 고객 데이터가
     * 아니다. 행위자·사유를 요구하면 통제가 아니라 형식만 늘어나고, 열람 감사에
     * 의미 없는 행이 쌓여 정작 봐야 할 고객정보 열람이 묻힌다.
     */
    @GetMapping("/products")
    public List<InternalProductCatalogEntry> products() {
        return productCatalogService.sellingCatalog();
    }

    /**
     * 약관 전문. 상품 카탈로그와 같은 이유로 관문을 지나지 않는다 — 고객 데이터가 아니다.
     *
     * <p>검색어 매칭은 부르는 쪽이 한다. 여기서 LIKE 를 받으면 와일드카드 이스케이프를
     * 서비스 경계 너머에서 책임지게 되고, 한쪽만 고치면 조용히 어긋난다.
     */
    @GetMapping("/special-terms")
    public List<InternalSpecialTerm> specialTerms() {
        return specialTermRepository.findAll().stream()
                .map(InternalSpecialTerm::from)
                .toList();
    }

    /**
     * 고객의 이자 지급 내역.
     *
     * <p>자원 코드는 거래와 같은 것을 쓴다. 이자도 계좌에 실제로 들어온 돈이므로
     * 거래 열람과 같은 무게로 다룬다 — 따로 두면 거래는 막고 이자는 열리는
     * 조합이 생긴다.
     */
    @GetMapping("/customers/{customerId}/interest-history")
    public List<InternalInterestHistory> interestHistory(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "50") int size,
            @RequestHeader(value = EMPLOYEE_ID_HEADER, required = false) String employeeId,
            @RequestHeader(value = CUSTOMER_ID_HEADER, required = false) String callerCustomerId,
            @RequestHeader(value = SERVICE_HEADER,     required = false) String service,
            @RequestHeader(value = REASON_HEADER,      required = false) String reason,
            @RequestHeader(value = TRACE_HEADER,       required = false) String traceId) {

        AccessActor actor = actorResolver.resolve(
                employeeId, callerCustomerId, service, reason, traceId,
                RESOURCE_INTEREST + "_" + ACTION_READ, customerId);
        accessGuard.authorizeRead(actor, RESOURCE_INTEREST, ACTION_READ, customerId);

        List<Long> accountIds = accountService.findByCustomer(customerId).stream()
                .map(a -> a.getAccountId())
                .toList();
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return interestHistoryRepository.findByAccountIdInOrderByInterestIdDesc(accountIds).stream()
                .limit(Math.min(Math.max(size, 1), MAX_PAGE_SIZE))
                .map(InternalInterestHistory::from)
                .toList();
    }
}
