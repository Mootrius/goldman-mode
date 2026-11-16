#!/usr/bin/env python3
"""Set the collection log to pay TARGET_TOTAL in all.

Fixed amounts (gp): every pet 5m except Beef (1m), every "3rd age" item 10m, the gilded armour and tools 5m, and the items in
tools/clog-new-items.json (added in November: Fractured Archive, Last Man Standing, shared clue rewards, Misc ... each priced by hand).
The new items are added on top: TARGET_TOTAL below is the original billion plus their total.
Everything else is the existing payouts.json "clog" amount scaled by one factor, so the relative weights set by
tools/apply_boss_tiers.py (tier pools, rarity curve) are kept, and the total lands on TARGET_TOTAL (to the nearest 100 gp).
Safe to re-run: it only rescales the non-fixed items to whatever is left of the target.

Usage: python3 tools/scale_clog.py [--write]
"""
import json, os, sys

here = os.path.dirname(os.path.abspath(__file__))
NEW_ITEMS = json.load(open(os.path.join(here, "clog-new-items.json")))
TARGET_TOTAL = 1_000_000_000 + sum(NEW_ITEMS.values())
PET_GP, BEEF_GP, THIRD_AGE_GP, GILDED_GP = 5_000_000, 1_000_000, 10_000_000, 5_000_000
PETS = """abyssal orphan|ikkle hydra|moxi|nid|beef|callisto cub|hellpuppy|pet chaos elemental|pet zilyana|pet dark core
|pet dagannoth prime|pet dagannoth supreme|pet dagannoth rex|dom|baron|smol heredit|pet general graardor|baby mole|noon
|kalphite princess|prince black dragon|pet kraken|pet kree'arra|pet k'ril tsutsaroth|maggot marquess|nexling|tzrek-jad
|youngllef|huberte|jal-nib-rek|lil'viathan|aggy|little nightmare|muphin|bran|sraracha|scorpia's offspring|scurry|gull
|skotos|tiny tempor|pet smoke devil|wisp|butch|venenatis spiderling|vet'ion jr.|vorki|phoenix|yami|smolcano|pet snakeling
|olmlet|lil' zik|tumeken's guardian|bloodhound|pet penance queen|abyssal protector|lil' creator|chompy chick|heron
|orion|rock golem|beaver|baby chinchompa|giant squirrel|tangleroot|rocky|rift guardian|herbi|quetzin|soup|mr mcgroot|kruk jr""".replace("\n", "").split("|")
# gilded armour/tools only; the gilded smile flag is not part of the set
GILDED = {"gilded platebody", "gilded platelegs", "gilded plateskirt", "gilded full helm", "gilded kiteshield", "gilded med helm",
          "gilded chainbody", "gilded sq shield", "gilded 2h sword", "gilded spear", "gilded hasta", "gilded scimitar", "gilded boots",
          "gilded coif", "gilded d'hide vambraces", "gilded d'hide body", "gilded d'hide chaps", "gilded pickaxe", "gilded axe", "gilded spade"}

path = os.path.join(here, "..", "src", "main", "resources", "com", "goldmanmode", "payouts.json")
d = json.load(open(path))
clog = d["clog"]
for n, v in NEW_ITEMS.items():
    clog.setdefault(n, v)


def fixed(name):
    if name in NEW_ITEMS:
        return NEW_ITEMS[name]
    if name == "beef":
        return BEEF_GP
    if name in PETS:
        return PET_GP
    if "3rd age" in name:
        return THIRD_AGE_GP
    if name in GILDED:
        return GILDED_GP
    return None


missing = [p for p in PETS if p not in clog]
assert not missing, missing
fx = {n: fixed(n) for n in clog if fixed(n) is not None}
rest = {n: v for n, v in clog.items() if n not in fx}
fixed_total = sum(fx.values())
k = (TARGET_TOTAL - fixed_total) / sum(rest.values())
new = dict(clog)
new.update(fx)
for n, v in rest.items():
    new[n] = max(100, int(round(v * k / 100.0)) * 100)
diff = TARGET_TOTAL - sum(new.values())          # rounding leftovers: put them on the biggest paid items, 100 gp at a time
big = sorted(rest, key=lambda n: -new[n])
i = 0
while diff != 0:
    step = 100 if diff > 0 else -100
    new[big[i % len(big)]] += step
    diff -= step
    i += 1
print("pets %d (beef 1m)  3rd age %d  gilded %d  fixed total %s  other items %d  factor x%.4f"
      % (sum(1 for n in fx if n in PETS), sum(1 for n in fx if '3rd age' in n), sum(1 for n in fx if n in GILDED), format(fixed_total, ","), len(rest), k))
print("total before %s  after %s" % (format(sum(clog.values()), ","), format(sum(new.values()), ",")))
if "--write" in sys.argv:
    d["clog"] = new
    json.dump(d, open(path, "w"), separators=(",", ":"), ensure_ascii=False)
    print("wrote payouts.json")
