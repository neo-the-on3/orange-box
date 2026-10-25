#!/usr/bin/env python3
"""Synthesizes Orange Box's alert tones (M8 #59) into app/src/main/res/raw.

All sounds are generated here from sine waves; none come from CarooLive/Pokevian or any other app.
Run from the repo root: python3 tools/gen_alert_sounds.py
"""
import math
import struct
import wave
from pathlib import Path

RATE = 22050
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"


def tone(freq_start, freq_end, ms, volume=0.6, harmonics=(1.0, 0.25)):
    n = int(RATE * ms / 1000)
    out, phase = [], 0.0
    for i in range(n):
        f = freq_start + (freq_end - freq_start) * i / max(1, n - 1)
        phase += 2 * math.pi * f / RATE
        attack = min(1.0, i / (RATE * 0.008))
        release = min(1.0, (n - i) / (RATE * 0.04))
        s = sum(a * math.sin(phase * (k + 1)) for k, a in enumerate(harmonics)) / sum(harmonics)
        out.append(s * volume * attack * release)
    return out


def silence(ms):
    return [0.0] * int(RATE * ms / 1000)


def write(name, samples):
    OUT.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT / f"{name}.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))


SOUNDS = {
    # Two quick high beeps.
    "alert_overspeed": tone(1320, 1320, 120) + silence(70) + tone(1320, 1320, 120),
    # A short falling chirp.
    "alert_harsh": tone(980, 620, 220, volume=0.55),
    # A soft rising two-note chime.
    "alert_idling": tone(660, 660, 160, volume=0.45) + silence(30) + tone(880, 880, 260, volume=0.45),
    # Three low pulses.
    "alert_mil": tone(440, 440, 110) + silence(60) + tone(440, 440, 110) + silence(60) + tone(440, 440, 110),
}

if __name__ == "__main__":
    for name, samples in SOUNDS.items():
        write(name, samples)
        print(f"{name}.wav: {len(samples) / RATE * 1000:.0f} ms")
