#!/usr/bin/env python3
"""Goldman Mode area pricing.

Reads tools/area-factors.json (size in tiles + the factors entered on the Goldman Area Draw page)
and writes a "price" into src/main/resources/com/goldmanmode/areas.json.

  price = k * (size^SIZE_EXP * (1 + score/SCORE_DIV) * (1 - DANGER_DISC * danger)
               * (1 + DIST_A * (dist/DIST_SCALE)^DIST_P)) ^ PRICE_EXP
  dist  = tiles from the area's label tile to the nearest hub (HUBS below; stored in area-factors.json)
  score = sum(feature weights) + USEFUL_W * useful + EARNING_W * earning

Pricing runs in three stages. (1) k is solved so the formula prices add up to REFERENCE_TOTAL; that is the "base curve".
(2) Areas named "...Guild..." (not "Outer ...") are very useful: together they get GUILD_FACTOR x their base money, shared out
with a flatter curve (GUILD_EXP) so the cheap guilds are not next to free. (3) A progressive trim takes
a share off every price, a bigger share off dearer areas (price / (1 + TRIM * (price / TRIM_PIVOT) ^ TRIM_EXP)), with TRIM solved so
the final prices add up to TARGET_TOTAL in play. Cheap areas lose little, dear ones lose the most. An optional "override" in area-factors.json is a
fixed price as the player pays it at the default multiplier. Wilderness areas get WILDERNESS_FACTOR and each hub costs HUB_RATIO x the median price of its nearest neighbours. Free areas
cost 0. Prices round to 100 gp. The plugin's "Price multiplier (%)" setting scales the file prices (default 100).

Usage: python3 tools/price_areas.py [--write]
"""
import json, math, os, statistics, sys

TARGET_TOTAL = 300_000_000      # what all the areas add up to in play (at the default multiplier)
REFERENCE_TOTAL = 500_000_000   # the pool the base curve (stage 1) is calibrated to, before the guild bump and the trim
GUILD_FACTOR = 2.0              # together, the guilds cost this many times their base-curve prices ("Outer ..." areas are not guilds)
GUILD_EXP = 0.5                 # how the guilds share that money: 1 = in proportion to base price, 0 = equally. Lower = cheap guilds
                                # (Champions', Cooks') cost more and the dear ones (Farming, Woodcutting) cost less than a flat x2
TRIM_PIVOT = 1_000_000          # a base price of this much gets trimmed by TRIM
TRIM_EXP = 0.8                  # how fast the trim grows with price (0 = flat share, 1 = proportional to price)
PLUGIN_MULTIPLIER = 100  # must match the "Price multiplier (%)" default in GoldmanModeConfig
WILDERNESS_FACTOR = 0.5  # areas named "Wilderness ..." cost this share of the formula price
MIN_PRICE = 100
PRICE_EXP = 1.4          # >1 stretches the formula: the dearer an area scores, the faster its price climbs
SIZE_EXP = 0.85
SCORE_DIV = 10.0
USEFUL_W = 2.0
EARNING_W = 1.5
DANGER_DISC = 0.06      # each danger point takes 6% off (danger 5 = -30%)
DIST_A = 3.0            # distance from nearest hub: x1 at a hub, x4 at 1000 tiles, x13 at 2000
DIST_P = 2.0            # curve steepness (1 = straight line, 2 = quadratic)
DIST_SCALE = 1000.0
# "Ground zero" areas for distance: dist in area-factors.json is tiles from an area's label tile to the
# nearest of these (the "hub" field says which).
HUB_RATIO = 0.3     # a hub costs this share of the median price of its HUB_NEIGHBOURS nearest paid areas
HUB_NEIGHBOURS = 10
HUBS = ["Lumbridge Castle", "East Ardougne", "Kingstown", "Civitas illa Fortis"]

FEATURE_W = {
    "Bank": 4, "Teleport destination": 2, "Transport hub": 2, "Port / mooring point": 1.5,
    "Altar": 1.5, "General store": 1, "Specialty shop": 1, "Slayer master": 2,
    "Slayer task location": 1, "Minigame entrance": 2, "Boss or raid entrance": 2,
    "Dungeon entrance": 1.5, "Range or furnace": 1.5, "Anvil": 1, "Farming patch": 2,
    "Runecrafting altar": 2, "Skilling method": 1, "Skilling facilities": 1.5,
    "Clue step(s)": 0.5, "Quest start or step": 0.5, "PoH portal": 1.5,
}

here = os.path.dirname(os.path.abspath(__file__))
areas_path = os.path.join(here, "..", "src", "main", "resources", "com", "goldmanmode", "areas.json")
factors = json.load(open(os.path.join(here, "area-factors.json")))


def weight(raw):
    s = 0.0
    for f in raw.get("features", []):
        if f not in FEATURE_W:
            print("WARNING: no weight for feature", repr(f), "- counted as 1")
        s += FEATURE_W.get(f, 1)
    return s + USEFUL_W * raw.get("useful", 0) + EARNING_W * raw.get("earning", 0)


