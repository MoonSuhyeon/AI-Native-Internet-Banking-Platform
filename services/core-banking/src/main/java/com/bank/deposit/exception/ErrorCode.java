package com.bank.deposit.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    INVALID_STATUS(HttpStatus.BAD_REQUEST, "유효하지 않은 상태입니다."),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "잔액이 부족합니다."),
    ACCOUNT_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "계좌가 활성 상태가 아닙니다."),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다."),
    CONTRACT_NOT_FOUND(HttpStatus.NOT_FOUND, "계약을 찾을 수 없습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    PRODUCT_NOT_SELLING(HttpStatus.BAD_REQUEST, "판매 중인 상품이 아닙니다."),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "거래를 찾을 수 없습니다."),
    ALREADY_CANCELED(HttpStatus.BAD_REQUEST, "이미 취소된 거래입니다."),
    DAILY_TRANSFER_AMOUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "하루 이체 금액 한도를 초과했습니다."),
    DAILY_TRANSFER_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "하루 이체 횟수 한도를 초과했습니다."),
    DUPLICATE(HttpStatus.CONFLICT, "이미 존재하는 데이터입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
