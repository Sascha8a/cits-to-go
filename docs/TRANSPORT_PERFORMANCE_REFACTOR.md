# USB/BLE capture transport performance refactor

Date: 2026-09-03

## Scope

This review follows the receive path from the ESP32-C5 Wi-Fi driver to the
Android packet consumer, with special attention to why Android can observe a
lower packet rate over BLE than over USB.

## Firmware architecture before the refactor

### Wi-Fi receive path

- The ESP-IDF promiscuous callback performs filtering and a single bounded copy
  into the Rust `RX_POOL`.
- `RX_POOL` and the Embassy `CAPTURES` channel are both statically bounded; no
  heap allocation occurs in the hot capture path.
- A pool lease is queued instead of moving/copying the packet again. The capture
  task returns the scarce Wi-Fi pool slot before handing the encoded CTG1 frame
  to the output transports.
- Oversized packets and pool exhaustion are counted in debugger-visible atomics.

This is a good design for a driver callback: short, bounded, allocation-free,
and non-blocking.

### Executor and tasks

- Embassy runs on one executor polled by the IDF `app_main` FreeRTOS task.
- Embassy wakeups are translated to FreeRTOS task notifications. The executor
  sleeps when no future is runnable rather than polling.
- There are four logical Embassy workers: capture, USB input, BLE input, and
  radio transmit. The two input workers share a pooled task definition.
- USB RX, USB TX, BLE/NimBLE and raw-radio TX use separate IDF/FreeRTOS tasks.
- The radio TX worker has a depth-1 queue and the Embassy transmit future waits
  on an explicit completion signal, so only one raw radio transmission is in
  flight.

The task split is appropriate. The main avoidable cost was an explicit Embassy
`yield_now()` after every capture/input item, which creates extra wake/poll work
under load even though the queues themselves already provide suspension points.

### USB

- USB RX blocks in the IDF serial/JTAG driver. Hardware/driver activity wakes the
  reader task; there is no busy loop in Rust.
- USB TX uses four full-frame slots with separate control/capture queues and two
  slots effectively reserved for control responses.
- The USB writer drains independently of the capture task and performs bounded
  writes with one record-level deadline.

### BLE before the refactor

- BLE TX also used static full-frame slots, but its slot count was
  `CONFIG_CITS_PACKET_POOL_SIZE`. This accidentally coupled BLE burst capacity
  to the Wi-Fi receive pool.
- Two slots were reserved for control, leaving six capture slots with the
  default packet pool of eight.
- Capture and control frames shared one FIFO, so reserved capacity protected
  control allocation but did not actually give queued control records priority.
- The BLE TX task took one CTG1 frame at a time and emitted that frame in
  `MTU - 3` notification fragments. It never packed bytes from a following CTG1
  frame into unused space in the current ATT notification.
- NimBLE `ENOMEM` handling was already event-driven via notify-completion events,
  which is good and is retained.

## Why BLE could show fewer packets/s than USB

There is an unavoidable physical difference: USB has much more transport
headroom than BLE. The software should therefore absorb short BLE stalls and
use each BLE connection event efficiently instead of turning those stalls into
packet drops or Android-side backlog.

The concrete software multipliers were:

1. **Android subscribed before MTU negotiation.** Notification subscription was
   completed first; only after the connection was declared ready did Android
   request MTU 517. Capture could therefore begin with the default 23-byte ATT
   MTU, yielding only 20 data bytes per notification until the asynchronous MTU
   exchange completed.
2. **One firmware frame per notification stream fragment.** Even with a large
   MTU, a small CTG1 packet caused a separate notification instead of sharing an
   ATT payload with following CTG1 records. That increases GATT, controller and
   Android callback overhead per captured packet.
3. **BLE burst buffering was tied to the Wi-Fi pool.** The BLE writer drains far
   more slowly than USB. Six capture slots can therefore overflow during a
   short scheduling/controller stall even though the capture pipeline itself is
   healthy.
4. **Android read one notification at a time.** The serial reader uses a 16 KiB
   read buffer, but BLE returned at most one queued notification per `read()`.
   This magnified Java/Kotlin call and parser dispatch overhead relative to USB,
   which naturally delivers larger chunks.
5. **Android allocated/copy-decoded several temporary arrays per CTG1 record.**
   `ByteArrayOutputStream.toByteArray()` and the old COBS decode path generated
   temporary buffers for every packet. BLE's higher callback/chunk frequency
   makes that overhead more visible.
6. **Per-item Embassy yields and LED timer restarts added hot-path scheduling
   work.** They affect both transports, but removing them gives the BLE drain
   path more CPU margin when the system is busy.

## Implemented refactor

### Firmware

- Added `CONFIG_CITS_BLE_OUTPUT_SLOTS` (default 12), independent of
  `CONFIG_CITS_PACKET_POOL_SIZE`.
- Split BLE output into capture and control queues; control records are selected
  first at CTG1 record boundaries while two free slots remain reserved for
  control traffic.
- Reworked BLE TX into a byte-stream packer. It fills each negotiated ATT
  payload with as much CTG1 stream data as possible, including multiple complete
  small records, while still allowing large records to span notifications.
