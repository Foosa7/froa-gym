#include "lsm6ds3tr.h"

#include <nrf_gpio.h>

// P1.08 powers the IMU on the XIAO nRF52840 Sense, and P0.07/P0.27 are its
// I2C lines. We poke these through the nRF HAL rather than digitalWrite() so
// nothing depends on the Arduino variant exposing pin numbers for them.
static constexpr uint32_t kImuPowerPin = NRF_GPIO_PIN_MAP(1, 8);
static constexpr uint32_t kImuSdaPin   = NRF_GPIO_PIN_MAP(0, 7);
static constexpr uint32_t kImuSclPin   = NRF_GPIO_PIN_MAP(0, 27);

bool Lsm6ds3tr::onboardBusReleased() {
  nrf_gpio_cfg_input(kImuSdaPin, NRF_GPIO_PIN_PULLUP);
  nrf_gpio_cfg_input(kImuSclPin, NRF_GPIO_PIN_PULLUP);
  delayMicroseconds(200);
  return nrf_gpio_pin_read(kImuSdaPin) && nrf_gpio_pin_read(kImuSclPin);
}

bool Lsm6ds3tr::powerOnBoardSensor() {
  // HIGH DRIVE (H0H1) is required, not optional. P1.08 feeds the sensor's
  // supply rail directly instead of switching a MOSFET, so the pin has to
  // source the part's operating current - about 1 mA in high-performance
  // mode. The nRF52's standard drive cannot: measured on this board, standard
  // drive never brings the rail up at all (I2C lines still held low after
  // 3 seconds), while high drive releases them in 2 ms. With the rail down the
  // unpowered chip clamps SDA/SCL low, and the first Wire call then hangs
  // forever.
  nrf_gpio_cfg(kImuPowerPin, NRF_GPIO_PIN_DIR_OUTPUT,
               NRF_GPIO_PIN_INPUT_DISCONNECT, NRF_GPIO_PIN_NOPULL,
               NRF_GPIO_PIN_H0H1, NRF_GPIO_PIN_NOSENSE);
  nrf_gpio_pin_set(kImuPowerPin);

  // Poll rather than sleeping a fixed time: measured release is ~2 ms, but the
  // answer is also what tells us the rail actually came up.
  for (int i = 0; i < 100; i++) {
    if (onboardBusReleased()) {
      delay(20);   // the part's own boot time once powered
      return true;
    }
    delay(1);
  }
  return false;
}

void Lsm6ds3tr::recomputeScales() {
  // Sensitivities from the datasheet, converted from mg/LSB and mdps/LSB.
  switch (accel_range_) {
    case ACCEL_2G:  accel_scale_ = 0.061f / 1000.0f; break;
    case ACCEL_4G:  accel_scale_ = 0.122f / 1000.0f; break;
    case ACCEL_8G:  accel_scale_ = 0.244f / 1000.0f; break;
    case ACCEL_16G: accel_scale_ = 0.488f / 1000.0f; break;
  }
  switch (gyro_range_) {
    case GYRO_245DPS:  gyro_scale_ =  8.75f / 1000.0f; break;
    case GYRO_500DPS:  gyro_scale_ = 17.50f / 1000.0f; break;
    case GYRO_1000DPS: gyro_scale_ = 35.00f / 1000.0f; break;
    case GYRO_2000DPS: gyro_scale_ = 70.00f / 1000.0f; break;
  }
}

