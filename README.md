# Goldman Mode (RuneLite plugin, test build)

Map areas stay **dimmed and locked** until you **buy them with Goldman GP** from the plugin's sidebar panel.
This is the first test build: it loads your hand-drawn areas, prices them by size and lets you buy them.
Experience, level-ups, quests, achievement diaries, combat achievements and the collection log all earn GP; the panel also has testing tools to hand yourself GP.

## Run it

Needs a JDK (11 or newer).

```
./gradlew runClient
```

That starts RuneLite in developer mode with the plugin loaded. (Or open the folder in IntelliJ and run
`GoldmanModePluginTest.main`.) Log in, then click the gold **G** button in the right-hand sidebar.

## What it does

* **Glowing red knee-high barrier** drawn in the 3D scene along the edge of every locked area (wherever a locked tile
  meets an unlocked, free or unmapped tile). It fades in as you approach (default: visible within 15 tiles, fading out
  between the 14th and 15th) and gently pulses. Its look is fixed (see *Settings* below). The game's own world map is not dimmed: the Goldman world map replaces it.
  Anything not inside an area (dungeons, underground, unmapped places) is never locked.
  Areas apply to every plane, so the same barrier applies upstairs.
* **Minimap**: locked areas are tinted on the minimap with a red line along their border, turning with the camera. Not shown inside instanced areas.
* **Click blocking** (setting "Block clicks in locked areas", on by default): walking to a tile in a locked area, and
  clicking NPCs, objects, ground items or players that stand in one, does nothing (you get a chat message). Examine,
  inventory and interface clicks still work. Minimap clicks are blocked too. Where a diagonal border cuts across a tile,
  that whole tile counts as locked, so you can't stand half out of bounds. Places outside every area (dungeons,
  unmapped ground) are not blocked.
  A walk to an unlocked tile can still path *through* a locked area.
* **Sidebar panel**: the Goldman GP balance (centred), the testing tools (a dark
  text box, then **+**, **-** and **SET** buttons), and the area list, titled in white with how many areas are unlocked ("3 / 405 areas unlocked"). The list has a dark search box with a magnifying
  glass, a **locked / unlocked** padlock filter (two buttons, either or both on) and a sort bar: **A-Z**, **$$$** (cost) and
  **SIZE**, where the chosen one shows an arrow (up = A to Z / cheapest / smallest first) and clicking it again flips the direction. The list opens showing only unlocked areas, sorted by cost with the cheapest at the top.
  Each area card has a green bar if it is unlocked and a red bar if it is locked, its cost, and (if it is unlocked and not free) a small padlock
  (gray, red when hovered) that locks it again and **refunds its price**. There is no Buy button: you buy areas on the Goldman world map. Tutorial Island
  and Lumbridge Castle are free: their cards say 0 gp and have no padlock.
  The sidebar has no hover tooltips; fields and buttons brighten slightly under the mouse.
* **Price** comes from the areas file, worked out per area from its size, what is in it (bank, teleport, shops,
  altars...), usefulness, danger, earning potential and how far it is from the nearest of four "ground zero" areas
  (Lumbridge Castle, East Ardougne, Kingstown and Civitas illa Fortis): the further away, the steeper the price.
  The four ground-zero areas themselves cost 30% of the median price of the 10 areas nearest them, and Wilderness
  areas cost half the formula price, and the guilds (areas with "Guild" in the name, except the "Outer ..." ones) cost double their formula price in total (they are very useful), shared out so the cheap guilds are not next to free. All areas add up to 300m: the curve is steep, then a progressive trim takes a bigger share off the dearest areas and almost none off the cheapest. See `tools/price_areas.py` for the formula. An area with no price in
  the file costs its size x 65 gp.
* **Free starting areas**: Tutorial Island and Lumbridge Castle.
* **Testing tools**: type an amount (`250000`, `250k`, `1.5m`) and press **+** to add it, **-** to take it away or **SET**
  to make it the balance.
