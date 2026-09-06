#include "imu_ble_service.h"

// 128-bit UUIDs in the byte order the SoftDevice wants: little-endian, i.e.
// the textual UUID reversed. These live at file scope because BLEUuid keeps
// the pointer it is given for the lifetime of the service.
static const uint8_t UUID_SVC[16] = {
    0x95, 0xE2, 0xED, 0xEB, 0x1B, 0xA0, 0x39, 0x8A,
    0xDF, 0x4B, 0xD3, 0x8E, 0x00, 0x75, 0xC8, 0xA3};  // a3c87500-...
static const uint8_t UUID_SAMPLE[16] = {
    0x95, 0xE2, 0xED, 0xEB, 0x1B, 0xA0, 0x39, 0x8A,
    0xDF, 0x4B, 0xD3, 0x8E, 0x01, 0x75, 0xC8, 0xA3};  // a3c87501-...
static const uint8_t UUID_CONFIG[16] = {
    0x95, 0xE2, 0xED, 0xEB, 0x1B, 0xA0, 0x39, 0x8A,
    0xDF, 0x4B, 0xD3, 0x8E, 0x02, 0x75, 0xC8, 0xA3};  // a3c87502-...

ImuBleService* ImuBleService::instance_ = nullptr;

ImuBleService::ImuBleService()
    : svc_(UUID_SVC), sample_(UUID_SAMPLE), cfg_(UUID_CONFIG) {
  config_ = ConfigPacket{50, 4, Lsm6ds3tr::GYRO_2000DPS, 0};
}

bool ImuBleService::begin(const char* device_name, const ConfigPacket& initial) {
  instance_ = this;
  config_ = initial;

  // Deliberately NOT calling configPrphBandwidth(BANDWIDTH_MAX): it raises the
  // SoftDevice's RAM requirement, and the sample packet is sized to fit the
  // default 23-byte ATT MTU anyway, so there is nothing to gain by asking for
  // a bigger one.
  if (!Bluefruit.begin(1, 0)) return false;   // 1 peripheral, 0 central links
  Bluefruit.setTxPower(4);        // dBm; valid: -40 -20 -16 -12 -8 -4 0 2 3 4 5 6 7 8
  Bluefruit.setName(device_name);

  // Ask for a 7.5-15 ms connection interval (units of 1.25 ms). One
  // notification goes out per connection event, so this interval - not
  // rate_hz - is the real ceiling on throughput: a central that settles on
  // 60 ms caps the stream near 16 Hz no matter what rate is configured. The
  // central has the final say; this is only a request.
  Bluefruit.Periph.setConnInterval(6, 12);

  dis_.setManufacturer("Seeed Studio");
  dis_.setModel("XIAO nRF52840 Sense");
  dis_.setSoftwareRev("imu-ble 1.0");
  dis_.begin();

  battery_.begin();
  battery_.write(100);

  if (svc_.begin() != ERROR_NONE) return false;

  sample_.setProperties(CHR_PROPS_NOTIFY | CHR_PROPS_READ);
  sample_.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  sample_.setFixedLen(sizeof(ImuPacket));
  sample_.setUserDescriptor("IMU sample");
  if (sample_.begin() != ERROR_NONE) return false;

  cfg_.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE);
  cfg_.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  cfg_.setFixedLen(sizeof(ConfigPacket));
  cfg_.setUserDescriptor("IMU config");
  cfg_.setWriteCallback(onWriteTrampoline);
  if (cfg_.begin() != ERROR_NONE) return false;
  cfg_.write(&config_, sizeof(config_));

  Bluefruit.Advertising.restartOnDisconnect(true);
  // 20 ms .. 152.5 ms in 0.625 ms units: fast while a phone is likely
  // scanning, then slower to save power.
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  // Set the advertisement up exactly once. It deliberately never changes
  // afterwards: BLEAdvertising::_start() caches the advertising data
  // descriptor in a function-level `static`, so its lengths are captured on
  // the first start and never refreshed. Re-advertising different content
  // therefore fails - sd_ble_gap_adv_set_configure rejects it, start()
  // returns false, and the device stops advertising altogether. Anything
  // dynamic has to go over a connection, not the advertisement.
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addService(svc_);
  Bluefruit.ScanResponse.addName();
  if (!Bluefruit.Advertising.start(0)) return false;  // 0 = advertise forever
  return true;
}

void ImuBleService::publish(const ImuPacket& pkt) {
  if (!Bluefruit.connected()) return;
  // notify() returns false when the SoftDevice's TX queue is full; the client
  // sees that as a gap in ImuPacket::seq.
  sample_.notify(&pkt, sizeof(pkt));
}

void ImuBleService::onWriteTrampoline(uint16_t, BLECharacteristic*,
                                      uint8_t* data, uint16_t len) {
  if (instance_ != nullptr) instance_->onConfigWrite(data, len);
}

void ImuBleService::onConfigWrite(const uint8_t* data, uint16_t len) {
  if (len != sizeof(ConfigPacket)) return;

  ConfigPacket in;
  memcpy(&in, data, sizeof(in));

  // Clamp to what the hardware can actually do, so a bad write cannot wedge
  // the stream. 208 Hz is the highest LSM6DS3TR-C ODR this firmware uses.
  if (in.rate_hz > 208) in.rate_hz = 208;
  if (in.accel_g != 2 && in.accel_g != 4 && in.accel_g != 8 && in.accel_g != 16) {
    in.accel_g = config_.accel_g;
  }
  if (in.gyro_code > Lsm6ds3tr::GYRO_2000DPS) in.gyro_code = config_.gyro_code;

  config_ = in;
  config_dirty_ = true;

  // Echo the clamped values back so a read reflects what is really in effect.
  cfg_.write(&config_, sizeof(config_));
}

bool ImuBleService::takeConfigChange(ConfigPacket* out) {
  if (!config_dirty_) return false;
  config_dirty_ = false;
  *out = config_;
  return true;
}
