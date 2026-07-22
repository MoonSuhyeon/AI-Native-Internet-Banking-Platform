"""4 layer 오케스트레이션."""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd

from . import application, financial_profile, oracle, persona_sampler

log = logging.getLogger(__name__)


def _split_assign(n: int, seed: int = 42,
                  train: float = 0.71, valid: float = 0.145, holdout: float = 0.145) -> np.ndarray:
    rng = np.random.default_rng(seed)
    arr = np.empty(n, dtype=object)
    n_train = int(n * train)
    n_valid = int(n * valid)
    idx = rng.permutation(n)
    arr[idx[:n_train]] = "train"
    arr[idx[n_train:n_train + n_valid]] = "valid"
    arr[idx[n_train + n_valid:]] = "holdout"
    return arr


def build(n: int = 10_000, seed: int = 42) -> pd.DataFrame:
    log.info("=== build synthetic n=%d seed=%d ===", n, seed)
    p = persona_sampler.sample(n, seed=seed)
    log.info("layer1 done: %d rows", len(p))

    f = financial_profile.synthesize(p, seed=seed)
    log.info("layer2 done: cols=%s", list(f.columns)[-8:])

    a = application.synthesize(f, seed=seed)
    log.info("layer3 done")

    o = oracle.label(a, seed=seed)
    log.info("layer4 done. decision dist: %s", o["oracle_decision"].value_counts().to_dict())

    o["split"] = _split_assign(len(o), seed=seed)
    return o
