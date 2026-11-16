package com.goldmanmode;

import java.util.List;
import lombok.Getter;

/**
 * One unlockable map area, built from the shapes drawn on the Goldman area-drawing page.
 * Coordinates are OSRS world tiles (x east, y north). An area is made of one or more
 * parts; each part is an outer ring plus optional hole rings (even-odd fill).
 */
public class GoldmanArea
{
	@Getter
	private final String id;
	@Getter
	private final String name;
	@Getter
	private final int colourIndex;
	@Getter
	private final String notes;
	/** Every ring of every part, as [ [x,y], ... ] - used to draw the area on the world map. */
	@Getter
	private final List<double[][]> rings;
	/** The rings of each part (outer first, then holes) - used to rasterise it. */
	@Getter
	private final List<List<double[][]>> parts;
	@Getter
	private final int minX, minY, maxX, maxY;

	/** Packed tiles owned by this shape: (x << 16) | y. Filled in by AreaIndex. */
	@Getter
	private int[] tiles = new int[0];
	@Getter
	private int index = -1;

	/** Price in Goldman GP worked out by tools/price_areas.py, or -1 if the areas file has none. */
	@Getter
	private final long price;

	GoldmanArea(String id, String name, int colourIndex, String notes, List<List<double[][]>> parts)
	{
		this(id, name, colourIndex, notes, parts, -1);
	}

	GoldmanArea(String id, String name, int colourIndex, String notes, List<List<double[][]>> parts, long price)
	{
		this.price = price;
		this.id = id;
		this.name = name;
		this.colourIndex = colourIndex;
		this.notes = notes == null ? "" : notes;
		this.parts = parts;
		this.rings = new java.util.ArrayList<>();
		double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
		for (List<double[][]> part : parts)
		{
			for (double[][] ring : part)
			{
				rings.add(ring);
				for (double[] p : ring)
				{
					x0 = Math.min(x0, p[0]);
					y0 = Math.min(y0, p[1]);
					x1 = Math.max(x1, p[0]);
					y1 = Math.max(y1, p[1]);
				}
			}
		}
		if (rings.isEmpty())
		{
			x0 = y0 = x1 = y1 = 0;
		}
		minX = (int) Math.floor(x0);
		minY = (int) Math.floor(y0);
		maxX = (int) Math.ceil(x1);
		maxY = (int) Math.ceil(y1);
	}

	void setTiles(int[] tiles, int index)
	{
		this.tiles = tiles;
		this.index = index;
	}

	/** Size in tiles (what the price is based on). */
	public int getSize()
	{
		return tiles.length;
	}

	/** A tile inside the area that is closest to its centre of mass (a good spot to centre the map on). */
	public int[] getLabelTile()
	{
		if (tiles.length == 0)
		{
			return new int[]{(minX + maxX) / 2, (minY + maxY) / 2};
		}
		double cx = 0, cy = 0;
		for (int t : tiles)
		{
			cx += t >>> 16;
			cy += t & 0xFFFF;
		}
		cx /= tiles.length;
		cy /= tiles.length;
		long best = Long.MAX_VALUE;
		int bx = 0, by = 0;
		for (int t : tiles)
		{
			int x = t >>> 16, y = t & 0xFFFF;
			long d = (long) ((x - cx) * (x - cx) + (y - cy) * (y - cy));
			if (d < best)
			{
				best = d;
				bx = x;
				by = y;
			}
		}
		return new int[]{bx, by};
	}
}
