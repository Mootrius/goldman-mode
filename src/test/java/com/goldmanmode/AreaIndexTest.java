package com.goldmanmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import org.junit.Test;

public class AreaIndexTest
{
	private static AreaIndex of(String json)
	{
		return AreaIndex.parse(json);
	}

	@Test
	public void squareIsItsAreaInTiles()
	{
		AreaIndex idx = of("[{\"id\":\"a\",\"n\":\"Sq\",\"c\":0,\"parts\":[{\"p\":[[100,200],[110,200],[110,210],[100,210]],\"h\":[]}]}]");
		assertEquals(100, idx.get(0).getSize());
		assertNotNull(idx.areaAt(100, 200));
		assertNotNull(idx.areaAt(109, 209));
		assertNull(idx.areaAt(110, 200));
		assertNull(idx.areaAt(99, 200));
		assertNull(idx.areaAt(100, 210));
	}

	@Test
	public void holesAreCutOutAndCanHoldOtherAreas()
	{
		AreaIndex idx = of("[{\"id\":\"big\",\"n\":\"Big\",\"c\":0,\"parts\":[{\"p\":[[0,0],[10,0],[10,10],[0,10]],\"h\":[[[3,3],[6,3],[6,6],[3,6]]]}]},"
			+ "{\"id\":\"in\",\"n\":\"In\",\"c\":1,\"parts\":[{\"p\":[[3,3],[6,3],[6,6],[3,6]],\"h\":[]}]}]");
		assertEquals(91, idx.get(0).getSize());
		assertEquals(9, idx.get(1).getSize());
		assertEquals("Big", idx.areaAt(1, 1).getName());
		assertEquals("In", idx.areaAt(4, 4).getName());
	}

	@Test
	public void separatedPartsAreOneArea()
	{
		AreaIndex idx = of("[{\"id\":\"x\",\"n\":\"Two\",\"c\":0,\"parts\":[{\"p\":[[0,0],[2,0],[2,2],[0,2]],\"h\":[]},{\"p\":[[50,50],[53,50],[53,53],[50,53]],\"h\":[]}]}]");
		assertEquals(4 + 9, idx.get(0).getSize());
		assertEquals("Two", idx.areaAt(51, 51).getName());
		assertEquals("Two", idx.areaAt(1, 1).getName());
		assertNull(idx.areaAt(20, 20));
	}

	@Test
	public void smallerAreaWinsAnOverlap()
	{
		AreaIndex idx = of("[{\"id\":\"b\",\"n\":\"Big\",\"c\":0,\"parts\":[{\"p\":[[0,0],[10,0],[10,10],[0,10]],\"h\":[]}]},"
			+ "{\"id\":\"s\",\"n\":\"Small\",\"c\":1,\"parts\":[{\"p\":[[2,2],[4,2],[4,4],[2,4]],\"h\":[]}]}]");
		assertEquals("Small", idx.areaAt(3, 3).getName());
		assertEquals("Big", idx.areaAt(8, 8).getName());
	}

	@Test
	public void triangleAndRawDbFormat()
	{
		// raw db doc form: n, p, h, x
		AreaIndex idx = of("[{\"id\":\"t\",\"n\":\"Tri\",\"p\":[[0,0],[10,0],[0,10]],\"h\":[],\"c\":2}]");
		int s = idx.get(0).getSize();
		assertTrue("size " + s, s >= 40 && s <= 55);
		assertEquals("Tri", idx.areaAt(1, 1).getName());
		assertNull(idx.areaAt(9, 9));
	}

	@Test
	public void bundledAreasLoad() throws Exception
	{
		try (InputStream in = GoldmanState.class.getResourceAsStream("areas.json"))
		{
			AreaIndex idx = AreaIndex.load(in);
			assertEquals(405, idx.getAreas().size());
			long total = 0, gp = 0;
			for (GoldmanArea a : idx.getAreas())
			{
				assertTrue(a.getName(), a.getSize() > 0);
				assertTrue(a.getName() + " has no price", a.getPrice() >= 0);
				total += a.getSize();
				gp += a.getPrice();
				int[] t = a.getLabelTile();
				// the label tile belongs to the area, or to a smaller area drawn inside it (the smaller one owns overlapping tiles)
				GoldmanArea owner = idx.areaAt(t[0], t[1]);
				assertTrue(a.getName(), owner == a || (owner != null && owner.getSize() < a.getSize()));
			}
			System.out.println("total tiles " + total);
			assertEquals(300_000_000L, gp);
			assertEquals("Lumbridge Castle", idx.areaAt(3222, 3218).getName());
		}
	}
}
