package com.goldmanmode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;

/**
 * Everything about Goldman progress: the areas, which are unlocked, and the GP balance.
 * Persisted in the RuneLite profile config (group "goldmanmode").
 */
@Slf4j
@Singleton
public class GoldmanState
{
	static final String KEY_UNLOCKED = "unlocked";
	static final String KEY_BALANCE = "balance";
	/** experience gained that has not been turned into whole gp yet (can be a little negative: see {@link #convertXp}) */
	static final String KEY_XP_POOL = "xpPool";
	/** quests, collection log items, combat tasks and diary / combat tiers that have already been paid, so nothing pays twice */
	static final String KEY_CLAIMED = "claimed";
	/** drop a file here to use your own areas without rebuilding the plugin */
	public static final File OVERRIDE_FILE = new File(new File(RuneLite.RUNELITE_DIR, "goldman-mode"), "areas.json");

	/** the kinds of payout the settings menu can multiply */
	public enum Payout
	{
		EXPERIENCE, KILLS, SKILL_LEVELS, COMBAT_LEVELS, QUESTS, DIARIES, COMBAT_ACHIEVEMENTS, COLLECTION_LOG
	}

	/** the biggest multiplier the plugin will use, so a typo can't overflow a balance */
	static final double MAX_MULTIPLIER = 1000.0;

	private final ConfigManager configManager;
	/** from the settings menu; 1.0 until the config has been read. Replaced as a whole when it is re-read. */
	private volatile double costMultiplier = 1.0;
	private volatile double[] payoutMultipliers = new double[Payout.values().length];

