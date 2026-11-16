package com.goldmanmode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The straight outline of every area in pieces of at most one tile, each remembering which area lies on either side.
 * Built once: which pieces get a barrier depends on what is locked. Following the drawn outline rather than tile
 * edges is what gives diagonal borders a straight barrier.
 */
public class BarrierGeometry
{
	/** how far either side of an edge we look to see which area is there (tiles) */
	private static final double PROBE = 0.25;

	public static class Piece
	{
		public final float x1, y1, x2, y2;
		/** area whose outline this piece is part of */
		public final int ring;
		/** area on each side (-1 = none) */
		public final int o1, o2;

		Piece(float x1, float y1, float x2, float y2, int ring, int o1, int o2)
		{
			this.x1 = x1;
			this.y1 = y1;
			this.x2 = x2;
			this.y2 = y2;
			this.ring = ring;
			this.o1 = o1;
			this.o2 = o2;
		}
	}

	private final List<GoldmanArea> areas;
	private final Map<Long, List<Integer>> areaBuckets = new HashMap<>();
	private final Map<Long, List<Piece>> pieceBuckets = new HashMap<>();
	private int count;
	/** pieces that cut through the inside of a tile (not just run along its edge), by tile */
	private final Map<Long, List<Piece>> crossing = new HashMap<>();

	public BarrierGeometry(List<GoldmanArea> areas)
	{
		this.areas = areas;
		for (int i = 0; i < areas.size(); i++)
		{
			GoldmanArea a = areas.get(i);
			for (int rx = a.getMinX() >> 6; rx <= a.getMaxX() >> 6; rx++)
			{
				for (int ry = a.getMinY() >> 6; ry <= a.getMaxY() >> 6; ry++)
				{
					areaBuckets.computeIfAbsent(key(rx, ry), k -> new ArrayList<>()).add(i);
				}
			}
		}
		for (int i = 0; i < areas.size(); i++)
		{
			for (double[][] ring : areas.get(i).getRings())
			{
				addRing(i, ring);
			}
		}
	}

	public int getPieceCount()
	{
		return count;
	}

	/** pieces whose midpoint is in the 64x64 region (rx, ry); may be null */
	public List<Piece> region(int rx, int ry)
	{
		return pieceBuckets.get(key(rx, ry));
	}

	private static long key(int rx, int ry)
	{
		return ((long) rx << 32) | (ry & 0xFFFFFFFFL);
	}

	private void addRing(int area, double[][] ring)
	{
		int n = ring.length;
		for (int i = 0; i < n; i++)
		{
			double[] a = ring[i], b = ring[(i + 1) % n];
			double dx = b[0] - a[0], dy = b[1] - a[1];
			double len = Math.sqrt(dx * dx + dy * dy);
			if (len < 1e-6)
			{
				continue;
			}
			double nx = -dy / len, ny = dx / len;
			int parts = Math.max(1, (int) Math.ceil(len));
			for (int k = 0; k < parts; k++)
			{
				double t0 = (double) k / parts, t1 = (double) (k + 1) / parts;
				double x1 = a[0] + dx * t0, y1 = a[1] + dy * t0;
				double x2 = a[0] + dx * t1, y2 = a[1] + dy * t1;
				double mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
				int o1 = owner(mx + nx * PROBE, my + ny * PROBE);
				int o2 = owner(mx - nx * PROBE, my - ny * PROBE);
				if (o1 == o2)
				{
					continue; // same area on both sides: never a border
				}
				Piece p = new Piece((float) x1, (float) y1, (float) x2, (float) y2, area, o1, o2);
				indexCrossings(p);
				pieceBuckets.computeIfAbsent(key((int) Math.floor(mx) >> 6, (int) Math.floor(my) >> 6), kk -> new ArrayList<>()).add(p);
				count++;
			}
		}
	}

	/** pieces of border that pass through the inside of tile (tx, ty); may be null. Edge-hugging pieces don't count. */
	public List<Piece> crossing(int tx, int ty)
	{
		return crossing.get(key(tx, ty));
	}

	/** how far inside a tile a piece must get to count as touching it (tile fractions) */
	private static final double MARGIN = 0.04;

	private void indexCrossings(Piece p)
	{
		int x0 = (int) Math.floor(Math.min(p.x1, p.x2)), x1 = (int) Math.floor(Math.max(p.x1, p.x2));
		int y0 = (int) Math.floor(Math.min(p.y1, p.y2)), y1 = (int) Math.floor(Math.max(p.y1, p.y2));
		for (int tx = x0; tx <= x1; tx++)
		{
			for (int ty = y0; ty <= y1; ty++)
			{
				if (entersSquare(p, tx + MARGIN, ty + MARGIN, tx + 1 - MARGIN, ty + 1 - MARGIN))
				{
					crossing.computeIfAbsent(key(tx, ty), k -> new ArrayList<>(2)).add(p);
				}
			}
		}
	}

	/** Liang-Barsky: does the segment pass through the rectangle? */
	private static boolean entersSquare(Piece p, double minX, double minY, double maxX, double maxY)
	{
		double t0 = 0, t1 = 1, dx = p.x2 - p.x1, dy = p.y2 - p.y1;
		double[] pp = {-dx, dx, -dy, dy};
		double[] qq = {p.x1 - minX, maxX - p.x1, p.y1 - minY, maxY - p.y1};
		for (int i = 0; i < 4; i++)
		{
			if (Math.abs(pp[i]) < 1e-9)
			{
				if (qq[i] < 0)
				{
					return false;
				}
			}
			else
			{
				double r = qq[i] / pp[i];
				if (pp[i] < 0)
				{
					t0 = Math.max(t0, r);
				}
				else
				{
					t1 = Math.min(t1, r);
				}
				if (t0 > t1)
				{
					return false;
				}
			}
		}
		return true;
	}

	/** index of the smallest area that geometrically contains the point, or -1 */
	int owner(double x, double y)
	{
		List<Integer> cand = areaBuckets.get(key((int) Math.floor(x) >> 6, (int) Math.floor(y) >> 6));
		if (cand == null)
		{
			return -1;
		}
		int best = -1;
		int bestSize = Integer.MAX_VALUE;
		for (int i : cand)
		{
			GoldmanArea a = areas.get(i);
			if (x < a.getMinX() || x > a.getMaxX() || y < a.getMinY() || y > a.getMaxY())
			{
				continue;
			}
			if (a.getSize() < bestSize && contains(a, x, y))
			{
				best = i;
				bestSize = a.getSize();
			}
		}
		return best;
	}

	static boolean contains(GoldmanArea a, double x, double y)
	{
		for (List<double[][]> part : a.getParts())
		{
			boolean in = false;
			for (double[][] ring : part)
			{
				int n = ring.length;
				for (int i = 0, j = n - 1; i < n; j = i++)
				{
					double yi = ring[i][1], yj = ring[j][1];
					if ((yi > y) != (yj > y) && x < (ring[j][0] - ring[i][0]) * (y - yi) / (yj - yi) + ring[i][0])
					{
						in = !in;
					}
				}
			}
			if (in)
			{
				return true;
			}
		}
		return false;
	}
}
