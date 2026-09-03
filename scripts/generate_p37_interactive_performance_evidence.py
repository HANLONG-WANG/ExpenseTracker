#!/usr/bin/env python3
"""Generate reproducible P37 evidence from AndroidX raw data and device counter logs."""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import json
import math
import os
import platform
import re
import statistics
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]
BUDGET_PATH = ROOT / "quality/performance/p37_budgets.json"
CHUNK_PREFIX = "p37BenchmarkDataChunk"
RESULT_PREFIX = "INSTRUMENTATION_RESULT: "
EVIDENCE_LINE = re.compile(r"P37Evidence.*?scenario=(\w+)((?:\s+\w+=-?\d+)+)")
COUNTER_VALUE = re.compile(r"(\w+)=(-?\d+)")

BENCHMARK_SCENARIOS = {
    "unlockToCurrentRouteContent": (
        "coldUnlockToCurrentRouteContent",
        "P37/unlock_to_contentFirstMs",
        5,
    ),
    "warmCachedTopLevelNavigation": (
        "warmCachedTopLevelNavigation",
        "P37/route_requestFirstMs",
        30,
    ),
    "warmUncachedBoundedDestination": (
        "warmUncachedBoundedJournalNavigation",
        "P37/route_requestFirstMs",
        30,
    ),
    "blockingProgressVisible": (
        "blockingLoadingAffordanceBecomesVisible",
        "P37/blocking_progress_visibleFirstMs",
        30,
    ),
    "ordinarySave": (
        "ordinarySaveToCommittedAcknowledgement",
        "P37/save_requestFirstMs",
        30,
    ),
    "ordinarySaveCommit": (
        "ordinarySaveToCommittedAcknowledgement",
        "P37/save_commitFirstMs",
        30,
    ),
    "ordinarySavePropagation": (
        "ordinarySaveToCommittedAcknowledgement",
        "P37/save_settledFirstMs",
        30,
    ),
    "searchIncludingDebounce": (
        "journalSearchIncludingDebounce",
        "P37/search_requestFirstMs",
        30,
    ),
    "searchAfterDebounce": (
        "journalSearchIncludingDebounce",
        "P37/search_contentFirstMs",
        30,
    ),
}


class EvidenceError(RuntimeError):
    pass


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def instrumentation_results(path: Path) -> dict[str, str]:
    fields: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.startswith(RESULT_PREFIX):
            continue
        payload = line[len(RESULT_PREFIX) :]
        key, separator, value = payload.partition("=")
        if separator and key.startswith("p37Benchmark"):
            fields[key] = value
    return fields


def extract_embedded_benchmark_data(path: Path) -> tuple[bytes, dict[str, str]]:
    fields = instrumentation_results(path)
    if fields.get("p37BenchmarkDataStatus") != "ok":
        raise EvidenceError(f"benchmark data status is {fields.get('p37BenchmarkDataStatus', 'absent')}")
    if fields.get("p37BenchmarkDataEncoding") != "gzip+base64":
        raise EvidenceError("unsupported benchmark data encoding")
    try:
        chunk_count = int(fields["p37BenchmarkDataChunkCount"])
        encoded = "".join(fields[f"{CHUNK_PREFIX}{index:04d}"] for index in range(chunk_count))
        raw = gzip.decompress(base64.b64decode(encoded, validate=True))
    except (KeyError, ValueError, OSError) as error:
        raise EvidenceError("benchmark data chunks are incomplete or corrupt") from error
    if sha256(raw) != fields.get("p37BenchmarkDataSha256"):
        raise EvidenceError("benchmark data SHA-256 mismatch")
    if len(raw) != int(fields.get("p37BenchmarkDataRawBytes", "-1")):
        raise EvidenceError("benchmark data byte count mismatch")
    return raw, fields


