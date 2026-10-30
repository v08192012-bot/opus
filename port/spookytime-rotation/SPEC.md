# SPEC: SpokyTime rotation (извлечено из ZenithDLC)

Источник: `src/main/java/org/zenith/client/modules/impl/combat/AuraModule.java` (режим
`SpokyTime`, `entry6`) → `base/rotation/BcCameraTestnerv.java` (`rotationConfig2` = `Class44`
→ `Enum1.FUNTIME`) → `base/rotation/mods/Motor.java` (`method3659`), модель
`src/main/resources/models/motor.json`.

Здесь описано всё, что нужно, чтобы реализовать то же самое на другом языке/клиенте.

---

## 1. Модель

Конфиг (из `motor.json` → `config`):

| ключ | значение |
|---|---|
| `n_features` | 47 |
| `mlp_hidden` | 256 (2 входных слоя: 47→256, 256→256) |
| `gru_hidden` / `gru_layers` | 256 / 2 |
| `mix` | 5 компонент смеси |
| `latency` | 1 тик |
| `asinh_scale` | 1.0 |
| `act_scale` | `[1.0, 0.35]` (yaw, pitch) |
| `inside_sharp` | 6.0 |
| `log_sigma_min/max` | -3.0 / 2.0 |
| `max_cmd_yaw/pitch` | 130.0 / 31.0 °/тик |
| `peak_decay` | 0.98 |
| `zero_eps` | 1e-4 |
| `act_quant` | `trunc` |
| `act_residual` | `true` |
| `envelope` | 14 границ (в «пикселях»), 15 caps для yaw, 15 caps для pitch |
| `use_player` | `false` (one-hot игрока не используется) |

### Forward pass (точный порядок)

```
x = (features[i] - mean[i]) / sqrt(var[i] + 1e-4)          // 47 -> 47
for layer in inp:                                          // 2 слоя
    x = W_layer @ x + b_layer
    x[i] = x[i] / (1 + exp(-x[i]))                         // SiLU, НЕ сигмоида!
h = zeros(gru_layers, gru_hidden)
for layer in gru:                                          // 2 слоя
    n = W_ih @ x + b_ih                                    // (3H)
    g = W_hh @ h[layer] + b_hh                             // (3H)
    r[j] = sigmoid(n[j]         + g[j])
    z[j] = sigmoid(n[H + j]     + g[H + j])
    c[j] = tanh   (n[2H + j]    + r[j] * g[2H + j])
    h[layer][j] = (1 - z[j]) * c[j] + z[j] * h[layer][j]
    x = h[layer]

pi        = head_pi @ x                    // 5
mu        = reshape(head_mu @ x, 2)        // 5 x 2
log_sigma = clamp(head_log_sigma @ x, -3, 2)  // 5 x 2
rho       = tanh(head_rho @ x) * 0.95      // 5
pause     = (head_pause @ x)[0]            // скаляр (логит)
```

Матрицы лежат построчно: `w[row * cols + col]`.

### Формат `model/motor.bin`

Little-endian, `double` = IEEE-754 binary64.

```
char[8]  "ZENMOTOR"
i32      version = 1
i32      n_features, mlp_hidden, gru_hidden, gru_layers, mix, latency
f64      asinh_scale, act_scale_yaw, act_scale_pitch, inside_sharp,
         log_sigma_min, log_sigma_max, max_cmd_yaw, max_cmd_pitch,
         peak_decay, zero_eps
u8       act_quant_round (1 = round, 0 = trunc)
u8       act_residual
u8       has_envelope
if 1:    i32 n, f64 edges[n];  i32 n, f64 caps_yaw[n];  i32 n, f64 caps_pitch[n]
f64      norm_mean[n_features]
f64      norm_var[n_features]
i32      inp_count;  per layer: i32 rows, i32 cols, f64 w[rows*cols], f64 b[rows]
i32      gru_count;  per layer: i32 H, i32 in_dim,
                     f64 w_ih[3H*in_dim], f64 b_ih[3H], f64 w_hh[3H*H], f64 b_hh[3H]
per head (pi, mu, log_sigma, rho, pause):
         i32 rows, i32 cols, f64 w[rows*cols], f64 b[rows]
i32      golden_count
per golden tick:
         f64 features[47], f64 pi[5], f64 mu[10], f64 log_sigma[10], f64 rho[5],
         f64 pause, f64 hidden_out[gru_layers * gru_hidden]
```

Пересборка из JSON: `python3 tools/convert_model.py` (заодно проверяет эталон).

---

## 2. Признаки (47)

`s = asinh_scale = 1.0`. `cur` — текущая (тихая) ротация, `e` — снимок текущего тика,
`a/b/c` — снимки с задержкой `latency`, `latency+1`, `latency+2` тиков (история на 8 тиков,
индекс 0 — самый свежий).

