package com.goldmanmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MinimapMathTest
{
	private static double[] at(double dx, double dy, int yaw)
	{
		double[] o = new double[2];
		MinimapMath.project(dx, dy, 4.0, yaw, o);
		return o;
	}

	@Test
	public void northIsUpAndEastIsRightWhenFacingNorth()
	{
		double[] n = at(0, 5, 0);
		assertEquals(0, n[0], 1e-3);
		assertEquals(-20, n[1], 1e-3); // 5 tiles x 4 px, up the screen
		double[] e = at(5, 0, 0);
		assertEquals(20, e[0], 1e-3);
		assertEquals(0, e[1], 1e-3);
	}

	@Test
	public void quarterTurnRotates()
	{
		// camera yaw 90 degrees (4096 of 16384): north swings round to the right of the screen
		double[] n = at(0, 5, 4096);
		assertEquals(20, n[0], 1e-2);
		assertEquals(0, n[1], 1e-2);
		// half turn: north is at the bottom
		double[] s = at(0, 5, 8192);
		assertEquals(0, s[0], 1e-2);
		assertEquals(20, s[1], 1e-2);
	}

	@Test
	public void distancesAreKept()
	{
		for (int yaw = 0; yaw < 16384; yaw += 777)
		{
			double[] p = at(3, -7, yaw);
			assertEquals(Math.hypot(3, 7) * 4, Math.hypot(p[0], p[1]), 0.01);
		}
		assertTrue(true);
	}
}
