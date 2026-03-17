# Decidr Sensor Design

How every Galaxy Watch Ultra sensor enhances each app mode.

---

## Sensors Used

| # | Sensor | Android Type | Data |
|---|--------|-------------|------|
| 1 | Accelerometer | `TYPE_ACCELEROMETER` | XYZ acceleration (m/s^2) |
| 2 | Gyroscope | `TYPE_GYROSCOPE` | XYZ rotation rate (rad/s) |
| 3 | Magnetometer | `TYPE_MAGNETIC_FIELD` | XYZ magnetic field (uT) |
| 4 | Barometer | `TYPE_PRESSURE` | Atmospheric pressure (hPa) |
| 5 | Light sensor | `TYPE_LIGHT` | Ambient illuminance (lux) |
| 6 | Heart rate | `TYPE_HEART_RATE` | BPM (requires BODY_SENSORS) |
| 7 | Step counter | `TYPE_STEP_COUNTER` | Cumulative steps since reboot |
| 8 | Gravity | `TYPE_GRAVITY` | Gravity vector XYZ |
| 9 | Rotation vector | `TYPE_ROTATION_VECTOR` | Device orientation quaternion |

---

## Coin Flip

| Sensor | Role | Detail |
|--------|------|--------|
| **Accelerometer** | Primary trigger | Shake to flip. `shakeDetected` fires the flip animation. |
| **Gyroscope** | Alt trigger | Wrist TWIST (Z-axis spike) triggers a flip when the user rotates their wrist like flipping a real coin. Detected via `wristTwist`. |
| **Magnetometer** | Randomness seed | Magnetic field noise is XOR'd with `System.nanoTime()` to produce a hardware-entropy seed via `magneticEntropySeed()`. More genuinely random than `Random()`. |
| **Heart rate** | Cosmetic display | While waiting for a flip, show current BPM. If HR > 85, display "Nervous?" label. |
| **Barometer** | Cosmetic display | Show estimated altitude: "Flipping at 95 ft elevation". Derived via `altitudeFeet`. |

### Coin Flip Flow
```
User shakes watch  OR  twists wrist
  -> SensorHub.shakeDetected / wristTwist
  -> generate result using magneticEntropySeed()
  -> play flip animation
  -> show result + HR + altitude
```

---

## Wheel Spin

| Sensor | Role | Detail |
|--------|------|--------|
| **Gyroscope** | Spin control | Physical arm rotation controls spin speed. `gyroZ` (Z-axis rad/s) maps directly to wheel angular velocity. Faster wrist rotation = faster spin. |
| **Accelerometer** | Nudge | While wheel is spinning, tilt (`tiltAngleX/Y`) applies a subtle bias force, nudging the wheel slightly in the tilt direction. |
| **Light sensor** | Color adaptation | Wheel segment colors scale brightness inversely with ambient light via `lightAdaptiveBrightness()`. Brighter UI in dark rooms, subtler in bright light. |
| **Magnetometer** | Pointer orientation | The selector pointer arrow rotates to align with magnetic north via `compassBearing`. Cosmetic but adds a satisfying physical-world link. |

### Wheel Spin Flow
```
User rotates wrist clockwise
  -> SensorHub.gyroZ feeds wheel angular velocity
  -> wheel spins, decelerating over time
  -> tilt nudges via tiltAngleX/Y during spin
  -> pointer arrow tracks compassBearing
  -> colors adjust via lightAdaptiveBrightness()
  -> wheel stops -> result
```

---

## Dice Roll

| Sensor | Role | Detail |
|--------|------|--------|
| **Accelerometer** | Trigger + tumble duration | Shake triggers the roll. `shakeIntensity` determines how long dice tumble: gentle shake (< 18 m/s^2) = ~1 s tumble, hard shake (> 25 m/s^2) = ~3 s dramatic tumble. |
| **Gyroscope** | Visual tumble axis | `gyroscopeValues` XYZ map to which axis the 3D dice model tumbles on during the animation. Wrist rotation = matching dice rotation. |
| **Step counter** | Lucky number | Display "Lucky number: X" where X = `luckyNumber()` (step count modulo 6 + 1). |
| **Barometer** | Pressure trend | Tiny arrow icon next to result: up-arrow if `pressureTrend == 1` (rising), down-arrow if `-1` (falling), dash if stable. Purely cosmetic. |

### Dice Roll Flow
```
User shakes watch
  -> SensorHub.shakeDetected + shakeIntensity
  -> tumble duration = map(intensity, 14..30, 1.0..3.0) seconds
  -> dice 3D rotation axes driven by gyroscopeValues
  -> result generated (Random or magneticEntropySeed)
  -> show result + lucky number + pressure trend arrow
```

---

## Magic 8-Ball

