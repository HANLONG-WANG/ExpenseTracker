"""Provide a true U+2212 minus in the journal's licensed hand font.

YohakuHand maps minus and hyphen to identical outlines/advance (350 units).
Keep its ink outline, but align the mathematical minus with the plus width
(600 units). The new subset contains only U+2212; hyphens and other glyphs
continue to use the original font. Run with Python/fontTools.
"""
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.transformPen import TransformPen

root = Path(__file__).resolve().parents[1]
font = TTFont(root / 'app/src/main/res/font/hand_full.ttf')
glyphs = font.getGlyphSet()
minus = font.getBestCmap()[0x2212]
pen = TTGlyphPen(glyphs)
# Original outline x=23..329; plus horizontal extent x=51..547.
scale = 496 / 306
glyphs[minus].draw(TransformPen(pen, (scale, 0, 0, 1, 51 - 23 * scale, 0)))
font['glyf'][minus] = pen.glyph()
font['hmtx'][minus] = (600, 51)
for record in font['name'].names:
    if record.nameID in (1, 4, 6, 16):
        record.string = 'YohakuJournalMath'.encode(record.getEncoding())
options = subset.Options()
options.name_IDs = ['*']
subsetter = subset.Subsetter(options)
subsetter.populate(unicodes=[0x2212])
subsetter.subset(font)
font.save(root / 'app/src/main/res/font/journal_math.ttf')