bool Lsm6ds3tr::begin(TwoWire& bus, uint8_t addr) {
  // Refuse to touch a bus that is being held low. Every Wire call on this core
  // is an unbounded spin, so this check is what keeps a sensor fault from
  // becoming a firmware hang.
  if (&bus == &Wire1 && !onboardBusReleased()) return false;

  reg_.attach(bus, addr);

  if (!reg_.read8(REG_WHO_AM_I, &who_am_i_)) return false;
  if (who_am_i_ != WHO_AM_I_TRC && who_am_i_ != WHO_AM_I_PLAIN) return false;

  // SW_RESET, then poll until the part clears the bit for us.
  if (!reg_.write8(REG_CTRL3_C, 0x01)) return false;
  for (int i = 0; i < 20; i++) {
    uint8_t ctrl3;
    if (reg_.read8(REG_CTRL3_C, &ctrl3) && (ctrl3 & 0x01) == 0) break;
    delay(2);
  }

  // BDU=1 so the low/high halves of a 16-bit output always belong to the same
  // sample; IF_INC=1 for register auto-increment during burst reads.
  if (!reg_.write8(REG_CTRL3_C, 0x44)) return false;

  // High-performance mode for both blocks (the reset default, set explicitly
  // so the configuration does not depend on reset state).
  reg_.update8(REG_CTRL6_C, 0x10, 0x00);  // XL_HM_MODE = 0 -> enabled
  reg_.update8(REG_CTRL7_G, 0x80, 0x00);  // G_HM_MODE  = 0 -> enabled

  if (!setAccelRange(accel_range_)) return false;
  if (!setGyroRange(gyro_range_)) return false;
  if (!setOdr(odr_)) return false;

  return true;
}

bool Lsm6ds3tr::setAccelRange(AccelRange range) {
  accel_range_ = range;
  recomputeScales();
  return reg_.update8(REG_CTRL1_XL, 0x0C, (uint8_t)(range << 2));
}

bool Lsm6ds3tr::setGyroRange(GyroRange range) {
  gyro_range_ = range;
  recomputeScales();
  // FS_125 (bit 1) stays clear so FS_G selects the 245..2000 dps ranges.
  return reg_.update8(REG_CTRL2_G, 0x0E, (uint8_t)(range << 2));
}

bool Lsm6ds3tr::setOdr(Odr odr) {
  odr_ = odr;
  uint8_t field = (uint8_t)(odr << 4);
  if (!reg_.update8(REG_CTRL1_XL, 0xF0, field)) return false;
  return reg_.update8(REG_CTRL2_G, 0xF0, field);
}

bool Lsm6ds3tr::dataReady() {
  uint8_t status;
  if (!reg_.read8(REG_STATUS, &status)) return false;
  return (status & 0x03) == 0x03;  // XLDA | GDA
}

bool Lsm6ds3tr::read(Sample* out) {
  // 0x22..0x2D is gyro XYZ followed by accel XYZ, so one burst gets both.
  uint8_t raw[12];
  if (!reg_.read(REG_OUTX_L_G, raw, sizeof(raw))) return false;

  int16_t v[6];
  for (int i = 0; i < 6; i++) {
    v[i] = (int16_t)((uint16_t)raw[2 * i] | ((uint16_t)raw[2 * i + 1] << 8));
  }

  out->gx = v[0] * gyro_scale_;
  out->gy = v[1] * gyro_scale_;
  out->gz = v[2] * gyro_scale_;
  out->ax = v[3] * accel_scale_;
  out->ay = v[4] * accel_scale_;
  out->az = v[5] * accel_scale_;
  out->temp_c = NAN;  // filled separately; it updates far slower than the IMU
  return true;
}

bool Lsm6ds3tr::readTemperature(float* celsius) {
  uint8_t raw[2];
  if (!reg_.read(REG_OUT_TEMP_L, raw, sizeof(raw))) return false;
  int16_t t = (int16_t)((uint16_t)raw[0] | ((uint16_t)raw[1] << 8));
  // Both parts zero the reading at 25 degC but scale it differently:
  // 256 LSB/degC on the TR-C, 16 LSB/degC on the original LSM6DS3.
  const float lsb_per_deg = (who_am_i_ == WHO_AM_I_TRC) ? 256.0f : 16.0f;
  *celsius = 25.0f + t / lsb_per_deg;
  return true;
}
