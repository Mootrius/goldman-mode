# Goldman Mode

Unlock Gielinor one area at a time by earning and spending Goldman GP!

Every area of the game world starts **locked** (except Tutorial Island and Lumbridge Castle). You earn Goldman GP by playing, then spend it on the Goldman world map to unlock areas. You can't walk to, or interact with anything in, an area you haven't unlocked.

## How it works

**Locked areas** are marked in the 3D world by a glowing red barrier along their border, and on the minimap by a red tint and border line. With *Block clicks in locked areas* on, walking to a tile in a locked area, or clicking an NPC, object, ground item or player standing in one, does nothing. Examine, inventory and interface clicks still work.

**Unlocking** is done on the Goldman world map, which opens from the world map icon beside the minimap. Hover an area to see its price, click a locked area you can afford, then click it again to confirm. The sidebar panel (gold **G** button) shows your balance and lists every area with its price; the padlock on an unlocked area locks it again and refunds the price.

**Earning GP**

| Source | Pays |
| --- | --- |
| Experience | 1 gp per xp, in every skill |
| Skill levels and combat levels | A bonus for every level gained |
| Kills | 10 gp per combat level of an NPC you killed |
| Quests | Each quest, once, when it is completed |
| Achievement diaries | Each task, plus a bonus for each tier |
| Combat achievements | Each task, plus a bonus for each tier |
| Collection log | Each new item, once. Hover an item in the log to see what it pays |

Everything pays once only, and anything you had before you logged in pays nothing. Turn on *Collection log - New addition notification* in the game's settings so the plugin can see new log items.

## Settings

* **Blocked clicks**: turn click blocking on or off.
* **Cost multiplier**: *Area costs* makes every area cost more or less. 1.0 is the normal price.
* **Payout multipliers**: one for each source of GP above, so you can make the challenge easier or harder. 1.0 is the normal payout.

## Your own areas

The areas are built in. To try your own, save them as `~/.runelite/goldman-mode/areas.json` and restart the plugin; that file is used instead of the built-in list. The format is described in [`docs/areas-format.md`](docs/areas-format.md). How prices and payouts are worked out is in [`docs/economy.md`](docs/economy.md).

## Credits

World map pictures are from the [mejrs map tiles](https://mejrs.github.io/layers_osrs/), downloaded as you look at them and cached in `~/.runelite/goldman-mode/maptiles/`.

## Development

Needs a JDK 11 or newer. `./gradlew runClient` starts RuneLite in developer mode with the plugin loaded, and `./gradlew test` runs the tests. The scripts in `tools/` generate the price and payout data.
