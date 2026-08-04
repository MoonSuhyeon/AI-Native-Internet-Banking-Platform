package com.bank.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 16진 해시 유틸 (전 서비스 공통).
 *
 * <p>리프레시 토큰을 Redis(RT:)에 평문으로 두지 않기 위한 단방향 해시 등에 쓴다.
 * customer-service {@code LoginService}·{@code AuthEventService} 에 중복돼 있던 구현을 통합했다.
 */
public final class Sha256 {

    private Sha256() {
    }

    /** 입력 문자열의 SHA-256 해시를 소문자 16진 문자열로 반환한다. */
    public static String hex(String input) {
        return hex(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 바이트 배열의 SHA-256 해시를 소문자 16진 문자열로 반환한다.
     * 업로드 파일 무결성 해시(loan_document.doc_hash) 등 바이너리 입력용.
     */
    public static String hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
