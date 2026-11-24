#!/usr/bin/env python3
# Copyright (C) 2026 Mawja contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generates Mawja's "Nile Glass" UI sound packs (Water, Glass, Oud, Space) procedurally.

Pure standard library (math, random, wave, struct): no samples are borrowed from anywhere, every clip
is synthesised here, so the clips are original and reproducible. Output: mono 16-bit 22.05 kHz WAV in
app/src/main/res/raw, the same format as the existing packs. Run from the repo root:

    python3 scripts/sounds/gen_ui_sound_packs.py
"""
import math
import os
import random
import struct
import wave

RATE = 22050
OUT = os.path.join("app", "src", "main", "res", "raw")


def env_exp(n, decay):
    """Exponential decay envelope with a 2 ms attack, so no clip starts with a click."""
    attack = max(1, int(0.002 * RATE))
    return [min(1.0, i / attack) * math.exp(-decay * i / RATE) for i in range(n)]


def silence(seconds):
    return [0.0] * int(seconds * RATE)


def mix(*tracks):
    n = max(len(t) for t in tracks)
    out = [0.0] * n
    for t in tracks:
        for i, v in enumerate(t):
            out[i] += v
    return out


def offset(track, seconds):
    return silence(seconds) + track


def sine_sweep(f0, f1, seconds, decay, amp=1.0):
    n = int(seconds * RATE)
    e = env_exp(n, decay)
    phase = 0.0
    out = []
    for i in range(n):
        f = f0 * (f1 / f0) ** (i / n)
        phase += 2 * math.pi * f / RATE
        out.append(amp * e[i] * math.sin(phase))
    return out


def partials(freq, seconds, decay, ratios, amp=1.0):
    """Inharmonic bell/glass: a few partials, higher ones dying faster."""
    n = int(seconds * RATE)
    out = [0.0] * n
    for k, (ratio, gain) in enumerate(ratios):
        e = env_exp(n, decay * (1 + 0.6 * k))
        f = freq * ratio
        for i in range(n):
            out[i] += amp * gain * e[i] * math.sin(2 * math.pi * f * i / RATE)
    return out


def plucked(freq, seconds, decay, amp=1.0, seed=7):
    """Karplus-Strong pluck: a warm, oud-like string."""
    rnd = random.Random(seed)
    period = max(2, int(RATE / freq))
    buf = [rnd.uniform(-1, 1) for _ in range(period)]
    n = int(seconds * RATE)
    out = []
    e = env_exp(n, decay)
    for i in range(n):
        v = buf[i % period]
        nxt = buf[(i + 1) % period]
        buf[i % period] = 0.996 * 0.5 * (v + nxt)
        out.append(amp * e[i] * v)
    return out


def lowpass(track, alpha):
    out, y = [], 0.0
    for v in track:
        y += alpha * (v - y)
        out.append(y)
    return out


def write(name, track, peak=0.7):
    m = max(1e-9, max(abs(v) for v in track))
    # 6 ms fade-out so every clip ends at zero.
    fade = int(0.006 * RATE)
    data = bytearray()
    for i, v in enumerate(track):
        g = 1.0 if i < len(track) - fade else (len(track) - i) / fade
        s = int(max(-1.0, min(1.0, v / m * peak * g)) * 32767)
        data += struct.pack("<h", s)
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(bytes(data))
    return os.path.getsize(path)


GLASS = [(1.0, 1.0), (2.76, 0.45), (5.4, 0.22), (8.93, 0.1)]


def water():
    tap = sine_sweep(900, 1700, 0.07, 55)
    toggle = mix(sine_sweep(700, 1300, 0.07, 50), offset(sine_sweep(1000, 1900, 0.07, 50, 0.8), 0.05))
    success = mix(
        sine_sweep(600, 1100, 0.12, 30),
        offset(sine_sweep(800, 1500, 0.12, 30, 0.85), 0.09),
        offset(sine_sweep(1050, 2000, 0.16, 26, 0.75), 0.18),
    )
    return tap, toggle, success


def glass():
    tap = partials(2100, 0.09, 45, GLASS)
    toggle = mix(partials(1760, 0.12, 35, GLASS), offset(partials(2350, 0.12, 35, GLASS, 0.7), 0.05))
    success = mix(
        partials(1568, 0.3, 14, GLASS),
        offset(partials(1976, 0.3, 14, GLASS, 0.8), 0.08),
        offset(partials(2349, 0.32, 12, GLASS, 0.7), 0.16),
    )
    return tap, toggle, success


def oud():
    # D, E, G, A: a warm maqam-like set of open notes.
    tap = lowpass(plucked(293.7, 0.11, 30, seed=3), 0.35)
    toggle = lowpass(mix(plucked(293.7, 0.14, 26, seed=4), offset(plucked(392.0, 0.12, 26, 0.8, seed=5), 0.05)), 0.35)
    success = lowpass(
        mix(
            plucked(293.7, 0.32, 10, seed=6),
            offset(plucked(329.6, 0.3, 10, 0.85, seed=8), 0.09),
            offset(plucked(440.0, 0.32, 9, 0.8, seed=9), 0.18),
        ),
        0.35,
    )
    return tap, toggle, success


def space():
    def blip(f0, f1, seconds, decay, amp=1.0):
        a = sine_sweep(f0, f1, seconds, decay, amp)
        b = sine_sweep(f0 * 1.005, f1 * 1.005, seconds, decay, amp * 0.6)  # slow beating = "space"
        return mix(a, b)

    tap = blip(1400, 700, 0.08, 40)
    toggle = mix(blip(600, 1200, 0.1, 30), offset(blip(900, 1800, 0.08, 30, 0.6), 0.05))
    success = mix(
        blip(523, 1046, 0.22, 12),
        offset(blip(659, 1318, 0.22, 12, 0.8), 0.08),
        offset(blip(784, 1568, 0.26, 10, 0.7), 0.16),
    )
    return tap, toggle, success


def main():
    total = 0
    for pack, fn in (("water", water), ("glass", glass), ("oud", oud), ("space", space)):
        tap, toggle, success = fn()
        total += write("ui_%s_tap" % pack, tap)
        total += write("ui_%s_toggle" % pack, toggle)
        total += write("ui_%s_success" % pack, success, peak=0.65)
    print("wrote 12 clips, %d bytes" % total)


if __name__ == "__main__":
    main()
