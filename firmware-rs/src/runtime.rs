//! Embassy owns application work. ESP-IDF callbacks only copy into bounded
//! pools and wake tasks. No parsing, transport writes, or radio TX in RX callbacks.
use crate::{
    config,
    pool::{Lease, Pool, Slot},
    protocol::{self as wire, CaptureMeta, Decoder, Request, MAX_ENCODED, MAX_PACKET},
};
use core::{
    ffi::c_void,
    sync::atomic::{AtomicU32, Ordering},
};
use embassy_executor::raw::Executor;
use embassy_sync::{
    blocking_mutex::raw::CriticalSectionRawMutex, channel::Channel, signal::Signal,
};
use static_cell::StaticCell;

type Mutex = CriticalSectionRawMutex;

unsafe extern "C" {
    fn cits_platform_start() -> i32;
    fn cits_platform_wait();
    fn cits_platform_wake();
    fn cits_platform_input_consumed();
    fn cits_platform_emit(data: *const u8, len: usize, control: bool, usb_only: bool) -> u32;
    fn cits_platform_tx(data: *const u8, len: usize, system_sequence: bool);
    fn cits_platform_enroll();
    fn cits_platform_led();
    fn cits_platform_enter() -> u32;
    fn cits_platform_exit(state: u32);
}

struct CriticalSection;
critical_section::set_impl!(CriticalSection);
unsafe impl critical_section::Impl for CriticalSection {
    unsafe fn acquire() -> u32 {
        unsafe { cits_platform_enter() }
    }
    unsafe fn release(state: u32) {
        unsafe { cits_platform_exit(state) }
    }
}

// All wake sources run in IDF task context. The USB interrupt wakes its blocking
// reader through the driver; it does not call Rust. See main/platform.c.
#[export_name = "__pender"]
fn pender(_: *mut ()) {
    unsafe { cits_platform_wake() }
}

struct Packet {
    meta: CaptureMeta,
    len: usize,
    bytes: [u8; MAX_PACKET],
}
impl Packet {
    const fn new() -> Self {
        Self {
            meta: CaptureMeta {
                timestamp_us: 0,
                rssi: 0,
                wifi_type: 0,
                rx_state: 0,
            },
            len: 0,
            bytes: [0; MAX_PACKET],
        }
    }
}
struct Tx {
    id: u32,
    flags: u16,
    len: usize,
    bytes: [u8; MAX_PACKET],
}
impl Tx {
    const fn new() -> Self {
        Self {
            id: 0,
            flags: 0,
            len: 0,
            bytes: [0; MAX_PACKET],
        }
    }
}
struct Chunk {
    len: usize,
    epoch: u32,
    bytes: [u8; 512],
}
impl Chunk {
    const fn new() -> Self {
        Self {
            len: 0,
            epoch: 0,
            bytes: [0; 512],
        }
    }
}

static RX_POOL: Pool<Packet, { config::RX_POOL }> =
    Pool::new([const { Slot::new(Packet::new()) }; config::RX_POOL]);
static TX_POOL: Pool<Tx, { config::TX_POOL }> =
    Pool::new([const { Slot::new(Tx::new()) }; config::TX_POOL]);
static USB_INPUT: Pool<Chunk, 4> = Pool::new([const { Slot::new(Chunk::new()) }; 4]);
static BLE_INPUT: Pool<Chunk, 4> = Pool::new([const { Slot::new(Chunk::new()) }; 4]);
static CAPTURES: Channel<Mutex, Lease<Packet>, { config::RX_POOL }> = Channel::new();
static TRANSMITS: Channel<Mutex, Lease<Tx>, { config::TX_POOL }> = Channel::new();
static USB_CHUNKS: Channel<Mutex, Lease<Chunk>, 4> = Channel::new();
static BLE_CHUNKS: Channel<Mutex, Lease<Chunk>, 4> = Channel::new();
static BLE_EPOCH: AtomicU32 = AtomicU32::new(0);
static ENROLL_DONE: Signal<Mutex, i32> = Signal::new();
static TX_DONE: Signal<Mutex, i32> = Signal::new();