- Retained event-driven NimBLE backpressure handling and connection-epoch
  protection. A failed partial notification causes the partial record remainder
  to be discarded/connection terminated instead of silently concatenating a
  corrupt stream.
- Added independent debugger counters `CITS_USB_OUTPUT_DROPS` and
  `CITS_BLE_OUTPUT_DROPS`, while preserving the aggregate
  `CITS_OUTPUT_DROPS` count.
- Reduced Embassy cooperative-yield frequency to once per four capture/input
  items. Radio TX no longer yields after an operation that already awaited its
  completion signal.
- Made the activity LED edge-triggered for the duration of a pulse rather than
  restarting an `esp_timer` for every accepted packet.
- Explicitly pinned BLE 5 feature support and the LE 2M PHY in
  `sdkconfig.defaults`, even though the pinned ESP-IDF revision currently
  defaults these options on.

### Android

- MTU negotiation now occurs after service discovery **before** notification
  subscription. Subscription begins from `onMtuChanged`, with a fallback to the
  default MTU if the request cannot be started.
- BLE `read()` now blocks for the first notification and then drains/coalesces
  all immediately available notification chunks into the caller's buffer.
- The connected transport description shows the negotiated MTU, making field
  verification easy (`... (MTU 517)`, or the actual negotiated value).
- `SerialPacketReader` now uses fixed reusable record storage instead of a
  `ByteArrayOutputStream` and correctly discards an oversized record through the
  next delimiter before resynchronizing.
- COBS decoding can now decode into caller-owned storage; `CtgFrameDecoder`
  reuses an 8 KiB decode scratch buffer. The payload still gets its own array
  because it escapes the decoder and needs independent ownership.
- Added `CaptureSequenceTracker`. Android displays **Stream gaps**, derived from
  missing firmware capture sequence numbers. Because the sequence is assigned
  before transport emission, this is a useful end-to-end indicator of loss
  after the capture task (transport queue, BLE stream, or Android framing).
- Added unit tests for sequence wrap/gaps and serial-reader resynchronization/
  coalesced records.

## Queue/backpressure policy after the refactor

The intended policy is now explicit:

- **Wi-Fi capture:** never block the Wi-Fi driver callback; drop and count if the
  bounded RX pool is exhausted.
- **USB output:** never block the Embassy capture task; enqueue to the independent
  USB writer or count a USB-only output drop.
- **BLE output:** never block the Embassy capture task or NimBLE host; enqueue to
  an independently tunable BLE queue or count a BLE-only output drop.
- **Control responses:** reserve capacity and prioritize them over queued capture
  frames at record boundaries.
- **Android BLE input:** drain notifications in batches; do not make one parser
  invocation depend on one GATT callback.

This preserves bounded memory while preventing a slow BLE transport from
holding scarce Wi-Fi receive buffers.

## Validation performed in this workspace

- Repository firmware build-script regression tests: **3/3 passed**.
- Pure Kotlin protocol code (COBS, decoder, serial reader, sequence tracker) was
  compiled with the installed Kotlin compiler and exercised with round-trip,
  coalescing, gap and oversize-resynchronization checks: **passed**.
- Full Android Gradle tests could not start because the wrapper needs to download
  Gradle 8.14.4 and this sandbox has no outbound access to `services.gradle.org`.
- The Rust/ESP-IDF firmware could not be rebuilt here because Cargo/Nix and the
  pinned ESP-IDF toolchain are not installed in this sandbox. The repository's
  earlier full-link validation remains useful as a baseline but predates these
  changes.

## Hardware acceptance / measurement plan

Use the same RF scene and packet source for USB and BLE. For each run record:

1. Android received packets/s and total packets over a fixed interval.
2. Android **Stream gaps**.
3. Firmware debugger counters: `CITS_RX_NO_BUFFER`, `CITS_USB_OUTPUT_DROPS`,
   `CITS_BLE_OUTPUT_DROPS`, `CITS_BLE_INPUT_DROPS`.
4. BLE negotiated MTU shown in the connection status. It should normally be
   close to the requested 517 (platform/controller limits may choose lower).
5. Actual BLE PHY/connection parameters from a BLE sniffer or controller logs if
   packet rate still plateaus.
6. CPU load, minimum heap and task stack high-water marks under sustained peak
   traffic.

Expected interpretation:

- If `CITS_RX_NO_BUFFER` rises on both transports, the bottleneck is before the
  transport split.
- If only `CITS_BLE_OUTPUT_DROPS` / Android Stream gaps rise, BLE link throughput
  is still below the offered traffic rate.
- If firmware BLE drops stay zero but Android Stream gaps rise, investigate
  Android callback/reader loss or stream corruption.
- If gaps stay zero but displayed BLE packets/s is temporarily lower, Android is
  backlogged rather than losing frames; compare totals after the queue drains.

If sustained offered traffic genuinely exceeds optimized GATT notification
capacity, no queue can make BLE equal USB indefinitely. The next architectural
step would be a higher-throughput BLE data channel (for example an L2CAP CoC
stream where Android/device support is acceptable), or explicit capture
sampling/filtering/backpressure policy. That is deliberately not introduced in
this refactor because it changes the wire/connection architecture rather than
fixing the existing implementation's avoidable overhead.
