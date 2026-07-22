# data-tools

자동심사·RAG 시드·합성 데이터 통합 유틸리티. Python 기반, Spring 모듈 외부.

> 24시간 상주 서비스가 아니다. 필요할 때 일회성으로 실행하는 batch 컨테이너.
> auto-loan-review, advisory-service 등 여러 서비스의 데이터 준비를 공통으로 담당.

## 구성

```
src/
  loaders/             외부 공공 데이터 API 로더
    config.py          .env 로드 (프로젝트 루트 .env 자동 인식)
    base.py            공통 HTTP 헬퍼·재시도·결과 저장
    ecos.py            한국은행 ECOS
    kosis.py           통계청 KOSIS
    data_go_kr.py      공공데이터포털
    fisis.py           금감원 FISIS
  synthesize/          합성 데이터 빌더
  training/            모델 학습 파이프라인
  evaluation/          공정성·메트릭 평가
scripts/
  fetch_all.py         공공데이터 일괄 수집 진입점
  probe_kosis.py       KOSIS 단건 탐색
  build_synthetic.py   합성 데이터 빌드
  train_model.py       모델 학습
  evaluate_model.py    모델 평가
  seed_hmda_rag.py     advisory-service 에 HMDA 편향 패턴 RAG 문서 시드
```

## 출력

`data/external/korean/<source>/<stat_id>__<period>.parquet`

`.gitignore` 의 `data/` 에 커버됨. 레포 미포함.

## 환경변수

프로젝트 루트 `.env` 에 다음 키 필요:

| 키 | 출처 |
|----|------|
| `KOREA_BANK_API_KEY` | <https://ecos.bok.or.kr/api/> |
| `KOSIS_API_KEY` | <https://kosis.kr/openapi/index/index.jsp> |
| `PUBLIC_DATA_API_KEY` | <https://www.data.go.kr> |
| `FISIS_API_KEY` | <https://fisis.fss.or.kr> |

## 로컬 실행

```bash
cd services/data-tools
pip install -r requirements.txt
python -m scripts.fetch_all                 # 전체
python -m scripts.fetch_all --source ecos   # 일부
python scripts/seed_hmda_rag.py             # RAG 시드
```

## Docker 실행 (운영)

```bash
# 공공데이터 수집
docker run --rm --env-file .env -v $PWD/data:/app/data \
  ghcr.io/<owner>/data-tools \
  python scripts/fetch_all.py

# advisory-service RAG 시드
docker run --rm --network host \
  ghcr.io/<owner>/data-tools \
  python scripts/seed_hmda_rag.py --host http://localhost:8080
```
