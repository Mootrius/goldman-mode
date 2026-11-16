#!/usr/bin/env python3
"""Re-pay the collection log boss pools from tiers.

Reads goldman-mode-economy.xlsx (its Collection Log sheet, with the cached values) and tools/clog-boss-tiers.json
({"Boss name": tier 1-5}). Tier pools: 1 = 100k, 2 = 250k, 3 = 750k, 4 = 2m, 5 = 5m Goldman GP.
Each pool's non-pet items share it by rarity weight, as the sheet does but with this script's RARITY_EXP:
  weight = (1 in N) ^ RARITY_EXP;  gp per weight = (pool - fixed payouts) / sum of the weights of the items that pay at that source
  item = ROUND(weight x gp per weight, -2)
Pets and other fixed payouts are unchanged. Bosses without a tier keep their pool. Clue tiers are left as the sheet has them.
Writes the new amounts into src/main/resources/com/goldmanmode/payouts.json ("clog").

Pools set directly in gp (not by tier) are in tools/clog-pool-overrides.json;
single items fixed in gp are in tools/clog-item-overrides.json.

After --write, run tools/scale_clog.py --write: it sets pets, 3rd age and gilded items and scales everything else to a 1,000,000,000 total.

Usage: python3 tools/apply_boss_tiers.py path/to/goldman-mode-economy.xlsx [--write]
"""
import json, os, sys, math
import openpyxl

# rarity weight = (1 in N) ^ RARITY_EXP: a drop 10x rarer pays 10^RARITY_EXP times more (0.35 = 2.2x, 0.845 = 7x)
RARITY_EXP = 0.845
POOLS = {1: 100_000, 2: 250_000, 3: 750_000, 4: 2_000_000, 5: 5_000_000}
here = os.path.dirname(os.path.abspath(__file__))
payouts_path = os.path.join(here, "..", "src", "main", "resources", "com", "goldmanmode", "payouts.json")


def xl_round100(x):  # Excel ROUND(x, -2): halves go away from zero
    return int(math.floor(abs(x) / 100.0 + 0.5)) * 100 * (1 if x >= 0 else -1)


def compute(xlsx, tiers, rarity_exp=None, overrides=None):
    """New clog amounts. Every non-clue pool is re-paid with weight = rarity ^ rarity_exp (the sheet uses 0.35);
    clue tiers keep the sheet's amounts. Boss pools named in tiers use their tier's pool."""
    exp = RARITY_EXP if rarity_exp is None else rarity_exp
    wb = openpyxl.load_workbook(xlsx, data_only=True)
    pools = {r[0]: r[2] for r in wb["Clog Pools"].iter_rows(min_row=5, values_only=True) if r[0]}
    rows = [r for r in wb["Collection Log"].iter_rows(min_row=5, values_only=True) if r[2]]
    for boss, t in tiers.items():
        pools[boss] = POOLS[t]
    pools.update(overrides or {})  # pools set directly in gp, e.g. {"Slayer": 5000000}
    by_pool = {}
    for r in rows:
        if r[12] and r[8] == "yes" and r[0] != "Clues" and r[13]:  # pool slot that pays
            by_pool.setdefault(r[12], []).append(r)
    clog = {}
    for r in rows:
        name = str(r[2]).strip().lower()
        paid = r[9] if isinstance(r[9], (int, float)) else 0
        if r[12] and r[8] == "yes" and r[0] != "Clues" and r[13]:
            ps = by_pool[r[12]]
            fixed = sum(x[11] or 0 for x in ps if x[11])
            weight = sum(x[13] ** exp for x in ps)
            paid = max(100, xl_round100(r[13] ** exp * (pools[r[12]] - fixed) / weight))  # at least 100 gp
        clog[name] = max(clog.get(name, 0), int(round(paid)))
    return clog


if __name__ == "__main__":
    tiers = json.load(open(os.path.join(here, "clog-boss-tiers.json")))
    overrides = json.load(open(os.path.join(here, "clog-pool-overrides.json")))
    clog = compute(sys.argv[1], tiers, overrides=overrides)
    # individual items fixed in gp, applied last (tools/clog-item-overrides.json)
    clog.update(json.load(open(os.path.join(here, "clog-item-overrides.json"))))
    old = json.load(open(payouts_path))["clog"]
    print("items %d  total before %s  after %s" % (len(clog), format(sum(old.values()), ","), format(sum(clog.values()), ",")))
    if "--write" in sys.argv:
        d = json.load(open(payouts_path))
        d["clog"] = clog
        json.dump(d, open(payouts_path, "w"), separators=(",", ":"), ensure_ascii=False)
        print("wrote payouts.json")
