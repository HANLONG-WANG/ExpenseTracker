"""Compare E12's fixed-fact HTML anchors with native screenshot-side dp measurements."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "verification"
reference = root / "phase-e-dialog-reference"
cases = {
    "book-trash-sheet": ("book-management", "trash-preview"),
    "account-stop-sheet": ("account-management", "stop"),
    "category-stop-sheet": ("category-management", "stop"),
    "activity-delete-sheet": ("activity-management", "preview"),
    "entry-purge-sheet": ("entry-lifecycle", "purge"),
}
result = []
for ref in json.loads((reference / "measurements.json").read_text()):
    if ref["state"] not in cases:
        continue  # Expanded activity is separately reviewed in the lower-viewport screenshots.
    family, shot = cases[ref["state"]]
    filename = f"{ref['theme']}-{ref['language']}-{shot}.bounds.json"
    native = json.loads((root / f"phase-e-{family}-standard" / filename).read_text())
    sheet = native["theme-sheet"][0]
    errors = {}
    for index, row in enumerate(n for n in ref["nodes"] if n["class"] == "detail-row"):
        actual = native[f"record-fact-{index}"][0]
        expected = row["rect"]
        errors[f"row{index}-left"] = actual["left"] - expected["left"]
        errors[f"row{index}-width"] = actual["right"] - actual["left"] - expected["width"]
        errors[f"row{index}-top"] = actual["top"] - sheet["top"] - expected["top"] + ref["sheet"]["y"]
        errors[f"row{index}-height"] = actual["bottom"] - actual["top"] - expected["height"]
    title = native["sheet-title"][0]
    expected = ref["title"]
    errors["title-left"] = title["left"] - expected["x"]
    errors["title-top"] = title["top"] - sheet["top"] - expected["y"] + ref["sheet"]["y"]
    errors["title-height"] = title["bottom"] - title["top"] - expected["height"]
    result.append({"theme": ref["theme"], "language": ref["language"], "state": ref["state"],
                   "errorsDp": {k: round(v, 4) for k, v in errors.items()},
                   "maximumAbsoluteDp": round(max(abs(v) for v in errors.values()), 4)})
if len(result) != 30:
    raise SystemExit(f"Expected 30 measured states, got {len(result)}")
(reference / "native-standard-comparison.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
maximum = max(row["maximumAbsoluteDp"] for row in result)
print(f"{len(result)} states; maximum anchor error {maximum:.4f} dp")
if maximum > 1:
    raise SystemExit("Measured anchor exceeds the 1 dp review target")
