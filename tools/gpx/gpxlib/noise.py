"""Realistic GPS noise: time-correlated offset + white jitter + short dropouts."""
from __future__ import annotations

import math
import random
from dataclasses import dataclass, replace
from typing import Sequence

from .geo import offset_m
from .profile import Sample


@dataclass(frozen=True)
class NoiseParams:
    sigma_m: float = 6.0  # std-dev of the slow, correlated offset (per axis)
    tau_s: float = 30.0  # correlation time of that offset
    jitter_m: float = 1.5  # white noise per fix (per axis)
    speed_sigma: float = 0.3  # m/s
    course_sigma: float = 4.0  # degrees
    dropout_rate: float = 1 / 150  # probability per second that a gap starts
    dropout_min_s: int = 2
    dropout_max_s: int = 5


def add_noise(samples: Sequence[Sample], rng: random.Random,
              p: NoiseParams = NoiseParams()) -> list[Sample]:
    """Return a noisy copy; dropped seconds are simply missing from the list."""
    phi = math.exp(-1.0 / p.tau_s)
    drive = p.sigma_m * math.sqrt(1 - phi * phi)
    ex = rng.gauss(0, p.sigma_m)
    ny = rng.gauss(0, p.sigma_m)
    out: list[Sample] = []
    skip_until = -1
    for i, s in enumerate(samples):
        ex = phi * ex + rng.gauss(0, drive)
        ny = phi * ny + rng.gauss(0, drive)
        jx = rng.gauss(0, p.jitter_m)
        jy = rng.gauss(0, p.jitter_m)
        first_or_last = i == 0 or i == len(samples) - 1
        if not first_or_last and s.t > skip_until and rng.random() < p.dropout_rate:
            skip_until = s.t + rng.randint(p.dropout_min_s, p.dropout_max_s) - 1
        if s.t <= skip_until and not first_or_last:
            continue
        lat, lon = offset_m((s.lat, s.lon), ex + jx, ny + jy)
        speed = max(0.0, s.speed + rng.gauss(0, p.speed_sigma)) if s.speed > 0 else \
            abs(rng.gauss(0, 0.1))
        course = (s.course + rng.gauss(0, p.course_sigma)) % 360.0
        out.append(replace(s, lat=lat, lon=lon, speed=speed, course=course))
    return out
