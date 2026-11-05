"""Generates app/src/main/assets/sample-simulated.jsonl: SYNTHETIC data for UI development and replay tests.
It proves nothing about the Yaris. Run: python3 tools/generate_sample_recording.py"""
import json, math, random

random.seed(7)
t = 1_762_340_000_000
out = []

def line(**kw):
    out.append(json.dumps(kw, separators=(",", ":")))

def ex(cmd, raw, error=None, duration=None):
    global t
    d = duration if duration is not None else random.randint(30, 60)
    line(type="exchange", t=t, cmd=cmd, durationMs=d, raw=raw, error=error) if error else line(type="exchange", t=t, cmd=cmd, durationMs=d, raw=raw)
    t += d + 2

def can(pid, *data):
    return "7E8 %02X 41 %s %s \r\r" % (2 + len(data), pid, " ".join("%02X" % b for b in data))

def init():
    ex("ATZ", "\r\rELM327 v1.4b\r\r", duration=900)
    for c in ("ATE0", "ATL0", "ATH1", "ATSP0"):
        ex(c, "OK\r\r")
    ex("ATI", "ELM327 v1.4b\r\r")
    ex("STI", "STN1155 v5.10.3\r\r")
    ex("0100", "SEARCHING...\r7E8 06 41 00 BE 3F A8 13 \r\r", duration=1200)
    ex("ATDP", "AUTO, ISO 15765-4 (CAN 11/500)\r\r")

line(type="session", formatVersion=1, startedAtEpochMs=t, source="simulated", appVersion="generator",
     note="Dati SIMULATI generati a mano: non provengono dalla Yaris e non dimostrano nulla sull'auto")
line(type="event", t=t, event="connected")
init()
for cycle in range(400):
    if cycle == 250:
        line(type="exchange", t=t, cmd="010C", durationMs=120, raw="7E8 04 4", error="DISCONNECTED")
        line(type="event", t=t + 120, event="disconnected", detail="connessione persa")
        t += 1200
        line(type="event", t=t, event="connect_failed", detail="LX non raggiungibile (simulato)")
        t += 2000
        line(type="event", t=t, event="connected")
        init()
    phase = cycle / 400 * 2 * math.pi
    speed = max(0, int(45 + 40 * math.sin(phase)))
    engine_on = (cycle // 60) % 2 == 0
    rpm = int(1200 + 900 * abs(math.sin(phase * 3))) if engine_on else 0
    ex("010C", can("0C", (rpm * 4) >> 8, (rpm * 4) & 0xFF))
    ex("010D", can("0D", speed))
    ex("0105", can("05", 40 + 78 + cycle // 100))
    if cycle in (120, 330):
        line(type="exchange", t=t, cmd="0104", durationMs=3000, error="TIMEOUT")
        t += 3002
    else:
        ex("0104", can("04", int(255 * (0.35 if engine_on else 0))))
    ex("ATRV", "%.1fV\r\r" % (14.0 + random.random() * 0.3))

with open("app/src/main/assets/sample-simulated.jsonl", "w") as f:
    f.write("\n".join(out) + "\n")
