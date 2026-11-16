package com.goldmanmode;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LevelBonusesTest
{
	@Test
	public void tablesMatchTheSpreadsheetTotals()
	{
		assertEquals(13_034_431L, LevelBonuses.forSkillLevels(1, 99)); // the xp for level 99
		assertEquals(2 * 50_003_400L, LevelBonuses.forCombatLevels(3, 126)); // combat 4-126, paid double
	}

	@Test
	public void singleLevelsAndJumps()
	{
		assertEquals(83L, LevelBonuses.forSkillLevels(1, 2));
		assertEquals(1_228_825L, LevelBonuses.forSkillLevels(98, 99));
		assertEquals(249L + 274L + 304L, LevelBonuses.forSkillLevels(12, 15)); // a lamp that jumps three levels
		assertEquals(2_200L, LevelBonuses.forCombatLevels(3, 4));
		assertEquals(2 * 3_227_800L, LevelBonuses.forCombatLevels(125, 126));
	}

	@Test
	public void killsPayTenPerCombatLevel()
	{
		assertEquals(1_000L, LevelBonuses.forKill(100, 10));
		assertEquals(30L, LevelBonuses.forKill(3, 10));
		assertEquals(0L, LevelBonuses.forKill(0, 10)); // no combat level, no pay
		assertEquals(0L, LevelBonuses.forKill(100, 0));
	}

	@Test
	public void nothingForNoGainOrAGoingDown()
	{
		assertEquals(0L, LevelBonuses.forSkillLevels(50, 50));
		assertEquals(0L, LevelBonuses.forSkillLevels(50, 40));
		assertEquals(0L, LevelBonuses.forCombatLevels(126, 126));
	}

	@Test
	public void virtualLevelsPayNoBonus()
	{
		assertEquals(0L, LevelBonuses.forSkillLevels(99, 100));
		assertEquals(0L, LevelBonuses.forSkillLevels(99, 126));
	}

	@Test
	public void experiencePastNinetyNineStillPays()
	{
		// raw xp has no cap: the last 186,965,569 xp from 99 to 200m pays 1 gp each
		assertEquals(186_965_569L, GoldmanState.convertXp(0, 186_965_569L, 1)[0]);
	}
}
