package com.bank.payment.domain.service;

import com.bank.common.time.BusinessDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * 이체 수수료 산정.
 *
 * <p><b>왜 빼내는가.</b> {@code isIntraBank ? 0L : 500L} 로 코드에 박혀 있었다.
 * 수수료는 은행에서 가장 자주 바뀌는 값 중 하나이고, 바뀔 때마다 배포해야 한다면
 * 결국 아무도 못 바꾼다. 무엇보다 <b>얼마를 왜 받는지가 코드 한 줄에 숨어</b> 있어
 * 감사나 고객 문의에 답하려면 소스를 읽어야 했다.
 *
 * <p><b>실무는 이보다 복잡하다.</b> 채널(창구·인터넷·ATM), 고객 등급, 금액 구간,
 * 영업시간 외 할증, 급여이체 면제 등이 얽힌다. 여기서는 지금 판정에 쓸 수 있는 축
 * (자행여부·영업시간)만 두고 나머지는 넣지 않았다 — 쓰지 않을 축을 미리 만들면
 * 설정만 늘고 검증은 안 된다.
 */
@Component
public class TransferFeePolicy {

    /** 자행이체 수수료. 우리 DB 안에서 끝나므로 망 이용료가 없다. */
    @Value("${payment.fee.intra-bank:0}")
    private long intraBankFee;

    /** 타행이체 기본 수수료. 금융결제원 망을 타는 비용이다. */
    @Value("${payment.fee.inter-bank:500}")
    private long interBankFee;

    /** 영업시간 외 타행이체 할증. 0 이면 할증 없음. */
    @Value("${payment.fee.inter-bank-after-hours-surcharge:0}")
    private long afterHoursSurcharge;

    @Value("${payment.fee.business-hour-from:9}")
    private int businessHourFrom;

    @Value("${payment.fee.business-hour-to:16}")
    private int businessHourTo;

    /**
     * @param isIntraBank 자행이체 여부. null 이면 타행으로 본다 —
     *                    모르는 상태를 무료로 처리하면 수수료를 빠뜨리는 쪽으로 기운다.
     * @param requestedAt 요청 시각. 영업시간 판정에 쓴다.
     */
    public long feeFor(Boolean isIntraBank, OffsetDateTime requestedAt) {
        if (Boolean.TRUE.equals(isIntraBank)) {
            return intraBankFee;
        }
        return interBankFee + (isAfterHours(requestedAt) ? afterHoursSurcharge : 0L);
    }

    /**
     * 영업시간 밖인가. 시각을 모르면 영업시간 안으로 본다 —
     * 판정하지 못한 것을 근거로 고객에게 더 받지 않는다.
     */
    private boolean isAfterHours(OffsetDateTime requestedAt) {
        if (requestedAt == null) {
            return false;
        }
        // 은행 영업시간은 KST 기준이다. UTC 로 재면 9시간이 밀린다.
        LocalTime kst = requestedAt.atZoneSameInstant(BusinessDate.ZONE).toLocalTime();
        return kst.isBefore(LocalTime.of(businessHourFrom, 0))
                || !kst.isBefore(LocalTime.of(businessHourTo, 0));
    }
}
