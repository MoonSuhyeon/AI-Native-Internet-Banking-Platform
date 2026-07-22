"""분류 + 세그먼트별 메트릭."""

from __future__ import annotations

from typing import Any

import numpy as np
import pandas as pd
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    confusion_matrix,
    f1_score,
    roc_auc_score,
)

from training.features import FeatureSchema


def overall(y_true: np.ndarray, proba: np.ndarray, schema: FeatureSchema) -> dict[str, Any]:
    pred = proba.argmax(axis=1)
    classes = schema.label_classes

    pr_auc, roc_auc = {}, {}
    for i, cls in enumerate(classes):
        y_bin = (y_true == i).astype(int)
        if y_bin.sum() in (0, len(y_bin)):
            pr_auc[cls] = None
            roc_auc[cls] = None
            continue
        pr_auc[cls] = float(average_precision_score(y_bin, proba[:, i]))
        roc_auc[cls] = float(roc_auc_score(y_bin, proba[:, i]))

    cm = confusion_matrix(y_true, pred, labels=list(range(len(classes))))

    return {
        "n_samples": int(len(y_true)),
        "accuracy": float(accuracy_score(y_true, pred)),
        "macro_f1": float(f1_score(y_true, pred, average="macro")),
        "pr_auc_per_class": pr_auc,
        "roc_auc_per_class": roc_auc,
        "confusion_matrix": cm.tolist(),
        "confusion_matrix_labels": list(classes),
    }


def by_segment(
    y_true: np.ndarray,
    proba: np.ndarray,
    segments: pd.Series,
    schema: FeatureSchema,
) -> dict[str, dict[str, Any]]:
    """applicant_segment 별 메트릭 분해.

    Layer 4 bias 가 precarious 그룹을 타겟하므로 그룹별 차이가 드러나야 한다.
    """
    pred = proba.argmax(axis=1)
    out: dict[str, dict[str, Any]] = {}
    reject_idx = schema.label_classes.index("REJECT")

    for seg, mask_series in segments.groupby(segments):
        mask = segments == seg
        if mask.sum() == 0:
            continue
        yt = y_true[mask.to_numpy()]
        yp = pred[mask.to_numpy()]
        pp = proba[mask.to_numpy()]

        per_class_recall = {}
        for i, cls in enumerate(schema.label_classes):
            y_bin = (yt == i)
            per_class_recall[cls] = (
                float(((yp == i) & y_bin).sum() / max(y_bin.sum(), 1))
                if y_bin.sum() > 0 else None
            )

        out[seg] = {
            "n": int(mask.sum()),
            "accuracy": float(accuracy_score(yt, yp)) if len(yt) > 0 else None,
            "predicted_reject_rate": float((yp == reject_idx).mean()),
            "true_reject_rate": float((yt == reject_idx).mean()),
            "mean_reject_proba": float(pp[:, reject_idx].mean()),
            "recall_per_class": per_class_recall,
        }
    return out