	private volatile AreaIndex index = new AreaIndex(new ArrayList<>());
	private volatile boolean[] locked = new boolean[0];
	private volatile String source = "";
	private final Set<String> unlockedIds = new HashSet<>();
	private final Set<String> freeNames = new HashSet<>();
	private long balance;
	private long xpPool;
	private final Set<String> claimed = new HashSet<>();
	private boolean claimedDirty; // the claimed list is big: only write it when it changed
	private boolean saving; // true while we write our own settings, so the change events they cause are ignored
	private volatile GoldmanArea current;

	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	@Inject
	GoldmanState(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	// ---------------------------------------------------------------- loading

	public void load()
	{
		AreaIndex idx = null;
		String src = "built-in";
		if (OVERRIDE_FILE.isFile())
		{
			try (InputStream in = new FileInputStream(OVERRIDE_FILE))
			{
				idx = AreaIndex.load(in);
				src = OVERRIDE_FILE.getPath();
			}
			catch (Exception e)
			{
				log.warn("Could not read {}, using built-in areas", OVERRIDE_FILE, e);
			}
		}
		if (idx == null)
		{
			try (InputStream in = GoldmanState.class.getResourceAsStream("areas.json"))
			{
				idx = in == null ? new AreaIndex(new ArrayList<>()) : AreaIndex.load(in);
			}
			catch (IOException e)
			{
				log.error("Could not read built-in areas", e);
				idx = new AreaIndex(new ArrayList<>());
			}
		}
		idx.getBarrier(); // build the barrier outline now, not in the middle of a frame
		synchronized (this)
		{
			index = idx;
			source = src;
			readConfig();
			rebuildLocked();
		}
		fire();
	}

	/** Re-read everything stored in the config (call after config changes / profile switches). */
	public synchronized void readConfig()
	{
		unlockedIds.clear();
		String u = configManager.getConfiguration(GoldmanModeConfig.GROUP, KEY_UNLOCKED);
		if (u != null && !u.isEmpty())
		{
			for (String s : u.split(","))
			{
				if (!s.isEmpty())
				{
					unlockedIds.add(s);
				}
			}
		}
		Long b = configManager.getConfiguration(GoldmanModeConfig.GROUP, KEY_BALANCE, Long.class);
		balance = b == null ? 0 : b;
		Long pool = configManager.getConfiguration(GoldmanModeConfig.GROUP, KEY_XP_POOL, Long.class);
		xpPool = pool == null ? 0 : pool;
		claimed.clear();
		String cl = configManager.getConfiguration(GoldmanModeConfig.GROUP, KEY_CLAIMED);
		if (cl != null && !cl.isEmpty())
		{
			for (String s : cl.split("\\|"))
			{
				if (!s.isEmpty())
				{
					claimed.add(s);
				}
			}
		}
		claimedDirty = false;
		readMultipliers();
		freeNames.clear();
		for (String s : Settings.STARTING_AREAS.split(","))
		{
			String t = s.trim().toLowerCase(Locale.ROOT);
			if (!t.isEmpty())
			{
				freeNames.add(t);
			}
		}
		rebuildLocked();
	}

	private void readMultipliers()
	{
		GoldmanModeConfig c = configManager.getConfig(GoldmanModeConfig.class);
		costMultiplier = clampMultiplier(c.areaCostMultiplier());
		double[] m = new double[Payout.values().length];
		m[Payout.EXPERIENCE.ordinal()] = clampMultiplier(c.xpMultiplier());
		m[Payout.KILLS.ordinal()] = clampMultiplier(c.killsMultiplier());
		m[Payout.SKILL_LEVELS.ordinal()] = clampMultiplier(c.skillLevelsMultiplier());
		m[Payout.COMBAT_LEVELS.ordinal()] = clampMultiplier(c.combatLevelsMultiplier());
		m[Payout.QUESTS.ordinal()] = clampMultiplier(c.questsMultiplier());
		m[Payout.DIARIES.ordinal()] = clampMultiplier(c.diariesMultiplier());
		m[Payout.COMBAT_ACHIEVEMENTS.ordinal()] = clampMultiplier(c.combatAchievementsMultiplier());
		m[Payout.COLLECTION_LOG.ordinal()] = clampMultiplier(c.collectionLogMultiplier());
		payoutMultipliers = m;
	}

	/** a multiplier from the settings, kept between 0 and {@link #MAX_MULTIPLIER} (a blank or nonsense value counts as 0) */
	static double clampMultiplier(double m)
	{
		return Double.isNaN(m) ? 0 : Math.max(0, Math.min(MAX_MULTIPLIER, m));
	}

	/** gp times a multiplier, rounded to the nearest gp */
	static long scale(long gp, double multiplier)
	{
		return Math.round(gp * clampMultiplier(multiplier));
	}

	/** the "Area costs" multiplier from the settings */
	public double getCostMultiplier()
	{
		return costMultiplier;
	}

	/** the multiplier from the settings for one kind of payout */
	public double getPayoutMultiplier(Payout p)
	{
		return payoutMultipliers[p.ordinal()];
	}

	/** what a payout of gp is worth after its multiplier */
	public long scaled(Payout p, long gp)
	{
		return scale(gp, getPayoutMultiplier(p));
	}

	public void configChanged()
	{
		if (saving)
		{
			return; // our own write: the memory copy is already the newest
		}
		readConfig();
		fire();
	}

	private synchronized void save()
	{
		saving = true;
		try
		{
			configManager.setConfiguration(GoldmanModeConfig.GROUP, KEY_UNLOCKED, String.join(",", unlockedIds));
			configManager.setConfiguration(GoldmanModeConfig.GROUP, KEY_BALANCE, balance);
			configManager.setConfiguration(GoldmanModeConfig.GROUP, KEY_XP_POOL, xpPool);
			if (claimedDirty)
			{
				configManager.setConfiguration(GoldmanModeConfig.GROUP, KEY_CLAIMED, String.join("|", claimed));
				claimedDirty = false;
			}
		}
		finally
		{
			saving = false;
		}
	}

	/**
	 * Turns experience into whole gp, rounding to the nearest gp and carrying the leftover so nothing is lost:
	 * with 23 xp per gp, 12 xp pays 1 gp (the pool goes to -11) and the next 11 xp pays nothing.
	 *
	 * @return {gp earned, new pool}
	 */
	static long[] convertXp(long pool, long xp, int xpPerGp)
	{
		return convertXp(pool, xp, Math.max(1, xpPerGp), 1.0);
	}

	/** As above, with the Experience multiplier: 2.0 makes each gp cost half the xp, 0.5 twice as much. */
	static long[] convertXp(long pool, long xp, int xpPerGp, double multiplier)
	{
		double m = clampMultiplier(multiplier);
		if (m <= 0)
		{
			return new long[]{0, pool}; // paying nothing for xp: nothing to carry either
		}
		double rate = Math.max(1, xpPerGp) / m; // xp per gp
		long total = pool + xp;
		long gp = Math.round(total / rate);
		return new long[]{gp, total - Math.round(gp * rate)};
	}

	/** Pay for experience just gained. @return the gp earned by this call (often 0 for a small drop) */
	public long earnFromXp(long xp, int xpPerGp)
	{
		long gp;
		synchronized (this)
		{
			long[] r = convertXp(xpPool, xp, xpPerGp, getPayoutMultiplier(Payout.EXPERIENCE));
			gp = r[0];
			xpPool = r[1];
			if (gp != 0)
			{
				balance = Math.max(0, balance + gp);
				save();
			}
		}
		if (gp != 0)
		{
			fire();
		}
		return gp;
	}

	/**
	 * Pays gp for something that can only be paid once (a quest, a collection log item, a tier...).
	 *
	 * @param key a name for the thing being paid, unique to it; null for something that can repeat
	 * @return the gp paid, 0 if it had been paid before (or there is nothing to pay)
	 */
	public long award(String key, long gp)
	{
		if (gp <= 0)
		{
			if (key != null)
			{
				claimQuietly(java.util.Collections.singleton(key)); // worth nothing now (a 0 multiplier): still done, so it can't pay later
			}
			return 0;
		}
		synchronized (this)
		{
			if (key != null)
			{
				if (!claimed.add(key))
				{
					return 0;
				}
				claimedDirty = true;
			}
			balance = Math.max(0, balance + gp);
			save();
		}
		fire();
		return gp;
	}

	public synchronized boolean isClaimed(String key)
	{
		return claimed.contains(key);
	}

	/** Marks things as already paid without paying them (what you had before the plugin started watching). */
	public synchronized void claimQuietly(java.util.Collection<String> keys)
	{
		if (claimed.addAll(keys))
		{
			claimedDirty = true;
			save();
		}
	}

	/** Keep the not-yet-paid experience (call when the plugin stops). */
	public synchronized void flush()
	{
		save();
	}

	private void rebuildLocked()
	{
		AreaIndex idx = index;
		boolean[] l = new boolean[idx.getAreas().size()];
		for (int i = 0; i < l.length; i++)
		{
			l[i] = !isUnlocked(idx.get(i));
		}
		locked = l;
	}

	// ---------------------------------------------------------------- queries

	public AreaIndex getIndex()
	{
		return index;
	}

	public String getSource()
	{
		return source;
	}

	public synchronized long getBalance()
	{
		return balance;
	}

	public boolean isFree(GoldmanArea a)
	{
		return freeNames.contains(a.getName().toLowerCase(Locale.ROOT));
	}

	public synchronized boolean isUnlocked(GoldmanArea a)
	{
		return isFree(a) || unlockedIds.contains(a.getId());
	}

	/** Fast per-frame check by area index. */
	public boolean isLockedIdx(int idx)
	{
		boolean[] l = locked;
		return idx >= 0 && idx < l.length && l[idx];
	}

	/**
	 * The area's price from the areas file (size, what is there, usefulness, danger, earning potential - see
	 * tools/price_areas.py) times the "Area costs" multiplier from the settings menu, rounded to the nearest 100. An area with no price
	 * in the file costs its size in tiles x 65. Free areas cost 0.
	 */
	public long costOf(GoldmanArea a)
	{
		if (isFree(a))
		{
			return 0;
		}
		double m = costMultiplier;
		if (m <= 0)
		{
			return 0;
		}
		double base = a.getPrice() >= 0 ? a.getPrice() : a.getSize() * 65.0;
		return Math.max(100L, Math.round(base / 100.0 * m) * 100L);
	}

	public synchronized int unlockedCount()
	{
		int n = 0;
		for (GoldmanArea a : index.getAreas())
		{
			if (isUnlocked(a))
			{
				n++;
			}
		}
		return n;
	}

	public GoldmanArea getCurrent()
	{
		return current;
	}

	public void setCurrent(GoldmanArea a)
	{
		if (a != current)
		{
			current = a;
			fire();
		}
	}

	// ---------------------------------------------------------------- changes

	/** @return null on success, otherwise the reason it failed */
	public String buy(GoldmanArea a)
	{
		synchronized (this)
		{
			if (isUnlocked(a))
			{
				return "Already unlocked";
			}
			long cost = costOf(a);
			if (cost > balance)
			{
				return "Not enough GP";
			}
			balance -= cost;
			unlockedIds.add(a.getId());
			rebuildLocked();
			save();
		}
		fire();
		return null;
	}

	/** Testing helper. */
	public void relock(GoldmanArea a)
	{
		synchronized (this)
		{
			if (unlockedIds.remove(a.getId()))
			{
				balance += costOf(a); // the price comes back
			}
			rebuildLocked();
			save();
		}
		fire();
	}

	public void addBalance(long delta)
	{
		synchronized (this)
		{
			balance = Math.max(0, balance + delta);
			save();
		}
		fire();
	}

	public void setBalance(long value)
	{
		synchronized (this)
		{
			balance = Math.max(0, value);
			save();
		}
		fire();
	}

	/** Testing helper: relock everything and zero the balance. */
	public void resetAll()
	{
		synchronized (this)
		{
			unlockedIds.clear();
			balance = 0;
			xpPool = 0;
			rebuildLocked();
			save();
		}
		fire();
	}

	// ---------------------------------------------------------------- listeners

	public void addListener(Runnable r)
	{
		listeners.add(r);
	}

	public void removeListener(Runnable r)
	{
		listeners.remove(r);
	}

	private void fire()
	{
		for (Runnable r : listeners)
		{
			try
			{
				r.run();
			}
			catch (RuntimeException e)
			{
				log.warn("listener failed", e);
			}
		}
	}
}
