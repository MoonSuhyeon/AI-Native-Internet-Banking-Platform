"""평가 결과 → JSON + Markdown 요약."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def to_json(report: dict[str, Any], out_path: Path) -> None:
    out_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, default=str),
        encoding="utf-8",
    )


def _fmt_pct(v) -> str:
    return f"{v*100:.2f}%" if isinstance(v, (int, float)) else "—"


def _fmt_num(v, ndigits: int = 4) -> str:
    return f"{v:.{ndigits}f}" if isinstance(v, (int, float)) else "—"


def to_markdown(report: dict[str, Any]) -> str:
    lines: list[str] = []

    meta = report.get("meta", {})
    lines += [
        f"# 자동심사 모델 평가 리포트",
        "",
        f"- model: `{meta.get('model_version')}`",
        f"- data: `{meta.get('data_version')}`",
        f"- evaluated_at: {meta.get('evaluated_at')}",
        f"- holdout size: **{report['overall']['n_samples']}**",
        "",
        "## 1. 전체 메트릭",
        "",
        f"- accuracy: **{_fmt_num(report['overall']['accuracy'])}**",
        f"- macro F1: **{_fmt_num(report['overall']['macro_f1'])}**",
        "",
        "| class | PR-AUC | ROC-AUC |",
        "|---|---|---|",
    ]
    for cls in report["overall"]["confusion_matrix_labels"]:
        pr = report["overall"]["pr_auc_per_class"].get(cls)
        roc = report["overall"]["roc_auc_per_class"].get(cls)
        lines.append(f"| {cls} | {_fmt_num(pr)} | {_fmt_num(roc)} |")

    lines += ["", "### Confusion Matrix", "", "| true \\ pred | " + " | ".join(report["overall"]["confusion_matrix_labels"]) + " |", "|---" * (len(report["overall"]["confusion_matrix_labels"]) + 1) + "|"]
    for label, row in zip(report["overall"]["confusion_matrix_labels"], report["overall"]["confusion_matrix"]):
        lines.append(f"| {label} | " + " | ".join(str(x) for x in row) + " |")

    lines += ["", "## 2. 세그먼트별 성능", "", "| segment | n | acc | true_reject | pred_reject | mean_p(REJECT) |", "|---|---|---|---|---|---|"]
    for seg, m in sorted(report["by_segment"].items()):
        lines.append(
            f"| {seg} | {m['n']} | {_fmt_num(m['accuracy'])} | "
            f"{_fmt_pct(m['true_reject_rate'])} | {_fmt_pct(m['predicted_reject_rate'])} | "
            f"{_fmt_num(m['mean_reject_proba'])} |"
        )

    lines += ["", "## 3. 공정성", ""]
    dp = report.get("demographic_parity_segment", {})
    if dp:
        lines += [
            "### 3.1 Demographic Parity (favorable=APPROVE, group=applicant_segment)",
            "",
            f"- DPD: **{_fmt_num(dp.get('dpd'))}** "
            f"(max={dp.get('max_group')}, min={dp.get('min_group')})",
            "",
            "| segment | P(APPROVE) |",
            "|---|---|",
        ]
        for g, r in sorted(dp["per_group_rate"].items(), key=lambda x: -x[1]):
            lines.append(f"| {g} | {_fmt_pct(r)} |")

    eo = report.get("equalized_odds_segment", {})
    if eo:
        lines += [
            "",
            "### 3.2 Equalized Odds (favorable=APPROVE)",
            "",
            f"- TPR spread: **{_fmt_num(eo.get('tpr_spread'))}** · FPR spread: **{_fmt_num(eo.get('fpr_spread'))}**",
            "",
            "| segment | TPR | FPR |",
            "|---|---|---|",
        ]
        for g in sorted(eo["per_group_tpr"].keys()):
            lines.append(f"| {g} | {_fmt_num(eo['per_group_tpr'].get(g))} | {_fmt_num(eo['per_group_fpr'].get(g))} |")

    br = report.get("bias_recovery", {})
    if br.get("available"):
        lines += [
            "",
            "### 3.3 의도 주입 편향 회수율",
            "",
            f"- bias 주입 행: {br.get('n_bias_rows')}",
            f"- 모델 예측 REJECT: {br.get('n_predicted_reject')}",
            f"- **recovery rate: {_fmt_pct(br.get('recovery_rate'))}**",
            "",
            "> 회수율이 높으면 모델이 학습 편향을 그대로 흡수한 것. 운영 전 mitigation 필요.",
        ]

    od = report.get("occupation_disparity", {})
    if od:
        lines += [
            "",
            f"### 3.4 직업별 REJECT 예측률 상위 (min support={od.get('min_support')})",
            "",
            "| occupation | n | reject_rate |",
            "|---|---|---|",
        ]
        for row in od.get("top_reject", []):
            lines.append(f"| {row['occupation']} | {row['n']} | {_fmt_pct(row['reject_rate'])} |")

    fi = report.get("feature_importance", [])
    if fi:
        lines += ["", "## 4. Feature Importance (XGBoost gain, top 15)", "", "| feature | gain |", "|---|---|"]
        for row in fi[:15]:
            lines.append(f"| {row['feature']} | {_fmt_num(row['gain'], 2)} |")

    return "\n".join(lines) + "\n"
