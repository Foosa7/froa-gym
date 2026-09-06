// i2c_reg.h - thin register-access helpers shared by the sensor drivers.
//
// Every sensor on this board speaks I2C with an 8-bit sub-address followed by
// one or more data bytes, so the drivers only need these four primitives.
#pragma once

#include <Arduino.h>
#include <Wire.h>

class I2CReg {
 public:
  I2CReg() : bus_(nullptr), addr_(0) {}
  I2CReg(TwoWire& bus, uint8_t addr) : bus_(&bus), addr_(addr) {}

  void attach(TwoWire& bus, uint8_t addr) {
    bus_ = &bus;
    addr_ = addr;
  }

  uint8_t address() const { return addr_; }

  bool write8(uint8_t reg, uint8_t value) {
    bus_->beginTransmission(addr_);
    bus_->write(reg);
    bus_->write(value);
    return bus_->endTransmission() == 0;
  }

  bool read(uint8_t reg, uint8_t* dst, size_t len) {
    bus_->beginTransmission(addr_);
    bus_->write(reg);
    // Repeated START: releasing the bus here lets another master in, and some
    // of these parts abort the burst if that happens.
    if (bus_->endTransmission(false) != 0) return false;
    if (bus_->requestFrom(addr_, (uint8_t)len) != len) return false;
    for (size_t i = 0; i < len; i++) dst[i] = bus_->read();
    return true;
  }

  bool read8(uint8_t reg, uint8_t* value) { return read(reg, value, 1); }

  // Read-modify-write of the bits selected by `mask`.
  bool update8(uint8_t reg, uint8_t mask, uint8_t value) {
    uint8_t cur;
    if (!read8(reg, &cur)) return false;
    uint8_t next = (uint8_t)((cur & ~mask) | (value & mask));
    if (next == cur) return true;
    return write8(reg, next);
  }

 private:
  TwoWire* bus_;
  uint8_t addr_;
};
