package com.bank.ai.review.client.dto;

import java.util.List;
import java.util.Map;

/**
 * inference-server 의 POST /predict 입력. features 는 raw key-value map 의 리스트.
 * 키는 Python 측 snake_case 컬럼명과 일치해야 한다.
 */
public record InferenceRequest(List<Map<String, Object>> features) {
}
