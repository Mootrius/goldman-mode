package com.goldmanmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapTilesTest
{
	@Test
	public void squareSizesMatchTheZoomLevels()
	{
		assertEquals(64, MapTiles.span(2));   // 4 px per tile
		assertEquals(32, MapTiles.span(3));
		assertEquals(16, MapTiles.span(4));
		assertEquals(256, MapTiles.span(0));
		assertEquals(2048, MapTiles.span(-3));
	}

	@Test
	public void levelFollowsPixelsPerTile()
	{
		assertEquals(2, MapTiles.levelFor(4));
		assertEquals(2, MapTiles.levelFor(4.4));   // slight upscaling is fine
		assertEquals(3, MapTiles.levelFor(5));
		assertEquals(4, MapTiles.levelFor(16));
		assertEquals(4, MapTiles.levelFor(60));    // past the sharpest pictures: stretch them
		assertEquals(1, MapTiles.levelFor(2));
		assertEquals(-3, MapTiles.levelFor(0.05)); // whole world
	}

	@Test
	public void keysAreUniquePerSquare()
	{
		assertNotEquals(MapTiles.key(2, 50, 51), MapTiles.key(2, 51, 50));
		assertNotEquals(MapTiles.key(2, 50, 50), MapTiles.key(3, 50, 50));
		assertEquals(MapTiles.key(-3, 1, 1), MapTiles.key(-3, 1, 1));
	}

	@Test
	public void regionListCoversTheKnownWorldAtEveryZoom()
	{
		MapRegions r = MapRegions.load();
		assertEquals(1632, r.count()); // the surface world: 48 x 34 map squares
		assertTrue(r.hasRegion(50, 50)); // Lumbridge
		assertFalse(r.hasRegion(0, 0));
		assertFalse(r.hasRegion(40, 80)); // dungeons and instances are not on this map
		assertTrue(r.hasTile(2, 50, 50));
		assertTrue(r.hasTile(3, 100, 100) && r.hasTile(3, 101, 101)); // its four halves...
		assertTrue(r.hasTile(4, 200, 200));
		assertTrue(r.hasTile(1, 25, 25));
		assertTrue(r.hasTile(-3, 1, 1));
		assertFalse(r.hasTile(-3, 0, 0));
		assertFalse(r.hasTile(-3, 20, 20));
		double[] b = r.worldBounds();
		assertTrue(b[0] < 3200 && b[2] > 3300 && b[1] < 3200 && b[3] > 3300);
	}
}
