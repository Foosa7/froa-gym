// xiao_imu_ble - streams the XIAO nRF52840 Sense's onboard 6-axis IMU over
// Bluetooth LE so a phone can read it live.
//
// Board:   Seeed Studio XIAO nRF52840 Sense
// Core:    "Seeed nRF52 Boards" (the Adafruit Bluefruit-based one, NOT the
//          mbed variant - this sketch uses bluefruit.h)
// Sensor:  LSM6DS3TR-C, 3-axis accelerometer + 3-axis gyroscope, on Wire1
//
// The board has no magnetometer, so this is a 6-axis stream. See README.md
// for the GATT layout and for how to read it on Android.

#include <Adafruit_TinyUSB.h>
#include <Wire.h>
#include <nrf_gpio.h>

#include "lsm6ds3tr.h"
#include "imu_ble_service.h"

// ---------------------------------------------------------------- settings

static const char* kDeviceName = "XIAO-IMU";

// Defaults; the client can change all of these at runtime by writing the
// Config characteristic.
static const ConfigPacket kDefaultConfig = {
    /* rate_hz   */ 50,
    /* accel_g   */ 4,
    /* gyro_code */ Lsm6ds3tr::GYRO_2000DPS,
    /* flags     */ 0,
};

// Mirror every sample to USB serial. Handy while bringing the board up, but it
// costs enough time to cap the achievable rate - leave it off for real use.
static const bool kSerialEcho = false;

// -------------------------------------------------------------------------

static Lsm6ds3tr imu;
static ImuBleService ble;

static bool imu_ok = false;
static uint8_t seq = 0;
static uint8_t pending_flags = 0;
static uint32_t sample_interval_us = 20000;  // recomputed from rate_hz
static uint32_t next_sample_us = 0;
static uint32_t last_temp_ms = 0;
static uint32_t last_batt_ms = 0;
static int16_t temp_centi = 0;

// Saturating float -> int16 conversion. Without the clamp, a hit that pins the
// accelerometer would wrap the fixed-point value and read as full-scale in the
// opposite direction.
static int16_t toFixed(float value, float scale) {
  float v = value * scale;
  if (v > 32767.0f) return 32767;
  if (v < -32768.0f) return -32768;
  return (int16_t)lroundf(v);
}

static Lsm6ds3tr::AccelRange accelRangeFromG(uint8_t g) {
  switch (g) {
    case 2:  return Lsm6ds3tr::ACCEL_2G;
    case 8:  return Lsm6ds3tr::ACCEL_8G;
    case 16: return Lsm6ds3tr::ACCEL_16G;
    default: return Lsm6ds3tr::ACCEL_4G;
  }
}

// Picks the lowest sensor ODR that is at least 2x the streaming rate, so every
// notification carries a fresh sample without running the IMU faster - and
// burning more power - than the link can use.
static Lsm6ds3tr::Odr odrForRate(uint8_t rate_hz) {
  const uint16_t want = (uint16_t)rate_hz * 2;
  if (want <= 13)  return Lsm6ds3tr::ODR_12_5HZ;
  if (want <= 26)  return Lsm6ds3tr::ODR_26HZ;
  if (want <= 52)  return Lsm6ds3tr::ODR_52HZ;
  if (want <= 104) return Lsm6ds3tr::ODR_104HZ;
  if (want <= 208) return Lsm6ds3tr::ODR_208HZ;
  return Lsm6ds3tr::ODR_416HZ;
}

static void applyConfig(const ConfigPacket& cfg) {
  imu.setAccelRange(accelRangeFromG(cfg.accel_g));
  imu.setGyroRange((Lsm6ds3tr::GyroRange)cfg.gyro_code);
  imu.setOdr(odrForRate(cfg.rate_hz));
  sample_interval_us = cfg.rate_hz ? (1000000UL / cfg.rate_hz) : 0;
  next_sample_us = micros();
}

// Battery sense on the XIAO nRF52840: P0.31 reads a 1M/510k divider off VBAT,
// and P0.14 must be driven low to connect it. Both pins are board-specific, so
// this compiles out on any variant that does not define them.
static uint8_t readBatteryPercent() {
#if defined(PIN_VBAT) && defined(VBAT_ENABLE)
  digitalWrite(VBAT_ENABLE, LOW);   // active low
  delayMicroseconds(50);
  const int raw = analogRead(PIN_VBAT);
  digitalWrite(VBAT_ENABLE, HIGH);

  const float v_adc = raw * (3.6f / 4096.0f);       // AR_INTERNAL, 12-bit
  const float v_bat = v_adc * (1510.0f / 510.0f);   // undo the divider

  // Coarse linear map over the useful part of a LiPo discharge curve. Good
  // enough for a battery bar; not a fuel gauge.
  float pct = (v_bat - 3.40f) / (4.10f - 3.40f) * 100.0f;
  if (pct < 0.0f) pct = 0.0f;
  if (pct > 100.0f) pct = 100.0f;
  return (uint8_t)pct;
#else
  return 100;
#endif
}

