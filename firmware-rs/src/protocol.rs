//! CTG1 framing, shared unchanged by USB and BLE. No hardware dependencies.
//!
//! Encoding streams header/payload/CRC directly into COBS output. There is no
//! second full-size decoded TX buffer. RX decodes COBS in-place.
pub use crate::config::MAX_PACKET;
pub const MAX_DECODED: usize = 32 + MAX_PACKET + 4;
pub const MAX_ENCODED: usize = MAX_DECODED + MAX_DECODED / 254 + 2;
pub const OK: i32 = 0;
pub const NO_MEM: i32 = 0x101;
pub const INVALID_SIZE: i32 = 0x104;
pub const INVALID_CRC: i32 = 0x109;

const fn crc_table() -> [u32; 256] {
    let mut table = [0; 256];
    let mut i = 0;
    while i < 256 {
        let mut c = i as u32;
        let mut n = 0;
        while n < 8 {
            c = (c >> 1) ^ (0xedb88320 & 0u32.wrapping_sub(c & 1));
            n += 1;
        }
        table[i] = c;
        i += 1;
    }
    table
}
const CRC_TABLE: [u32; 256] = crc_table();
fn update_crc(crc: u32, byte: u8) -> u32 {
    (crc >> 8) ^ CRC_TABLE[((crc ^ byte as u32) & 255) as usize]
}
pub fn crc32(bytes: &[u8]) -> u32 {
    !bytes.iter().fold(!0, |crc, &b| update_crc(crc, b))
}
fn u16_at(b: &[u8], i: usize) -> u16 {
    u16::from_le_bytes([b[i], b[i + 1]])
}
fn u32_at(b: &[u8], i: usize) -> u32 {
    u32::from_le_bytes(b[i..i + 4].try_into().unwrap())
}

#[derive(Debug, PartialEq, Eq)]
pub enum Request<'a> {
    Transmit {
        id: u32,
        flags: u16,
        packet: &'a [u8],
    },
    Reject {
        id: u32,
        len: u16,
        status: i32,
    },
    Enroll,
    Ignore,
}

pub fn parse_record(decoded: &[u8], from_usb: bool) -> Request<'_> {
    let n = decoded.len();
    if n < 12 || &decoded[..4] != b"CTG1" || decoded[4] != 1 {
        return Request::Ignore;
    }
    let header = u16_at(decoded, 6) as usize;
    let tx = decoded[5] == 2 && header == 16 && n >= 20;
    let reject = |status| Request::Reject {
        id: u32_at(decoded, 8),
        len: u16_at(decoded, 12),
        status,
    };
    if crc32(&decoded[..n - 4]) != u32_at(decoded, n - 4) {
        return if tx {
            reject(INVALID_CRC)
        } else {
            Request::Ignore
        };
    }
    if decoded[5] == 4 {
        return if from_usb && header == 12 && n == 16 && decoded[8] == 1 {
            Request::Enroll
        } else {
            Request::Ignore
        };
    }
    if !tx {
        return Request::Ignore;
    }
    let len = u16_at(decoded, 12) as usize;
    if len == 0 || len > MAX_PACKET || n != 20 + len {
        return reject(INVALID_SIZE);
    }
    Request::Transmit {
        id: u32_at(decoded, 8),
        flags: u16_at(decoded, 14),
        packet: &decoded[16..16 + len],
    }
}

/// Decoder retains partial records across arbitrary transport chunk boundaries.
/// Overflow or a lost chunk discards the *entire* record through its delimiter.
pub struct Decoder {
    bytes: [u8; MAX_ENCODED],
    len: usize,
    discarding: bool,
}
impl Default for Decoder {
    fn default() -> Self {
        Self::new()
    }
}
impl Decoder {
    pub const fn new() -> Self {
        Self {
            bytes: [0; MAX_ENCODED],
            len: 0,
            discarding: false,
        }
    }
    pub fn gap(&mut self) {
        self.len = 0;
        self.discarding = true;
    }
    pub fn reset(&mut self) {
        self.len = 0;
        self.discarding = false;
    }
    pub fn push(&mut self, byte: u8) -> Option<&[u8]> {
        if byte != 0 {
            if !self.discarding {
                if self.len == self.bytes.len() {
                    self.gap();
                } else {
                    self.bytes[self.len] = byte;
                    self.len += 1;
                }
            }
            return None;
        }
        let n = self.len;
        self.len = 0;
        if self.discarding {
            self.discarding = false;
            return None;
        }
        if n == 0 {
            return None;
        }
        let decoded = cobs_decode_in_place(&mut self.bytes[..n])?;
        if decoded > MAX_DECODED {
            return None;
        }
        Some(&self.bytes[..decoded])
    }
}

