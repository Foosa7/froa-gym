// lsm6ds3tr.h - driver for the LSM6DS3TR-C 6-axis IMU built into the
// XIAO nRF52840 Sense.
//
// On that board the part sits on the *secondary* I2C bus (Wire1, P0.07/P0.27)
// at address 0x6A, and its supply rail is gated by P1.08 - the chip is dead on
// the bus until that pin is driven high. See Lsm6ds3tr::powerOnBoardSensor().
#pragma once

#include <Arduino.h>
#include "i2c_reg.h"

class Lsm6ds3tr {
 public:
  // Full-scale ranges. Enum values are the register field encodings.
  enum AccelRange : uint8_t { ACCEL_2G = 0, ACCEL_16G = 1, ACCEL_4G = 2, ACCEL_8G = 3 };
  enum GyroRange : uint8_t { GYRO_245DPS = 0, GYRO_500DPS = 1, GYRO_1000DPS = 2, GYRO_2000DPS = 3 };

  // Output data rates, register field encoding.
  enum Odr : uint8_t {
    ODR_OFF = 0x0, ODR_12_5HZ = 0x1, ODR_26HZ = 0x2, ODR_52HZ = 0x3,
    ODR_104HZ = 0x4, ODR_208HZ = 0x5, ODR_416HZ = 0x6, ODR_833HZ = 0x7,
    ODR_1660HZ = 0x8, ODR_3330HZ = 0x9, ODR_6660HZ = 0xA,
  };

  struct Sample {
    float ax, ay, az;   // g
    float gx, gy, gz;   // degrees/second
    float temp_c;       // degrees Celsius
  };

  // Powers the onboard sensor and waits for its I2C lines to release.
  // Call this before Wire1.begin(). Safe to call more than once.
  //
  // Returns false if the bus never releases, which means the rail did not come
  // up. Check it: this core's Wire spins forever on a bus that is held low
  // (TWIM never raises TXSTARTED), so a single I2C call on a dead bus wedges
  // the whole sketch with no timeout and no error.
  static bool powerOnBoardSensor();

  // True if the onboard IMU's I2C lines (P0.07/P0.27) are released. Reads them
  // directly as pulled-up inputs, so it is safe to call before Wire1.begin()
  // and cannot block.
  static bool onboardBusReleased();

  // Brings up the part: probes WHO_AM_I, soft-resets, then applies the ranges
  // and ODR. `bus` must already be begin()-ed. Returns false if the chip does
  // not answer or does not identify as an LSM6DS3/LSM6DS3TR-C.
  bool begin(TwoWire& bus = Wire1, uint8_t addr = 0x6A);

  bool setAccelRange(AccelRange range);
  bool setGyroRange(GyroRange range);
  bool setOdr(Odr odr);           // applies to both accel and gyro

  // True once a fresh accel+gyro sample pair is in the output registers.
  bool dataReady();

  // Reads gyro+accel in one 12-byte burst (the output registers are
  // contiguous) and converts to engineering units.
  bool read(Sample* out);

  bool readTemperature(float* celsius);

  AccelRange accelRange() const { return accel_range_; }
  GyroRange gyroRange() const { return gyro_range_; }
  uint8_t whoAmI() const { return who_am_i_; }

 private:
  static constexpr uint8_t REG_WHO_AM_I  = 0x0F;
  static constexpr uint8_t REG_CTRL1_XL  = 0x10;
  static constexpr uint8_t REG_CTRL2_G   = 0x11;
  static constexpr uint8_t REG_CTRL3_C   = 0x12;
  static constexpr uint8_t REG_CTRL4_C   = 0x13;
  static constexpr uint8_t REG_CTRL6_C   = 0x15;
  static constexpr uint8_t REG_CTRL7_G   = 0x16;
  static constexpr uint8_t REG_STATUS    = 0x1E;
  static constexpr uint8_t REG_OUT_TEMP_L = 0x20;
  static constexpr uint8_t REG_OUTX_L_G  = 0x22;  // gyro X..Z then accel X..Z

  static constexpr uint8_t WHO_AM_I_TRC   = 0x6A;  // LSM6DS3TR-C
  static constexpr uint8_t WHO_AM_I_PLAIN = 0x69;  // original LSM6DS3

  I2CReg reg_;
  AccelRange accel_range_ = ACCEL_4G;
  GyroRange gyro_range_ = GYRO_2000DPS;
  Odr odr_ = ODR_104HZ;
  uint8_t who_am_i_ = 0;
  float accel_scale_ = 0.0f;  // g per LSB
  float gyro_scale_ = 0.0f;   // dps per LSB

  void recomputeScales();
};
