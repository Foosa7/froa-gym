#!/usr/bin/env python3
"""
Bridge the XIAO's BLE stream to a TCP socket.

Why this exists: the Android emulator has no Bluetooth radio, so an emulated
device cannot see the board at all. This connects to the XIAO over the
*laptop's* Bluetooth and re-streams the identical 20-byte packets on a TCP
port, so the app running in the emulator displays real live data from real
hardware rather than a simulation.

From inside the emulator the host machine is reachable at 10.0.2.2, which is
what the app uses by default when it detects it is running on an emulator.

Usage:
    python -m venv .venv && .venv/bin/pip install bleak
    .venv/bin/python tools/imu_bridge.py

Options:
    --port N     TCP port to serve on (default 9999)
    --name NAME  BLE device name to look for (default XIAO-IMU)
    --decode     also print decoded readings to the terminal
"""

import argparse
import asyncio
import struct
import sys

from bleak import BleakClient, BleakScanner

SERVICE_UUID = "a3c87500-8ed3-4bdf-8a39-a01bebede295"
SAMPLE_UUID = "a3c87501-8ed3-4bdf-8a39-a01bebede295"
PACKET_FMT = "<BBI7h"
PACKET_SIZE = 20

clients: set[asyncio.StreamWriter] = set()


def decode(payload: bytes) -> str:
    seq, flags, t_ms, ax, ay, az, gx, gy, gz, temp = struct.unpack(PACKET_FMT, payload)
    return (
        f"seq={seq:3d} t={t_ms:8d}ms "
        f"a=({ax/1000:+.3f},{ay/1000:+.3f},{az/1000:+.3f})g "
        f"g=({gx/10:+7.1f},{gy/10:+7.1f},{gz/10:+7.1f})dps "
        f"{temp/100:.2f}C"
    )


async def on_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    peer = writer.get_extra_info("peername")
    clients.add(writer)
    print(f"[tcp] client connected: {peer}  ({len(clients)} total)")
    try:
        # The app never sends anything; this just parks until it disconnects.
        await reader.read()
    except Exception:
        pass
    finally:
        clients.discard(writer)
        writer.close()
        print(f"[tcp] client gone: {peer}  ({len(clients)} left)")


def broadcast(payload: bytes) -> None:
    """Fan one packet out to every connected app. Drops slow/dead clients."""
    for w in list(clients):
        try:
            w.write(payload)
        except Exception:
            clients.discard(w)


async def run_ble(name: str, show: bool) -> None:
    """Connect to the board and pump notifications into the socket clients."""
    count = 0
    while True:
        print(f"[ble] scanning for {name!r}…")
        device = await BleakScanner.find_device_by_name(name, timeout=10.0)
        if device is None:
            print("[ble] not found; retrying in 3s")
            await asyncio.sleep(3)
            continue

        print(f"[ble] found {device.address}, connecting…")
        try:
            async with BleakClient(device) as client:
                print("[ble] connected; streaming")

                def handler(_, data: bytearray) -> None:
                    nonlocal count
                    payload = bytes(data)
                    if len(payload) != PACKET_SIZE:
                        return
                    broadcast(payload)
                    count += 1
                    if show:
                        print("      " + decode(payload))
                    elif count % 100 == 0:
                        print(f"[ble] {count} packets -> {len(clients)} client(s)")

                await client.start_notify(SAMPLE_UUID, handler)
                while client.is_connected:
                    await asyncio.sleep(0.5)
        except Exception as exc:
            print(f"[ble] error: {exc}")

        print("[ble] disconnected; reconnecting in 2s")
        await asyncio.sleep(2)


async def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--port", type=int, default=9999)
    ap.add_argument("--name", default="XIAO-IMU")
    ap.add_argument("--decode", action="store_true", help="print each reading")
    args = ap.parse_args()

    server = await asyncio.start_server(on_client, "0.0.0.0", args.port)
    print(f"[tcp] listening on 0.0.0.0:{args.port}")
    print("[tcp] emulator reaches this host at 10.0.2.2")
    async with server:
        await asyncio.gather(server.serve_forever(), run_ble(args.name, args.decode))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        print("\nbye")
