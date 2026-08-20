package com.bank.customer.banking;

import com.bank.common.web.ApiResponse;
import com.bank.customer.banking.domain.FavoriteType;
import com.bank.customer.banking.dto.FavoriteTransferResponse;
import com.bank.customer.banking.dto.RegisterFavoriteTransferRequest;
import com.bank.customer.banking.dto.UpdateOrderRequest;
import com.bank.customer.banking.service.FavoriteTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 이체 즐겨찾기.
 *
 * <p>고객번호는 게이트웨이가 넣어 준 {@code X-Customer-Id} 만 쓴다. 요청 본문이나
 * 경로에서 받으면 아이디만 바꿔 남의 즐겨찾기를 읽고 지울 수 있다.
 */
@RestController
@RequestMapping("/api/v1/banking/favorites")
@RequiredArgsConstructor
public class FavoriteTransferController {

    private final FavoriteTransferService favoriteTransferService;

    /** 즐겨찾기 목록. 자주쓰는계좌와 단축이체는 화면이 달라 종류로 나눠 준다. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<FavoriteTransferResponse>>> list(
            @RequestHeader("X-Customer-Id") Long customerId,
            @RequestParam FavoriteType type) {
        return ResponseEntity.ok(ApiResponse.ok(favoriteTransferService.list(customerId, type)));
    }

    /** 즐겨찾기 등록 */
    @PostMapping
    public ResponseEntity<ApiResponse<FavoriteTransferResponse>> register(
            @RequestHeader("X-Customer-Id") Long customerId,
            @Valid @RequestBody RegisterFavoriteTransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(favoriteTransferService.register(customerId, request)));
    }

    /** 즐겨찾기 삭제 */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @RequestHeader("X-Customer-Id") Long customerId,
            @PathVariable Long id) {
        favoriteTransferService.delete(customerId, id);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /** 목록 순서 변경 */
    @PutMapping("/order")
    public ResponseEntity<ApiResponse<Void>> updateOrder(
            @RequestHeader("X-Customer-Id") Long customerId,
            @RequestParam FavoriteType type,
            @Valid @RequestBody UpdateOrderRequest request) {
        favoriteTransferService.updateOrder(customerId, type, request);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