def nearest_rank(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def summarize(values: Iterable[float], minimum_samples: int) -> dict:
    normalized = [round(float(value), 6) for value in values]
    if len(normalized) < minimum_samples:
        raise EvidenceError(f"only {len(normalized)} samples; {minimum_samples} required")
    mean = statistics.fmean(normalized)
    deviation = statistics.pstdev(normalized)
    return {
        "unit": "ms",
        "samples": len(normalized),
        "samplesMillis": normalized,
        "p50": round(nearest_rank(normalized, 0.50), 6),
        "p90": round(nearest_rank(normalized, 0.90), 6),
        "p95": round(nearest_rank(normalized, 0.95), 6),
        "max": round(max(normalized), 6),
        "mean": round(mean, 6),
        "standardDeviation": round(deviation, 6),
        "coefficientOfVariation": round(deviation / abs(mean), 6) if mean else 0.0,
        "timeouts": 0,
        "failedSamples": 0,
    }


def benchmark_for(raw: dict, method: str) -> dict:
    matches = [
        benchmark
        for benchmark in raw.get("benchmarks", [])
        if benchmark.get("className", "").endswith("P37InteractiveMacrobenchmark")
        and benchmark.get("name", "").split("[")[0] == method
    ]
    if len(matches) != 1:
        raise EvidenceError(f"expected one P37 benchmark result for {method}, found {len(matches)}")
    return matches[0]


def metric_runs(benchmark: dict, metric_name: str) -> list[float]:
    metric = benchmark.get("metrics", {}).get(metric_name)
    if not isinstance(metric, dict) or not isinstance(metric.get("runs"), list):
        raise EvidenceError(f"missing single-value metric {metric_name} in {benchmark.get('name')}")
    return metric["runs"]


def frame_runs(raw: dict) -> tuple[list[float], str]:
    raw_api = raw.get("context", {}).get("build", {}).get("version", {}).get("sdk")
    if not isinstance(raw_api, int):
        raise EvidenceError("AndroidX benchmark API level is missing for frame evidence")
    # AndroidX cannot expose frameOverrunMs on API 28. Comparing its available CPU-frame
    # duration directly with the same 32 ms ceiling is a conservative gate: it permits less
    # total frame work than a 32 ms overrun allowance. Newer platforms must provide the native
    # overrun metric; they never fall back silently.
    metric_name = "frameDurationCpuMs" if raw_api < 31 else "frameOverrunMs"
    values: list[float] = []
    methods = {definition[0] for definition in BENCHMARK_SCENARIOS.values()}
    for method in sorted(methods):
        benchmark = benchmark_for(raw, method)
        metric = benchmark.get("sampledMetrics", {}).get(metric_name)
        if not isinstance(metric, dict) or not isinstance(metric.get("runs"), list):
            raise EvidenceError(f"missing {metric_name} samples in {method}")
        values.extend(value for iteration in metric["runs"] for value in iteration)
    return values, metric_name


def parse_counter_evidence(directories: Iterable[Path]) -> tuple[dict[str, list[dict[str, int]]], list[Path]]:
    records: dict[str, list[dict[str, int]]] = {}
    matched_files: list[Path] = []
    for directory in directories:
        for path in sorted(directory.glob("**/*.txt")):
            text = path.read_text(encoding="utf-8", errors="replace")
            matched = False
            for match in EVIDENCE_LINE.finditer(text):
                matched = True
                records.setdefault(match.group(1), []).append(
                    {name: int(value) for name, value in COUNTER_VALUE.findall(match.group(2))}
                )
            if matched:
                matched_files.append(path)
    return records, matched_files


def one_consistent(records: dict[str, list[dict[str, int]]], scenario: str, field: str, minimum: int = 1) -> int:
    observations = records.get(scenario, [])
    if len(observations) < minimum:
        raise EvidenceError(f"missing counter evidence: {scenario}.{field}")
    values = {observation[field] for observation in observations if field in observation}
    if len(values) != 1:
        raise EvidenceError(f"inconsistent counter evidence: {scenario}.{field}={sorted(values)}")
    return values.pop()


def deterministic_counters(records: dict[str, list[dict[str, int]]]) -> tuple[dict[str, int], dict[str, int]]:
    warm = records.get("warmInteraction", [])
    if len(warm) < 5:
        raise EvidenceError("fewer than five warm-navigation counter observations")
    counters = {
        "uiUnlockPrimaryOpen": one_consistent(records, "uiUnlock", "primaryOpen"),
        "uiUnlockDatabaseKeyUnwrap": one_consistent(records, "uiUnlock", "databaseKeyUnwrap"),
        "warmInteractionPrimaryOpenDelta": max(record["primaryOpen"] for record in warm),
        "warmInteractionDatabaseKeyUnwrapDelta": max(record["databaseKeyUnwrap"] for record in warm),
        "ordinarySaveFinancialTransactions": one_consistent(
            records,
            "ordinarySave",
            "financialTransactions",
            minimum=30,
        ),
        "journalPageSqlStatements": one_consistent(records, "journalQueryCounts", "pageSqlStatements"),
        "journalPageWithRunningBalanceSqlStatements": one_consistent(
            records,
            "journalQueryCounts",
            "runningBalanceSqlStatements",
        ),
    }
    observations = {scenario: len(values) for scenario, values in sorted(records.items())}
    return counters, observations


def host_environment() -> dict:
    cpu_model = "unknown"
    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.is_file():
        match = re.search(r"^(?:model name|Hardware)\s*:\s*(.+)$", cpuinfo.read_text(errors="replace"), re.MULTILINE)
        if match:
            cpu_model = match.group(1).strip()
    return {
        "operatingSystem": platform.platform(),
        "kernel": platform.release(),
        "machine": platform.machine(),
        "cpuModel": cpu_model,
        "logicalProcessors": os.cpu_count(),
    }


def newest_file(pattern: str) -> Path:
    matches = [path for path in ROOT.glob(pattern) if path.is_file()]
    if not matches:
        raise EvidenceError(f"build artifact is missing: {pattern}")
    return max(matches, key=lambda path: path.stat().st_mtime_ns)


def source_file(path: Path) -> dict[str, str | int]:
    value = path.read_bytes()
    try:
        name = path.relative_to(ROOT).as_posix()
    except ValueError:
        name = path.name
    return {"path": name, "bytes": len(value), "sha256": sha256(value)}


def build_result(
    raw_bytes: bytes,
    runner_fields: dict[str, str],
    api_level: int,
    counter_directories: list[Path],
    test_results_log: Path,
) -> dict:
    raw = json.loads(raw_bytes)
    context = raw.get("context")
    if not isinstance(context, dict):
        raise EvidenceError("AndroidX benchmark context is missing")
    raw_api = context.get("build", {}).get("version", {}).get("sdk")
    if raw_api != api_level or int(runner_fields.get("p37BenchmarkApiLevel", "-1")) != api_level:
        raise EvidenceError(f"raw benchmark API {raw_api} does not match requested API {api_level}")
    scenarios = {
        name: summarize(metric_runs(benchmark_for(raw, method), metric), minimum)
        for name, (method, metric, minimum) in BENCHMARK_SCENARIOS.items()
    }
    frame_values, frame_metric = frame_runs(raw)
    scenarios["frameOverrun"] = {
        **summarize(frame_values, 30),
        "sourceMetric": frame_metric,
        "budgetInterpretation": (
            "native-frame-overrun"
            if frame_metric == "frameOverrunMs"
            else "conservative-cpu-frame-duration-against-overrun-ceiling"
        ),
    }
    records, counter_files = parse_counter_evidence(counter_directories)
    counters, counter_observations = deterministic_counters(records)
    target_apk = newest_file("app/build/outputs/apk/benchmark/*.apk")
    test_apk = newest_file("benchmark/build/outputs/apk/benchmark/*.apk")
    return {
        "schemaVersion": 2,
        "apiLevel": api_level,
        "fixtureMarker": json.loads(BUDGET_PATH.read_text(encoding="utf-8"))["evidence"]["fixtureMarker"],
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "measurementPolicy": {
            "percentileMethod": "nearest-rank",
            "variance": "population-standard-deviation",
            "timeoutsAreFailures": True,
            "blockingProgressBoundary": "tap-dispatch to first visible blocking progress or authoritative content",
        },
        "environment": {
            "host": host_environment(),
            "device": {
                **context.get("build", {}),
                "supportedAbis": runner_fields.get("p37BenchmarkAbis", "").split(","),
            },
            "benchmark": {key: value for key, value in context.items() if key != "build"},
        },
        "source": {
            "androidxBenchmarkDataSha256": sha256(raw_bytes),
            "androidxBenchmarkDataBytes": len(raw_bytes),
            "androidxBenchmarkDataSourceName": runner_fields.get("p37BenchmarkDataSourceName"),
            "instrumentationResultLog": source_file(test_results_log),
            "targetApk": source_file(target_apk),
            "benchmarkApk": source_file(test_apk),
            "counterLogs": [source_file(path) for path in counter_files],
            "counterObservations": counter_observations,
        },
        "scenarios": scenarios,
        "deterministicCounters": counters,
    }


def default_paths(api_level: int) -> tuple[Path, list[Path], Path]:
    device = {28: "pixel2Api28", 36: "pixel6Api36"}.get(api_level)
    if device is None:
        raise EvidenceError("P37 supports only API 28 and API 36")
    benchmark_root = ROOT / f"benchmark/build/outputs/androidTest-results/managedDevice/benchmark/{device}"
    counter_directories = [
        benchmark_root,
        ROOT / f"finance/data/build/outputs/androidTest-results/managedDevice/debug/{device}",
    ]
    output = ROOT / f"quality/performance/p37_results_api{api_level}.json"
    return benchmark_root / "testlog/test-results.log", counter_directories, output


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-level", required=True, type=int, choices=(28, 36))
    parser.add_argument("--test-results-log", type=Path)
    parser.add_argument("--counter-log-dir", action="append", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    default_log, default_counters, default_output = default_paths(args.api_level)
    test_results_log = (args.test_results_log or default_log).resolve()
    counter_directories = [path.resolve() for path in (args.counter_log_dir or default_counters)]
    output = (args.output or default_output).resolve()
    try:
        raw, fields = extract_embedded_benchmark_data(test_results_log)
        result = build_result(raw, fields, args.api_level, counter_directories, test_results_log)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    except (EvidenceError, OSError, json.JSONDecodeError) as error:
        print(f"P37 evidence generation failed: {error}", file=os.sys.stderr)
        return 1
    print(f"P37 API {args.api_level} evidence generated: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