* Progress (balance + unlocked areas) is saved in your RuneLite profile, so it survives restarts.

## Earning GP

* **Experience pays GP.** Every skill, every xp drop, including lamps and quest rewards: **1 gp per xp**
  (`Settings.XP_PER_GP` in the code). Anything that doesn't
  divide evenly is rounded to the nearest whole gp with the leftover carried over, so small drops are never lost. The balance goes up the moment the xp arrives.
* **Level-up bonuses**: every skill level you gain pays the xp it took to get there from the level before ("Xp for this level" on the
  "XP Table" tab of goldman-mode-economy.xlsx: 83 gp for level 2, 1,228,825 for level 99; 13,034,431 per skill in total, the same for every
  skill). Virtual levels past 99 pay no level bonus, but the xp itself keeps paying all the way to 200m. Every combat level pays too, **double** the "Level Bonus" tab (2,200 gp for combat 4, rising to 6,455,600 for combat 126;
  100,006,800 in total). A lamp that jumps several levels pays for each one. Levels you already have when you log in pay nothing.
* **Kills**: killing an NPC you hit pays **10 gp for each of its combat levels** (a level 100 NPC pays 1,000 gp)
  NPCs with no combat level pay nothing. The chat line ("+N gp for killing a Goblin.") is a filtered message like xp.
* **Goldman GP overlay** on the screen (drag it with RuneLite's overlay mode): "Goldman GP" on the first row, the amount (no "gp" after it) centred on the second. The amount ticks up over 0.6 seconds each time it goes up.
* The xp you already have when you log in (or hop) is not paid, only what you gain afterwards.
* **Quests** pay **double** the Quests sheet (100,390,000 gp for all 189) when the quest complete scroll opens.
* **Achievement diary tasks** pay the Diaries sheet's task amount for each task as it is finished (the game's per-area, per-tier task
  count going up), and the tier bonus when you finish that tier in an area. Both are paid **double** the sheet (about 200m for all of them).
* **Combat achievements** pay the task amount for each task (from the "you've completed a ... combat task" message) and the tier bonus
  when your combat achievement points reach the points that unlock that tier. The tier bonuses run from 1,000,000 (easy) to 100,000,000
  (grandmaster), curved between (2.51m, 6.31m, 15.85m, 39.81m; 165,480,000 in total; see `tools/combat_bonuses.py`).
* **Collection log tooltips**: hovering an item in the in-game collection log shows "Pays 38,900 gp" (white "Pays", yellow amount and "gp").
* **Quest tooltips**: hovering a quest in the quest list shows the same "Pays 20,000 gp" line for that quest.
* **Collection log items** pay 1,027,044,600 gp for all 1,735 (the original billion plus 27,044,600 for the 131 items added in November, which are priced by hand in `tools/clog-new-items.json`): every pet 5m (Beef 1m), every 3rd age item 10m and the gilded armour and tools 5m (`tools/scale_clog.py`). The other items keep the relative amounts below, scaled up (about x2.26) to fill the rest of the original billion. Each boss's items share a pool set by its tier (100k to 5m before scaling, in `tools/clog-boss-tiers.json`), the Slayer items share a 5m pool, three items are fixed by hand (enhanced crystal weapon seed, crimson kisten, wyvern visage, in `tools/clog-item-overrides.json`), and in every non-clue pool a drop 10x rarer pays about 7x more
  (weight = rarity ^ 0.845, set in `tools/apply_boss_tiers.py`); the clue tiers keep the sheet's curve for each *new* item. Turn on
  *Collection log - New addition notification* in the game's settings, since the plugin reads that chat message.
* Everything pays once only, and what you already have when you log in pays nothing. Quests or items that aren't on the sheets are
  written to the RuneLite log and pay nothing. Every payout writes a chat line for every payout: quests, diary and combat achievement tasks and tiers, collection log items, skill and combat level-ups, and xp ("+N gp for Mining xp"). Xp lines are sent as filtered game messages, so they show only when the chat Game tab is set to All and are hidden on Filtered.

