package com.goldmanmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class BarrierGeometryTest
{
	private static List<BarrierGeometry.Piece> all(BarrierGeometry g, int rx0, int ry0, int rx1, int ry1)
	{
		List<BarrierGeometry.Piece> out = new ArrayList<>();
		for (int rx = rx0; rx <= rx1; rx++)
		{
			for (int ry = ry0; ry <= ry1; ry++)
			{
				List<BarrierGeometry.Piece> l = g.region(rx, ry);
				if (l != null)
				{
					out.addAll(l);
				}
			}
		}
		return out;
	}

	@Test
	public void squareHasAPieceForEveryUnitOfPerimeter()
	{
		AreaIndex idx = AreaIndex.parse("[{\"id\":\"a\",\"n\":\"Sq\",\"parts\":[{\"p\":[[100,200],[110,200],[110,210],[100,210]],\"h\":[]}]}]");
		BarrierGeometry g = idx.getBarrier();
		assertEquals(40, g.getPieceCount());
		for (BarrierGeometry.Piece p : all(g, 1, 3, 1, 3))
		{
			assertEquals(0, p.ring);
			assertTrue((p.o1 == 0 && p.o2 == -1) || (p.o1 == -1 && p.o2 == 0));
		}
	}

	@Test
	public void diagonalBorderIsOneStraightLine()
	{
		AreaIndex idx = AreaIndex.parse("[{\"id\":\"t\",\"n\":\"Tri\",\"parts\":[{\"p\":[[100,100],[110,100],[100,110]],\"h\":[]}]}]");
		BarrierGeometry g = idx.getBarrier();
		int diagonal = 0;
		for (BarrierGeometry.Piece p : all(g, 1, 1, 1, 1))
		{
			if (Math.abs(p.x1 + p.y1 - 210) < 1e-3 && Math.abs(p.x2 + p.y2 - 210) < 1e-3)
			{
				diagonal++;
			}
		}
		// hypotenuse is ~14.14 tiles long -> 15 pieces, all exactly on the line x + y = 210
		assertEquals(15, diagonal);
	}

	@Test
	public void tilesTheDiagonalCutsThroughAreCrossedButEdgeTilesAreNot()
	{
		AreaIndex tri = AreaIndex.parse("[{\"id\":\"t\",\"n\":\"Tri\",\"parts\":[{\"p\":[[100,100],[110,100],[100,110]],\"h\":[]}]}]");
		BarrierGeometry g = tri.getBarrier();
		assertTrue(g.crossing(104, 105) != null);   // the line x + y = 210 runs corner to corner through it
		assertTrue(g.crossing(104, 104) == null);   // only touches its corner
		assertTrue(g.crossing(103, 105) == null);
		AreaIndex sq = AreaIndex.parse("[{\"id\":\"s\",\"n\":\"Sq\",\"parts\":[{\"p\":[[0,0],[10,0],[10,10],[0,10]],\"h\":[]}]}]");
		for (int i = -1; i <= 10; i++)
		{
			assertTrue(sq.getBarrier().crossing(i, 0) == null && sq.getBarrier().crossing(i, -1) == null);
		}
	}

	@Test
	public void sharedEdgeKnowsBothAreasAndHoleRingsFaceTheOccupant()
	{
		AreaIndex idx = AreaIndex.parse("[{\"id\":\"a\",\"n\":\"A\",\"parts\":[{\"p\":[[0,0],[10,0],[10,10],[0,10]],\"h\":[]}]},"
			+ "{\"id\":\"b\",\"n\":\"B\",\"parts\":[{\"p\":[[10,0],[20,0],[20,10],[10,10]],\"h\":[]}]},"
			+ "{\"id\":\"big\",\"n\":\"Big\",\"parts\":[{\"p\":[[100,100],[140,100],[140,140],[100,140]],\"h\":[[[110,110],[120,110],[120,120],[110,120]]]}]},"
			+ "{\"id\":\"in\",\"n\":\"In\",\"parts\":[{\"p\":[[110,110],[120,110],[120,120],[110,120]],\"h\":[]}]}]");
		BarrierGeometry g = idx.getBarrier();
		int shared = 0;
		for (BarrierGeometry.Piece p : all(g, 0, 0, 0, 0))
		{
			if (p.x1 == 10 && p.x2 == 10)
			{
				shared++;
				assertTrue((p.o1 == 0 && p.o2 == 1) || (p.o1 == 1 && p.o2 == 0));
			}
		}
		assertEquals(20, shared); // 10 from A's ring + 10 from B's ring
		int holeEdge = 0;
		for (BarrierGeometry.Piece p : all(g, 1, 1, 2, 2))
		{
			if (p.x1 >= 110 && p.x2 >= 110 && p.x1 <= 120 && p.x2 <= 120 && p.y1 >= 110 && p.y2 >= 110 && p.y1 <= 120 && p.y2 <= 120)
			{
				holeEdge++;
				assertTrue((p.o1 == 2 && p.o2 == 3) || (p.o1 == 3 && p.o2 == 2));
			}
		}
		assertEquals(80, holeEdge); // Big's hole ring + In's outline, 40 each
	}

	@Test
	public void bundledAreasBuildQuickly() throws Exception
	{
		try (InputStream in = GoldmanState.class.getResourceAsStream("areas.json"))
		{
			AreaIndex idx = AreaIndex.load(in);
			long t = System.nanoTime();
			BarrierGeometry g = idx.getBarrier();
			long ms = (System.nanoTime() - t) / 1_000_000;
			System.out.println("barrier pieces " + g.getPieceCount() + " built in " + ms + " ms");
			assertTrue(g.getPieceCount() > 1000);
			assertTrue("took " + ms, ms < 5000);
		}
	}
}
