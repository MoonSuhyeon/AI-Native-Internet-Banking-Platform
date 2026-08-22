package com.bank.deposit.exception;

import com.bank.deposit.security.RiskGuidance;
import lombok.Getter;

/**
 * 이상거래로 거래를 세우면서 <b>이유와 할 일을 함께</b> 전하는 예외.
 *
 * <p><b>왜 보통 예외로는 부족한가.</b> {@link BusinessException} 은 코드와 메시지
 * 한 줄만 나른다. 그래서 지금까지 고객이 받은 것은 이것뿐이었다.
 *
 * <pre>
 *   "이상거래로 판단되어 이체가 제한되었습니다. 고객센터로 문의해 주세요."
 * </pre>
 *
 * <p>탐지기는 "평소 최대 50만원, 이번 300만원" 같은 근거를 이미 만들어 보냈는데,
 * 게이트가 그걸 로그에만 쓰고 버렸다. 만들어 놓고 잇지 않은 것이다.
 *
 * <p>예외에 안내를 실어 응답까지 내보낸다. 화면은 이 값으로 근거와 행동요령을
 * 그리고, 상담 연결 선택지를 띄운다.
 *
 * <p><b>안내가 없을 수도 있다.</b> 탐지기가 축소 판정했거나 구버전이면 {@code null}
 * 이다. 그때는 예전과 같은 한 줄로 되돌아가되, 그건 <b>정상 경로가 아니라 퇴화</b>다 —
 * 화면이 안내 없는 응답을 받으면 그 사실을 지표로 남긴다.
 */
@Getter
public class RiskGuidedException extends BusinessException {

    private final transient RiskGuidance guidance;

    public RiskGuidedException(ErrorCode errorCode, RiskGuidance guidance) {
        super(errorCode);
        this.guidance = guidance;
    }
}
