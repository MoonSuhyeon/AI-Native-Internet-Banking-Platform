package com.bank.payment.domain.mapper;

import com.bank.payment.domain.reconciliation.ClearingRecords;
import com.bank.payment.domain.reconciliation.ReconciliationBreak;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface ReconciliationMapper {

    /**
     * 내부 원장 쪽 — 결제별 청산대기 순액.
     *
     * <p>순액인 것이 중요하다. 취소된 거래는 청산대기(대변)와 역분개(차변)가 같은
     * 금액으로 남으므로 0 이 되고, 그래야 정상 거래와 구별된다.
     */
    List<ClearingRecords.Internal> selectInternalClearing(
            @Param("businessDate") String businessDate,
            @Param("clearingAccountId") String clearingAccountId);

    /** 외부 청산 쪽 — KFTC 청산 기록. */
    List<ClearingRecords.External> selectExternalKftc(
            @Param("businessDate") String businessDate);

    /** 외부 청산 쪽 — BOK 정산 기록. */
    List<ClearingRecords.External> selectExternalBok(
            @Param("businessDate") String businessDate);

    /**
     * 불일치 적재. 같은 (영업일, 망, 결제, 유형) 이 이미 있으면 갱신한다.
     *
     * <p>재실행이 중복 적재가 되면 건수 기반 지표가 부풀어 신뢰를 잃는다.
     * {@code first_detected_at} 은 유지하고 {@code last_detected_at} 만 갱신해,
     * "며칠째 미결인가" 를 셀 수 있게 남긴다.
     */
    void upsertBreak(@Param("businessDate") String businessDate,
                     @Param("network") String network,
                     @Param("b") ReconciliationBreak b,
                     @Param("now") OffsetDateTime now);

    /**
     * 이번 대사에서 사라진 불일치를 정리한다.
     *
     * <p>없으면 한 번 해소된 건이 목록에 영원히 남아, 시간이 지날수록 "이미 해결된
     * 것" 이 대부분이 되고 아무도 목록을 안 보게 된다.
     */
    int closeResolved(@Param("businessDate") String businessDate,
                      @Param("network") String network,
                      @Param("detectedAt") OffsetDateTime detectedAt);

    /** 조회 — 특정 영업일의 불일치. */
    List<ReconciliationBreakRow> selectBreaks(@Param("businessDate") String businessDate,
                                             @Param("network") String network);

    /** 지표용 — 미해결 불일치를 유형별로 센다. */
    List<BreakCount> countOpenByType();
}
