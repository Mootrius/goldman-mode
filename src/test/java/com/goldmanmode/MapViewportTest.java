package com.goldmanmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapViewportTest
{
	@Test
	public void screenAndWorldConvertBothWaysAndYPointsNorth()
	{
		MapViewport v = new MapViewport();
		v.w = 1000;
		v.h = 600;
		v.cx = 3200;
		v.cy = 3200;
		v.scale = 8;
		assertEquals(500, v.sx(3200), 1e-9);
		assertEquals(300, v.sy(3200), 1e-9);
		assertTrue(v.sy(3210) < v.sy(3200)); // north is up the screen
		assertEquals(3213.5, v.wx(v.sx(3213.5)), 1e-9);
		assertEquals(3190.25, v.wy(v.sy(3190.25)), 1e-9);
	}

	@Test
	public void zoomingKeepsThePointUnderTheMouseStill()
	{
		MapViewport v = new MapViewport();
		v.w = 1280;
		v.h = 800;
		v.cx = 3000;
		v.cy = 3500;
		v.scale = 2;
		double ax = v.wx(900), ay = v.wy(120);
		v.zoomKeeping(ax, ay, 900, 120, 17.5);
		assertEquals(900, v.sx(ax), 1e-6);
		assertEquals(120, v.sy(ay), 1e-6);
		assertEquals(17.5, v.scale, 1e-12);
	}

	@Test
	public void draggingMovesTheMapWithTheMouse()
	{
		MapViewport v = new MapViewport();
		v.cx = 100;
		v.cy = 100;
		v.scale = 4;
		double wx = 110, wy = 90;
		double sx = v.sx(wx), sy = v.sy(wy);
		v.panPixels(40, -20); // mouse moved right 40 and up 20
		assertEquals(sx + 40, v.sx(wx), 1e-9);
		assertEquals(sy - 20, v.sy(wy), 1e-9);
	}

	@Test
	public void fitShowsTheWholeBoxWithMargin()
	{
		MapViewport v = new MapViewport();
		v.w = 1000;
		v.h = 500;
		double[] box = {0, 0, 4000, 2000};
		v.fit(box, 20);
		assertTrue(v.sx(0) >= 20 - 1e-6 && v.sx(4000) <= 980 + 1e-6);
		assertTrue(v.sy(2000) >= 20 - 1e-6 && v.sy(0) <= 480 + 1e-6);
		assertEquals(2000, v.cx, 1e-9);
	}

	@Test
	public void clampKeepsTheCentreInsideTheWorld()
	{
		MapViewport v = new MapViewport();
		v.cx = -5000;
		v.cy = 99999;
		v.clampCentre(new double[]{960, 1216, 4224, 6528}, 64);
		assertEquals(896, v.cx, 1e-9);
		assertEquals(6592, v.cy, 1e-9);
		assertFalse(Double.isNaN(v.scale));
	}
}
