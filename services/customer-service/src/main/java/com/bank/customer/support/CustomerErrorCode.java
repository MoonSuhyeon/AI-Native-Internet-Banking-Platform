package com.bank.customer.support;

import com.bank.common.web.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * CUST 도메인 에러코드.
 * 회원(001-009) / 인증(010-019) / 계정(020-029) 구간 사용.
 */
@Getter
@RequiredArgsConstructor
public enum CustomerErrorCode implements ErrorCode {

    // 회원
    CUST_001(HttpStatus.CONFLICT,   "이미 사용 중인 로그인 ID입니다."),
    CUST_002(HttpStatus.NOT_FOUND,  "고객 정보를 찾을 수 없습니다."),
    CUST_003(HttpStatus.CONFLICT,   "이미 가입된 고객입니다."),

    // 인증
    CUST_010(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    CUST_011(HttpStatus.FORBIDDEN,    "계정이 잠겨 있습니다. 비밀번호 5회 오류."),
    CUST_012(HttpStatus.FORBIDDEN,    "탈퇴하거나 비활성화된 계정입니다."),
    CUST_013(HttpStatus.UNAUTHORIZED, "비밀번호가 만료되었습니다."),

    // 설정
    CUST_020(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),

    // 인증서 로그인 (030-039)
    CUST_030(HttpStatus.NOT_FOUND,     "인증서를 찾을 수 없습니다."),
    CUST_031(HttpStatus.UNAUTHORIZED,  "인증서가 만료되었습니다."),
    CUST_032(HttpStatus.FORBIDDEN,     "폐기된 인증서입니다."),
    CUST_033(HttpStatus.UNAUTHORIZED,  "인증서 PIN이 올바르지 않습니다."),
    CUST_034(HttpStatus.FORBIDDEN,     "인증서가 잠겨 있습니다."),

    // QR 로그인 (040-049)
    CUST_040(HttpStatus.NOT_FOUND,   "QR 토큰을 찾을 수 없습니다."),
    CUST_041(HttpStatus.GONE,        "QR 코드가 만료되었습니다."),
    CUST_042(HttpStatus.CONFLICT,    "이미 처리된 QR 코드입니다."),

    // 출금계좌 관리 (050-059)
    CUST_050(HttpStatus.NOT_FOUND,  "출금계좌를 찾을 수 없습니다."),
    CUST_051(HttpStatus.CONFLICT,   "이미 등록된 출금계좌입니다."),
    CUST_052(HttpStatus.BAD_REQUEST,"본행 보유계좌는 출금계좌 등록 대상이 아닙니다. 타행 계좌만 등록할 수 있습니다."),

    // FDS (060-069)
    CUST_060(HttpStatus.FORBIDDEN,  "비정상 접근이 감지되어 요청이 차단되었습니다."),
    CUST_061(HttpStatus.CONFLICT,   "이미 존재하는 FDS 룰 코드입니다."),
    CUST_062(HttpStatus.NOT_FOUND,  "FDS 탐지 결과를 찾을 수 없습니다."),
    CUST_063(HttpStatus.CONFLICT,   "이미 사고 처리가 등록된 탐지 결과입니다."),
    CUST_064(HttpStatus.NOT_FOUND,  "FDS 룰을 찾을 수 없습니다."),

    // 등록기기 (070-079)
    CUST_070(HttpStatus.NOT_FOUND,  "등록된 기기를 찾을 수 없습니다."),
    CUST_071(HttpStatus.BAD_REQUEST,"지정 PC는 PC 타입 기기만 설정할 수 있습니다."),

    // PIN (080-089)
    CUST_080(HttpStatus.NOT_FOUND,  "등록된 PIN을 찾을 수 없습니다."),
    CUST_081(HttpStatus.CONFLICT,   "이미 PIN이 등록된 기기입니다."),
    CUST_082(HttpStatus.UNAUTHORIZED,"PIN이 올바르지 않습니다."),
    CUST_083(HttpStatus.FORBIDDEN,  "PIN이 잠겨 있습니다."),

    // 휴대폰 인증 (090-099)
    CUST_090(HttpStatus.NOT_FOUND,  "인증 요청을 찾을 수 없습니다."),
    CUST_091(HttpStatus.GONE,       "인증 코드가 만료되었습니다."),
    CUST_092(HttpStatus.BAD_REQUEST,"인증 코드가 올바르지 않습니다."),
    CUST_093(HttpStatus.CONFLICT,   "이미 검증된 인증 요청입니다."),

    // 본인확인 (094-097)
    CUST_094(HttpStatus.NOT_FOUND,  "본인확인 정보를 찾을 수 없습니다."),
    CUST_095(HttpStatus.GONE,       "본인확인이 만료되었습니다. 다시 인증해주세요."),
    CUST_096(HttpStatus.CONFLICT,   "이미 사용된 본인확인입니다."),
    CUST_097(HttpStatus.BAD_REQUEST,"주민등록번호 형식이 올바르지 않습니다."),

    // 세션 (100-109)
    CUST_100(HttpStatus.NOT_FOUND,  "세션을 찾을 수 없습니다."),

    // 관계자 역할·관계·컴플라이언스 (110-119)
    CUST_110(HttpStatus.CONFLICT,   "이미 활성 상태인 역할입니다."),
    CUST_111(HttpStatus.NOT_FOUND,  "역할을 찾을 수 없습니다."),
    CUST_112(HttpStatus.BAD_REQUEST,"자기 자신과의 관계는 등록할 수 없습니다."),
    CUST_113(HttpStatus.CONFLICT,   "이미 존재하는 관계입니다."),
    CUST_114(HttpStatus.NOT_FOUND,  "관계를 찾을 수 없습니다."),
    CUST_115(HttpStatus.NOT_FOUND,  "컴플라이언스 정보를 찾을 수 없습니다."),

    // 개인정보·외국인·납세 (120-129)
    CUST_120(HttpStatus.NOT_FOUND,  "외국인 정보를 찾을 수 없습니다."),
    CUST_121(HttpStatus.NOT_FOUND,  "납세거주 정보를 찾을 수 없습니다."),

    // 인증수단 (130-139)
    CUST_130(HttpStatus.NOT_FOUND,  "인증수단을 찾을 수 없습니다."),
    CUST_131(HttpStatus.BAD_REQUEST,"주 인증수단은 비활성화할 수 없습니다."),

    // 보안카드 (140-149)
    CUST_140(HttpStatus.NOT_FOUND,   "활성 보안카드를 찾을 수 없습니다."),
    CUST_141(HttpStatus.CONFLICT,    "이미 활성 보안카드가 존재합니다."),
    CUST_142(HttpStatus.UNAUTHORIZED,"보안카드 코드가 올바르지 않습니다."),
    CUST_143(HttpStatus.GONE,        "보안카드 챌린지가 만료되었거나 존재하지 않습니다."),
    CUST_144(HttpStatus.BAD_REQUEST, "챌린지에 없는 위치 코드가 포함되어 있습니다."),

    // 거래 승인 토큰 (145-149) — 이체 등 자금이동 step-up 인증
    CUST_145(HttpStatus.UNAUTHORIZED,"거래 승인 정보가 올바르지 않거나 이미 사용되었습니다."),
    CUST_146(HttpStatus.GONE,        "거래 승인 유효시간이 지났습니다. 다시 인증해 주세요."),

    // 이체한도 (150-159)
    CUST_150(HttpStatus.BAD_REQUEST,"이체한도는 온라인에서 감액만 가능합니다. 증액은 영업점 방문 또는 본인인증이 필요합니다."),
    CUST_151(HttpStatus.BAD_REQUEST,"이체한도는 0보다 큰 금액으로 입력해 주세요."),
    CUST_152(HttpStatus.BAD_REQUEST,"1회 이체한도는 1일 이체한도를 초과할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
