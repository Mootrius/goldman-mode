package com.goldmanmode;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The map pictures: 256x256 pixel squares at several zoom levels (z = -3, 2048 tiles a side, to z = 4, 16 pixels a
 * tile), fetched when they come into view and cached on disk. Only squares asked for since the last {@link #endFrame()}
 * are fetched, so scrolling past something never queues it.
 */
@Slf4j
final class MapTiles
{
	static final int SIZE = 256;
	static final int MIN_Z = -3;
	static final int MAX_Z = 4;
	static final String URL = "https://mejrs.github.io/layers_osrs/mapsquares/-1/%d/0_%d_%d.png";
	private static final int MAX_CACHED = 420;
	private static final long RETRY_MS = 20_000;
	private static final int WORKERS = 4;

	static long key(int z, int tx, int ty)
	{
		return ((long) (z + 8) << 44) | ((long) (tx & 0x3FFFFF) << 22) | (ty & 0x3FFFFF);
	}

	/** world tiles covered by one square side */
	static int span(int z)
	{
		return z >= 0 ? SIZE >> z : SIZE << -z;
	}

	/** the zoom level whose pictures are right for this many physical pixels per tile */
	static int levelFor(double pixelsPerTile)
	{
		// a little upscaling (12%) is better than loading the next level up
		int z = (int) Math.ceil(Math.log(pixelsPerTile / 1.12) / Math.log(2) - 1e-9);
		return Math.max(MIN_Z, Math.min(MAX_Z, z));
	}

	private final OkHttpClient http; // null: disk only
	private final File dir;
	private final MapRegions regions;
	private final Runnable onChange;

	private final Map<Long, BufferedImage> cache = new LinkedHashMap<Long, BufferedImage>(512, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, BufferedImage> e)
		{
			return size() > MAX_CACHED;
		}
	};
	private final Object lock = new Object();
	private final ArrayDeque<long[]> queue = new ArrayDeque<>();
	private final Set<Long> loading = new HashSet<>();
	private final Map<Long, Long> failed = new HashMap<>();
	private final Set<Long> absent = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final LinkedHashSet<long[]> wanted = new LinkedHashSet<>();
	private final Set<Long> wantedKeys = new HashSet<>();
	private volatile boolean running = true;
	private volatile int failures;
	private final Thread[] workers = new Thread[WORKERS];

	MapTiles(OkHttpClient http, File dir, MapRegions regions, Runnable onChange)
	{
		this.http = http;
		this.dir = dir;
		this.regions = regions;
		this.onChange = onChange;
		for (int i = 0; i < WORKERS; i++)
		{
			workers[i] = new Thread(this::work, "goldman-map-tiles-" + i);
			workers[i].setDaemon(true);
			workers[i].start();
		}
	}

	/** the picture if it is in memory; never loads */
	BufferedImage peek(int z, int tx, int ty)
	{
		synchronized (cache)
		{
			return cache.get(key(z, tx, ty));
		}
	}

	boolean exists(int z, int tx, int ty)
	{
		return regions.hasTile(z, tx, ty) && !absent.contains(key(z, tx, ty));
	}

	/** Tell the loader this square is wanted for the frame being drawn; returns it if already in memory. */
	BufferedImage want(int z, int tx, int ty)
	{
		BufferedImage img = peek(z, tx, ty);
		if (img == null && exists(z, tx, ty))
		{
			synchronized (wanted)
			{
				if (wantedKeys.add(key(z, tx, ty)))
				{
					wanted.add(new long[]{z, tx, ty});
				}
			}
		}
		return img;
	}

	/** The frame is drawn: the squares wanted by it (in the order asked) replace whatever was queued before. */
	void endFrame()
	{
		long now = System.currentTimeMillis();
		synchronized (lock)
		{
			queue.clear();
			synchronized (wanted)
			{
				for (long[] w : wanted)
				{
					long k = key((int) w[0], (int) w[1], (int) w[2]);
					Long f = failed.get(k);
					if (!loading.contains(k) && (f == null || now - f > RETRY_MS))
					{
						queue.add(w);
					}
				}
				wanted.clear();
				wantedKeys.clear();
			}
			if (!queue.isEmpty())
			{
				lock.notifyAll();
			}
		}
	}

	/** how many squares are being fetched or waiting */
	int busy()
	{
		synchronized (lock)
		{
			return loading.size() + queue.size();
		}
	}

	/** true while recent downloads keep failing */
	boolean offline()
	{
		return failures >= 3;
	}

	void releaseMemory()
	{
		synchronized (cache)
		{
			cache.clear();
		}
	}

	void shutdown()
	{
		running = false;
		synchronized (lock)
		{
			lock.notifyAll();
		}
		for (Thread t : workers)
		{
			t.interrupt();
		}
	}

	private void work()
	{
		while (running)
		{
			long[] w;
			long k;
			synchronized (lock)
			{
				while (running && queue.isEmpty())
				{
					try
					{
						lock.wait();
					}
					catch (InterruptedException e)
					{
						return;
					}
				}
				if (!running)
				{
					return;
				}
				w = queue.pollFirst();
				k = key((int) w[0], (int) w[1], (int) w[2]);
				if (!loading.add(k))
				{
					continue;
				}
			}
			try
			{
				BufferedImage img = load((int) w[0], (int) w[1], (int) w[2]);
				if (img != null)
				{
					synchronized (cache)
					{
						cache.put(k, img);
					}
					failures = 0;
					onChange.run();
				}
			}
			catch (Exception e)
			{
				log.debug("map tile {} failed: {}", java.util.Arrays.toString(w), e.toString());
				synchronized (lock)
				{
					failed.put(k, System.currentTimeMillis());
				}
				failures++;
			}
			finally
			{
				synchronized (lock)
				{
					loading.remove(k);
				}
			}
		}
	}

	private File fileOf(int z, int tx, int ty)
	{
		return new File(new File(dir, Integer.toString(z)), "0_" + tx + "_" + ty + ".png");
	}

	private BufferedImage load(int z, int tx, int ty) throws IOException
	{
		File f = fileOf(z, tx, ty);
		byte[] data = null;
		if (f.isFile() && f.length() > 0)
		{
			data = Files.readAllBytes(f.toPath());
		}
		else if (http != null)
		{
			Request req = new Request.Builder().url(String.format(URL, z, tx, ty)).build();
			try (Response resp = http.newCall(req).execute())
			{
				if (resp.code() == 404)
				{
					absent.add(key(z, tx, ty));
					return null;
				}
				if (!resp.isSuccessful() || resp.body() == null)
				{
					throw new IOException("HTTP " + resp.code());
				}
				data = resp.body().bytes();
			}
			BufferedImage test = decode(data);
			if (test == null)
			{
				throw new IOException("not a picture");
			}
			f.getParentFile().mkdirs();
			File tmp = new File(f.getPath() + "." + java.util.UUID.randomUUID() + ".part");
			Files.write(tmp.toPath(), data);
			Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return test;
		}
		if (data == null)
		{
			return null;
		}
		BufferedImage img = decode(data);
		if (img == null)
		{
			f.delete(); // broken file: fetch it again next time
		}
		return img;
	}

	private static BufferedImage decode(byte[] data) throws IOException
	{
		BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
		if (src == null)
		{
			return null;
		}
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return out;
	}
}
