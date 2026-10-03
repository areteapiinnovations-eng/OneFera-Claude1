"""Prints small JPEG thumbnails of smoke-test screenshots as base64 into the CI log."""
import base64
import io
import pathlib
import sys

from PIL import Image

out = pathlib.Path(sys.argv[1])
for png in sorted(out.glob("*.png")):
    try:
        img = Image.open(png).convert("RGB")
    except Exception:
        continue
    img.thumbnail((360, 800))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=70)
    print(f"THUMB-BEGIN {png.stem}")
    data = base64.b64encode(buf.getvalue()).decode()
    for i in range(0, len(data), 4000):
        print(data[i:i + 4000])
    print(f"THUMB-END {png.stem}")
