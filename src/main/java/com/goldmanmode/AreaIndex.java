package com.goldmanmode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All the areas plus a fast tile -> area lookup.
 * If two areas overlap, the smaller one owns the overlapping tiles.
 */
public class AreaIndex
{
	private final List<GoldmanArea> areas;
	/** region (x>>6, y>>6) -> 64x64 grid of (area index + 1), 0 = no area */
	private final Map<Long, short[]> grid = new HashMap<>();

	public AreaIndex(List<GoldmanArea> areas)
	{
		this.areas = areas;
		build();
	}

	public List<GoldmanArea> getAreas()
	{
		return areas;
	}

	private BarrierGeometry barrier;

	/** straight outline pieces of every area (built on first use) */
	public synchronized BarrierGeometry getBarrier()
	{
		if (barrier == null)
		{
			barrier = new BarrierGeometry(areas);
		}
		return barrier;
	}

	public GoldmanArea get(int idx)
	{
		return areas.get(idx);
	}

	/** @return index into {@link #getAreas()} or -1 */
	public int indexAt(int x, int y)
	{
		short[] g = grid.get(key(x >> 6, y >> 6));
		if (g == null)
		{
			return -1;
		}
		return g[(x & 63) | ((y & 63) << 6)] - 1;
	}

	public GoldmanArea areaAt(int x, int y)
	{
		int i = indexAt(x, y);
		return i < 0 ? null : areas.get(i);
	}

	private static long key(int rx, int ry)
	{
		return ((long) rx << 32) | (ry & 0xFFFFFFFFL);
	}

	private void build()
	{
		for (int i = 0; i < areas.size(); i++)
		{
			GoldmanArea a = areas.get(i);
			a.setTiles(rasterise(a), i);
		}
		// smaller areas claim tiles first, so an area drawn inside a bigger one wins
		Integer[] order = new Integer[areas.size()];
		for (int i = 0; i < order.length; i++)
		{
			order[i] = i;
		}
		Arrays.sort(order, Comparator.comparingInt((Integer i) -> areas.get(i).getSize()).thenComparingInt(i -> i));
		for (int i : order)
		{
			for (int t : areas.get(i).getTiles())
			{
				int x = t >>> 16, y = t & 0xFFFF;
				short[] g = grid.computeIfAbsent(key(x >> 6, y >> 6), k -> new short[64 * 64]);
				int li = (x & 63) | ((y & 63) << 6);
				if (g[li] == 0)
				{
					g[li] = (short) (i + 1);
				}
			}
		}
	}

	/** Tiles whose centre lies inside the area (even-odd per part, so holes are cut out). */
	static int[] rasterise(GoldmanArea area)
	{
		java.util.BitSet seen = new java.util.BitSet();
		int w = area.getMaxX() - area.getMinX() + 1;
		int count = 0;
		int[] out = new int[256];
		for (List<double[][]> part : area.getParts())
		{
			double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
			for (double[][] ring : part)
			{
				for (double[] p : ring)
				{
					minY = Math.min(minY, p[1]);
					maxY = Math.max(maxY, p[1]);
				}
			}
			if (minY > maxY)
			{
				continue;
			}
			double[] xs = new double[64];
			for (int ty = (int) Math.floor(minY); ty <= (int) Math.ceil(maxY); ty++)
			{
				double yc = ty + 0.5;
				int n = 0;
				for (double[][] ring : part)
				{
					int len = ring.length;
					for (int i = 0; i < len; i++)
					{
						double[] a = ring[i], b = ring[(i + 1) % len];
						if ((a[1] <= yc && b[1] > yc) || (b[1] <= yc && a[1] > yc))
						{
							if (n == xs.length)
							{
								xs = Arrays.copyOf(xs, n * 2);
							}
							xs[n++] = a[0] + (yc - a[1]) * (b[0] - a[0]) / (b[1] - a[1]);
						}
					}
				}
				Arrays.sort(xs, 0, n);
				for (int i = 0; i + 1 < n; i += 2)
				{
					int from = (int) Math.ceil(xs[i] - 0.5);
					int to = (int) Math.ceil(xs[i + 1] - 0.5) - 1;
					for (int tx = from; tx <= to; tx++)
					{
						int bit = (ty - area.getMinY()) * w + (tx - area.getMinX());
						if (tx < 0 || ty < 0 || bit < 0 || seen.get(bit))
						{
							continue;
						}
						seen.set(bit);
						if (count == out.length)
						{
							out = Arrays.copyOf(out, count * 2);
						}
						out[count++] = (tx << 16) | ty;
					}
				}
			}
		}
		return Arrays.copyOf(out, count);
	}

