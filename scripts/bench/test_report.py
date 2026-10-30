"""Small synthetic fixtures exercise report generation; these are not benchmark measurements."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("report", Path(__file__).with_name("report.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ReportTest(unittest.TestCase):
    def test_report_can_be_rebuilt_from_compact_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report.ROOT, report.RESULTS = root, root / "bench/results"
            report.RESULTS.mkdir(parents=True)
            (root / "docs").mkdir()
            raw_dir = root / "bench/tmp"
            raw_dir.mkdir()
            (report.RESULTS / "machine.txt").write_text("TEST FIXTURE; NOT MEASUREMENTS\n")
            for scenario in ("initial", "typical", "heavy", "quiet", "cut", "invalid"):
                runs = [{"total_ms": i, "phase_ms": {"digest": 1, "assess": 1, "apply": 1},
                         "rows_per_second": 10, "peak_heap_bytes": 1024, "buckets_mismatched": 2, "outbox_rows": 1}
                        for i in (4, 5, 6)]
                (report.RESULTS / f"recon-{scenario}.json").write_text(json.dumps({"measured_runs": runs, "warmup_runs": 0, "heap_limit_bytes": 256 * 1024**2,
                                                                               "postgres_version": "TEST FIXTURE DATABASE"}))
            histogram = ('outbox_delivery_lag_seconds_bucket{le="1"} 5\n'
                         'outbox_delivery_lag_seconds_bucket{le="2"} 10\n'
                         'outbox_delivery_lag_seconds_bucket{le="+Inf"} 10\n'
                         'outbox_queue_depth{state="PENDING"} 12\n'
                         'outbox_queue_depth{state="CLAIMED"} 2\n'
                         'outbox_oldest_pending_age_seconds 3\n')
            for rate in (100, 250, 500, 1000):
                summary = {"metrics": {"ingest_ms": {p: rate for p in ("p(50)", "p(95)", "p(99)", "p(99.9)")},
                                       "iterations": {"count": 6000, "rate": 80}, "checks": {"passes": 6000, "fails": 0}}}
                (report.RESULTS / f"k6-{rate}.json").write_text(json.dumps(summary))
                (report.RESULTS / f"prometheus-{rate}.txt").write_text(histogram)
            (raw_dir / "contention.jsonl").write_text(json.dumps({"type": "Point", "metric": "ingest_ms", "data": {
                "time": "2023-11-14T22:13:20.500Z", "value": 100, "tags": {"status": "200"}}}) + "\n")
            (report.RESULTS / "contention-lock.csv").write_text("1700000000,true\n1700000001,false\n")
            report.main()
            first = (root / "docs/benchmark.md").read_text()
            self.assertIn("**250/s**", first)
            self.assertIn("**100.00 ms**", first)
            self.assertIn("| 100 | 100.0 |", first)
            self.assertIn("no warm-up", first)
            self.assertIn("| 12 | 2 | 3.000 |", first)
            (raw_dir / "contention.jsonl").unlink()
            report.main()
            self.assertEqual(first, (root / "docs/benchmark.md").read_text())
            self.assertEqual(report.metric({"metrics": {"x": {"values": {"count": 3}}}}, "x"), {"count": 3})


if __name__ == "__main__":
    unittest.main()
