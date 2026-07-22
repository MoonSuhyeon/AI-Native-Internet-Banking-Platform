package com.bank.docagent.verify.port;

public interface IdentityVerificationPort {

    enum VerifyType { DRIVER_LICENSE, RESIDENT_CARD, FOREIGNER_CARD }

    enum VerifyResult { VALID, INVALID, ERROR, SKIPPED }

    /**
     * @param type     진위확인 서류 유형
     * @param name     성명 (마스킹 해제된 원본 — 이 메서드 내부에서만 사용, 외부 노출 금지)
     * @param idNumber 면허번호 또는 주민번호 (원본)
     * @param birthDate 생년월일 YYYYMMDD (운전면허 API 필수)
     */
    VerifyResult verify(VerifyType type, String name, String idNumber, String birthDate);
}