def shape(raw):
    return (raw["size"] ** SIZE_EXP) * (1 + weight(raw) / SCORE_DIV) * (1 - DANGER_DISC * raw.get("danger", 0)) \
        * (1 + DIST_A * (raw.get("dist", 0) / DIST_SCALE) ** DIST_P)


def rounded(x):
    return max(MIN_PRICE, int(round(x / 100.0)) * 100)


def hub_neighbours():
    """for each paid hub: ids of its HUB_NEIGHBOURS nearest paid non-hub areas (by label tile)"""
    out = {}
    for h, hr in factors.items():
        if hr["n"] not in HUBS or hr.get("free"):
            continue
        near = sorted((math.hypot(r["x"] - hr["x"], r["y"] - hr["y"]), i) for i, r in factors.items()
                      if r["n"] not in HUBS and not r.get("free") and "override" not in r)
        out[h] = [i for _, i in near[:HUB_NEIGHBOURS]]
    return out


def trimmed(p, trim):
    """p / (1 + trim * (p / TRIM_PIVOT) ^ TRIM_EXP): with TRIM_EXP <= 1 this always keeps the price order (dearer stays dearer)"""
    return p / (1 + trim * (p / TRIM_PIVOT) ** TRIM_EXP)


def prices_for(k, flex, nbrs, trim=0.0):
    out = {i: rounded(trimmed(k * v, trim)) for i, v in flex.items()}
    for h, ids in nbrs.items():
        out[h] = rounded(HUB_RATIO * statistics.median(out[i] for i in ids))
    return out


def price_all():
    """Stage 1 solves k (bisection, because of rounding) so the base curve adds up to REFERENCE_TOTAL;
    stage 3 solves TRIM so the final file prices add up to TARGET_TOTAL / multiplier."""
    flex = {}
    fixed = 0
    for i, r in factors.items():
        if r.get("free") or r["n"] in HUBS:
            continue
        if "override" in r:  # optional fixed price, as the player pays it at the default multiplier
            fixed += int(round(r["override"] * 100.0 / PLUGIN_MULTIPLIER))
        else:
            flex[i] = shape(r) ** PRICE_EXP * (WILDERNESS_FACTOR if r["n"].startswith("Wilderness") else 1.0)
    nbrs = hub_neighbours()
    k = bisect(lambda x: fixed + sum(prices_for(x, flex, nbrs).values()), REFERENCE_TOTAL * 100.0 / PLUGIN_MULTIPLIER, 1e7)
    guilds = [i for i in flex if "Guild" in factors[i]["n"] and not factors[i]["n"].startswith("Outer")]
    if guilds:  # new = S * base^GUILD_EXP, with S chosen so the guilds' total is GUILD_FACTOR x their base total
        base = {i: k * flex[i] for i in guilds}
        scale = GUILD_FACTOR * sum(base.values()) / sum(p ** GUILD_EXP for p in base.values())
        for i in guilds:
            flex[i] = scale * base[i] ** GUILD_EXP / k
    goal = TARGET_TOTAL * 100.0 / PLUGIN_MULTIPLIER
    trim = bisect(lambda t: fixed + sum(prices_for(k, flex, nbrs, t).values()), goal, 1000.0, increasing=False)
    priced = prices_for(k, flex, nbrs, trim)
    out = {}
    for i, r in factors.items():
        if r.get("free"):
            out[i] = 0
        elif "override" in r:
            out[i] = int(round(r["override"] * 100.0 / PLUGIN_MULTIPLIER))
        else:
            out[i] = priced[i]
    return out


def bisect(total_of, goal, hi, increasing=True):
    """the x (0..hi) where total_of(x) crosses goal, from the side where the total is at or under the goal"""
    lo = 0.0
    for _ in range(100):
        x = (lo + hi) / 2
        if (total_of(x) > goal) == increasing:
            hi = x
        else:
            lo = x
    return lo if increasing else hi


if __name__ == "__main__":
    prices = price_all()
    tot = sum(prices.values())
    vals = sorted(v for v in prices.values() if v)
    print("areas %d  free %d  override %d  total %s (x%d%% = %s in play)" % (
        len(prices), sum(1 for v in prices.values() if v == 0), sum(1 for r in factors.values() if "override" in r), format(tot, ","), PLUGIN_MULTIPLIER, format(round(tot * PLUGIN_MULTIPLIER / 100), ",")))
    print("min %s  median %s  max %s" % (format(vals[0], ","), format(vals[len(vals) // 2], ","), format(vals[-1], ",")))
    if "--write" in sys.argv:
        areas = json.load(open(areas_path))
        for a in areas:
            a["price"] = prices[a["id"]]
        json.dump(areas, open(areas_path, "w"), separators=(",", ":"))
        print("wrote", len(areas), "areas")
