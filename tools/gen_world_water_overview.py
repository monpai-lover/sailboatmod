#!/usr/bin/env python3
"""Generate the bundled world water overview used by far-route planning on Tellus Earth worlds.

Source: Natural Earth 1:10m land polygons (public domain), https://www.naturalearthdata.com/
  curl -LO https://naciscdn.org/naturalearth/10m/physical/ne_10m_land.zip && unzip ne_10m_land.zip

Usage: python3 tools/gen_world_water_overview.py ne_10m_land.shp \
         src/main/resources/com/monpai/sailboatmod/route/water/world_water_overview.bin.gz

Output (gzip): magic b"SBWW", int32 BE width, height, pixelsPerDegree, then rows from latitude +MAX_LAT down to
-MAX_LAT, each row west (-180) to east, packed MSB-first, bit = 1 for water. A pixel is water when ANY of its
SUPER x SUPER sub-samples is outside land, so straits narrower than a pixel stay open (the overview is only a
search heuristic; navigability is always decided by Tellus' own land mask at run time).
"""
import gzip
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw

PPD = 16          # pixels per degree (1/16 degree ~ 7 km at the equator)
SUPER = 4         # sub-samples per pixel edge
MAX_LAT = 85      # Web-Mercator limit used by Tellus is 85.05


def read_polygons(path):
    with open(path, "rb") as f:
        data = f.read()
    pos = 100
    while pos < len(data):
        _, length = struct.unpack(">ii", data[pos:pos + 8])
        content = data[pos + 8:pos + 8 + length * 2]
        pos += 8 + length * 2
        shape_type = struct.unpack("<i", content[:4])[0]
        if shape_type != 5:
            continue
        num_parts, num_points = struct.unpack("<ii", content[36:44])
        parts = list(struct.unpack("<%di" % num_parts, content[44:44 + 4 * num_parts]))
        pts_off = 44 + 4 * num_parts
        points = np.frombuffer(content[pts_off:pts_off + 16 * num_points], dtype="<f8").reshape(-1, 2)
        parts.append(num_points)
        for i in range(num_parts):
            yield points[parts[i]:parts[i + 1]]


def main(shp, out):
    width, height = 360 * PPD, 2 * MAX_LAT * PPD
    sw, sh = width * SUPER, height * SUPER
    land = Image.new("L", (sw, sh), 0)
    draw = ImageDraw.Draw(land)
    scale = PPD * SUPER
    for ring in read_polygons(shp):
        xs = (ring[:, 0] + 180.0) * scale
        ys = (MAX_LAT - ring[:, 1]) * scale
        # Shapefile rings: clockwise outer (signed area < 0 in x-right/y-up), counter-clockwise holes.
        area = np.sum(ring[:-1, 0] * ring[1:, 1] - ring[1:, 0] * ring[:-1, 1])
        draw.polygon(list(zip(xs.tolist(), ys.tolist())), fill=0 if area > 0 else 255)
    arr = np.asarray(land, dtype=np.uint8).reshape(height, SUPER, width, SUPER)
    all_land = arr.min(axis=(1, 3)) == 255
    water = ~all_land
    bits = np.packbits(water, axis=1)
    with gzip.open(out, "wb", compresslevel=9) as f:
        f.write(b"SBWW" + struct.pack(">iii", width, height, PPD))
        f.write(bits.tobytes())
    print("wrote %s: %dx%d, water %.1f%%" % (out, width, height, 100.0 * water.mean()))
    Image.fromarray(np.where(water, 40, 200).astype(np.uint8)).resize((width // 4, height // 4)).save(out + ".preview.png")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
