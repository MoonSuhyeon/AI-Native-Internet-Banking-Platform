package com.bank.deposit.service;

import com.bank.deposit.domain.enums.CertificateType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 증빙 번호 채번.
 *
 * <p>형식: {@code RC-20260817-7F3K9Q2M} · {@code TC-20260817-7F3K9Q2M}
 *
 * <p><b>왜 순번이 아니라 난수인가.</b> 순번이면 증빙 번호가 곧 "오늘 몇 번째 발급인가"
 * 를 알려 준다. 발급량이 드러나는 것도 문제지만, 더 나쁜 것은 <b>번호를 추측할 수
 * 있다는 점</b>이다 — 번호로 조회하는 경로가 있으므로 남의 증빙을 찾아볼 수 있게 된다.
 *
 * <p>날짜를 앞에 두는 것은 사람이 읽기 위해서다. 언제 발급된 문서인지 번호만 보고
 * 알 수 있으면 보관·대조가 쉬워진다.
 *
 * <p>혼동하기 쉬운 글자(0/O, 1/I/L)는 뺐다. 종이에 인쇄된 번호를 사람이 옮겨 적는
 * 문서라, 한 글자만 잘못 읽어도 조회가 안 된다.
 */
@Component
@RequiredArgsConstructor
public class CertificateNumberGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int RANDOM_LEN = 8;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public String generate(CertificateType type) {
        String prefix = switch (type) {
            case RECEIPT -> "RC";
            case TRANSFER_CONFIRMATION -> "TC";
        };
        StringBuilder tail = new StringBuilder(RANDOM_LEN);
        for (int i = 0; i < RANDOM_LEN; i++) {
            tail.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return prefix + "-" + OffsetDateTime.now(clock).format(DATE) + "-" + tail;
    }
}
