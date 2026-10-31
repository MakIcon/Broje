"""Generate Broje's original periodic 64^3 noise atlas (numpy, no external assets).

RGBA: smooth value noise, cellular F1, finer cellular F1, finer value noise.
Each of 64 XY slices has a one-voxel periodic border for hardware bilinear reads.
Run from any directory; the checked-in binary is used directly at runtime.
"""
from itertools import product
from pathlib import Path
import numpy as np

SIZE = 64


def random3(cell, seed):
    c = cell.astype(np.uint32)
    with np.errstate(over="ignore"):
        h = c[..., 0] * np.uint32(1597334677) ^ c[..., 1] * np.uint32(3812015801) ^ c[..., 2] * np.uint32(2798796415)
        h ^= np.uint32(seed)
        result = []
        for salt in (0x68BC21EB, 0x02E5BE93, 0x967A889B):
            v = h ^ np.uint32(salt)
            v ^= v >> 16
            v *= np.uint32(2246822519)
            v ^= v >> 13
            v *= np.uint32(3266489917)
            v ^= v >> 16
            result.append((v >> 8).astype(np.float32) / 16777216)
    return np.stack(result, axis=-1)


def fields(period, seed):
    z, y, x = np.mgrid[:SIZE, :SIZE, :SIZE].astype(np.float32)
    p = np.stack((x, y, z), axis=-1) * (period / SIZE) + period / SIZE * 0.5
    cell = np.floor(p).astype(np.int32)
    f = p - cell
    u = f * f * f * (f * (f * 6 - 15) + 10)
    value = np.zeros(p.shape[:-1], dtype=np.float32)
    for offset in product((0, 1), repeat=3):
        o = np.array(offset)
        weight = np.prod(np.where(o == 0, 1 - u, u), axis=-1)
        value += weight * random3((cell + o) % period, seed)[..., 0]
    nearest = np.full_like(value, 4)
    for offset in product((-1, 0, 1), repeat=3):
        o = np.array(offset)
        feature = random3((cell + o) % period, seed + 91)
        d = o + feature - f
        nearest = np.minimum(nearest, np.sum(d * d, axis=-1))
    return value, np.clip(np.sqrt(nearest), 0, 1)


if __name__ == "__main__":
    value, cellular = fields(8, 521)
    fine, fine_cellular = fields(16, 739)
    volume = np.rint(np.stack((value, cellular, fine_cellular, fine), axis=-1) * 255).astype(np.uint8)
    atlas = np.zeros((528, 528, 4), dtype=np.uint8)
    for z in range(SIZE):
        tile = np.pad(volume[z], ((1, 1), (1, 1), (0, 0)), mode="wrap")
        x, y = z % 8 * 66, z // 8 * 66
        atlas[y:y + 66, x:x + 66] = tile
    target = Path(__file__).resolve().parents[1] / "src/main/resources/assets/broje/textures/cloud_noise.rgba"
    target.write_bytes(atlas.tobytes())
    print(f"Generated {target}: {atlas.shape}, {target.stat().st_size} bytes")
