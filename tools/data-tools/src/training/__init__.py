"""자동심사 모델 학습 파이프라인.

synthetic_application 데이터로 XGBoost multiclass 분류기를 학습한다.
타겟은 oracle_decision (APPROVE/CONDITIONAL/REJECT).
"""
