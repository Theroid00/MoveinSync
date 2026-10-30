#!/usr/bin/env python3
"""Generate the benchmark report from saved measurements, never from supplied estimates."""
import datetime as dt
import json
import math
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "bench/results"


def percentile(values, q):
    values = sorted(values)
    if not values:
        raise ValueError("no measurements for percentile")
    pos = (len(values) - 1) * q
    lo, hi = math.floor(pos), math.ceil(pos)
    return values[lo] + (values[hi] - values[lo]) * (pos - lo)


def metric(summary, name):
    value = summary["metrics"][name]
    return value.get("values", value)


def histogram_quantile(text, q):
    buckets = []
    for line in text.splitlines():
        if line.startswith("outbox_delivery_lag_seconds_bucket{"):
            upper = float(line.split('le="', 1)[1].split('"', 1)[0])
            buckets.append((upper, float(line.rsplit(" ", 1)[1])))
    buckets.sort()
    if not buckets or buckets[-1][1] == 0:
        return None
    target = buckets[-1][1] * q
    previous_upper = previous_count = 0.0
    for upper, count in buckets:
        if count >= target:
            if math.isinf(upper):
                return previous_upper
            return previous_upper + (upper - previous_upper) * (target - previous_count) / (count - previous_count)
        previous_upper, previous_count = upper, count
    raise ValueError("invalid histogram")


