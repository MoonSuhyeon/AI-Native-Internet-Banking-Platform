package com.bank.common.security.service;

/**
 * 서비스 간 호출에서 인가 단위가 되는 <b>작업</b>.
 *
 * <p><b>왜 역할이 아니라 작업인가.</b> 기존 방식은 {@code X-Internal-Token} 하나를 모든
 * 서비스가 공유하고 principal 이 {@code internal-service} 하나였다. 그 토큰을 가진
 * 무엇이든 모든 내부 작업을 할 수 있다는 뜻이다. 서비스 계정은 사람 계정보다 오래 살고
 * 회전이 적어, 권한이 넓으면 사고 시 영향 범위가 그만큼 넓다.
 *
 * <p>그래서 "누가 호출했는가"(신원)와 "무엇을 할 수 있는가"(작업)를 분리한다. 근거는
 * {@code docs/decisions/transaction-initiator-auth-model.md} 원칙 4 — operation 단위로
 * 최소 권한을 부여한다.
 *
 * <p><b>왜 common 에 두는가.</b> 어휘가 서비스마다 갈리면 같은 일을 서로 다른 이름으로
 * 부르게 되고, 그러면 권한 대장을 한눈에 볼 수 없다. 새 작업을 추가할 때 이 파일을
 * 고치게 되는 것은 불편이 아니라 <b>의도</b>다 — 어휘가 한 곳에 모인다.
 *
 * <p>이름은 DB {@code service_permission.operation} 에 그대로 저장되는 값이므로
 * 바꾸면 마이그레이션이 필요하다.
 */
public enum ServiceOperation {

    /** 대출 실행 — 집행계좌에서 고객 계좌로 자금 지급. */
    LOAN_DISBURSE,

    /**
     * 대출 상환 — 고객 계좌에서 수납계좌로 회수.
     *
     * <p><b>원금과 이자를 나누지 않는다.</b> 실제 상환은 결제 한 건으로 합산 금액이
     * 나간다({@code AutoDebitBatchService} 가 {@code scheduledTotal} 을 하나의
     * 요청으로 보낸다). 원금·이자로 쪼개 선언하면 어느 쪽도 사실이 아니게 된다.
     *
     * <p>두 축을 섞지 않는다.
     * <ul>
     *   <li>{@code operation} — 자금이동 단위. 돈이 어디서 어디로 얼마나 움직이는가</li>
     *   <li>{@code journal_type} — 회계 분개 단위. 그 움직임을 어떻게 기록하는가</li>
     * </ul>
     *
     * <p>상환 1건은 자금이동 1회이고 원장 분개는 3다리다(고객계좌 차변 / 대출채권
     * 대변 / 이자수익 대변). 원금·이자 구분은 그 분개 어휘에 속하며, 원장을
     * 완결하는 C1 ④ 에서 나온다.
     */
    LOAN_REPAY,

    /**
     * 대출 역분개 — 잘못 처리된 거래의 환급.
     *
     * <p><b>실행 권한과 함께 부여하지 않는다.</b> 한 서비스 신원이 자금을 내보내고
     * 그 흔적을 되돌리는 일을 모두 할 수 있으면 업무분리(SoD)에 어긋난다. 시드는
     * 이 작업을 비워 두고, 필요할 때 사유와 함께 명시적으로 부여한다.
     */
    LOAN_REVERSE,

    /**
     * 여신 원장 집계 조회 — 보조부와 원장을 대사하기 위해 읽는다.
     *
     * <p>자금을 움직이지 않지만 같은 인가 관문을 지난다. 읽기라고 권한을 면제하면
     * "무엇을 할 수 있는가" 의 목록이 반쪽이 되고, 그러면 권한 대장으로서 쓸모가 없다.
     */
    LOAN_LEDGER_READ,

    /**
     * 결제지시 상세 조회 — 이상거래 탐지가 판정을 위해 읽는다.
     *
     * <p>읽기만 준다. 탐지기는 거래를 만들지 않으므로, 읽기 권한만 주면 탐지기가
     * 뚫려도 자금이 움직이지 않는다.
     */
    PAYMENT_DETAIL_READ;

    /** DB 에 저장되는 값. enum 이름과 같지만, 저장 값임을 호출부에서 분명히 하려고 둔다. */
    public String code() {
        return name();
    }

    /**
     * 저장 값에서 되읽는다.
     *
     * @return 아는 작업이면 해당 상수, 모르는 값이면 {@code null}.
     *         모르는 값에 예외를 던지지 않는 이유는, 인가 판정이
     *         "알 수 없는 작업 → 거절" 로 자연스럽게 이어져야 하기 때문이다.
     *         여기서 예외가 나면 거절 감사를 남길 기회를 잃는다.
     */
    public static ServiceOperation from(String code) {
        if (code == null) {
            return null;
        }
        for (ServiceOperation op : values()) {
            if (op.name().equals(code)) {
                return op;
            }
        }
        return null;
    }
}
