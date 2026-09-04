# Debug diagnostics and firmware statistics

The Android app can expose an optional **Debug** page. Enable it in **Settings → Debug menu**.
The page is intended to distinguish radio/capture loss from transport loss without attaching a debugger.

## Interpreting the counters

The capture path has three useful stages:

1. `Wi-Fi RX` — eligible 802.11 frames received by the firmware after the configured receive filters.
2. `Accepted captures` — frames that obtained an RX pool slot and entered the Embassy capture queue.
3. `USB capture TX` / `BLE capture TX` — capture records successfully written/notified by the corresponding transport worker.

The CTG capture sequence number is assigned after stage 2, immediately before the record is handed to the transports.
Therefore:

- `Wi-Fi RX > Accepted captures` together with `RX pool exhausted` increasing means loss happened **before sequence assignment**. Android's **Stream gaps can remain zero** in this case.
- If `BLE capture drops` increases, the BLE output queue rejected capture records. Android should also observe capture sequence gaps for those records. If `Accepted captures` temporarily exceeds `BLE capture TX` without drops while the BLE queue grows, the difference is backlog rather than loss.
- If `USB capture drops` increases, the USB output queue rejected capture records. `USB partial writes` instead identifies records that were queued but could not be written completely.
- A growing BLE queue with lower `BLE stream` / notification rate indicates transport backpressure even before it becomes packet loss.
- `BLE notify failures` counts notifications that could not ultimately be submitted to NimBLE; `USB partial writes` counts records whose USB write failed after starting.

## CTG1 statistics record

Firmware emits CTG1 frame type `6` once per second. It is a diagnostic/control record, not an over-the-air packet, and Android does not write it to PCAP or publish it as a received C-ITS packet.

The decoded record has a fixed 112-byte header followed by the normal CTG1 CRC-32. Multi-byte fields are little-endian.

| Offset | Type | Field |
|---:|---|---|
| 8 | u32 | uptime ms |
| 12 | u32 | sample period ms |
| 16 | u32 | Wi-Fi RX packets/s |
| 20 | u32 | accepted captures/s |
| 24 | u32 | USB capture records successfully written/s |
| 28 | u32 | BLE capture records successfully notified/s |
| 32 | u32 | successfully written USB stream bytes/s |
| 36 | u32 | successfully submitted BLE notification bytes/s |
| 40 | u32 | BLE notifications/s |
| 44 | u32 | RX pool exhausted total |
| 48 | u32 | oversized RX total |
| 52 | u32 | BLE input drops total |
| 56 | u32 | USB capture-output drops total |
| 60 | u32 | BLE capture-output drops total |
| 64 | u32 | USB partial-write drops total |
| 68 | u32 | BLE notification failures total |
| 72 | u32 | Wi-Fi RX total |
| 76 | u32 | accepted captures total |
| 80 | u32 | USB capture records successfully written total |
| 84 | u32 | BLE capture records successfully notified total |
| 88 | u32 | link flags |
| 92 | u16 | USB output queue depth |
| 94 | u16 | USB output queue capacity |
| 96 | u16 | BLE output queue depth |
| 98 | u16 | BLE output queue capacity |
| 100 | u16 | BLE ATT MTU |
| 102 | u16 | BLE connection interval in 1.25 ms units |
| 104 | u16 | BLE peripheral latency |
| 106 | u16 | BLE supervision timeout in 10 ms units |
| 108 | u8 | BLE TX PHY |
| 109 | u8 | BLE RX PHY |

Link flag bits are USB connected (`0x01`), BLE connected (`0x02`), BLE notifications enabled (`0x04`) and BLE link secured (`0x08`). PHY values follow the controller/NimBLE values: 1 = LE 1M, 2 = LE 2M, 3 = LE Coded.

The 1 Hz timer only signals the Embassy statistics task; encoding and transport submission never run in the ESP timer callback.

## BLE timeout diagnostic note

Android GATT status 147 is a connection timeout. Firmware diagnostics therefore avoid issuing active HCI PHY-read commands from NimBLE GAP callbacks. The Android side still reports the negotiated TX/RX PHY via `BluetoothGatt.readPhy()`, while firmware-side PHY values may remain 0 (unknown) when no passive value is available. This keeps diagnostics from perturbing the timing of a busy Wi-Fi + BLE link.
