package com.goldmanmode;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class XpConversionTest
{
	@Test
	public void roundsToNearestGpAndCarriesTheRest()
	{
		// 23 xp per gp
		assertArrayEquals(new long[]{0, 11}, GoldmanState.convertXp(0, 11, 23)); // under half a gp: nothing yet
		assertArrayEquals(new long[]{1, -11}, GoldmanState.convertXp(0, 12, 23)); // over half: rounds up to 1 gp
		assertArrayEquals(new long[]{0, 0}, GoldmanState.convertXp(-11, 11, 23)); // the loan is paid back
		assertArrayEquals(new long[]{1, 0}, GoldmanState.convertXp(0, 23, 23));
		assertArrayEquals(new long[]{4348, -4}, GoldmanState.convertXp(0, 100_000, 23));
	}

	@Test
	public void nothingIsLostOverManySmallDrops()
	{
		long pool = 0, gp = 0, xp = 0;
		for (int i = 0; i < 100_000; i++)
		{
			long[] r = GoldmanState.convertXp(pool, 7, 23);
			gp += r[0];
			pool = r[1];
			xp += 7;
		}
		assertEquals(Math.round(xp / 23.0), gp);
	}

	@Test
	public void maxedAccountPaysTwoHundredMillion()
	{
		assertEquals(200_000_000L, GoldmanState.convertXp(0, 23L * 200_000_000L, 23)[0]); // 23 skills x 200m xp = 4.6b xp
	}

	@Test
	public void experienceMultiplierChangesHowMuchXpAGpCosts()
	{
		assertArrayEquals(GoldmanState.convertXp(5, 100, 23), GoldmanState.convertXp(5, 100, 23, 1.0));
		// x2.0: 11.5 xp per gp
		assertArrayEquals(new long[]{8696, -4}, GoldmanState.convertXp(0, 100_000, 23, 2.0));
		// x0.5: 46 xp per gp
		assertArrayEquals(new long[]{2174, -4}, GoldmanState.convertXp(0, 100_000, 23, 0.5));
		// x0: pays nothing and carries nothing
		assertArrayEquals(new long[]{0, 7}, GoldmanState.convertXp(7, 1000, 23, 0));
	}

	@Test
	public void multipliersScaleGpAndAreKeptInRange()
	{
		assertEquals(1500, GoldmanState.scale(1000, 1.5));
		assertEquals(1000, GoldmanState.scale(1000, 1.0));
		assertEquals(0, GoldmanState.scale(1000, 0));
		assertEquals(0, GoldmanState.scale(1000, -2));
		assertEquals(0, GoldmanState.scale(1, 0.4));
		assertEquals(1, GoldmanState.scale(1, 0.5));
		assertEquals(10_000, GoldmanState.scale(10, 1_000_000)); // capped at x1000
		assertEquals(0.0, GoldmanState.clampMultiplier(Double.NaN), 0);
		assertEquals(1.2, GoldmanState.clampMultiplier(1.2), 1e-12);
	}
}
