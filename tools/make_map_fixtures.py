#!/usr/bin/env python3
"""Generate test fixtures for the Kotlin PMTiles/MVT readers using the reference Python libraries.
pip install pmtiles mapbox-vector-tile
"""
import gzip, json, sys, os
from pmtiles.writer import Writer
from pmtiles.tile import zxy_to_tileid, TileType, Compression
import mapbox_vector_tile

out = sys.argv[1] if len(sys.argv) > 1 else "android/core/src/test/resources/map"
os.makedirs(out, exist_ok=True)

def build(path, zooms, leafy):
    comp = Compression.NONE if leafy else Compression.GZIP
    tiles = []
    for z in zooms:
        n = 1 << z
        rng = range(n) if n <= 128 else range(0, n, max(1, n // 128))
        for x in rng:
            for y in rng:
                tiles.append((zxy_to_tileid(z, x, y), z, x, y))
    tiles.sort()
    import pmtiles.writer as pw
    orig = pw.optimize_directories
    if leafy:  # force root + leaf directories even though the archive is small
        pw.optimize_directories = lambda entries, target: pw.build_roots_leaves(entries, 512)
    with open(path, "wb") as f:
        w = Writer(f)
        for tid, z, x, y in tiles:
            data = f"{z}/{x}/{y}".encode()
            w.write_tile(tid, data if leafy else gzip.compress(data, mtime=0))
        w.finalize({
            "tile_type": TileType.MVT, "tile_compression": comp,
            "min_zoom": min(zooms), "max_zoom": max(zooms),
            "min_lon_e7": int(11.2 * 1e7), "min_lat_e7": int(44.4 * 1e7),
            "max_lon_e7": int(11.5 * 1e7), "max_lat_e7": int(44.6 * 1e7),
            "center_zoom": 12, "center_lon_e7": int(11.34 * 1e7), "center_lat_e7": int(44.49 * 1e7),
        }, {"name": "fixture"})
    pw.optimize_directories = orig
    return len(tiles)

print("small", build(os.path.join(out, "small.pmtiles"), [0, 1, 2, 3], False))
print("leafy", build(os.path.join(out, "leafy.pmtiles"), [0, 1, 2, 3, 4, 5, 6, 7], True))

mvt = mapbox_vector_tile.encode([
    {"name": "roads", "features": [
        {"geometry": "LINESTRING(0 0, 100 100, 200 50)", "properties": {"kind": "major_road", "name": "Via Emilia", "lanes": 2}},
    ]},
    {"name": "water", "features": [
        {"geometry": "POLYGON((10 10, 60 10, 60 60, 10 60, 10 10))", "properties": {"kind": "water", "area": 1.5}},
    ]},
    {"name": "places", "features": [
        {"geometry": "POINT(300 400)", "properties": {"kind": "locality", "name": "Bologna", "capital": True}},
    ]},
], default_options={"y_coord_down": True, "quantize_bounds": None, "extents": 4096})
open(os.path.join(out, "sample.mvt"), "wb").write(mvt)
print("mvt", len(mvt))