// Symbols intentionally retained for debugger inspection without contaminating
// the Android binary byte stream with logs.
#[no_mangle]
pub static CITS_RX_NO_BUFFER: AtomicU32 = AtomicU32::new(0);
#[no_mangle]
pub static CITS_RX_TOO_LARGE: AtomicU32 = AtomicU32::new(0);
#[no_mangle]
pub static CITS_BLE_INPUT_DROPS: AtomicU32 = AtomicU32::new(0);
#[no_mangle]
pub static CITS_OUTPUT_DROPS: AtomicU32 = AtomicU32::new(0);

fn emit(bytes: &[u8], control: bool, usb_only: bool) {
    let drops = unsafe { cits_platform_emit(bytes.as_ptr(), bytes.len(), control, usb_only) };
    CITS_OUTPUT_DROPS.fetch_add(drops, Ordering::Relaxed);
}
fn result(out: &mut [u8], id: u32, status: i32, len: u16, packet: &[u8]) {
    let n = wire::encode_result(out, id, status, len, packet).expect("bounded TX result");
    emit(&out[..n], true, false);
}

/// Called only by the Wi-Fi task, with a valid FCS-free slice for this call.
#[no_mangle]
unsafe extern "C" fn cits_rs_capture(
    data: *const u8,
    len: usize,
    timestamp: u64,
    rssi: i8,
    kind: u8,
    state: u8,
) {
    if data.is_null() || state != 0 || kind == 3 {
        return;
    }
    if len > MAX_PACKET {
        CITS_RX_TOO_LARGE.fetch_add(1, Ordering::Relaxed);
        return;
    }
    let bytes = unsafe { core::slice::from_raw_parts(data, len) };
    if config::BROADCAST_ONLY && !wire::is_broadcast(bytes) {
        return;
    }
    let Some(mut packet) = RX_POOL.try_acquire() else {
        CITS_RX_NO_BUFFER.fetch_add(1, Ordering::Relaxed);
        return;
    };
    packet.meta = CaptureMeta {
        timestamp_us: timestamp,
        rssi,
        wifi_type: kind,
        rx_state: state,
    };
    packet.len = len;
    packet.bytes[..len].copy_from_slice(bytes);
    // Queue capacity equals pool capacity, so an acquired slot always fits.
    if CAPTURES.try_send(packet).is_err() {
        CITS_RX_NO_BUFFER.fetch_add(1, Ordering::Relaxed);
    }
}

#[no_mangle]
unsafe extern "C" fn cits_rs_input(data: *const u8, len: usize, usb: bool) -> bool {
    if data.is_null() || len > 512 {
        return false;
    }
    let pool = if usb { &USB_INPUT } else { &BLE_INPUT };
    let queue = if usb { &USB_CHUNKS } else { &BLE_CHUNKS };
    let Some(mut chunk) = pool.try_acquire() else {
        return false;
    };
    chunk.len = len;
    chunk.epoch = if usb {
        0
    } else {
        BLE_EPOCH.load(Ordering::Acquire)
    };
    chunk.bytes[..len].copy_from_slice(unsafe { core::slice::from_raw_parts(data, len) });
    queue.try_send(chunk).is_ok()
}

/// Called on BLE disconnect and any rejected chunk; generations prevent a
/// partial record from one connection being completed by another connection.
#[no_mangle]
extern "C" fn cits_rs_ble_reset() {
    BLE_EPOCH.fetch_add(1, Ordering::AcqRel);
}
#[no_mangle]
extern "C" fn cits_rs_ble_gap() {
    cits_rs_ble_reset();
    CITS_BLE_INPUT_DROPS.fetch_add(1, Ordering::Relaxed);
}
#[no_mangle]
extern "C" fn cits_rs_enroll_done(status: i32) {
    ENROLL_DONE.signal(status);
}
#[no_mangle]
extern "C" fn cits_rs_tx_done(status: i32) {
    TX_DONE.signal(status);
}

#[embassy_executor::task]
async fn captures() {
    let mut out = [0; MAX_ENCODED];
    let mut seq = 0u32;
    loop {
        let slot = CAPTURES.receive().await;
        let n = wire::encode_capture(
            &mut out,
            seq,
            config::FREQUENCY,
            slot.meta,
            &slot.bytes[..slot.len],
        )
        .expect("bounded capture");
        seq = seq.wrapping_add(1);
        drop(slot); // Return scarce Wi-Fi memory before handing off to transports.
        emit(&out[..n], false, false);
        unsafe { cits_platform_led() };
        embassy_futures::yield_now().await;
    }
}

