package com.bank.customer.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 민감정보(주민등록번호 등) 양방향 암복호화 — AES-256-GCM.
 *
 * <p>{@link com.bank.customer.party.domain.PartyPerson#rrnEncrypted} 등 PII 컬럼의 암복호화를
 * 서비스 레이어에서 이 컴포넌트로 일원화한다. 평문은 절대 로그/응답에 남기지 않는다.
 *
 * <p>저장 포맷: Base64( IV(12B) || ciphertext+GCM tag ). 키는 설정값 패스프레이즈를
 * SHA-256 으로 32바이트로 정규화해 사용한다(키 길이 실수 방지). 운영 키는 env 로 주입한다.
 */
// 빈 이름을 명시해 common 모듈의 cryptoService(@Bean, 별도 키 파생)와 이름 충돌을 피한다.
// 주입은 타입 기준이며, 본 구현은 customer.crypto.rrn-key(SHA-256 정규화)로 기존 RRN 암복호화를 유지한다.
@Component("customerCryptoService")
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH      = 12;   // GCM 권장 96bit
    private static final int    TAG_BITS       = 128;

    private final SecretKeySpec key;
    private final SecureRandom  random = new SecureRandom();

    public CryptoService(@Value("${customer.crypto.rrn-key:dev-rrn-crypto-key-change-in-production}") String passphrase,
                         org.springframework.core.env.Environment environment) {
        // 운영에서 기본 키면 기동 중단 — 키가 공개값이면 주민번호를 암호화한 의미가 사라진다.
        com.bank.common.security.DevSecretGuard.verify("customer.crypto.rrn-key", passphrase, environment);
        this.key = new SecretKeySpec(sha256(passphrase), "AES");
    }

    /** 평문 → Base64(IV || ciphertext+tag). null/blank 는 그대로 통과. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("암호화 실패", e);
        }
    }

    /** Base64(IV || ciphertext+tag) → 평문. null/blank 는 그대로 통과. */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return encoded;
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(in, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[in.length - IV_LENGTH];
            System.arraycopy(in, IV_LENGTH, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("복호화 실패", e);
        }
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
