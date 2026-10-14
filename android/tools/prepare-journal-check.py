"""Extract the HTML journal's Noto Sans CJK SC check glyph at actual wght400.

Usage: python prepare-journal-check.py /path/to/NotoSansCJK-VF.ttc
Requires fontTools. Noto-OFL.txt is shipped with the application.
"""
from pathlib import Path
import sys
from fontTools.ttLib import TTCollection
from fontTools import subset
from fontTools.varLib.instancer import instantiateVariableFont

font = TTCollection(sys.argv[1]).fonts[2]  # SC, as identified by fontconfig/CDP
options = subset.Options()
options.name_IDs = ['*']
subsetter = subset.Subsetter(options=options)
subsetter.populate(text='✓')
subsetter.subset(font)
font = instantiateVariableFont(font, {'wght': 400}, inplace=True)
for record in font['name'].names:
    if record.nameID in (1, 4, 6, 16):
        record.string = 'YohakuJournalCheck'.encode(record.getEncoding())
font.save(Path(__file__).resolve().parents[1] / 'app/src/main/res/font/journal_check.ttf')
