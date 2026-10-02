# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 stec314 and the Retrovision contributors
"""Reference implementation of the Retrovision wire framing.

    frame   = COBS( envelope_bytes || CRC32_LE(envelope_bytes) ) || 0x00

This module is the executable form of docs/protocol.md section "Framing".
Firmware (C) and app (Kotlin) implementations must reproduce the
vectors in proto/testvectors/framing.json byte for byte.
"""

from __future__ import annotations

import zlib
from dataclasses import dataclass, field

DELIMITER = 0x00
CRC_LEN = 4
# Largest decoded frame (envelope + CRC) any peer is required to accept.
# Envelope worst case from nanopb is 1128 bytes; rounded up for headroom.
MAX_DECODED_FRAME = 1280
# COBS worst case adds one byte per 254 plus one.
MAX_ENCODED_FRAME = MAX_DECODED_FRAME + MAX_DECODED_FRAME // 254 + 1


class FrameError(ValueError):
    pass


def crc32(data: bytes) -> int:
    """CRC-32/ISO-HDLC (zlib, java.util.zip.CRC32, esp_rom_crc32_le)."""
    return zlib.crc32(data) & 0xFFFFFFFF


def cobs_encode(data: bytes) -> bytes:
    out = bytearray()
    code_idx = 0
    out.append(0)  # placeholder for first code byte
    code = 1
    for b in data:
        if b == 0:
            out[code_idx] = code
            code_idx = len(out)
            out.append(0)
            code = 1
        else:
            out.append(b)
            code += 1
            if code == 0xFF:
                out[code_idx] = code
                code_idx = len(out)
                out.append(0)
                code = 1
    out[code_idx] = code
    return bytes(out)


def cobs_decode(data: bytes) -> bytes:
    out = bytearray()
    i = 0
    n = len(data)
    while i < n:
        code = data[i]
        if code == 0:
            raise FrameError("zero byte inside COBS block")
        i += 1
        end = i + code - 1
        if end > n:
            raise FrameError("COBS block overruns frame")
        chunk = data[i:end]
        if 0 in chunk:
            raise FrameError("zero byte inside COBS block")
        out += chunk
        i = end
        if code != 0xFF and i < n:
            out.append(0)
    return bytes(out)


def encode_frame(envelope: bytes) -> bytes:
    payload = envelope + crc32(envelope).to_bytes(CRC_LEN, "little")
    if len(payload) > MAX_DECODED_FRAME:
        raise FrameError(f"frame too large: {len(payload)} > {MAX_DECODED_FRAME}")
    return cobs_encode(payload) + bytes([DELIMITER])


def decode_frame(encoded: bytes) -> bytes:
    """Decode one frame *without* its trailing delimiter. Returns envelope bytes."""
    if len(encoded) > MAX_ENCODED_FRAME:
        raise FrameError("encoded frame too large")
    payload = cobs_decode(encoded)
    if len(payload) < CRC_LEN + 1:
        raise FrameError("frame too short")
    body, tail = payload[:-CRC_LEN], payload[-CRC_LEN:]
    if crc32(body) != int.from_bytes(tail, "little"):
        raise FrameError("CRC mismatch")
    return body


@dataclass
class StreamDecoder:
    """Incremental decoder for a byte stream (USB CDC, UART, BLE notify...).

    Feed arbitrary chunks; get back complete envelopes. Bad frames are counted
    and skipped; the decoder always resynchronises on the next 0x00.
    """

    buf: bytearray = field(default_factory=bytearray)
    overflow: bool = False
    bad_frames: int = 0

    def feed(self, chunk: bytes) -> list[bytes]:
        envelopes: list[bytes] = []
        for b in chunk:
            if b == DELIMITER:
                if self.overflow:
                    self.bad_frames += 1
                elif self.buf:
                    try:
                        envelopes.append(decode_frame(bytes(self.buf)))
                    except FrameError:
                        self.bad_frames += 1
                # empty frame (consecutive delimiters) is legal and ignored
                self.buf.clear()
                self.overflow = False
            elif not self.overflow:
                self.buf.append(b)
                if len(self.buf) > MAX_ENCODED_FRAME:
                    self.overflow = True
                    self.buf.clear()
        return envelopes
