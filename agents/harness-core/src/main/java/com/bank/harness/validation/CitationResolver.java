package com.bank.harness.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

/**
 * 인용 id 가 실존하는 근거를 가리키는지 확인한다.
 *
 * <p>LLM 이 그럴듯한 출처를 지어내는 것을 막는 지점이다. 환각 인용은 문장만 놓고 보면
 * 진짜와 구별되지 않는다 — 형식도 어투도 옳다. 사람이 읽어서 거르는 것으로는 잡히지 않고,
 * id 를 인덱스에 대조하는 기계적 확인만이 잡는다.
 *
 * <p><b>prefix 로 근거의 출처를 구분한다.</b> 이 규약은 도메인 지식이 아니다.
 * 프롬프트에 직접 실어 보낸 근거인지({@value #INLINE_PREFIX}) 검색으로 가져온
 * 근거인지({@value #RAG_PREFIX})의 구분이라, 대출이든 사기든 상담이든 같은 모양이다.
 * 무엇이 근거로 인정되는지만 도메인이 정하고 그것이 {@link CitationIndex} 다.
 */
@Slf4j
public final class CitationResolver {

    public static final String INLINE_PREFIX = "inline:";
    public static final String RAG_PREFIX = "rag:";

    private final CitationIndex inlineIndex;

    @Nullable
    private final CitationIndex ragIndex;

    /**
     * @param inlineIndex prefix 가 없거나 {@code inline:} 인 인용이 향할 인덱스
     * @param ragIndex    {@code rag:} 인용이 향할 인덱스. RAG 를 끈 환경에서는 null 이며,
     *                    이때 {@code rag:} 인용은 <b>전부 미존재로 취급된다.</b>
     *                    확인할 수 없는 근거를 통과시키면 RAG 를 끄는 것이 검증을 끄는 일이 된다.
     */
    public CitationResolver(CitationIndex inlineIndex, @Nullable CitationIndex ragIndex) {
        this.inlineIndex = inlineIndex;
        this.ragIndex = ragIndex;
    }

    /** 인용 id 가 실존하는 근거를 가리키는지. */
    public boolean exists(String citationId) {
        if (citationId == null) {
            return false;
        }
        if (isRagCitation(citationId)) {
            if (ragIndex == null) {
                log.warn("rag: 인용 검증 시도이나 RAG 인덱스 비활성: id={}", citationId);
                return false;
            }
            return ragIndex.exists(citationId.substring(RAG_PREFIX.length()));
        }
        String lookupId = citationId.startsWith(INLINE_PREFIX)
                ? citationId.substring(INLINE_PREFIX.length())
                : citationId;
        return inlineIndex.exists(lookupId);
    }

    /**
     * 검색으로 가져온 근거를 인용한 것인지.
     *
     * <p>RAG 인용 비율은 "검색이 실제로 쓰이고 있는가"의 지표라 도메인에서 계측한다.
     * 그 계측이 prefix 문자열을 직접 비교하면 규약이 두 군데로 흩어지므로 여기서 답한다.
     */
    public boolean isRagCitation(String citationId) {
        return citationId != null && citationId.startsWith(RAG_PREFIX);
    }
}