	// ------------------------------------------------------------------ loading

	public static AreaIndex load(InputStream in) throws IOException
	{
		try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			return parse(new JsonParser().parse(r));
		}
	}

	public static AreaIndex parse(String json)
	{
		return parse(new JsonParser().parse(json));
	}

	/**
	 * Accepts a list of areas as exported from the drawing page:
	 * [{id, n, c, t, parts:[{p:[[x,y],..], h:[[[x,y],..],..]}]}]
	 * (the raw database form {n, p, h, x:[{p,h}], c, t} also works).
	 */
	static AreaIndex parse(JsonElement root)
	{
		List<GoldmanArea> list = new ArrayList<>();
		JsonArray arr = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
		int n = 0;
		for (JsonElement el : arr)
		{
			JsonObject o = el.getAsJsonObject();
			List<List<double[][]>> parts = new ArrayList<>();
			if (o.has("parts") && o.get("parts").isJsonArray())
			{
				for (JsonElement pe : o.getAsJsonArray("parts"))
				{
					addPart(parts, pe.getAsJsonObject());
				}
			}
			else
			{
				addPart(parts, o);
				if (o.has("x") && o.get("x").isJsonArray())
				{
					for (JsonElement pe : o.getAsJsonArray("x"))
					{
						addPart(parts, pe.getAsJsonObject());
					}
				}
			}
			if (parts.isEmpty())
			{
				continue;
			}
			String id = o.has("id") ? o.get("id").getAsString() : "area" + n;
			String name = o.has("n") && !o.get("n").isJsonNull() && !o.get("n").getAsString().trim().isEmpty()
				? o.get("n").getAsString().trim() : "Unnamed area " + (n + 1);
			int c = o.has("c") && !o.get("c").isJsonNull() ? o.get("c").getAsInt() : 0;
			String t = o.has("t") && !o.get("t").isJsonNull() ? o.get("t").getAsString() : "";
			long price = o.has("price") && !o.get("price").isJsonNull() ? o.get("price").getAsLong() : -1;
			list.add(new GoldmanArea(id, name, c, t, parts, price));
			n++;
		}
		return new AreaIndex(list);
	}

	private static void addPart(List<List<double[][]>> parts, JsonObject po)
	{
		if (!po.has("p") || !po.get("p").isJsonArray())
		{
			return;
		}
		List<double[][]> rings = new ArrayList<>();
		double[][] outer = ring(po.getAsJsonArray("p"));
		if (outer.length < 3)
		{
			return;
		}
		rings.add(outer);
		if (po.has("h") && po.get("h").isJsonArray())
		{
			for (JsonElement he : po.getAsJsonArray("h"))
			{
				double[][] hole = ring(he.getAsJsonArray());
				if (hole.length >= 3)
				{
					rings.add(hole);
				}
			}
		}
		parts.add(rings);
	}

	private static double[][] ring(JsonArray pts)
	{
		double[][] r = new double[pts.size()][];
		for (int i = 0; i < r.length; i++)
		{
			JsonArray p = pts.get(i).getAsJsonArray();
			r[i] = new double[]{p.get(0).getAsDouble(), p.get(1).getAsDouble()};
		}
		return r;
	}
}