pub fn cobs_decode_in_place(bytes: &mut [u8]) -> Option<usize> {
    let mut read = 0;
    let mut write = 0;
    while read < bytes.len() {
        let code = bytes[read] as usize;
        read += 1;
        if code == 0 || read + code - 1 > bytes.len() {
            return None;
        }
        for _ in 1..code {
            bytes[write] = bytes[read];
            write += 1;
            read += 1;
        }
        if code != 255 && read < bytes.len() {
            bytes[write] = 0;
            write += 1;
        }
    }
    Some(write)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BufferTooSmall;

struct Encoder<'a> {
    out: &'a mut [u8],
    pos: usize,
    code_pos: usize,
    code: u8,
    crc: u32,
}
impl<'a> Encoder<'a> {
    fn new(out: &'a mut [u8]) -> Result<Self, BufferTooSmall> {
        if out.is_empty() {
            return Err(BufferTooSmall);
        }
        Ok(Self {
            out,
            pos: 1,
            code_pos: 0,
            code: 1,
            crc: !0,
        })
    }
    fn raw(&mut self, b: u8) -> Result<(), BufferTooSmall> {
        if self.pos >= self.out.len() {
            return Err(BufferTooSmall);
        }
        if b == 0 {
            self.out[self.code_pos] = self.code;
            self.code_pos = self.pos;
            self.pos += 1;
            self.code = 1;
        } else {
            self.out[self.pos] = b;
            self.pos += 1;
            self.code += 1;
            if self.code == 255 {
                if self.pos >= self.out.len() {
                    return Err(BufferTooSmall);
                }
                self.out[self.code_pos] = 255;
                self.code_pos = self.pos;
                self.pos += 1;
                self.code = 1;
            }
        }
        Ok(())
    }
    fn bytes(&mut self, bytes: &[u8]) -> Result<(), BufferTooSmall> {
        for &b in bytes {
            self.crc = update_crc(self.crc, b);
            self.raw(b)?;
        }
        Ok(())
    }
    fn finish(mut self) -> Result<usize, BufferTooSmall> {
        for b in (!self.crc).to_le_bytes() {
            self.raw(b)?;
        }
        if self.pos >= self.out.len() {
            return Err(BufferTooSmall);
        }
        self.out[self.code_pos] = self.code;
        self.out[self.pos] = 0;
        Ok(self.pos + 1)
    }
}
fn header(kind: u8, len: u16) -> [u8; 8] {
    [b'C', b'T', b'G', b'1', 1, kind, len as u8, (len >> 8) as u8]
}

#[derive(Clone, Copy, Default)]
pub struct CaptureMeta {
    pub timestamp_us: u64,
    pub rssi: i8,
    pub wifi_type: u8,
    pub rx_state: u8,
}
pub fn is_broadcast(packet: &[u8]) -> bool {
    packet.len() >= 10 && packet[4..10] == [255; 6]
}

pub fn encode_capture(
    out: &mut [u8],
    seq: u32,
    frequency: u16,
    meta: CaptureMeta,
    packet: &[u8],
) -> Result<usize, BufferTooSmall> {
    if packet.len() > MAX_PACKET {
        return Err(BufferTooSmall);
    }
    let mut e = Encoder::new(out)?;
    e.bytes(&header(1, 32))?;
    e.bytes(&(is_broadcast(packet) as u16).to_le_bytes())?;
    e.bytes(&seq.to_le_bytes())?;
    e.bytes(&meta.timestamp_us.to_le_bytes())?;
    e.bytes(&frequency.to_le_bytes())?;
    e.bytes(&(packet.len() as u16).to_le_bytes())?;
    e.bytes(&(packet.len() as u16).to_le_bytes())?;
    e.bytes(&[meta.rssi as u8, meta.wifi_type, meta.rx_state, 0])?;
    e.bytes(packet)?;
    e.finish()
}
pub fn encode_result(
    out: &mut [u8],
    id: u32,
    status: i32,
    len: u16,
    packet: &[u8],
) -> Result<usize, BufferTooSmall> {
    let mut e = Encoder::new(out)?;
    e.bytes(&header(3, 20))?;
    e.bytes(&id.to_le_bytes())?;
    e.bytes(&status.to_le_bytes())?;
    e.bytes(&len.to_le_bytes())?;
    e.bytes(&[0, 0])?;
    if status == OK {
        if packet.len() != len as usize || packet.len() > MAX_PACKET {
            return Err(BufferTooSmall);
        }
        e.bytes(packet)?;
    }
    e.finish()
}
pub fn encode_enrollment(out: &mut [u8], status: i32) -> Result<usize, BufferTooSmall> {
    let mut e = Encoder::new(out)?;
    e.bytes(&header(5, 16))?;
    e.bytes(&status.to_le_bytes())?;
    e.bytes(&[(status == OK) as u8, 0, 0, 0])?;
    e.finish()
}