Снимок (`e`) хранит: `selfYaw/selfPitch` (ротация в момент снимка), `targetYaw/targetPitch`
(углы на центр бокса), `min/maxYawOffset`, `min/maxPitchOffset` (разброс по 8 вершинам
относительно центра), `distance` (до центра бокса).

| # | имя | формула |
|---|---|---|
| 0 | e_now_yaw | `asinh(wrap(e.targetYaw − cur.yaw) / s)` |
| 1 | e_now_pitch | `asinh((e.targetPitch − cur.pitch) / s)` |
| 2 | e_lag_yaw | `asinh(wrap(e.targetYaw − e.selfYaw) / s)` |
| 3 | e_lag_pitch | `asinh((e.targetPitch − e.selfPitch) / s)` |
| 4 | e_ext_yaw | `asinh(wrap(a.targetYaw + (latency+1)·velYaw − cur.yaw) / s)` |
| 5 | e_ext_pitch | `asinh((a.targetPitch + (latency+1)·velPitch − cur.pitch) / s)` |
| 6 | e_abs | `asinh(max(hypot(nowYaw, nowPitch), 1e-6) / s)` |
| 7 | e_dir_yaw | `nowYaw / e_abs_raw` |
| 8 | e_dir_pitch | `nowPitch / e_abs_raw` |
| 9 | tgt_vel_yaw | `asinh(wrap(a.targetYaw − b.targetYaw) / s)` |
| 10 | tgt_vel_pitch | `asinh((a.targetPitch − b.targetPitch) / s)` |
| 11 | tgt_acc_yaw | `asinh((velYaw − wrap(b.targetYaw − c.targetYaw)) / s)` |
| 12 | tgt_acc_pitch | `asinh((velPitch − (b.targetPitch − c.targetPitch)) / s)` |
| 13 | u_yaw | `clamp((−nowYaw − centerYaw) / halfYaw, −4, 4)` |
| 14 | u_pitch | `clamp((−nowPitch − centerPitch) / halfPitch, −4, 4)` |
| 15 | soft_inside | `sigmoid(inside_sharp · (1 − max(|u_yaw|, |u_pitch|)))` |
| 16 | box_half_yaw | `asinh(max(0.5·(a.maxYawOff − a.minYawOff), 1e-3) / s)` |
| 17 | box_half_pitch | `asinh(max(0.5·(a.maxPitchOff − a.minPitchOff), 1e-3) / s)` |
| 18 | log_dist | `log1p(e.distance)` |
| 19 | d_dist | `clamp(e.distance − b.distance, −2, 2)` |
| 20 | cam_pitch_norm | `cur.pitch / 90` |
| 21 | cam_pitch_headroom | `(90 − |cur.pitch|) / 90` |
| 22 | self_last1_yaw | `asinh(last1[0] / s)` |
| 23 | self_last1_pitch | `asinh(last1[1] / s)` |
| 24 | self_last2_yaw | `asinh(last2[0] / s)` |
| 25 | self_last2_pitch | `asinh(last2[1] / s)` |
| 26 | self_acc_yaw | `asinh((last1[0] − last2[0]) / s)` |
| 27 | self_acc_pitch | `asinh((last1[1] − last2[1]) / s)` |
| 28 | self_zero_run_yaw | `tanh(zeroRun[0] / 8)` |
| 29 | self_zero_run_pitch | `tanh(zeroRun[1] / 8)` |
| 30 | self_flip_yaw | `tanh(flipRun[0] / 8)` |
| 31 | self_flip_pitch | `tanh(flipRun[1] / 8)` |
| 32 | self_peak_ratio_yaw | `clamp(|last1[0]| / max(peak[0], 1e-6), 0, 4)` |
| 33 | self_peak_ratio_pitch | `clamp(|last1[1]| / max(peak[1], 1e-6), 0, 4)` |
| 34 | self_peak_yaw | `asinh(peak[0] / s)` |
| 35 | self_peak_pitch | `asinh(peak[1] / s)` |
| 36..40 | movement_input_forward/backward/left/right/jump | 1/0 по зажатым клавишам |
| 41 | state_on_ground | 1/0 |
| 42 | state_sprinting | 1/0 |
| 43 | state_in_sneaking_pose | 1/0 |
| 44 | attack_cooldown_progress | `getAttackCooldownProgress(0)` ∈ [0,1] |
| 45 | ticks_since_last_attack_clamped | `clamp(ticksSinceLastAttack, 0, 50)` |
| 46 | did_attack_previous_tick | 1, если кулдаун за прошлый тик просел более чем на 1e-4 |

