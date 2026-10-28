#!/usr/bin/env python3
"""Проверка извлечённой математики SpokyTime-ротации (Motor / neurotest-motor-v1).

Чистый python, без numpy и сторонних зависимостей.
Читает src/main/resources/models/motor.json и прогоняет golden-тики через
восстановленный forward-pass. Если max|delta| <= 1e-9 -- извлечение верное.

    python3 port/spookytime-rotation/tools/reference_motor.py [motor.json]
"""
from __future__ import annotations

import json
import math
import sys
from typing import List, Sequence

FORMAT = "neurotest-motor-v1"
N_FEATURES = 47


def sigmoid(z: float) -> float:
    return 1.0 / (1.0 + math.exp(-z))


def silu(z: float) -> float:
    """Swish. В обфусцированном коде это выглядит как `x /= 1 + exp(-x)`, что НЕ сигмоида."""
    return z / (1.0 + math.exp(-z))


def matvec(w: Sequence[Sequence[float]], b: Sequence[float], x: Sequence[float]) -> List[float]:
    out = [0.0] * len(w)
    for i, row in enumerate(w):
        acc = b[i]
        for j, wj in enumerate(row):
            acc += wj * x[j]
        out[i] = acc
    return out


class Motor:
    """Рантайм модели: нормализация -> MLP(sigmoid) -> GRU x2 -> головы смеси."""

    def __init__(self, doc: dict):
        if doc.get("format") != FORMAT:
            raise ValueError("формат %r, ожидался %r" % (doc.get("format"), FORMAT))
        cfg = doc["config"]
        self.cfg = cfg
        self.n_features = cfg["n_features"]
        if self.n_features != N_FEATURES:
            raise ValueError("модель ждёт %d признаков, рантайм собирает %d" % (self.n_features, N_FEATURES))
        if cfg.get("use_player"):
            raise ValueError("модель обучена с one-hot игрока -- переобучите с --player-cond none")

        self.mean = doc["norm"]["mean"]
        self.var = doc["norm"]["var"]
        self.inp = [(l["w"], l["b"]) for l in doc["inp"]]
        self.gru = doc["gru"]
        self.heads = doc["heads"]

        self.layers = cfg["gru_layers"]
        self.hidden = cfg["gru_hidden"]
        self.mix = cfg["mix"]
        self.log_sigma_min = cfg["log_sigma_min"]
        self.log_sigma_max = cfg["log_sigma_max"]
        if len(self.gru) != self.layers:
            raise ValueError("слоёв GRU %d, в конфиге %d" % (len(self.gru), self.layers))
        for l in self.gru:
            if len(l["w_ih"]) != 3 * self.hidden or len(l["w_hh"]) != 3 * self.hidden:
                raise ValueError("матрицы GRU не на %d" % self.hidden)
        if len(self.heads["mu"]["w"]) != 2 * self.mix or len(self.heads["pi"]["w"]) != self.mix:
            raise ValueError("головы не на %d компонент" % self.mix)
        self.reset()

    def reset(self) -> None:
        self.h = [[0.0] * self.hidden for _ in range(self.layers)]

    def forward(self, features: Sequence[float]):
        """Возвращает (pi, mu, log_sigma, rho, pause, hidden_out) и обновляет состояние GRU."""
        if len(features) != self.n_features:
            raise ValueError("нужно %d признаков, пришло %d" % (self.n_features, len(features)))

        x = [(features[i] - self.mean[i]) / math.sqrt(self.var[i] + 1e-4) for i in range(self.n_features)]
        for w, b in self.inp:
            x = [silu(v) for v in matvec(w, b, x)]

        for i, layer in enumerate(self.gru):
            n = matvec(layer["w_ih"], layer["b_ih"], x)
            g = matvec(layer["w_hh"], layer["b_hh"], self.h[i])
            h = self.h[i]
            new = [0.0] * self.hidden
            for j in range(self.hidden):
                r = sigmoid(n[j] + g[j])
                z = sigmoid(n[self.hidden + j] + g[self.hidden + j])
                cand = math.tanh(n[2 * self.hidden + j] + r * g[2 * self.hidden + j])
                new[j] = (1.0 - z) * cand + z * h[j]
            self.h[i] = new
            x = new

        pi = matvec(self.heads["pi"]["w"], self.heads["pi"]["b"], x)
        mu_flat = matvec(self.heads["mu"]["w"], self.heads["mu"]["b"], x)
        ls_flat = matvec(self.heads["log_sigma"]["w"], self.heads["log_sigma"]["b"], x)
        rho_flat = matvec(self.heads["rho"]["w"], self.heads["rho"]["b"], x)
        pause = matvec(self.heads["pause"]["w"], self.heads["pause"]["b"], x)[0]

        mu = [[mu_flat[2 * k], mu_flat[2 * k + 1]] for k in range(self.mix)]
        log_sigma = [
            [
                min(self.log_sigma_max, max(self.log_sigma_min, ls_flat[2 * k])),
                min(self.log_sigma_max, max(self.log_sigma_min, ls_flat[2 * k + 1])),
            ]
            for k in range(self.mix)
        ]
        rho = [math.tanh(rho_flat[k]) * 0.95 for k in range(self.mix)]
        hidden_out: List[float] = []
        for layer_h in self.h:
            hidden_out.extend(layer_h)
        return pi, mu, log_sigma, rho, pause, hidden_out


def max_delta(a, b) -> float:
    if isinstance(a, list):
        return max(max_delta(x, y) for x, y in zip(a, b))
    return abs(a - b)


def self_check(path: str) -> int:
    doc = json.load(open(path, encoding="utf-8"))
    model = Motor(doc)
    golden = doc.get("golden") or []
    if not golden:
        raise SystemExit("в модели нет golden-тиков -- проверить нечем")

    worst = 0.0
    worst_name = ""
    # эталонные тики идут подряд одной последовательностью: состояние GRU
    # НЕ сбрасывается между ними (так же, как в рантайме мода)
    model.reset()
    for idx, tick in enumerate(golden):
        pi, mu, log_sigma, rho, pause, hidden_out = model.forward(tick["features"])
        pairs = [
            ("pi", pi, tick["pi"]),
            ("mu", mu, tick["mu"]),
            ("log_sigma", log_sigma, tick["log_sigma"]),
            ("rho", rho, tick["rho"]),
            ("pause", pause, tick["pause"]),
            ("hidden_out", hidden_out, tick["hidden_out"]),
        ]
        for name, got, want in pairs:
            d = max_delta(got, want)
            if d > worst:
                worst, worst_name = d, "тик %d / %s" % (idx, name)
    print("golden-тиков: %d, худшее расхождение: %.3e (%s)" % (len(golden), worst, worst_name))
    if worst > 1e-9:
        print("ОШИБКА: математика не сошлась")
        return 1
    print("OK: forward-pass совпадает с эталоном")
    return 0


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/models/motor.json"
    sys.exit(self_check(path))
