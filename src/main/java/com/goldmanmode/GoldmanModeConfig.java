package com.goldmanmode;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

/**
 * The plugin's settings menu: click blocking, the area cost multiplier and the payout multipliers. Everything else is
 * fixed (see {@link Settings}). This group is also where the plugin keeps your Goldman GP balance and unlocked areas.
 * <p>
 * Each multiplier is a number box that starts at 1.0 and moves by 0.1 with its up and down arrows (you can also type
 * a value). 1.0 pays or costs exactly the normal amount, 2.0 doubles it, 0.5 halves it, 0 turns it off.
 */
@ConfigGroup(GoldmanModeConfig.GROUP)
public interface GoldmanModeConfig extends Config
{
	String GROUP = "goldmanmode";

	@ConfigSection(name = "Blocked clicks", description = "Whether the plugin stops you from interacting with locked areas", position = 0)
	String BLOCKED_CLICKS = "blockedClicks";

	@ConfigSection(name = "Cost multiplier", description = "Makes areas cost more or less than base 1.0 factor", position = 1)
	String COST_MULTIPLIER = "costMultiplier";

	@ConfigSection(name = "Payout multipliers", description = "Makes payouts give more or less than the base 1.0 factor", position = 2)
	String PAYOUT_MULTIPLIERS = "payoutMultipliers";

	@ConfigItem(keyName = "blockLockedClicks", name = "Block clicks in locked areas", description = "Walking to, and clicking NPCs, objects, items, and players in locked areas does nothing", section = BLOCKED_CLICKS, position = 0)
	default boolean blockLockedClicks()
	{
		return true;
	}

	@ConfigItem(keyName = "areaCostMultiplier", name = "Area costs", description = "Multiplies how much areas cost, by factors of 0.1 (eg. 2.0 doubles costs while 0.5 halves costs)", section = COST_MULTIPLIER, position = 0)
	default double areaCostMultiplier()
	{
		return 1.0;
	}

	@ConfigItem(keyName = "xpMultiplier", name = "Experience", description = "Multiplies how much experience pays out, by factors of 0.1 (eg. 2.0 doubles payouts while 0.5 halves payouts)", section = PAYOUT_MULTIPLIERS, position = 0)
	default double xpMultiplier()
	{
		return 1.0;
	}

	@ConfigItem(keyName = "killsMultiplier", name = "Kills", description = "Multiplies how much kills pay out, by factors of 0.1 (eg. 2.0 doubles payouts while 0.5 halves payouts)", section = PAYOUT_MULTIPLIERS, position = 1)
	default double killsMultiplier()
	{
		return 1.0;
	}

	@ConfigItem(keyName = "skillLevelsMultiplier", name = "Skill levels", description = "Multiplies how much skill levels pay out, by factors of 0.1 (eg. 2.0 doubles payouts while 0.5 halves payouts)", section = PAYOUT_MULTIPLIERS, position = 2)
	default double skillLevelsMultiplier()
	{
		return 1.0;
	}

	@ConfigItem(keyName = "combatLevelsMultiplier", name = "Combat levels", description = "Multiplies how much combat levels pay out, by factors of 0.1 (eg. 2.0 doubles payouts while 0.5 halves payouts)", section = PAYOUT_MULTIPLIERS, position = 3)
	default double combatLevelsMultiplier()
	{
		return 1.0;
	}

	@ConfigItem(keyName = "questsMultiplier", name = "Quests", description = "Multiplies how much quests pay out, by factors of 0.1 (eg. 2.0 doubles payouts while 0.5 halves payouts)", section = PAYOUT_MULTIPLIERS, position = 4)
	default double questsMultiplier()
	{
		return 1.0;
	}

	@ConfigItem(keyName = "diariesMultiplier", name = "Achievement Diaries", description = "Multiplies how much Achievement Diaries pay out, by factors of 0.1 (eg. 2.0 doubles payouts while 0.5 halves payouts)", section = PAYOUT_MULTIPLIERS, position = 5)
	default double diariesMultiplier()
	{
		return 1.0;
	}

	@ConfigItem(keyName = "combatAchievementsMultiplier", name = "Combat Achievements", description = "Multiplies how much Combat Achievements pay out, by factors of 0.1 (eg. 2.0 doubles payouts while 0.5 halves payouts)", section = PAYOUT_MULTIPLIERS, position = 6)
	default double combatAchievementsMultiplier()
	{
		return 1.0;
	}

	@ConfigItem(keyName = "collectionLogMultiplier", name = "Collection logs", description = "Multiplies how much collection logs pay out, by factors of 0.1 (eg. 2.0 doubles payouts while 0.5 halves payouts)", section = PAYOUT_MULTIPLIERS, position = 7)
	default double collectionLogMultiplier()
	{
		return 1.0;
	}
}
