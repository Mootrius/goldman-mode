package com.goldmanmode;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What every quest, diary task, combat achievement and collection log item pays, from the economy spreadsheet (payouts.json). */
final class Payouts
{
	/** gp for one task and gp for finishing the tier, by tier name (lower case) */
	static final class Tier
	{
		final long task;
		final long bonus;

		Tier(long task, long bonus)
		{
			this.task = task;
			this.bonus = bonus;
		}
	}

	/** a quest title found in some text and what it pays */
	static final class QuestHit
	{
		final String name;
		final long gp;

		QuestHit(String name, long gp)
		{
			this.name = name;
			this.gp = gp;
		}
	}

	private final Map<String, Long> quests = new HashMap<>();
	/** quest names, longest first, so "Dragon Slayer II" is found before "Dragon Slayer I" */
	private final List<String> questNames = new ArrayList<>();
	private final Map<String, Long> clog = new HashMap<>();
	private final Map<String, Tier> diaries = new HashMap<>();
	private final Map<String, Tier> combat = new HashMap<>();

	static Payouts load()
	{
		try (InputStream in = Payouts.class.getResourceAsStream("payouts.json"))
		{
			if (in == null)
			{
				return new Payouts();
			}
			return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			return new Payouts();
		}
	}

	/** quest payouts are paid this many times what the spreadsheet says */
	static final long QUEST_MULTIPLIER = 2;
	/** achievement diary payouts (each task and each tier bonus) are paid this many times what the spreadsheet says */
	static final long DIARY_MULTIPLIER = 2;

	static Payouts parse(Reader r)
	{
		Payouts p = new Payouts();
		JsonObject root = new JsonParser().parse(r).getAsJsonObject();
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("quests").entrySet())
		{
			p.quests.put(normalise(e.getKey()), QUEST_MULTIPLIER * e.getValue().getAsLong());
		}
		p.questNames.addAll(p.quests.keySet());
		p.questNames.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("clog").entrySet())
		{
			p.clog.put(normalise(e.getKey()), e.getValue().getAsLong());
		}
		readTiers(root.getAsJsonObject("diaries"), p.diaries, DIARY_MULTIPLIER);
		readTiers(root.getAsJsonObject("combat"), p.combat, 1);
		return p;
	}

	private static void readTiers(JsonObject o, Map<String, Tier> into, long multiplier)
	{
		for (Map.Entry<String, JsonElement> e : o.entrySet())
		{
			JsonObject t = e.getValue().getAsJsonObject();
			into.put(e.getKey().toLowerCase(Locale.ROOT), new Tier(multiplier * t.get("task").getAsLong(), multiplier * t.get("bonus").getAsLong()));
		}
	}

	/** lower case, no colour tags, straight apostrophes, single spaces */
	static String normalise(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replaceAll("<[^>]*>", "")
			.replace('’', '\'').replace('‘', '\'')
			.replace(' ', ' ')
			.trim().replaceAll("\\s+", " ")
			.toLowerCase(Locale.ROOT);
	}

	int questCount()
	{
		return quests.size();
	}

	int clogCount()
	{
		return clog.size();
	}

	long questTotal()
	{
		return quests.values().stream().mapToLong(Long::longValue).sum();
	}

	long clogTotal()
	{
		return clog.values().stream().mapToLong(Long::longValue).sum();
	}

	/**
	 * The quest named somewhere in some text such as "You have completed Dragon Slayer II!"
	 * (the longest quest name wins, and a name must stand on its own, not sit inside a longer word).
	 */
	QuestHit questIn(String text)
	{
		String t = normalise(text);
		for (String name : questNames)
		{
			int from = 0;
			int at;
			while ((at = t.indexOf(name, from)) >= 0)
			{
				boolean startOk = at == 0 || !Character.isLetterOrDigit(t.charAt(at - 1));
				int end = at + name.length();
				boolean endOk = end >= t.length() || !Character.isLetterOrDigit(t.charAt(end));
				if (startOk && endOk)
				{
					return new QuestHit(name, quests.get(name));
				}
				from = at + 1;
			}
		}
		return null;
	}

	/** gp a quest pays, by its exact name as listed in the game's quest list, or -1 if it isn't one we know */
	long quest(String name)
	{
		Long v = quests.get(normalise(name));
		return v == null ? -1 : v;
	}

	/** gp for a collection log item (by its name in the game), or -1 if it isn't one we know */
	long clogItem(String name)
	{
		Long v = clog.get(normalise(name));
		return v == null ? -1 : v;
	}

	Tier diary(String tier)
	{
		return diaries.get(tier.toLowerCase(Locale.ROOT));
	}

	Tier combat(String tier)
	{
		return combat.get(tier.toLowerCase(Locale.ROOT));
	}
}
