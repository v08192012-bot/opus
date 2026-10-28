#!/usr/bin/env python3
"""motor.json -> motor.bin (компактный бинарный формат для переноса ротации).

Заодно проверяет математику по golden-тикам и печатает сводку.

    python3 tools/convert_model.py [--json ../../src/main/resources/models/motor.json] [--out ../model/motor.bin]

Формат описан в ../SPEC.md. Все числа little-endian, double = IEEE-754 binary64.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import struct
import sys

MAGIC = b"ZENMOTOR"
VERSION = 1
HEAD_NAMES = ("pi", "mu", "log_sigma", "rho", "pause")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from reference_motor import Motor  # noqa: E402  (валидация golden)


def pack_doubles(values) -> bytes:
    return struct.pack("<%dd" % len(values), *values)


def flatten(rows):
    out = []
    for r in rows:
        out.extend(r)
    return out


def write_model(doc: dict, out_path: str) -> None:
    cfg = doc["config"]
    n_features = cfg["n_features"]
    layers = cfg["gru_layers"]
    hidden = cfg["gru_hidden"]
    mix = cfg["mix"]
    act_scale = cfg["act_scale"]
    if isinstance(act_scale, (int, float)):
        act_scale = [act_scale, act_scale]

    buf = bytearray()
    buf += MAGIC
    buf += struct.pack("<i", VERSION)
    buf += struct.pack("<6i", n_features, cfg["mlp_hidden"], hidden, layers, mix, cfg["latency"])
    buf += pack_doubles([
        cfg["asinh_scale"], float(act_scale[0]), float(act_scale[1]),
        cfg["inside_sharp"], cfg["log_sigma_min"], cfg["log_sigma_max"],
        cfg["max_cmd_yaw"], cfg["max_cmd_pitch"], cfg["peak_decay"], cfg["zero_eps"],
    ])
    buf += struct.pack("<BB", 1 if cfg.get("act_quant") == "round" else 0, 1 if cfg.get("act_residual") else 0)

    env = cfg.get("envelope")
    if env:
        buf += struct.pack("<B", 1)
        for key in ("edges_counts", "caps_yaw_counts", "caps_pitch_counts"):
            values = env[key]
            buf += struct.pack("<i", len(values))
            buf += pack_doubles(values)
    else:
        buf += struct.pack("<B", 0)

    buf += pack_doubles(doc["norm"]["mean"])
    buf += pack_doubles(doc["norm"]["var"])

    inp = doc["inp"]
    buf += struct.pack("<i", len(inp))
    for layer in inp:
        w = flatten(layer["w"])
        buf += struct.pack("<2i", len(layer["w"]), len(layer["w"][0]))
        buf += pack_doubles(w)
        buf += pack_doubles(layer["b"])

    gru = doc["gru"]
    buf += struct.pack("<i", len(gru))
    for layer in gru:
        buf += struct.pack("<2i", hidden, len(layer["w_ih"][0]))
        buf += pack_doubles(flatten(layer["w_ih"]))
        buf += pack_doubles(layer["b_ih"])
        buf += pack_doubles(flatten(layer["w_hh"]))
        buf += pack_doubles(layer["b_hh"])

    for name in HEAD_NAMES:
        head = doc["heads"][name]
        buf += struct.pack("<2i", len(head["w"]), len(head["w"][0]))
        buf += pack_doubles(flatten(head["w"]))
        buf += pack_doubles(head["b"])

    golden = doc.get("golden") or []
    buf += struct.pack("<i", len(golden))
    for tick in golden:
        buf += pack_doubles(tick["features"])
        buf += pack_doubles(tick["pi"])
        buf += pack_doubles(flatten(tick["mu"]))
        buf += pack_doubles(flatten(tick["log_sigma"]))
        buf += pack_doubles(tick["rho"])
        buf += struct.pack("<d", tick["pause"])
        buf += pack_doubles(tick["hidden_out"])

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "wb") as fh:
        fh.write(buf)
    print("записано %s (%d байт, %.1f МБ)" % (out_path, len(buf), len(buf) / 1048576))


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default=os.path.join(here, "..", "..", "..", "src", "main", "resources", "models", "motor.json"))
    ap.add_argument("--out", default=os.path.join(here, "..", "model", "motor.bin"))
    args = ap.parse_args()

    json_path = os.path.normpath(args.json)
    out_path = os.path.normpath(args.out)
    if not os.path.isfile(json_path):
        print("нет файла модели: %s" % json_path, file=sys.stderr)
        return 2

    doc = json.load(open(json_path, encoding="utf-8"))

    # 1) проверяем forward-pass по эталонным тикам
    model = Motor(doc)
    golden = doc.get("golden") or []
    worst = 0.0
    model.reset()
    for tick in golden:
        pi, mu, ls, rho, pause, hidden_out = model.forward(tick["features"])

        def delta(a, b):
            if isinstance(a, list):
                return max(delta(x, y) for x, y in zip(a, b))
            return abs(a - b)

        worst = max(worst,
                    delta(pi, tick["pi"]), delta(mu, tick["mu"]),
                    delta(ls, tick["log_sigma"]), delta(rho, tick["rho"]),
                    delta(pause, tick["pause"]), delta(hidden_out, tick["hidden_out"]))
    print("golden-тиков: %d, максимальное расхождение: %.3e" % (len(golden), worst))
    if golden and worst > 1e-9:
        print("ОШИБКА: математика не сошлась с эталоном", file=sys.stderr)
        return 1

    # 2) пишем бинарник
    write_model(doc, out_path)

    cfg = doc["config"]
    print("конфиг: %d признаков, MLP %d, GRU %dx%d, смесь %d, latency %d, act_scale %s"
          % (cfg["n_features"], cfg["mlp_hidden"], cfg["gru_layers"], cfg["gru_hidden"],
             cfg["mix"], cfg["latency"], cfg["act_scale"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
