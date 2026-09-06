// imu_ble_service.h - custom GATT service that streams LSM6DS3TR-C samples.
//
// UUIDs (128-bit, base a3c875xx-8ed3-4bdf-8a39-a01bebede295):
//   a3c87500-...  IMU service
//   a3c87501-...  Sample     notify + read, 20 bytes, see ImuPacket
//   a3c87502-...  Config     read + write,   4 bytes, see ConfigPacket
//
// The sample payload is deliberately 20 bytes so it fits a notification at the
// default 23-byte ATT MTU. That matters because Android never renegotiates the
// MTU on its own - a client that does not ask for a larger one would otherwise
// receive silently truncated packets.
#pragma once

#include <Arduino.h>
#include <bluefruit.h>
#include "lsm6ds3tr.h"

// Wire format of the Sample characteristic. Little-endian, packed, 20 bytes.
// Fixed-point rather than float so it stays 20 bytes and stays trivial to
// decode from a hex dump in nRF Connect.
struct __attribute__((packed)) ImuPacket {
  uint8_t  seq;        // rolling counter; gaps mean dropped notifications
  uint8_t  flags;      // see FLAG_* below
  uint32_t t_ms;       // millis() at sample time
  int16_t  ax, ay, az; // milli-g          (divide by 1000 for g)
  int16_t  gx, gy, gz; // deci-deg/s       (divide by 10 for deg/s)
  int16_t  temp;       // centi-degC       (divide by 100 for degC)
};
static_assert(sizeof(ImuPacket) == 20, "ImuPacket must fit a 23-byte ATT MTU");

// Wire format of the Config characteristic. Write all 4 bytes at once.
struct __attribute__((packed)) ConfigPacket {
  uint8_t rate_hz;      // notification rate, 1..208; 0 pauses the stream
  uint8_t accel_g;      // full scale: 2, 4, 8 or 16
  uint8_t gyro_code;    // full scale: 0=245, 1=500, 2=1000, 3=2000 deg/s
  uint8_t flags;        // reserved, write 0
};
static_assert(sizeof(ConfigPacket) == 4, "ConfigPacket layout changed");

class ImuBleService {
 public:
  static constexpr uint8_t FLAG_DROPPED = 0x01;  // a notification was lost

  ImuBleService();

  // Starts the SoftDevice, the standard Device Information and Battery
  // services, this custom service, and advertising. `device_name` shows up in
  // the scan list, so keep it short - it shares the 31-byte scan response with
  // the 128-bit service UUID.
  bool begin(const char* device_name, const ConfigPacket& initial);

  // Notifies every subscriber with one sample. No-op when nobody is
  // connected, which is also when the radio is left alone to advertise.
  void publish(const ImuPacket& pkt);

  bool connected() const { return Bluefruit.connected() > 0; }

  // Current settings, including any change written by the client.
  const ConfigPacket& config() const { return config_; }

  // True once the client has written a new config; copies it out and clears
  // the flag. Poll this from loop() and reconfigure the sensor when it fires.
  bool takeConfigChange(ConfigPacket* out);

  void setBatteryLevel(uint8_t percent) { battery_.write(percent); }

 private:
  static void onWriteTrampoline(uint16_t conn_hdl, BLECharacteristic* chr,
                                uint8_t* data, uint16_t len);
  void onConfigWrite(const uint8_t* data, uint16_t len);

  // NOTE: BLEUuid stores the pointer it is handed rather than copying the
  // bytes, so these must be constructed from the file-scope arrays in the .cpp
  // (static storage duration), never from a temporary.
  BLEService svc_;
  BLECharacteristic sample_;
  BLECharacteristic cfg_;

  BLEDis dis_;
  BLEBas battery_;

  ConfigPacket config_;
  volatile bool config_dirty_ = false;

  static ImuBleService* instance_;
};
