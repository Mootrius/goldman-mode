package com.goldmanmode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Which 64x64-tile map squares have any map picture at all (the surface world, plane 0), so the map never asks the
 * server for open nothing. Also gives the outer edge of the known world, and which tiles of every zoom level exist.
 */
final class MapRegions
{
	/** zoom level of the 64x64 squares: 4 pixels per game tile */
	static final int NATIVE_Z = 2;

	private final Set<Long> regions = new HashSet<>();
	/** tile keys per zoom level that overlap at least one region */
	private final Set<Long> tiles = new HashSet<>();
	final int minRx, maxRx, minRy, maxRy;

	MapRegions(Iterable<int[]> list)
	{
		int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
		for (int[] r : list)
		{
			regions.add(MapTiles.key(NATIVE_Z, r[0], r[1]));
			x0 = Math.min(x0, r[0]);
			x1 = Math.max(x1, r[0]);
			y0 = Math.min(y0, r[1]);
			y1 = Math.max(y1, r[1]);
			for (int z = MapTiles.MIN_Z; z <= MapTiles.MAX_Z; z++)
			{
				if (z <= NATIVE_Z)
				{
					tiles.add(MapTiles.key(z, r[0] >> (NATIVE_Z - z), r[1] >> (NATIVE_Z - z)));
				}
				else
				{
					int f = 1 << (z - NATIVE_Z);
					for (int dx = 0; dx < f; dx++)
					{
						for (int dy = 0; dy < f; dy++)
						{
							tiles.add(MapTiles.key(z, r[0] * f + dx, r[1] * f + dy));
						}
					}
				}
			}
		}
		minRx = x0;
		maxRx = x1;
		minRy = y0;
		maxRy = y1;
	}

	static MapRegions load()
	{
		java.util.List<int[]> list = new java.util.ArrayList<>();
		try (InputStream in = MapRegions.class.getResourceAsStream("regions.txt");
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				String[] p = line.trim().split("\\s+");
				if (p.length >= 2)
				{
					list.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])});
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			throw new IllegalStateException("regions.txt missing or broken", e);
		}
		return new MapRegions(list);
	}

	int count()
	{
		return regions.size();
	}

	boolean hasRegion(int rx, int ry)
	{
		return regions.contains(MapTiles.key(NATIVE_Z, rx, ry));
	}

	/** does tile (tx, ty) of zoom z have a picture? */
	boolean hasTile(int z, int tx, int ty)
	{
		return tiles.contains(MapTiles.key(z, tx, ty));
	}

	/** the known world in tiles: {minX, minY, maxX, maxY} (max exclusive) */
	double[] worldBounds()
	{
		return new double[]{minRx * 64.0, minRy * 64.0, (maxRx + 1) * 64.0, (maxRy + 1) * 64.0};
	}
}
