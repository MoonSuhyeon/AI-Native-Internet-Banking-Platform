# inference-server

자동심사 ML 모델 추론 서버. ai-service(Java) 의 게이트웨이가 이 서버를 호출한다.
운영 ML 패턴으로 Python 모델 서빙과 Java 비즈니스 로직을 분리.

## 실행

```bash
cd inference-server
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8090
```

환경변수:
- `MODEL_VERSION` (기본 `v1`) — `data/models/auto_review_<v>/` 디렉토리 선택
- `MODEL_DIR` — 절대 경로 직지정 (Docker 마운트용)

## API

### `GET /health`
모델 로드 상태 + holdout accuracy.

### `POST /predict`
```json
{
  "features": [
    { "sex": "남자", "age": 35, "occupation": "사무 보조원", "dsr": 0.35, ... }
  ]
}
```
응답:
```json
{
  "model_version": "v1",
  "predictions": [
    {
      "decision": "APPROVE",
      "score": 0.91,
      "proba": { "APPROVE": 0.91, "CONDITIONAL": 0.07, "REJECT": 0.02 }
    }
  ]
}
```

누락된 키는 NaN/null 로 처리되며 XGBoost 가 missing 분기로 라우팅한다.
