package com.bank.payment.domain.service;

import com.bank.payment.config.PaymentMetrics;
import com.bank.payment.domain.mapper.ReconciliationBreakRow;
import com.bank.payment.domain.mapper.ReconciliationMapper;
import com.bank.payment.domain.reconciliation.ClearingRecords;
import com.bank.payment.domain.reconciliation.ReconciliationBreak;
import com.bank.payment.domain.reconciliation.ReconciliationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 대사 실행 — 내부 원장과 외부 청산 기록을 영업일 단위로 맞춰 본다.
 *
 * <p><b>복식부기와 무엇이 다른가.</b> 복식부기는 우리 장부 안에서 차변과 대변이 맞는지만
 * 본다. 우리 장부가 바깥세상과 맞는지는 보장하지 않는다 — 출금 분개와 청산대기 분개가
 * 완벽히 균형을 이루면서도 정작 그 돈이 망으로 나가지 않았을 수 있다. 그 상태는
 * 원장만 봐서는 영원히 안 보이고, 대사가 유일한 발견 수단이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 청산대기가 쌓이는 내부 계정. 망마다 다르다. */
    private static final String KFTC_CLEARING_ACCOUNT = "KB-CLR-088";
    private static final String BOK_CLEARING_ACCOUNT = "KB-CLR-BOK";

    public static final String NETWORK_KFTC = "KFTC";
    public static final String NETWORK_BOK = "BOK";

    private final ReconciliationMapper reconciliationMapper;
    private final PaymentMetrics metrics;

    /**
     * 한 영업일·한 망을 대사하고 결과를 적재한다.
     *
     * <p>트랜잭션으로 묶은 이유는 <b>부분 적재를 남기지 않기 위해서</b>다. 불일치 절반만
     * 저장되고 해소 처리가 안 돌면, 다음에 볼 때 "줄었다" 로 읽혀 사고가 축소 보고된다.
     *
     * @param businessDate yyyyMMdd
     * @param network      {@link #NETWORK_KFTC} 또는 {@link #NETWORK_BOK}
     * @return 이번 실행에서 발견한 불일치 (심각한 것부터)
     */
    @Transactional
    public List<ReconciliationBreak> run(String businessDate, String network) {
        // 밀리초로 자르는 것이 중요하다. 컬럼이 TIMESTAMP(3) 이라 더 정밀한 값을 넣으면
        // 저장된 값과 파라미터가 미세하게 달라지고, 아래 closeResolved 의
        // "last_detected_at < now" 가 참이 되어 **방금 적재한 불일치를 그 자리에서
        // 해소 처리**한다. 대사는 정상으로 보이는데 불일치가 통째로 사라진다.
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);

        List<ClearingRecords.Internal> internal = reconciliationMapper.selectInternalClearing(
                businessDate, clearingAccountOf(network));
        List<ClearingRecords.External> external = NETWORK_BOK.equals(network)
                ? reconciliationMapper.selectExternalBok(businessDate)
                : reconciliationMapper.selectExternalKftc(businessDate);

        List<ReconciliationBreak> breaks =
                ReconciliationEngine.reconcile(internal, external, dayClosed(businessDate));

        for (ReconciliationBreak b : breaks) {
            reconciliationMapper.upsertBreak(businessDate, network, b, now);
        }

        // 이번에 안 나온 건은 해소된 것이다. 남겨 두면 목록이 옛 항목으로 가득 차
        // 아무도 안 보게 된다.
        int closed = reconciliationMapper.closeResolved(businessDate, network, now);

        // 0건도 남긴다. "돌았는데 깨끗했다" 와 "안 돌았다" 가 로그에서 구별되지 않으면
        // 대사가 멈춘 것을 눈치채지 못한다.
        log.info("[대사] 영업일={} 망={} 내부={}건 외부={}건 불일치={}건 해소={}건",
                businessDate, network, internal.size(), external.size(), breaks.size(), closed);

        metrics.reconciliationRun(network, breaks.size());

        return breaks;
    }

    /** 두 망을 한 번에. 마감 배치·수동 실행이 망을 빠뜨리지 않게 한다. */
    @Transactional
    public List<ReconciliationBreak> runAll(String businessDate) {
        List<ReconciliationBreak> all = new java.util.ArrayList<>(run(businessDate, NETWORK_KFTC));
        all.addAll(run(businessDate, NETWORK_BOK));
        return all;
    }

    @Transactional(readOnly = true)
    public List<ReconciliationBreakRow> breaks(String businessDate, String network) {
        return reconciliationMapper.selectBreaks(businessDate, network);
    }

    private static String clearingAccountOf(String network) {
        if (NETWORK_BOK.equals(network)) {
            return BOK_CLEARING_ACCOUNT;
        }
        if (NETWORK_KFTC.equals(network)) {
            return KFTC_CLEARING_ACCOUNT;
        }
        // 모르는 망을 기본값으로 흘려보내면 엉뚱한 계정을 대사하고 "이상 없음" 을
        // 보고한다. 대사에서 가장 나쁜 실패 방식이라 여기서 끊는다.
        throw new IllegalArgumentException("알 수 없는 청산망: " + network);
    }

    /**
     * 이 영업일이 이미 지났는가.
     *
     * <p>진행 중인 날에 미확정 상태를 미결로 보면 정상 거래가 전부 잡힌다. 그러면
     * 목록이 매일 수백 건씩 나오고, 진짜 미결이 그 안에 묻힌다.
     */
    private static boolean dayClosed(String businessDate) {
        return businessDate.compareTo(LocalDate.now().format(YYYYMMDD)) < 0;
    }
}
