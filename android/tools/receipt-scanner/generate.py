#!/usr/bin/env python3
"""Generate ONLY procedural receipts and small numerical goldens, using external D7a code.
Never enumerates or reads ReceiptScanEval/work, photos, or out; never changes the reference repo.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

sys.dont_write_bytecode = True  # External reference tree is read-only.

os.environ.setdefault('OPENBLAS_NUM_THREADS', '1')
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ANDROID = Path(__file__).resolve().parents[2]
ASSETS = ANDROID / 'app/src/androidTest/assets/receipt-scanner'
NUMERICS = ANDROID / 'app/src/test/resources/receipt-scanner/numerics.json'
MODELS = ANDROID / 'app/src/main/assets/receipt-scanner'


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, default=lambda v: v.tolist() if isinstance(v, np.ndarray) else v.item()) + '\n')


def numerical_goldens():
    from uv_grid import extend_grid, pixel_grid
    from ribbon import sample_field
    rng = np.random.default_rng(2026)
    grid = rng.uniform(-.8, .8, (7, 5, 2)).astype(np.float32)
    extended = extend_grid(grid)
    pixels, _ = pixel_grid(grid, (19, 25), (11, 13))
    u = np.linspace(0, 1, 9, dtype=np.float32)
    y = np.float32(5.125); left = np.float32(1.25); right = np.float32(8.875)
    x = left + (right-left)*u
    q = sample_field(grid, x*np.float32(4/10), np.full(9, y*np.float32(6/12), np.float32))
    px = (q[:, 0]+1)*np.float32(18/2); py = (q[:, 1]+1)*np.float32(24/2)
    back = np.array([.96, -.28, 3.25, .28, .96, -2.5], dtype=np.float64)
    # Java preserves float32 bilinear evaluation, then evaluates the affine in float64.
    mx = (back[0]*px.astype(float)+back[1]*py.astype(float)+back[2]).astype(np.float32)
    my = (back[3]*px.astype(float)+back[4]*py.astype(float)+back[5]).astype(np.float32)
    keep = (y >= 2.0+.45*x) & (y <= 8.0-.1*x) & (mx >= 0) & (mx <= 18) & (my >= 0) & (my <= 24)
    pil = []
    for w, h, ow, oh in [(13, 7, 7, 5), (7, 17, 5, 6), (4, 3, 9, 8)]:
        source = rng.integers(0, 256, (h, w, 3), np.uint8)
        expected = np.asarray(Image.fromarray(source).resize((ow, oh), Image.Resampling.BILINEAR))
        pil.append(dict(w=w, h=h, ow=ow, oh=oh, source=source.ravel(), expected=expected.ravel()))
    bounds = [7, 8, 3, 19, 1, 32, 5, 7]*5
    pcg = np.random.default_rng(2026)
    values = np.array([3, -9, 2, 2, 2, 40, 1, -9, 17, 5, 5, 5], float)
    ps = [0, .1, .25, .5, .9, 1]
    write_json(NUMERICS, dict(grid=grid.ravel(), extended=extended.ravel(), extended_wh=list(extended.shape[1::-1]),
        pixels=pixels.ravel(), compose=dict(back=back, mx=mx, my=my, outside=np.where(keep, 0, 255)), pil=pil,
        bounds=bounds, random=[int(pcg.integers(n)) for n in bounds], values=values, percentiles=ps,
        quantiles=np.quantile(values, ps), rounds=np.rint([-.5, -1.5, -2.5, .5, 1.5, 2.5, 3.5])))


def paper():
    im = Image.new('RGB', (560, 1720), (246, 244, 235))
    draw = ImageDraw.Draw(im)
    font_path = '/usr/share/fonts/liberation-mono-fonts/LiberationMono-Regular.ttf'
    if not Path(font_path).is_file():
        font_path = '/usr/share/fonts/truetype/liberation2/LiberationMono-Regular.ttf'
    font = ImageFont.truetype(font_path, 22)
    title = ImageFont.truetype(font_path, 34)
    draw.text((85, 50), 'YOHAKU MARKET', fill=(28, 29, 26), font=title)
    lines = ['PROCEDURAL TEST RECEIPT', 'No customer or real purchase data', '2026-01-01       REGISTER 03', '-'*33,
             'ITEM                   AMOUNT']
    products = ['APPLES', 'OAT MILK', 'BREAD', 'GREEN TEA', 'RICE', 'TOFU', 'COFFEE', 'TOMATOES']
    for i in range(25):
        lines.append(f'{products[i % len(products)]:<19} {120+i*31:>7}.00')
    lines += ['-'*33, 'TOTAL                   12345.00', 'CARD PAYMENT', '', 'THANK YOU FOR VISITING', 'SYNTHETIC FIXTURE - NOT A SALE']
    for i, line in enumerate(lines):
        draw.text((32, 130+i*38), line, fill=(36, 35, 32), font=font)
    for x in range(100, 460, 7):
        draw.rectangle((x, 1590, x+2+(x % 3), 1650), fill=(30, 30, 30))
    return np.asarray(im)[:, :, ::-1].copy()


def synthetic(index):
    w, h = 1300, 2000
    yy, xx = np.mgrid[:h, :w].astype(np.float32)
    # Smooth wood/cloth-like texture keeps checked-in JPEGs small and deterministic.
    texture = 5*np.sin(xx/17 + .7*np.sin(yy/180)) + 3*np.sin(yy/11) + 8*xx/w
    colors = [(47, 57, 65), (58, 48, 38), (48, 62, 48), (58, 53, 48), (44, 43, 56)]
    background = np.clip(np.array(colors[index], np.float32)[None, None]+texture[:, :, None], 0, 255).astype(np.uint8)
    quads = [
        [[390, 140], [920, 175], [900, 1850], [370, 1815]],
        [[480, 160], [960, 280], [860, 1850], [300, 1720]],
        [[390, 130], [940, 160], [900, 1880], [350, 1820]],
        [[400, 150], [935, 195], [900, 2250], [370, 2205]],
        [[355, 110], [905, 155], [990, 2240], [330, 2190]],
    ]
    p = paper(); ph, pw = p.shape[:2]
    matrix = cv2.getPerspectiveTransform(np.float32([[0, 0], [pw-1, 0], [pw-1, ph-1], [0, ph-1]]), np.float32(quads[index]))
    warped = cv2.warpPerspective(p, matrix, (w, h))
    alpha = cv2.warpPerspective(np.full((ph, pw), 255, np.uint8), matrix, (w, h))
    if index in (2, 4):
        amplitude = 24 if index == 2 else 38
        mx = xx-amplitude*np.sin(yy/h*2*np.pi)
        my = yy-18*np.cos((xx-650)/300*np.pi)
        warped = cv2.remap(warped, mx, my, cv2.INTER_LINEAR)
        alpha = cv2.remap(alpha, mx, my, cv2.INTER_LINEAR)
    shadow = cv2.GaussianBlur(alpha, (0, 0), 12)
    background = np.clip(background.astype(float)*(1-.25*shadow[:, :, None]/255), 0, 255)
    a = alpha[:, :, None]/255
    return np.rint(warped*a+background*(1-a)).astype(np.uint8), alpha > 127


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--eval-dir', type=Path, default=os.environ.get('RECEIPT_SCAN_EVAL'))
    parser.add_argument('--numerics-only', action='store_true')
    args = parser.parse_args()
    if not args.eval_dir: parser.error('--eval-dir or RECEIPT_SCAN_EVAL must point to ReceiptScanEval/eval')
    sys.path.insert(0, str(args.eval_dir.resolve()))
    from models import Models, session
    from geometry import clean_mask, Geometry
    from syntheticdoc import SyntheticGrid
    from ribbon import upright_crop, process
    from compare_device import LIMITS, GEOMETRY_FIELDS
    import onnxruntime as ort
    import PIL
    cv2.setNumThreads(2)
    # Check the reference uses exactly the shipped models before generating any golden data.
    expected_models = {}
    for model in MODELS.glob('*.onnx'):
        source = args.eval_dir.parent/'models'/model.name
        digest = hashlib.sha256(model.read_bytes()).hexdigest()
        assert hashlib.sha256(source.read_bytes()).hexdigest() == digest, model.name
        expected_models[model.name] = digest
    numerical_goldens()
    if args.numerics_only: return
    models = Models(threads=2); synthetic_model = SyntheticGrid('D7a', threads=2)
    ASSETS.mkdir(parents=True, exist_ok=True)
    cases = []
    for i, name in enumerate(['flat', 'perspective', 'curled', 'bottom-cut', 'curled-bottom-cut']):
        im, truth = synthetic(i)
        path = ASSETS/f'{name}.jpg'
        assert cv2.imwrite(str(path), im, [cv2.IMWRITE_JPEG_QUALITY, 94])
        # Goldens use the delivered JPEG, not the generator's pre-JPEG pixels.
        im = cv2.imread(str(path)); h, w = im.shape[:2]
        raw, segmentation = models.edge(im, [[w*.5, h*f] for f in [.35, .5, .65]], models.edge_encode(im))
        assert segmentation['selection_eligible'], (name, segmentation)
        mask, center = clean_mask(raw)
        truth_iou = np.count_nonzero((mask > 0) & truth)/np.count_nonzero((mask > 0) | truth)
        assert truth_iou > .95, (name, truth_iou)
        geo = Geometry(mask)
        crop, cm, valid, back, meta = upright_crop(im, mask, .1)
        pic, _, detail, _ = process(im, mask, crop, cm, valid, back, geo.top_inside, geo.bottom_inside, synthetic_model)
        meta.update(detail)
        assert cv2.imwrite(str(ASSETS/f'{name}.mask.png'), mask*255)
        # Keep the reference's JPEG90; production JPEG95 is compared with unchanged MAE limits.
        assert cv2.imwrite(str(ASSETS/f'{name}.gold.jpg'), pic, [cv2.IMWRITE_JPEG_QUALITY, 90])
        write_json(ASSETS/f'{name}.geometry.json', {k: meta[k] for k in GEOMETRY_FIELDS})
        # Evaluate the 5-handle cubic contour approximation independently in Python too.
        l, r = geo.strip(np.linspace(0, 1, 5)); l = geo.to_original(l); r = geo.to_original(r)
        poly = np.concatenate([np.polynomial.polynomial.polyval(np.linspace(0, 4, 64), np.polynomial.polynomial.polyfit(np.arange(5), l, 3)).T, np.polynomial.polynomial.polyval(np.linspace(4, 0, 64), np.polynomial.polynomial.polyfit(np.arange(5), r, 3)).T])
        poly[:, 0] = np.clip(poly[:, 0], 0, w-1); poly[:, 1] = np.clip(poly[:, 1], 0, h-1)
        roundtrip = np.zeros_like(mask); cv2.fillPoly(roundtrip, [np.rint(poly).astype(np.int32)], 1)
        contour_iou = np.count_nonzero((roundtrip > 0) & (mask > 0))/np.count_nonzero((roundtrip > 0) | (mask > 0))
        assert contour_iou > .98, (name, contour_iou)
        cases.append(dict(name=name, source_wh=[w, h], output_wh=list(pic.shape[1::-1]), segmentation=segmentation,
            truth_iou=truth_iou, contour_iou=contour_iou, top_in_frame=geo.top_inside, bottom_in_frame=geo.bottom_inside))
        print(name, 'eligible', segmentation['model_score'], 'truth IoU', truth_iou, 'contour IoU', contour_iou, 'output', pic.shape[1::-1], flush=True)
    # Uniform frame exercises real model no-candidate behavior (not just a mocked selector).
    blank = np.full((640, 480, 3), 30, np.uint8)
    assert cv2.imwrite(str(ASSETS/'no-receipt.png'), blank)
    _, blank_meta = models.edge(blank, [[240, 640*f] for f in [.35, .5, .65]], models.edge_encode(blank))
    assert not blank_meta['selection_eligible'], blank_meta
    write_json(ASSETS/'manifest.json', dict(generator='android/tools/receipt-scanner/generate.py', models=expected_models,
        versions=dict(opencv=cv2.__version__, numpy=np.__version__, pillow=PIL.__version__, onnxruntime=ort.__version__),
        limits=LIMITS, geometry_fields=GEOMETRY_FIELDS, cases=cases, no_receipt=blank_meta))


if __name__ == '__main__':
    main()
