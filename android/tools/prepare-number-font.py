"""Prepare licensed Gelasio with magazine line metrics; retain original glyphs.

Run with Python/fontTools. Original OFL source stays in tools/font-source.
The measured HTML line metrics use 2048 units/em, ascent 1878, descent -449.
Win clipping extents stay unchanged so glyphs are not clipped or rescaled.
"""
from pathlib import Path
from fontTools.ttLib import TTFont

root = Path(__file__).resolve().parents[1]
font = TTFont(root / 'tools/font-source/gelasio-regular.ttf')
assert font['head'].unitsPerEm == 2048
font['hhea'].ascent = font['OS/2'].sTypoAscender = 1878
font['hhea'].descent = font['OS/2'].sTypoDescender = -449
font['hhea'].lineGap = font['OS/2'].sTypoLineGap = 0
for record in font['name'].names:
    if record.nameID in (1, 4, 6, 16):
        record.string = 'YohakuNumbers'.encode(record.getEncoding())
font.save(root / 'app/src/main/res/font/editorial_number.ttf')
