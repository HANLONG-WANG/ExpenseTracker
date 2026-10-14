"""Subset the HTML ink-bullet's actual DejaVu Sans glyph. Requires fontTools.

Usage: python prepare-journal-star.py /path/to/DejaVuSans.ttf
The original DejaVu/Bitstream license accompanies the renamed derivative.
"""
from pathlib import Path
import sys
from fontTools.ttLib import TTFont
from fontTools import subset
font=TTFont(sys.argv[1])
options=subset.Options()
options.name_IDs=['*']
subsetter=subset.Subsetter(options=options)
subsetter.populate(text='✳')
subsetter.subset(font)
for record in font['name'].names:
    if record.nameID in (1,4,6,16):
        record.string='YohakuJournalStar'.encode(record.getEncoding())
font.save(Path(__file__).resolve().parents[1]/'app/src/main/res/font/journal_star.ttf')
