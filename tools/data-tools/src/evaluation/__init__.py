"""자동심사 모델 평가 + 공정성 분석.

학습 산출물(model.json + feature_schema.json)을 로드해 holdout 데이터로:
- 분류 메트릭 (acc, macro F1, per-class PR-AUC)
- 세그먼트별 성능 (regular/young/senior/precarious/self_employed)
- 공정성 메트릭 (직업군별 거절률 격차, DPD, EOD)
- bias 회수율 (oracle_bias_injected 가 예측에 반영됐는가)
- feature importance (XGBoost gain)
"""