## Goldman world map

Opened by clicking the **world map icon** next to the minimap (it replaces the game's own map). It is drawn right over the whole game view inside RuneLite, and while it is open it takes the mouse and
keyboard so nothing reaches the game. It fades in and out quickly and closes with `Esc`, and when you log out.

* **First open after logging in** shows the whole world. There are no on-screen buttons, no player marker and no keyboard shortcuts inside the map except `Esc` to close.
* **Smooth zoom and scrolling**: mouse wheel (zooms where the mouse is; zoom speed is 60%, gentler than normal), drag to pan (it stops where you let go).
* **Sharp pixels** when zoomed in close, real higher-detail map pictures as you zoom in.
* **No map icons**: the pictures are the bare map.
* **Locked areas** are outlined and tinted red; hover any area for a tooltip reading "Unlock <area> (<cost> gp)" (price green if you can afford it, red if not), in the RuneScape font.
  **Click a locked area you can afford** to arm it (the tooltip changes to "Confirm unlock <area> (<cost> gp)"), then click it again to buy it. Dragging, moving off the area or clicking something else cancels. Areas you can't afford do nothing.
  Border lines get thinner as you zoom out.
  The hovered area gets a white outline on top of its neighbours (and a white tint if it is unlocked).
  Unlocked areas are outlined in green too.

The map pictures come from the free [mejrs map tiles](https://mejrs.github.io/layers_osrs/) (the same ones your area
drawing page uses). Each picture is downloaded the first time you look at that bit of map and kept in
`~/.runelite/goldman-mode/maptiles/`, so it works offline for anything you have already seen.

## Updating the areas

The 405 areas you have drawn are built in (`src/main/resources/com/goldmanmode/areas.json`).
To test newer ones without rebuilding, export them from the drawing page in the same format and save the file as

```
~/.runelite/goldman-mode/areas.json
```

then toggle the plugin off and on. If that file exists it is used instead of the built-in list.
Area ids are what unlocks are stored against, so renaming or redrawing an area keeps its unlock state.

## File format (`areas.json`)

```
[ { "id": "...", "n": "Lumbridge", "c": 3, "t": "notes",
    "parts": [ { "p": [[x,y], ...],            // outer outline, OSRS world tiles
                 "h": [ [[x,y], ...], ... ] }  // optional holes
             ] } ]
```

Several `parts` = one area made of separate pieces. Where areas overlap, the smaller one owns the overlapping tiles.

## Settings

Almost everything is fixed in `Settings.java` (barrier colour #64FF0000, height 250, distance 15 tiles, fade 5 tiles, pulse 60% every 1.5 s;
minimap tint #4BFF0000 and border #FFFF0000; price multiplier 100%; the Goldman world map options; the GP overlay, xp rate, kill pay,
payout announcements and the collection log and quest tooltips all on). The plugin's settings menu has three sections:

* **Blocked clicks**: *Block clicks in locked areas* (a checkbox, on by default).
* **Cost multiplier**: *Area costs*, a number box that starts at 1.0 and moves by 0.1 with its up and down arrows (a typed value works too).
  Every area's price is multiplied by it (rounded to the nearest 100 gp); 0 makes areas free.
* **Payout multipliers**: number boxes that start at 1.0 and move by 0.1: *Experience*, *Kills*, *Skill levels*, *Combat levels*, *Quests*,
  *Achievement Diaries*, *Combat Achievements* and *Collection logs*. Each one multiplies what that kind of payout pays (rounded to the nearest gp;
  the xp one just changes how much xp a gp costs). *Achievement Diaries* and *Combat Achievements* cover both the task payouts and the tier bonuses,
  and the collection log and quest tooltips show the multiplied amount. A multiplier is capped at 1000, and 0 stops that payout.
  A quest, item, task or tier that would pay 0 is still marked as done, so it can't pay later if you turn the multiplier back up.
