package com.goldmanmode;

/**
 * What a level-up pays in Goldman GP, from goldman-mode-economy.xlsx. A skill level pays the xp needed to get to it from the
 * level before ("Xp for this level" on the "XP Table" tab), the same for every skill. Combat levels have their own table
 * (the "Goldman GP" column on the "Level Bonus" tab) and are paid {@link #COMBAT_MULTIPLIER} times over.
 * Skill levels stop at 99: virtual levels past it pay no level bonus (the xp itself still pays).
 */
final class LevelBonuses
{
	private LevelBonuses()
	{
	}

	/** GP for reaching skill level 2, 3, ... 99: the xp from the level before to that level */
	private static final long[] SKILL = {
		83L, 91L, 102L, 112L, 124L, 138L, 151L, 168L,
		185L, 204L, 226L, 249L, 274L, 304L, 335L, 369L,
		408L, 450L, 497L, 548L, 606L, 667L, 737L, 814L,
		898L, 990L, 1094L, 1207L, 1332L, 1470L, 1623L, 1791L,
		1977L, 2182L, 2409L, 2658L, 2935L, 3240L, 3576L, 3947L,
		4358L, 4810L, 5310L, 5863L, 6471L, 7144L, 7887L, 8707L,
		9612L, 10612L, 11715L, 12934L, 14278L, 15764L, 17404L, 19214L,
		21212L, 23420L, 25856L, 28546L, 31516L, 34795L, 38416L, 42413L,
		46826L, 51699L, 57079L, 63019L, 69576L, 76818L, 84812L, 93638L,
		103383L, 114143L, 126022L, 139138L, 153619L, 169608L, 187260L, 206750L,
		228269L, 252027L, 278259L, 307221L, 339198L, 374502L, 413482L, 456519L,
		504037L, 556499L, 614422L, 678376L, 748985L, 826944L, 913019L, 1008052L,
		1112977L, 1228825L,
	};

	/** GP for reaching combat level 4, 5, ... 126 */
	private static final long[] COMBAT = {
		1100L, 2400L, 2500L, 2700L, 2800L, 3000L, 3100L, 3300L,
		3500L, 3600L, 3800L, 4100L, 4300L, 4600L, 4800L, 5100L,
		5400L, 5700L, 6100L, 6400L, 6800L, 7100L, 7600L, 8100L,
		8600L, 9100L, 9600L, 10200L, 10900L, 11500L, 12000L, 12600L,
		13200L, 14000L, 14800L, 15500L, 16300L, 17200L, 18300L, 19300L,
		20400L, 21400L, 22600L, 24300L, 25600L, 27000L, 28500L, 30200L,
		32400L, 34200L, 36200L, 38200L, 40900L, 43700L, 46000L, 48400L,
		51000L, 54700L, 57800L, 61000L, 64300L, 68100L, 73100L, 77300L,
		81600L, 86200L, 92100L, 98400L, 104200L, 110200L, 116500L, 125600L,
		133500L, 141400L, 149700L, 159300L, 171700L, 182100L, 193100L, 204400L,
		217800L, 231600L, 244400L, 257800L, 271800L, 292400L, 309500L, 327000L,
		345500L, 367100L, 394200L, 417100L, 441200L, 466700L, 500600L, 534400L,
		566000L, 599400L, 634600L, 687300L, 728900L, 772800L, 819100L, 875200L,
		941800L, 1002800L, 1067700L, 1136300L, 1231000L, 1320400L, 1406900L, 1498400L,
		1598600L, 1740800L, 1856100L, 1978100L, 2106900L, 2269000L, 2451400L, 2614200L,
		2786300L, 2968200L, 3227800L,
	};

	/** combat level bonuses are paid this many times what the "Level Bonus" tab says */
	static final long COMBAT_MULTIPLIER = 2;

	private static final int SKILL_FIRST = 2;
	private static final int COMBAT_FIRST = 4;

	/** GP for gaining the skill levels after {@code from} up to and including {@code to} (e.g. 12 -> 15 pays 13, 14 and 15) */
	static long forSkillLevels(int from, int to)
	{
		return sum(SKILL, SKILL_FIRST, from, to);
	}

	/** GP for killing an NPC of this combat level: {@code perLevel} for each of its combat levels (none for a level 0 NPC) */
	static long forKill(int npcCombatLevel, int perLevel)
	{
		return Math.max(0, npcCombatLevel) * (long) Math.max(0, perLevel);
	}

	/** GP for gaining the combat levels after {@code from} up to and including {@code to} */
	static long forCombatLevels(int from, int to)
	{
		return COMBAT_MULTIPLIER * sum(COMBAT, COMBAT_FIRST, from, to);
	}

	private static long sum(long[] table, int first, int from, int to)
	{
		long total = 0;
		for (int level = Math.max(from + 1, first); level <= to; level++)
		{
			int i = level - first;
			if (i >= 0 && i < table.length)
			{
				total += table[i];
			}
		}
		return total;
	}
}