#[embassy_executor::task(pool_size = 2)]
async fn input(usb: bool) {
    let queue = if usb { &USB_CHUNKS } else { &BLE_CHUNKS };
    let mut decoder = Decoder::new();
    let mut epoch = 0;
    let mut out = [0; MAX_ENCODED];
    loop {
        let chunk = queue.receive().await;
        // Epoch changes invalidate any partial old record. A fresh connection
        // may start at its first byte; lost chunks require resync, see BLE shim.
        if chunk.epoch != epoch {
            decoder.reset();
            epoch = chunk.epoch;
        }
        for &byte in &chunk.bytes[..chunk.len] {
            let Some(decoded) = decoder.push(byte) else {
                continue;
            };
            match wire::parse_record(decoded, usb) {
                Request::Transmit { id, flags, packet } => {
                    if let Some(mut tx) = TX_POOL.try_acquire() {
                        tx.id = id;
                        tx.flags = flags;
                        tx.len = packet.len();
                        tx.bytes[..packet.len()].copy_from_slice(packet);
                        if TRANSMITS.try_send(tx).is_err() {
                            result(&mut out, id, wire::NO_MEM, packet.len() as u16, &[]);
                        }
                    } else {
                        result(&mut out, id, wire::NO_MEM, packet.len() as u16, &[]);
                    }
                }
                Request::Reject { id, len, status } => result(&mut out, id, status, len, &[]),
                Request::Enroll => {
                    unsafe { cits_platform_enroll() };
                    let status = ENROLL_DONE.wait().await;
                    let n = wire::encode_enrollment(&mut out, status).unwrap();
                    emit(&out[..n], true, true);
                }
                Request::Ignore => {}
            }
        }
        drop(chunk);
        if usb {
            unsafe { cits_platform_input_consumed() };
        }
        embassy_futures::yield_now().await;
    }
}

#[embassy_executor::task]
async fn transmit() {
    let mut out = [0; MAX_ENCODED];
    loop {
        let tx = TRANSMITS.receive().await;
        // The dedicated IDF radio worker may block on the Wi-Fi mutex. It holds
        // this pointer only until it signals completion. This task is never canceled.
        unsafe { cits_platform_tx(tx.bytes.as_ptr(), tx.len, tx.flags & 1 != 0) };
        let status = TX_DONE.wait().await;
        result(&mut out, tx.id, status, tx.len as u16, &tx.bytes[..tx.len]);
        if status == wire::OK {
            unsafe { cits_platform_led() }
        };
        drop(tx);
        embassy_futures::yield_now().await;
    }
}

#[no_mangle]
extern "C" fn cits_rs_run() -> ! {
    static EXECUTOR: StaticCell<Executor> = StaticCell::new();
    let executor = EXECUTOR.init(Executor::new(core::ptr::null_mut::<c_void>().cast()));
    let spawner = executor.spawner();
    spawner.spawn(captures()).unwrap();
    spawner.spawn(input(true)).unwrap();
    spawner.spawn(input(false)).unwrap();
    spawner.spawn(transmit()).unwrap();
    assert_eq!(
        unsafe { cits_platform_start() },
        0,
        "platform initialization failed"
    );
    loop {
        // Only this task polls this executor, never recursively. A counting
        // notification retains wakes between poll() and the blocking wait.
        unsafe {
            executor.poll();
            cits_platform_wait();
        }
    }
}

// Account for the actual future layouts generated by the pinned Embassy macro.
// Round each task's allocation up conservatively for arena alignment. This also
// gives a debugger-readable bound without running or allocating the futures.
#[no_mangle]
pub extern "C" fn cits_rs_task_storage_bytes() -> usize {
    fn storage<F: core::future::Future + 'static>(_: F) -> usize {
        (core::mem::size_of::<embassy_executor::raw::TaskStorage<F>>() + 63) & !63
    }
    storage(__captures_task()) + 2 * storage(__input_task(true)) + storage(__transmit_task())
}

#[cfg(test)]
mod tests {
    #[test]
    fn configured_task_arena_has_enough_space() {
        let capacity = if super::MAX_PACKET <= 2352 {
            16384
        } else {
            32768
        };
        assert!(super::cits_rs_task_storage_bytes() <= capacity);
    }
}
