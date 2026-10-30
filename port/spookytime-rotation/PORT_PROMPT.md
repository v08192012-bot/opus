# Промпт для переноса ротации SpokyTime в другой чит

> Файл — готовый промпт для кодового агента (Cursor / Claude Code / Codex / любой LLM).
> Копировать целиком, начиная с раздела **«ПРОМПТ»**. Раздел «Как пользоваться»
> тоже можно копировать — он даёт агенту контекст, откуда брать файлы.

---

## Как пользоваться (не обязательно копировать)

1. Сначала положи в новый проект (или дай агенту ссылку) четыре вещи:
   `model/motor.bin`, `java/spooky/SpookyModel.java`, `java/spooky/SpookyRotation.java`
   (плюс `Rotation.java`, `MathX.java`, `SelfTest.java`). Промпт ниже ссылается на них
   по именам и по raw-ссылкам.
2. Если целевой чит не на Java (C++, C#, Kotlin, Rust) — оставь промпт как есть:
   в нём вся математика описана формулами, а Java-файлы служат эталоном поведения.
3. Попроси агента в конце выдать отчёт по чек-листу из раздела 10 — по нему видно,
   всё ли перенесено.

Ссылки на исходники (публичный репозиторий, ветка `arena/c318d3fc-opus`):

```
https://raw.githubusercontent.com/v08192012-bot/opus/arena/c318d3fc-opus/port/spookytime-rotation/README.md
https://raw.githubusercontent.com/v08192012-bot/opus/arena/c318d3fc-opus/port/spookytime-rotation/SPEC.md
https://raw.githubusercontent.com/v08192012-bot/opus/arena/c318d3fc-opus/port/spookytime-rotation/model/motor.bin
https://raw.githubusercontent.com/v08192012-bot/opus/arena/c318d3fc-opus/port/spookytime-rotation/java/spooky/SpookyModel.java
https://raw.githubusercontent.com/v08192012-bot/opus/arena/c318d3fc-opus/port/spookytime-rotation/java/spooky/SpookyRotation.java
https://raw.githubusercontent.com/v08192012-bot/opus/arena/c318d3fc-opus/port/spookytime-rotation/java/spooky/Rotation.java
https://raw.githubusercontent.com/v08192012-bot/opus/arena/c318d3fc-opus/port/spookytime-rotation/java/spooky/MathX.java
https://raw.githubusercontent.com/v08192012-bot/opus/arena/c318d3fc-opus/port/spookytime-rotation/java/spooky/SelfTest.java
https://raw.githubusercontent.com/v08192012-bot/opus/arena/c318d3fc-opus/port/spookytime-rotation/tools/convert_model.py
https://raw.githubusercontent.com/v08192012-bot/opus/arena/c318d3fc-opus/port/spookytime-rotation/tools/reference_motor.py
```

Контрольные суммы/размеры:

```
motor.bin        7 015 167 байт   sha256 57a182586519a4bb85dbced1b342b254053518de2525d2f319f43ef2105230c9
SpookyModel.java  384 строки
SpookyRotation.java 475 строк
```

---

# ПРОМПТ

Ты — senior Java/C++ разработчик читов для Minecraft. Твоя задача — перенести в целевой
клиент **ротацию режима `SpokyTime`** (нейросетевая имитация человеческого доведения
прицела из killaura-модуля) так, чтобы поведение совпадало с оригиналом тик в тик,
а не «примерно похоже».

Работай не по описанию «сделай плавную ротацию», а строго по спецификации ниже:
это реверс конкретной модели (`neurotest-motor-v1`), у неё есть эталонные тики, и любое
отклонение в формулах ловится автотестом с допуском 1e-9.

## 1. Что именно переносится

Переносится **только ротация**: на вход — цель и состояние игрока, на выход — новая пара
`yaw/pitch` (дельта, уже кратная шагу мыши).

| Компонент | Что делает |
|---|---|
| геометрия | углы на центр AABB цели, дистанция, разброс углов по 8 вершинам бокса |
| признаки | 47 чисел: ошибка прицела, скорость/ускорение цели, свои прошлые движения мыши, попадание в бокс, ввод, состояние игрока, кулдаун атаки |
| политика | `MLP 47→256 (SiLU) → MLP 256→256 (SiLU) → GRU 2×256 → 5 голов смеси` |
| сэмплирование | смесь из 5 двумерных гауссиан + отдельная голова «ничего не делать» |
| ограничители | огибающая скорости по модулю прошлых шагов, квантование в целые «пиксели мыши» с переносом остатка |

**НЕ переносится** (это отдельные подсистемы оригинала, их реализует владелец клиента):

- выбор цели, сортировка по FOV/дистанции/HP/броне;
- аим-поинт, рандомизация точки попадания, рейкаст/обход стен, предсказание хитбокса;
- логика атаки, кулдаунов, критов, TPS-синхронизации;
- отправка пакетов и синхронизация камеры (кроме требований из раздела 8).

## 2. Входные файлы (эталон)

- `model/motor.bin` — веса + конфиг + эталонные тики в компактном бинарном виде (7 МБ).
  Контрольная сумма указана выше; если она не совпадает — файл побился.
- `SpookyModel.java` — загрузчик формата и forward-pass. **Это эталон семантики.**
- `SpookyRotation.java` — логика состояния, признаки, сэмплирование, квантование.
  **Это эталон поведения.** При расхождении с формулами этого документа верь файлу,
  а расхождение зафиксируй в отчёте.
- `SelfTest.java` — как проверяется корректность переноса.
- `tools/reference_motor.py` — независимая реализация forward-pass на python (удобно,
  если целевой язык не Java: сверяйся с ней).
- `tools/convert_model.py` — если у тебя нет `motor.bin`, но есть исходный `motor.json`
  (49 МБ), этот скрипт соберёт бинарник и заодно проверит эталон.

Если целевой проект не может читать бинарник — разбирай `motor.json` сам (структура в
разделе 3.3), но **не пересчитывай веса**, только читай.

## 3. Модель

### 3.1 Конфиг (жёстко зашит в файле, менять нельзя)

| параметр | значение | смысл |
|---|---|---|
| `n_features` | 47 | размер входа |
| `mlp_hidden` | 256 | скрытый размер входных слоёв (2 слоя) |
| `gru_hidden`, `gru_layers` | 256, 2 | рекуррентная часть |
| `mix` | 5 | компонент смеси |
| `latency` | 1 | задержка, на которую экстраполируется цель |
| `asinh_scale` | 1.0 | масштаб в `asinh(...)` |
| `act_scale` | `[1.0, 0.35]` | масштаб действия по yaw/pitch |
| `inside_sharp` | 6.0 | резкость «попал внутрь бокса» |
| `log_sigma_min/max` | −3.0 / 2.0 | клип лог-сигмы |
| `max_cmd_yaw/pitch` | 130.0 / 31.0 | лимит команды, °/тик |
| `peak_decay` | 0.98 | затухание пика своей скорости |
| `zero_eps` | 1e-4 | порог «шаг не нулевой» |
| `act_quant` | `trunc` | квантование усечением |
| `act_residual` | `true` | переносить остаток квантования |
| `use_player` | `false` | one-hot игрока не используется |
| `envelope` | таблицы ниже | ограничитель скорости |

Огибающая (числа из модели, «counts» — это пиксели мыши за тик):

```
edges_counts     = [0.5, 20, 40, 80, 140, 220, 320, 440, 600, 800, 1100, 1500, 2000, 4000]
caps_yaw_counts  = [921, 921, 921, 1557, 1557, 1643, 1833, 2125, 2125, 2225, 2452, 2543, 2543, 2781, 2781]
caps_pitch_counts= [250, 308, 420, 420, 432, 729, 729, 729, 729, 729, 729, 729, 729, 729, 729]
```

### 3.2 Forward pass (порядок операций критичен)

```
x[i] = (features[i] - mean[i]) / sqrt(var[i] + 1e-4)      // 47 -> 47, double
for layer in inp:                                          // 2 слоя
    x = W_layer · x + b_layer
    x[i] = x[i] / (1 + exp(-x[i]))                         // SiLU = x*sigmoid(x)
h = zeros(gru_layers × gru_hidden)                         // состояние, живёт между тиками
for layer in gru:                                          // 2 слоя
    n = W_ih · x + b_ih                                    // длина 3H
    g = W_hh · h[layer] + b_hh                             // длина 3H
    for j in 0..H-1:
        r = sigmoid(n[j]          + g[j])
        z = sigmoid(n[H + j]      + g[H + j])
        c = tanh   (n[2H + j]     + r * g[2H + j])
        h_new[j] = (1 - z) * c + z * h[layer][j]
    h[layer] = h_new
    x = h_new                                              // вход следующего слоя
pi        = W_pi · x + b_pi                                // 5 логитов
mu        = reshape(W_mu · x + b_mu, 2)                    // 5 × 2
log_sigma = clamp(reshape(W_ls · x + b_ls, 2), -3.0, 2.0)  // 5 × 2
rho       = tanh(W_rho · x + b_rho) * 0.95                 // 5
pause     = (W_pause · x + b_pause)[0]                     // логит, 1 число
```

Матрицы лежат **построчно**: `w[row * cols + col]`, `out[row] = b[row] + Σ_col w[row*cols+col] * x[col]`.

**Ключевая деталь, на которой ломаются все:** после входных слоёв стоит **SiLU**
(`x / (1 + e^-x)`), а **не сигмоида**. В обфусцированном оригинале это выглядит как
`x /= 1.0 + Math.exp(-x)` и легко читается как `sigmoid(x)`. С сигмоидой эталон
расходится на единицы, с SiLU — совпадает до 2.7e-15. Если твоя реализация не проходит
самотест, первым делом проверь это.

Всё считается в `double`. `float` допустим только для хранения весов, но тогда допуск
самотеста ослабляется до 1e-4, и это нужно явно указать в отчёте.

### 3.3 Формат `motor.bin` (little-endian, `double` = IEEE-754 binary64)

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
если 1:  i32 n, f64 edges[n];  i32 n, f64 caps_yaw[n];  i32 n, f64 caps_pitch[n]
f64      norm_mean[n_features]
f64      norm_var[n_features]
i32      inp_count;  на слой: i32 rows, i32 cols, f64 w[rows*cols], f64 b[rows]
i32      gru_count;  на слой: i32 H, i32 in_dim,
                     f64 w_ih[3H*in_dim], f64 b_ih[3H], f64 w_hh[3H*H], f64 b_hh[3H]
для каждой головы (pi, mu, log_sigma, rho, pause):
         i32 rows, i32 cols, f64 w[rows*cols], f64 b[rows]
i32      golden_count
на каждый эталонный тик:
         f64 features[47], f64 pi[5], f64 mu[10], f64 log_sigma[10], f64 rho[5],
         f64 pause, f64 hidden_out[gru_layers * gru_hidden]
```

Валидации при загрузке (нужны, чтобы не отлаживать немой мусор): сигнатура, версия,
`n_features == 47`, размерности слоёв, `mu/log_sigma` = `2*mix`, все 5 голов на месте.
Исходный `motor.json` (если работаешь с ним) — объект с ключами
`format/config/feature_names/ctx_names/norm{mean,var}/inp[]/gru[]/heads{pi,mu,log_sigma,rho,pause}/golden[]`,
где `inp[]` и `gru[]` — массивы слоёв с `w/b` и `w_ih/w_hh/b_ih/b_hh`, а `golden[]` — тики
с `features/pi/mu/log_sigma/rho/pause/hidden_out` (`mu` и `log_sigma` там 2-мерные).

## 4. Состояние рантайма

Модель рекуррентная, поэтому у экземпляра есть состояние. Заводи одну структуру:

```
history[8]      // снимки тика, индекс 0 — самый свежий
historySize     // 0..8
last1[2], last2[2]        // две последние применённые дельты (yaw, pitch)
peak[2]                   // сглаженный пик |дельты|
zeroRun[2], flipRun[2]    // тики без движения / тики с разворотом
lastBigSign[2]            // знак последнего «заметного» шага
residual[2]               // остаток квантования
lastCooldown = 1.0        // прогресс кулдауна атаки на прошлом тике
didAttackPrev             // кулдаун просел => был удар
lastTickId, lastTargetId  // служебные для сбросов/кэша
lastResult                // чтоб считать модель не более раза за тик
```

Снимок (`history[i]`): `selfYaw, selfPitch` (ротация на момент снимка), `targetYaw,
targetPitch` (углы на центр бокса), `minYawOff, maxYawOff, minPitchOff, maxPitchOff`
(разброс по 8 вершинам относительно центра), `distance` (до центра бокса).

Сброс (`reset()`): обнулить GRU, историю, все счётчики, `residual`, `lastCooldown = 1.0`,
`lastTickId/lastTargetId = MIN`, `lastResult = null`.

## 5. Шаг рантайма — одна функция `tick()`

Вход: `current` (текущая ротация — тихая или реальная, если тихой нет), `target`
(AABB + id + alive), `player` (глаза, клавиши, onGround/sprint/sneak, прогресс кулдауна,
`ticksSinceLastAttack`), `tickId` (монотонный номер тика, в MC — `player.age`).
Выход: новая ротация.

```
если target == null || !target.alive:
    reset(); вернуть current

если tickId == lastTickId && lastResult != null:
    вернуть lastResult                          // строго 1 расчёт на тик

если lastTickId != MIN && tickId != lastTickId + 1:
    reset(); lastTargetId = target.id           // разрыв тиков => полный сброс
иначе если target.id != lastTargetId:
    lastTargetId = target.id; lastResult = null // сменилась цель: историю НЕ чистим
lastTickId = tickId

didAttackPrev = player.attackCooldown < lastCooldown - 1e-4
lastCooldown  = player.attackCooldown

step = pixelStep(sensitivity)                   // см. раздел 7; sens 0.5 -> 0.15°
если step не конечен или <= 0: вернуть current

e = snapshot(current, target, player)           // null, если цель совпала с глазами
если e == null: запомнить(tickId, current); вернуть current
push(e)                                         // история на 8, index 0 = новый

f = features(e, current, player)                // 47 чисел, раздел 6
если есть не-конечные значения: запомнить(tickId, current); вернуть current

out = model(f)                                  // forward-pass, обновляет GRU
cmd = sample(out)                               // раздел 7

если модель содержит огибающую:
    для axis in {yaw, pitch}:
        counts = max(|last1[axis]|, |last2[axis]|) / step
        idx    = первый i, где edges[i] >= counts   (если таких нет — последний)
        cap    = caps[axis][min(idx, len-1)] * step
        cmd[axis] = clamp(cmd[axis], -cap, cap)

nYaw   = quantize(cmd[0], 0, step)
nPitch = quantize(cmd[1], 1, step)

next = current + (nYaw*step, nPitch*step)        // pitch зажать в [-90, 90]
updateStats(next.yaw - current.yaw (через wrap), next.pitch - current.pitch)

если nYaw == 0 && nPitch == 0: запомнить(tickId, current); вернуть current
запомнить(tickId, next); вернуть next
```

Снимок (`snapshot`):

```
dx,dy,dz = целевой center - eye
если dx²+dy²+dz² < 1e-10: return null
targetYaw   = wrapDegrees(toDegrees(atan2(dz, dx)) - 90)
targetPitch = wrapDegrees(-toDegrees(atan2(dy, hypot(dx, dz))))
distance    = sqrt(dx²+dy²+dz²)
для corner в 0..7:
    cx = (corner&1 ? maxX : minX) - eye.x
    cy = (corner&2 ? maxY : minY) - eye.y
    cz = (corner&4 ? maxZ : minZ) - eye.z
    yaw_c   = wrapDegrees(toDegrees(atan2(cz, cx)) - 90)
    pitch_c = wrapDegrees(-toDegrees(atan2(cy, hypot(cx, cz))))
    min/max по (wrap(yaw_c - targetYaw)) и (pitch_c - targetPitch)
если ни одна вершина не посчиталась: return null
```

## 6. 47 признаков (полная таблица)

`wrap(x)` = приведение к (−180, 180]. `s = asinh_scale = 1.0`. `cur` — текущая ротация,
`e` — снимок текущего тика, `a/b/c` — снимки с задержкой `latency`, `latency+1`,
`latency+2` (доступ: `history[min(index, historySize-1)]`).

| # | имя | формула |
|---|---|---|
| 0 | e_now_yaw | `asinh(wrap(e.targetYaw − cur.yaw) / s)` |
| 1 | e_now_pitch | `asinh((e.targetPitch − cur.pitch) / s)` |
| 2 | e_lag_yaw | `asinh(wrap(e.targetYaw − e.selfYaw) / s)` |
| 3 | e_lag_pitch | `asinh((e.targetPitch − e.selfPitch) / s)` |
| 4 | e_ext_yaw | `asinh(wrap(a.targetYaw + (latency+1)·velYaw − cur.yaw) / s)` |
| 5 | e_ext_pitch | `asinh((a.targetPitch + (latency+1)·velPitch − cur.pitch) / s)` |
| 6 | e_abs | `asinh(max(hypot(nowYaw, nowPitch), 1e-6) / s)` |
| 7 | e_dir_yaw | `nowYaw / max(hypot(nowYaw, nowPitch), 1e-6)` |
| 8 | e_dir_pitch | `nowPitch / max(hypot(nowYaw, nowPitch), 1e-6)` |
| 9 | tgt_vel_yaw | `asinh(wrap(a.targetYaw − b.targetYaw) / s)` = `asinh(velYaw/s)` |
| 10 | tgt_vel_pitch | `asinh((a.targetPitch − b.targetPitch) / s)` = `asinh(velPitch/s)` |
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
| 36 | movement_input_forward | `1.0 / 0.0` по зажатой клавише |
| 37 | movement_input_backward | то же |
| 38 | movement_input_left | то же |
| 39 | movement_input_right | то же |
| 40 | movement_input_jump | то же |
| 41 | state_on_ground | `1.0 / 0.0` |
| 42 | state_sprinting | `1.0 / 0.0` |
| 43 | state_in_sneaking_pose | `1.0 / 0.0` |
| 44 | attack_cooldown_progress | `getAttackCooldownProgress(0)` ∈ [0,1] |
| 45 | ticks_since_last_attack_clamped | `clamp(ticksSinceLastAttack, 0, 50)` |
| 46 | did_attack_previous_tick | `didAttackPrev ? 1 : 0` |

Где:

```
nowYaw   = wrap(e.targetYaw − cur.yaw);      nowPitch = e.targetPitch − cur.pitch
velYaw   = wrap(a.targetYaw − b.targetYaw);  velPitch = a.targetPitch − b.targetPitch
centerYaw   = 0.5·(a.minYawOff   + a.maxYawOff)
centerPitch = 0.5·(a.minPitchOff + a.maxPitchOff)
halfYaw     = max(0.5·(a.maxYawOff   − a.minYawOff),   1e-3)
halfPitch   = max(0.5·(a.maxPitchOff − a.minPitchOff), 1e-3)
```

Обновление статистики после применения дельты (`d` = фактически применённая дельта):

```
big  = |d| > zero_eps
flip = big && sign(d) · lastBigSign[axis] < 0
zeroRun[axis] = big  ? 0 : zeroRun[axis] + 1
flipRun[axis] = flip ? 0 : flipRun[axis] + 1
peak[axis]    = max(peak[axis] · peak_decay, |d|)
если big: lastBigSign[axis] = sign(d)
last2[axis] = last1[axis]; last1[axis] = d
```

## 7. Сэмплирование, шаг пикселя, квантование

**Шаг пикселя** (формула Minecraft; sens — обычная чувствительность мыши 0..1):

```
f = sensitivity * 0.6 + 0.2
pixelStep = f * f * f * 8.0 * 0.15          // sens 0.5 -> 0.15°
```

**Сэмплирование команды:**

```
если rand() < sigmoid(pause):  вернуть (0, 0)          // «человек держит прицел»
k    = выбор компоненты по softmax(pi)                  // численно устойчиво: exp(pi-max)/Σ
sd1  = exp(log_sigma[k][0]);  sd2 = exp(log_sigma[k][1]);  r = rho[k]
g1, g2 ~ N(0,1)                                         // независимые нормальные
raw1 = mu[k][0] + sd1·g1
raw2 = mu[k][1] + sd2·(r·g1 + sqrt(max(1 − r², 1e-6))·g2)
raw1 = clamp(raw1, ±asinh(max_cmd_yaw   / act_scale_yaw))
raw2 = clamp(raw2, ±asinh(max_cmd_pitch / act_scale_pitch))
dYaw   = clamp(act_scale_yaw   · sinh(raw1), ±max_cmd_yaw)
dPitch = clamp(act_scale_pitch · sinh(raw2), ±max_cmd_pitch)
```

**Квантование в пиксели с остатком:**

```
acc = cmd[axis] + (act_residual ? residual[axis] : 0)
q   = acc / step;   q += sign(q) * 1e-4        // чтобы 0.999999 не усекалось в 0
n   = act_quant_round ? round(q) : trunc(q)    // в этой модели trunc
residual[axis] = act_residual ? clamp(acc − n·step, ±step) : 0
применяется n·step градусов
```

Итог: за тик ротация меняется на целое число «пикселей мыши» — именно из-за этого
движение выглядит как человеческое, а не как непрерывное сглаживание.

## 8. Требования к интеграции в целевой клиент

1. **Тихая ротация.** В оригинале значения подменяются в исходящих пакетах движения,
   камера игрока не крутится. Если в клиенте есть свой rotation-менеджер (silent
   rotations) — отдавай `yaw/pitch` туда. Если ротация «жёсткая» (крутит камеру),
   предупреди: поведение будет отличаться, модель обучалась на записях мыши.
2. **Один тик — один расчёт.** Держи кэш по `tickId`; повторный вызов в том же тике
   возвращает прошлый результат (иначе GRU «убежит» и ротация задрожит).
3. **Порядок вызова.** Ротация считается после обработки ввода и до формирования пакетов
   движения — иначе признаки (ввод, кулдаун) отстают на тик.
4. **pitch всегда в [−90, 90]** после сложения с дельтой.
5. **Сбросы.** `reset()` на: пропажу/смерть цели, смену цели (частично — см. шаг 5),
   разрыв тиков (телепорт, лаг, респавн, смена мира), выход из мира.
6. **Отпускание ротации (важно).** Когда заявок на ротацию нет, оригинал доводит значение
   к реальной ротации игрока и при расхождении **≤ 9°** синхронизирует камеру
   (`player.setYaw/setPitch`, `renderYaw`) и обнуляет тихую ротацию. Без этого шага
   клиент «залипает»: после ауры следующий ввод мыши прыгает.
7. **Производительность.** Один forward-pass ≈ 0.9 млн multiply-add, это микросекунды;
   главное — не грузить модель каждый тик (грузись один раз, можно асинхронно, как в
   оригинале: пока модель не загрузилась, возвращай текущую ротацию).
8. **RNG.** Обычный `java.util.Random` (или аналог) — годится; главное, чтобы
   `nextGaussian()` и `nextDouble()` вызывались в том же порядке и количестве, иначе
   распределение поедет (детерминизм тик-в-тик не требуется, но структура — да).

## 9. Обязательные автотесты (критерии приёмки)

1. **Эталонные тики.** В `motor.bin` лежат `golden`-тики (входные 47 признаков и
   ожидаемые `pi/mu/log_sigma/rho/pause/hidden_out`). Прогони их **подряд, без сброса
   состояния между тиками**, и сравни максимум модуля разницы:
   - требуется **≤ 1e-9**; эталон даёт ~2.7e-15;
   - сравнивать нужно все шесть выходов, включая `hidden_out` (это ловит ошибки в
     порядке слоёв/сбросах);
   - если пришлось ослабить допуск (float-веса) — указать это в отчёте и обосновать.
2. **Дымовой прогон рантайма.** Цель-манекен, 40 тиков, начальная ротация `(0,0)`:
   ротация должна монотонно (с паузами и перелётами) прийти к направлению на цель,
   без NaN и без «дёрганья» на ±180°.
3. **Отсутствие исключений** при: null-цели, цели на нулевой дистанции, `historySize = 1`,
   экстремальном pitch (±89.9), выключенном/включённом модуле.
4. **Чистая компиляция** целевого проекта с максимальным уровнем предупреждений,
   которые в нём включены.

Готовый эталон теста — `SelfTest.java` (там же пример инициализации модели и целей).

## 10. Что сдать

1. Код порта (классы/модули) с короткими комментариями на русском или английском.
2. Файл модели, подключённый штатным для проекта способом (resources/embedded/loader).
3. Тест, автоматически проверяющий golden-тики (пункт 9.1), запускаемый одной командой.
4. `PORT_NOTES.md` с отчётом по чек-листу:

```
[ ] motor.bin читается, sha256 совпал, размерности провалидированы
[ ] forward-pass: golden <= 1e-9, фактическое расхождение ____
[ ] SiLU (а не sigmoid) после входных слоёв
[ ] 47 признаков в правильном порядке (список 0..46 сверен построчно)
[ ] история на 8 тиков, доступ min(index, historySize-1)
[ ] latency = 1 используется в признаках 4/5 и в a/b/c
[ ] огибающая в counts (не в градусах), caps умножаются на pixelStep
[ ] квантование trunc + перенос остатка, sign(q)*1e-4
[ ] pitch клампится в [-90, 90]
[ ] ровно один forward-pass на тик (кэш по tickId)
[ ] reset() на разрыв тиков / смерть цели / смену мира
[ ] отпускание ротации при расхождении <= 9°
[ ] 9.2 дымовой прогон: доводит до цели без NaN, значения ____
[ ] чем отличался целевой клиент от оригинала и что решено ____
```

5. Если какой-то пункт невозможен в целевом клиенте (нет клавиш, нет кулдауна,
   нет `ticksSinceLastAttack`) — не выдумывай: заполни признаки разумным
   приближением (например, `attackCooldownProgress = 1.0`, `ticksSinceLastAttack = 50`,
   клавиши = `false`), **явно перечисли это в отчёте** и отметь, какие признаки деградируют.

## 11. Известные грабли (проверяй первым делом при расхождениях)

1. **SiLU вместо sigmoid** после входных слоёв — самая частая ошибка.
2. **Сброс GRU между эталонными тиками** — golden-тики это одна непрерывная
   последовательность; сброс даёт расхождение в единицы.
3. **Путаница индексов признаков 4/5 (экстраполяция) и 9–12 (скорость/ускорение).**
   `e_ext_*` считается как `a.target + (latency+1)·vel − cur`, а не как `now − a`.
4. **Огибающая в градусах вместо counts** (`counts = |delta| / pixelStep`, cap тоже
   умножается на `pixelStep`).
5. **`act_scale` применяется в asinh-пространстве**: сначала клип `raw` по
   `asinh(max_cmd/act_scale)`, потом `sinh`, потом клип в градусах.
6. **Byte order**: `motor.bin` little-endian; `DataInputStream` в Java читает big-endian —
   читай через `ByteBuffer.order(LITTLE_ENDIAN)` (или пиши big-endian в конвертере, но
   тогда потеряешь совместимость с готовым файлом).
7. **Плотные слои**: веса хранятся плоско (`w[row*cols+col]`), не как `double[][]`.
8. **Знак/направление углов**: `yaw = atan2(dz, dx) − 90°`, `pitch = −atan2(dy, hypot)`.
   Перепутанные оси дают «смотрит в противоположную сторону» — легко теряется при
   переносе между системами координат (Z-up / left-handed).
9. **`wrapDegrees` при вычитании углов** — обязательно везде, где встречается разность
   yaw; иначе один раз в 360° ротация крутанётся на полный оборот.
10. **Кэш по тику**: без него модель вызывается 2–3 раза за тик (в рендере и в пакетах)
    и ротация начинает дрожать.
11. **`residual` и `last1/last2/peak` не сбрасываются при смене цели** — так задумано
    в оригинале (полный сброс только при разрыве тиков); «улучшать» не нужно.
12. **Веса нельзя пересчитывать/нормализовать**: `norm.mean/var` — часть модели
    (плюс `+1e-4` под корнем).

## 12. Стиль работы

- Не изобретай «сглаживание поверх» и не подменяй модель эвристиками — приёмка идёт по
  совпадению с эталоном.
- Не рефактори формулы «для красоты»: порядок операций и клипы влияют на результат.
- Если целевой клиент требует иной архитектуры (например, всё в одном классе без
  состояния) — сделай это, но сохрани математику и добавь тест.
- Изменения, которые нельзя проверить автотестом (применение ротации, отпускание по 9°),
  описывай в `PORT_NOTES.md` с указанием, где именно в клиенте это встроено.