def main():
    # These boundary checks protect the report's two interpolation calculations.
    assert percentile([1, 3, 5, 7], .5) == 4
    assert percentile([5], .99) == 5
    assert histogram_quantile('outbox_delivery_lag_seconds_bucket{le="1"} 5\n'
                              'outbox_delivery_lag_seconds_bucket{le="2"} 10\n'
                              'outbox_delivery_lag_seconds_bucket{le="+Inf"} 10', .75) == 1.5
    warmups = json.loads((RESULTS / "recon-initial.json").read_text())["warmup_runs"]
    protocol = "no warm-up (user-requested protocol)" if warmups == 0 else f"{warmups} warm-up run"
    lines = ["# Benchmark results", "", "## Machine", "", "```text",
             (RESULTS / "machine.txt").read_text().strip(), "```", "",
             "## Reconciliation", "",
             f"200,000 baseline rows, fixed seed 42, three measured runs per scenario with {protocol}. "
             "Each iteration restores its baseline outside the timing. Java heap limit: 256 MiB. "
             "Exact mutation counts and the typical/heavy 60-second bound are assertions.", "",
             "| Scenario | Total ms median (min–max) | Digest + assess + apply ms median (min–max) | Rows/s median | Peak heap MiB (max) | Mismatched buckets median | Outbox rows |",
             "|---|---:|---:|---:|---:|---:|---:|"]

    def median_range(values):
        return f"{statistics.median(values):,.0f} ({min(values):,.0f}–{max(values):,.0f})"

    for scenario in ("initial", "typical", "heavy", "quiet", "cut", "invalid"):
        data = json.loads((RESULTS / f"recon-{scenario}.json").read_text())
        runs = data["measured_runs"]
        assert len(runs) == 3 and data["warmup_runs"] == warmups and data["heap_limit_bytes"] <= 256 * 1024**2
        totals = [r["total_ms"] for r in runs]
        diff = [sum(r["phase_ms"].get(p, 0) for p in ("digest", "assess", "apply")) for r in runs]
        if scenario in ("typical", "heavy"):
            assert max(diff) < 60_000
        lines.append(f"| {scenario} | {median_range(totals)} | {median_range(diff)} | "
                     f"{statistics.median(r['rows_per_second'] for r in runs):,.0f} | "
                     f"{max(r['peak_heap_bytes'] for r in runs) / 1024**2:.1f} | "
                     f"{statistics.median(r['buckets_mismatched'] for r in runs):,.0f} | {runs[0]['outbox_rows']:,} |")
    lines += ["", "Reconciliation database: " + data["postgres_version"] + ". "
              "Its disposable Testcontainers server uses max_connections=400; the API machine section records the Compose server settings.", "",
              "Phase timings and exact mutation counts are in `bench/results/recon-*.json`. "
              "Heap is sampled every 100 ms; it is sampled Java heap usage, not process RSS or database memory.", "",
              "## API load", "",
              "k6 constant arrival rate, 60 seconds per rate, 200 initial VUs / 1,000 maximum. "
              "Payload modes: 50% new, 46% updates over 10,000 seeded IDs, 2% exact duplicates, 2% older versions. "
              "Latency uses the measured ingest requests; achieved rate is completed iterations / 60 seconds, excluding setup. "
              "Thresholds are reported, not enforced.", "",
              "| Target /s | Achieved /s | p50 ms | p95 ms | p99 ms | p99.9 ms | Errors % | Dropped iterations |",
              "|---:|---:|---:|---:|---:|---:|---:|---:|"]
    knee = None
    for rate in (100, 250, 500, 1000):
        data = json.loads((RESULTS / f"k6-{rate}.json").read_text())
        latency, iterations, checks = (metric(data, n) for n in ("ingest_ms", "iterations", "checks"))
        error = 100 * checks.get("fails", 0) / max(1, checks.get("passes", 0) + checks.get("fails", 0))
        dropped = metric(data, "dropped_iterations")["count"] if "dropped_iterations" in data["metrics"] else 0
        if knee is None and latency["p(99)"] > 100:
            knee = rate
        lines.append(f"| {rate} | {iterations['count'] / 60:.1f} | " + " | ".join(f"{latency[p]:.2f}" for p in ("p(50)", "p(95)", "p(99)", "p(99.9)"))
                     + f" | {error:.2f} | {dropped:,.0f} |")
    lines += ["", f"First tested rate where p99 exceeds 100 ms: **{str(knee) + '/s' if knee else 'not reached'}**.", "",
              "## Contention", ""]
    raw_path, compact = ROOT / "bench/tmp/contention.jsonl", RESULTS / "contention-requests.jsonl"
    if raw_path.exists():
        with raw_path.open() as raw, compact.open("w") as out:
            for line in raw:
                item = json.loads(line)
                if item.get("type") == "Point" and item.get("metric") == "ingest_ms":
                    point = item["data"]
                    # Keep only timestamp, latency and HTTP status; raw k6 output is disposable.
                    sample = {"time": point["time"], "ms": point["value"], "status": point["tags"]["status"]}
                    out.write(json.dumps(sample, separators=(",", ":")) + "\n")
    samples = []
    for line in compact.read_text().splitlines():
        point = json.loads(line)
        end = dt.datetime.fromisoformat(point["time"].replace("Z", "+00:00")).timestamp()
        samples.append((end - point["ms"] / 1000, end, point["ms"]))
    observations = [line.split(",") for line in (RESULTS / "contention-lock.csv").read_text().splitlines() if line.strip()]
    held = [float(t) for t, state in observations if state == "true"]
    assert held, "exclusive tenant lock was not observed; repeat the contention run"
    start, last = min(held), max(held)
    end = next((float(t) for t, state in observations if float(t) > last and state == "false"), last)
    during = [value for a, b, value in samples if a <= end and b >= start]
    assert during, "no ingest request overlaps the observed apply window"
    lock_stats = {"observed_window_start_epoch": start, "observed_window_end_epoch": end,
                  "overlapping_requests": len(during), "overlapping_request_p99_ms": percentile(during, .99)}
    (RESULTS / "contention-window.json").write_text(json.dumps(lock_stats, indent=2) + "\n")
    lines += [f"At 250/s, {len(during):,} requests overlapped the observed exclusive-lock window; "
              f"their p99 was **{percentile(during, .99):.2f} ms**. The lock was sampled via `pg_locks` every ~200 ms "
              "plus Docker/SQL overhead; requests are included when any part overlaps the sampled window. "
              "`contention-run.json` records the completed run, counts and phase timings. "
              "`prometheus-contention.txt` records `roster_reconciliation_lock_seconds`.", "",
              "## Delivery lag", "", "Histogram-interpolated quantiles after each API load run:", "",
              "| API target /s | Delivery p50 s | Delivery p99 s | Pending | Claimed | Oldest pending s |", "|---:|---:|---:|---:|---:|---:|"]
    for rate in (100, 250, 500, 1000):
        text = (RESULTS / f"prometheus-{rate}.txt").read_text()
        quantiles = [histogram_quantile(text, q) for q in (.5, .99)]
        assert all(q is not None for q in quantiles)
        gauges = {line.split()[0]: float(line.split()[1]) for line in text.splitlines()
                  if line.startswith(("outbox_queue_depth{", "outbox_oldest_pending_age_seconds "))}
        pending = gauges['outbox_queue_depth{state="PENDING"}']
        claimed = gauges['outbox_queue_depth{state="CLAIMED"}']
        oldest = gauges['outbox_oldest_pending_age_seconds']
        lines.append(f"| {rate} | {quantiles[0]:.3f} | {quantiles[1]:.3f} | {pending:.0f} | {claimed:.0f} | {oldest:.3f} |")
    lines += ["", "Delivery lag describes events acknowledged by scrape time, including setup events. "
              "A remaining backlog is not represented by delivered-event quantiles; queue depth and oldest pending age "
              "are polled every five seconds and retained in the same scrapes. At 1,000/s the relay builds a backlog even while HTTP ingest stays fast. "
              "Raw inputs are under `bench/results/`.", ""]
    (ROOT / "docs/benchmark.md").write_text("\n".join(lines))


if __name__ == "__main__":
    main()