| Sensor | Role | Detail |
|--------|------|--------|
| **Accelerometer + Gyroscope** | Combined trigger | Both `shakeDetected` AND significant gyroscope activity must co-occur within a short window. This prevents false triggers and requires a deliberate "shake the 8-ball" gesture. |
| **Heart rate** | Answer biasing | `heartRateMood()` influences answer pool: HR > 90 ("excited") skews toward dramatic/exciting answers ("IT IS CERTAIN!", "ABSOLUTELY YES!"). HR < 70 ("calm") skews toward neutral/measured answers ("Signs point to yes", "Ask again later"). |
| **Magnetometer** | Triangle rotation | The blue answer triangle inside the ball slowly rotates so one vertex always points toward magnetic north via `compassBearing`. Like a mystical compass. |
| **Light sensor** | Glow intensity | In dark environments (`isDarkEnvironment()`), the 8-ball's inner glow effect renders at higher intensity/opacity. The ball feels more "magical" in the dark. |
| **Barometer** | Answer mood | `pressureMood()` provides a secondary bias: falling pressure ("ominous") favors foreboding answers ("Don't count on it", "Outlook not so good"). Rising pressure ("positive") favors upbeat answers. Combined with HR mood for final answer selection. |

### Magic 8-Ball Answer Selection
```
mood = heartRateMood()       // "excited" | "calm" | "neutral"
weather = pressureMood()     // "ominous" | "positive" | "neutral"

if mood == "excited" AND weather == "positive":
    pick from STRONGLY_POSITIVE answers (80%) or NEUTRAL (20%)
elif mood == "calm" AND weather == "ominous":
    pick from NEGATIVE answers (60%) or NEUTRAL (40%)
else:
    uniform random across all answers

Triangle rotates to compassBearing
Glow alpha = if isDarkEnvironment() then 0.95 else 0.5
```

---

## Home Screen / Global

| Sensor | Role | Detail |
|--------|------|--------|
| **All sensors** | Sensor pulse ring | Tiny dots arranged around the circular screen edge. Each dot corresponds to a sensor and pulses/breathes based on live data magnitude. Accelerometer dots pulse with motion, HR dot pulses with heartbeat rhythm, etc. |
| **Step counter** | Complication | Daily step count displayed as a small label on the home screen. |
| **Heart rate** | Complication | Current BPM shown as a subtle heart icon with the number beside it. |
| **Light sensor** | UI adaptation | Overall UI brightness subtly adapts to ambient light for comfortable viewing via `lightAdaptiveBrightness()`. |

### Sensor Pulse Visualization
```
Dots positioned at clock positions around bezel:
  12 o'clock: Heart rate    — pulses red at HR frequency
   2 o'clock: Accelerometer — pulses cyan with motion magnitude
   4 o'clock: Gyroscope     — pulses blue with rotation speed
   5 o'clock: Magnetometer  — pulses purple, steady
   7 o'clock: Barometer     — pulses green, slow drift
   8 o'clock: Light         — pulses yellow, brightness-reactive
  10 o'clock: Steps         — pulses orange on step events
```

---

## Permissions Required

```xml
<!-- Heart rate sensor -->
<uses-permission android:name="android.permission.BODY_SENSORS" />

<!-- Step counter -->
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />

<!-- Haptic feedback -->
<uses-permission android:name="android.permission.VIBRATE" />

<!-- Higher fidelity accelerometer/gyroscope sampling (API 31+) -->
<uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />
```

---

## SensorHub API Summary

### Raw Flows
- `accelerometerValues: StateFlow<FloatArray>` — XYZ m/s^2
- `gyroscopeValues: StateFlow<FloatArray>` — XYZ rad/s
- `magnetometerValues: StateFlow<FloatArray>` — XYZ uT
- `pressure: StateFlow<Float>` — hPa
- `light: StateFlow<Float>` — lux
- `heartRate: StateFlow<Float>` — BPM
- `steps: StateFlow<Float>` — cumulative steps
- `gravityValues: StateFlow<FloatArray>` — XYZ m/s^2
- `rotationVectorValues: StateFlow<FloatArray>` — quaternion

### Computed Flows
- `compassBearing: StateFlow<Float>` — 0-360 degrees
- `altitude: StateFlow<Float>` — meters
- `altitudeFeet: StateFlow<Float>` — feet
- `shakeDetected: StateFlow<Boolean>` — edge-triggered
- `shakeIntensity: StateFlow<Float>` — net force of last shake
- `wristTwist: StateFlow<Boolean>` — edge-triggered
- `tiltAngleX: StateFlow<Float>` — degrees
- `tiltAngleY: StateFlow<Float>` — degrees
- `gyroZ: StateFlow<Float>` — Z-axis rad/s
- `magneticMagnitude: StateFlow<Float>` — total field strength
- `pressureTrend: StateFlow<Int>` — +1 rising, 0 stable, -1 falling

### Utility Functions
- `magneticEntropySeed(): Long` — hardware-noise RNG seed
- `luckyNumber(): Int` — steps % 6 + 1
- `isDarkEnvironment(): Boolean` — light < 20 lux
- `lightAdaptiveBrightness(): Float` — 0.6 to 1.4 multiplier
- `heartRateMood(): String` — "excited" / "calm" / "neutral"
- `pressureMood(): String` — "ominous" / "positive" / "neutral"