где `centerYaw = 0.5·(a.minYawOff + a.maxYawOff)`, `halfYaw = max(0.5·(a.maxYawOff − a.minYawOff), 1e-3)`
(аналогично для pitch), `velYaw/velPitch` — из #9/#10, `wrap` — приведение к (−180, 180].

Состояние «своей руки» обновляется после каждого тика по фактически применённой дельте:

```
big   = |d| > zero_eps
flip  = big && sign(d) * lastBigSign < 0
zeroRun  = big ? 0 : zeroRun + 1
flipRun  = flip ? 0 : flipRun + 1
peak     = max(peak * peak_decay, |d|)
lastBigSign = big ? sign(d) : lastBigSign
last2 = last1; last1 = d
```

---

## 3. Шаг рантайма (на каждый тик)

```
если цели нет / мертва:            reset(); вернуть cur
если tickId == прошлый тик:        вернуть запомненный результат (1 расчёт на тик)
если tickId != прошлый + 1:        reset()
иначе если id цели изменился:      забыть только результат/кэш (историю сохранить)

step   = pixelStep(sensitivity)              // sens 0.5 -> 0.15°
didAttack = cooldown < lastCooldown - 1e-4; lastCooldown = cooldown
e = снимок(cur, target, eye)
push(e)                                      // история на 8
x = features(e, cur, player)
step_out = model(x)                          // обновляет GRU
cmd = sample(step_out)                       // см. ниже
cmd[axis] = clamp(cmd[axis], -cap(axis)·step, +cap(axis)·step)   // огибающая
nYaw   = quantize(cmd[0], axis 0, step)
nPitch = quantize(cmd[1], axis 1, step)
next = cur + (nYaw·step, nPitch·step)        // pitch зажимается в ±90
updateStats(deltaYaw, deltaPitch)
вернуть next (при нулевых пикселях — cur)
```

**Сэмплирование:**

```
если rand() < sigmoid(pause):      вернуть (0, 0)
k   = softmax-выбор по pi
sd1 = exp(log_sigma[k][0]); sd2 = exp(log_sigma[k][1]); r = rho[k]
g1, g2 ~ N(0,1)
raw1 = mu[k][0] + sd1·g1
raw2 = mu[k][1] + sd2·(r·g1 + sqrt(max(1 − r², 1e-6))·g2)
raw1 = clamp(raw1, ±asinh(max_cmd_yaw / act_scale_yaw))
raw2 = clamp(raw2, ±asinh(max_cmd_pitch / act_scale_pitch))
dYaw   = clamp(act_scale_yaw   · sinh(raw1), ±max_cmd_yaw)
dPitch = clamp(act_scale_pitch · sinh(raw2), ±max_cmd_pitch)
```

**Огибающая:** `counts = max(|last1[axis]|, |last2[axis]|) / step`; `idx` = первый индекс,
где `edges[idx] ≥ counts`; `cap = caps[axis][min(idx, len−1)]`.

**Квантование:**

```
acc  = cmd[axis] + (act_residual ? residual[axis] : 0)
q    = acc / step;  q += sign(q)·1e-4
n    = act_quant_round ? round(q) : trunc(q)
residual[axis] = act_residual ? clamp(acc − n·step, ±step) : 0
```

---

## 4. Как это встроено в мод (для понимания контекста)

- `AuraModule.method6337()` при выбранном `SpokyTime` кладёт в `RotationManager` заявку
  `RotationRequest(supplier = () -> BcCameraTestnerv.method1851(cfg, rotation), cfg)` c TTL = 1 тик.
- `BcCameraTestnerv.method1851()` для `FUNTIME` берёт `motor.method3659()` и затем
  нормализует результат (в т.ч. квантует по шагу мыши).
- `RotationManager.method7846()` (событие после ввода): раз в тик забирает заявку, ставит
  `currentRotation = supplier.get()`, зажимает pitch в ±90. Когда заявок нет — доводит
  `currentRotation` к реальной ротации тем же движком и при расхождении ≤ 9° синхронизирует
  камеру игрока и отпускает ротацию.
- Цель берётся из `AuraModule.method6358()` (текущая цель модуля, только если модуль включён).
- Внутри `Motor` есть кэш по `player.age` — модель вызывается строго один раз за тик.

## 5. Отличия от «обычного» killaura-сглаживания

- Выход — не «повернуть на N градусов к цели», а распределение **человеческих движений
  мыши**, зависящее от кулдауна атаки, ввода, усталости (peak/zeroRun/flip) и попадания
  в хитбокс.
- Наличие `pause`-головы означает, что в часть тиков ротация вообще не двигается (как у
  человека, который «держит» прицел).
- Ограничение скорости берётся из эмпирической огибающей по дистанции/скорости, а не из
  константы «макс. градусов за тик».
