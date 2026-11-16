#!/usr/bin/env python3
"""Combat achievement tier completion bonuses.

Easy pays FIRST and grandmaster pays LAST; the four tiers between sit on a geometric curve (each tier pays the same
multiple of the one before it). Per-task amounts are unchanged. Amounts round to 10,000.
Writes "combat" -> tier -> "bonus" in src/main/resources/com/goldmanmode/payouts.json.

Usage: python3 tools/combat_bonuses.py [--write]
"""
import json, os, sys

FIRST = 1_000_000
LAST = 100_000_000
ROUND_TO = 10_000
TIERS = ["easy", "medium", "hard", "elite", "master", "grandmaster"]

here = os.path.dirname(os.path.abspath(__file__))
path = os.path.join(here, "..", "src", "main", "resources", "com", "goldmanmode", "payouts.json")


def bonuses():
    ratio = (LAST / FIRST) ** (1.0 / (len(TIERS) - 1))
    return {t: int(round(FIRST * ratio ** i / ROUND_TO)) * ROUND_TO for i, t in enumerate(TIERS)}


if __name__ == "__main__":
    d = json.load(open(path))
    new = bonuses()
    for t in TIERS:
        print("%-12s %12s -> %12s" % (t, format(d["combat"][t]["bonus"], ","), format(new[t], ",")))
    print("total", format(sum(new.values()), ","))
    if "--write" in sys.argv:
        for t in TIERS:
            d["combat"][t]["bonus"] = new[t]
        json.dump(d, open(path, "w"), separators=(",", ":"), ensure_ascii=False)
        print("wrote payouts.json")
