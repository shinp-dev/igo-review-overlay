import unittest
from katago_bridge import normalize, to_query


class BridgeTests(unittest.TestCase):
    def test_coordinates_and_orientation(self):
        board = ["B........"] + ["........."] * 7 + ["........W"]
        q, limit = to_query({"board": board, "toPlay": "W",
                             "maxVisits": 100, "candidates": 2}, "test")
        self.assertEqual(q["boardXSize"], 9)
        self.assertEqual(q["initialStones"], [["B", "A9"], ["W", "J1"]])
        self.assertEqual(q["initialPlayer"], "W")
        self.assertEqual(q["komi"], 7)
        self.assertEqual(q["moves"], [])
        self.assertEqual(limit, 2)

    def test_invalid_board(self):
        with self.assertRaises(ValueError):
            to_query({"board": ["........."] * 8, "toPlay": "B"}, "test")

    def test_perspective_conversion(self):
        result = {
            "rootInfo": {"winrate": .75, "scoreLead": 3.5},
            "moveInfos": [
                {"move": "C4", "order": 0, "visits": 80,
                 "winrate": .7, "scoreLead": 2},
                {"move": "pass", "order": 1, "winrate": .4},
            ],
        }
        converted = normalize(result, 3, "W", "BLACK")
        self.assertAlmostEqual(converted["rootWinrate"], .25)
        self.assertAlmostEqual(converted["rootScoreLead"], -3.5)
        self.assertEqual(converted["candidates"][0]["move"], "C4")
        self.assertAlmostEqual(converted["candidates"][0]["winrate"], .3)
        self.assertEqual(len(converted["candidates"]), 1)


if __name__ == "__main__":
    unittest.main()
