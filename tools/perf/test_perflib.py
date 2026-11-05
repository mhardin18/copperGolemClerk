"""Tests for perflib (no server needed): python3 -I -m unittest discover -s tools/perf"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import perflib as L  # noqa: E402

MSPT = ("§6Server tick times §e(§7avg§e/§7min§e/§7max§e)§6 from last 5s§7,§6 10s§7,§6 1m§e:\n"
        "§6◴ §a0.7§7/§a0.4§7/§a9.0§e, §a1.7§7/§a0.4§7/§a9.0§e, §a2.7§7/§a0.4§7/§a19.0")


class Parsing(unittest.TestCase):
    def test_mspt_takes_the_5_second_window(self):
        self.assertEqual(L.parse_mspt(MSPT), (0.7, 0.4, 9.0))

    def test_mspt_whole_numbers_and_garbage(self):
        self.assertEqual(L.parse_mspt("◴ 12/3/40, 1/1/1"), (12.0, 3.0, 40.0))
        self.assertIsNone(L.parse_mspt("Unknown command"))

    def test_players(self):
        self.assertEqual(L.parse_players("There are 0 of a max of 20 players online: "), 0)
        self.assertEqual(L.parse_players("§6There are §c3§6 of a max of 20 players online: a, b, c"), 3)
        self.assertIsNone(L.parse_players(""))


class Statistics(unittest.TestCase):
    def test_summary(self):
        s = L.summarize([1, 2, 3, 4, None, 100])
        self.assertEqual((s["n"], s["median"], s["p95"]), (5, 3, 100))

    def test_empty_summary(self):
        self.assertEqual(L.summarize([None])["median"], None)

    def test_slope_per_hour(self):
        times = [0, 1800, 3600]
        self.assertAlmostEqual(L.slope_per_hour(times, [10, 11, 12]), 2.0)
        self.assertIsNone(L.slope_per_hour([0, 1], [1, 2]), "too few points")

    def test_noise(self):
        cool = {"temp_c": 50, "mhz_top": 3492, "survival_players": 0, "survival_mspt": 1}
        hot = dict(cool, temp_c=95, mhz_top=2900)
        self.assertEqual(L.noise([cool] * 10), [])
        self.assertEqual(L.noise([cool] * 9 + [hot]), [], "one hot sample in ten is fine")
        reasons = L.noise([cool] * 5 + [hot] * 5)
        self.assertEqual(len(reasons), 2, reasons)
        self.assertTrue(L.noise([dict(cool, survival_players=2)] * 3)[0].startswith("players on survival"))
        self.assertEqual(L.noise([]), ["no samples"])
        self.assertEqual(L.noise([dict(cool, mhz_top=1600)] * 5), [], "a cool CPU idling slow is fine")


class Layout(unittest.TestCase):
    def test_units(self):
        units = L.unit_positions()
        self.assertEqual(len(units), 210)
        self.assertEqual(len(set(units)), 210)
        x1, z1, x2, z2 = L.unit_bounds(margin=0)
        self.assertLessEqual((x2 - x1 + 1) * (z2 - z1 + 1) * 7, 32768, "one /fill clears the units' area")

    def test_spread(self):
        units = L.unit_positions()
        self.assertEqual(L.spread(units, 0), [])
        self.assertEqual(len(set(L.spread(units, 20))), 20)
        self.assertEqual(L.spread(units, 500), units)

    def test_filler_reaches_128(self):
        pos = L.filler_positions()
        self.assertEqual(len(pos), 43 * 43)
        self.assertEqual(max(abs(x - L.CX) for x, _ in pos), 126)

    def test_forceload_strips_stay_under_the_limit_and_cover_everything(self):
        strips = L.forceload_strips(L.FAR + 16)
        chunks = set()
        for x1, z1, x2, z2 in strips:
            area = {(cx, cz) for cx in range(x1 >> 4, (x2 >> 4) + 1) for cz in range(z1 >> 4, (z2 >> 4) + 1)}
            self.assertLessEqual(len(area), 256)
            chunks |= area
        want = {(x >> 4, z >> 4) for x, z in L.chunk_centres(L.FAR + 16)}
        self.assertEqual(chunks, want)


OPTS = {"reps": 2, "warmup": 60, "measure": 180, "counts": [0, 20, 50, 100, 150, 200], "modes": ["plugin", "vanilla"],
        "distances": [32, 64, 96, 128], "cauldron_distances": [32, 128], "search_golems": 20,
        "soak_golems": 100, "soak_minutes": 60}


class Planning(unittest.TestCase):
    def test_all(self):
        points = L.plan(["baseline", "golems", "search", "cauldrons", "chests"], OPTS)
        self.assertEqual(len(points), 1 + 12 + 5 + 4 + 1)
        minutes = L.estimate_seconds(points, OPTS) / 60
        self.assertTrue(150 < minutes < 240, minutes)

    def test_soak(self):
        points = L.plan(["soak"], OPTS)
        self.assertEqual(points[0]["params"], {"golems": 100, "minutes": 60})
        self.assertTrue(60 < L.estimate_seconds(points, OPTS) / 60 < 65)

    def test_unknown(self):
        with self.assertRaises(ValueError):
            L.plan(["nonsense"], OPTS)

    def test_compare_flags_only_clear_slowdowns(self):
        def pt(n, median):
            return {"test": "golems", "mode": "plugin", "params": {"golems": n}, "summary": {"median": median}}
        old = [pt(20, 4.0), pt(100, 10.0), pt(200, 20.0)]
        new = [pt(20, 4.4), pt(100, 11.5), pt(200, 21.0), pt(150, 9.0)]
        rows = {r[0]: r for r in L.compare(old, new)}
        self.assertFalse(rows["golems [plugin] golems 20"][4], "+10% but only 0.4 ms")
        self.assertTrue(rows["golems [plugin] golems 100"][4], "+15% and 1.5 ms")
        self.assertFalse(rows["golems [plugin] golems 200"][4], "+5%")
        self.assertNotIn("golems [plugin] golems 150", rows, "not in the old run")


if __name__ == "__main__":
    unittest.main()
