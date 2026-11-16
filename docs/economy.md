# Prices and payouts

How the numbers in Goldman Mode are worked out. The data is in `src/main/resources/com/goldmanmode/` (`areas.json`, `payouts.json`) and the scripts that generate it are in `tools/`.

## Area prices

Each area's price comes from the areas file, worked out from its size, what is in it (bank, teleport, shops, altars...), usefulness, danger, earning potential and how far it is from the nearest of four "ground zero" areas (Lumbridge Castle, East Ardougne, Kingstown and Civitas illa Fortis): the further away, the steeper the price.

* The four ground-zero areas cost 30% of the median price of the 10 areas nearest them.
* Wilderness areas cost half the formula price.
* Guilds (areas with "Guild" in the name, except the "Outer ..." ones) cost double their formula price in total, shared out so the cheap guilds are not next to free.
* All areas add up to 300m: the curve is steep, then a progressive trim takes a bigger share off the dearest areas and almost none off the cheapest.
* An area with no price in the file costs its size in tiles x 65 gp.

See `tools/price_areas.py` for the formula. The *Area costs* setting multiplies every price (rounded to the nearest 100 gp).

## Payouts

* **Experience**: 1 gp per xp (`Settings.XP_PER_GP`). Anything that doesn't divide evenly is rounded to the nearest gp with the leftover carried over, so small drops are never lost.
* **Skill levels**: every level gained pays the xp it took to get there from the level before (83 gp for level 2, 1,228,825 for level 99; 13,034,431 per skill in total). Virtual levels past 99 pay no level bonus, but the xp keeps paying.
* **Combat levels**: double the "Level Bonus" table (2,200 gp for combat 4, up to 6,455,600 for combat 126; 100,006,800 in total).
* **Kills**: 10 gp per combat level of an NPC you hit and killed (`Settings.GP_PER_KILL_LEVEL`). NPCs with no combat level pay nothing.
* **Quests**: double the Quests sheet (100,390,000 gp for all 189), paid when the quest complete scroll opens.
* **Achievement diaries**: each task pays the Diaries sheet's amount as it is finished, and each tier pays a bonus when finished. Both are double the sheet (about 200m in total).
* **Combat achievements**: each task pays its amount (from the "you've completed a ... combat task" message), and a tier's bonus is paid when you unlock the tier. The bonuses run from 1,000,000 (easy) to 100,000,000 (grandmaster), curved between (165,480,000 in total; see `tools/combat_bonuses.py`).
* **Collection log**: 1,027,044,600 gp for all 1,735 items. Every pet pays 5m (Beef 1m), every 3rd age item 10m and the gilded armour and tools 5m (`tools/scale_clog.py`). The rest share the remainder: each boss's items share a pool set by its tier (`tools/clog-boss-tiers.json`), the Slayer items share a 5m pool, three items are fixed by hand (`tools/clog-item-overrides.json`), and in every non-clue pool a drop 10x rarer pays about 7x more (weight = rarity ^ 0.845, in `tools/apply_boss_tiers.py`). The 131 items added in November are priced by hand in `tools/clog-new-items.json`.

Each *Payout multipliers* setting multiplies its payout (rounded to the nearest gp; the Experience one changes how much xp a gp costs). A multiplier is capped at 1000, and 0 stops that payout. Something that would pay 0 is still marked as done, so it can't pay later if you turn the multiplier back up.

Quests or items that aren't in the sheets are written to the RuneLite log and pay nothing. Every payout writes a chat line ("+N gp for Mining xp"); xp lines are filtered game messages, so they show only when the chat Game tab is set to All.