void setup() {
  Serial.begin(115200);
  // Wait briefly for a host to open the port, but never block on it - the
  // board has to run standalone on a battery.
  const uint32_t serial_deadline = millis() + 2000;
  while (!Serial && millis() < serial_deadline) delay(10);

  Serial.println(F("XIAO nRF52840 Sense - IMU over BLE"));

  // BLE comes up first and unconditionally. If the IMU is the thing that is
  // broken, we still want the board discoverable so the fault is visible from
  // the phone rather than the board simply never appearing.
  if (!ble.begin(kDeviceName, kDefaultConfig)) {
    Serial.println(F("FATAL: BLE stack failed to start"));
    for (;;) delay(1000);
  }
  Serial.print(F("Advertising as "));
  Serial.println(kDeviceName);

  if (!Lsm6ds3tr::powerOnBoardSensor()) {
    // Rail never came up: do not call Wire at all, it would hang forever.
    Serial.println(F("ERROR: IMU rail did not come up (P1.08); bus held low"));
    imu_ok = false;
  } else {
    Wire1.begin();
    Wire1.setClock(400000);
    imu_ok = imu.begin(Wire1, 0x6A);
  }
  if (imu_ok) {
    Serial.print(F("IMU ready, WHO_AM_I=0x"));
    Serial.println(imu.whoAmI(), HEX);
    applyConfig(kDefaultConfig);
  } else {
    // Stay advertising but send no samples: streaming zeros would look like
    // real data. A steady red LED plus a discoverable device with a silent
    // stream is the honest signal that the sensor, not the radio, is at fault.
    Serial.println(F("ERROR: LSM6DS3TR-C not responding on Wire1 @ 0x6A"));
    pinMode(LED_RED, OUTPUT);
    digitalWrite(LED_RED, LOW);   // active low
  }

#if defined(PIN_VBAT) && defined(VBAT_ENABLE)
  pinMode(VBAT_ENABLE, OUTPUT);
  digitalWrite(VBAT_ENABLE, HIGH);
  analogReference(AR_INTERNAL);   // 0.6 V reference with 1/6 gain -> 3.6 V FS
  analogReadResolution(12);
#endif

  pinMode(LED_BLUE, OUTPUT);
  digitalWrite(LED_BLUE, HIGH);   // XIAO LEDs are active low
}

void loop() {
  ConfigPacket updated;
  if (ble.takeConfigChange(&updated)) {
    applyConfig(updated);
    Serial.print(F("config: rate="));
    Serial.print(updated.rate_hz);
    Serial.print(F("Hz accel=+/-"));
    Serial.print(updated.accel_g);
    Serial.print(F("g gyro_code="));
    Serial.println(updated.gyro_code);
  }

  if (sample_interval_us == 0) {   // rate_hz == 0: stream paused
    delay(10);
    return;
  }

  const uint32_t now_us = micros();
  // Signed comparison so this stays correct across the micros() rollover.
  if ((int32_t)(now_us - next_sample_us) < 0) return;
  next_sample_us += sample_interval_us;
  // If we fell behind (a long BLE event, say), resync instead of trying to
  // catch up with a burst of back-to-back samples.
  if ((int32_t)(micros() - next_sample_us) > (int32_t)sample_interval_us) {
    next_sample_us = micros() + sample_interval_us;
  }

  if (!imu_ok) {
    delay(100);
    return;
  }

  Lsm6ds3tr::Sample s;
  if (!imu.read(&s)) {
    pending_flags |= ImuBleService::FLAG_DROPPED;
    return;
  }

  // The die temperature moves slowly; sampling it at 1 Hz keeps it off the
  // per-sample I2C budget.
  const uint32_t now_ms = millis();
  if ((uint32_t)(now_ms - last_temp_ms) >= 1000) {
    last_temp_ms = now_ms;
    float c;
    if (imu.readTemperature(&c)) temp_centi = toFixed(c, 100.0f);
  }
  if ((uint32_t)(now_ms - last_batt_ms) >= 30000) {
    last_batt_ms = now_ms;
    ble.setBatteryLevel(readBatteryPercent());
  }

  ImuPacket pkt;
  pkt.seq = seq++;
  pkt.flags = pending_flags;
  pending_flags = 0;
  pkt.t_ms = now_ms;
  pkt.ax = toFixed(s.ax, 1000.0f);   // g      -> milli-g
  pkt.ay = toFixed(s.ay, 1000.0f);
  pkt.az = toFixed(s.az, 1000.0f);
  pkt.gx = toFixed(s.gx, 10.0f);     // deg/s  -> deci-deg/s
  pkt.gy = toFixed(s.gy, 10.0f);
  pkt.gz = toFixed(s.gz, 10.0f);
  pkt.temp = temp_centi;

  ble.publish(pkt);

  digitalWrite(LED_BLUE, ble.connected() ? LOW : HIGH);

  if (kSerialEcho) {
    Serial.printf("a %7.3f %7.3f %7.3f g   g %8.2f %8.2f %8.2f dps  %.1fC\n",
                  s.ax, s.ay, s.az, s.gx, s.gy, s.gz, temp_centi / 100.0f);
  }
}
