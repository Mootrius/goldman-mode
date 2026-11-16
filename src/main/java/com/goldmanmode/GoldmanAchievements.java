package com.goldmanmode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

/**
 * Goldman GP for quests, achievement diary tasks, combat achievements and collection log items.
 * (Experience and level-ups are paid in {@link GoldmanModePlugin}.) Everything here runs on the game thread.
 *
 * <ul>
 * <li><b>Quests</b>: when the quest complete scroll opens, the quest named on it is paid.</li>
 * <li><b>Diary tasks</b>: the game keeps a count of finished tasks for each area and tier; every task the count
 * goes up by is paid. The tier's bonus is paid when the game says "you have completed all of the easy tasks in the
 * Lumbridge &amp; Draynor area" (a chat message or the "Click here to continue" box), or when the area's tier is marked complete.</li>
 * <li><b>Combat achievements</b>: each "you've completed a ... combat task" message pays that tier's task amount;
 * a tier's bonus is paid when the game says "you've completed enough Combat Achievement tasks to unlock Easy Tier
 * rewards", or when your points reach the points that unlock that tier.</li>
 * <li><b>Collection log</b>: each "New item added to your collection log" message pays that item.</li>
 * </ul>
 * Anything you already had when you logged in is never paid, only what you finish afterwards, and each quest,
 * item, task and tier pays only once.
 */
@Slf4j
@Singleton
public class GoldmanAchievements
{
	private static final Pattern COMBAT_TASK = Pattern.compile(
		"(?i).*?completed an? (\\w+) combat task:?\\s*(.+?)(?:\\s*\\(\\d+ points?\\))?\\s*\\.?");
	/** "You've completed enough Combat Achievement tasks to unlock Easy Tier rewards! You can now claim your rewards from Ghommal." */
	private static final Pattern COMBAT_TIER_UNLOCK = Pattern.compile(
		"(?i).*completed enough combat achievement tasks to unlock (\\w+) tier rewards.*");
	/** "Congratulations! You have completed all of the easy tasks in the Lumbridge & Draynor area. Speak to Mikasi ..." */
	private static final Pattern DIARY_TIER_DONE = Pattern.compile(
		"(?i).*completed all of the (\\w+) tasks in the (.+?) area\\b.*");
	/** the plain "Click here to continue" message boxes: the diary message shows up in one of these */
	private static final int[] MESSAGE_BOX_GROUPS = {229, 193};
	private static final Pattern CLOG_ITEM = Pattern.compile("New item added to your collection log: (.*)");
	/** ticks to wait after logging in before looking at what you already have, so the login burst of values has landed */
	private static final int BASELINE_DELAY = 3;

	private static final String[] CA_TIERS = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
	private static final int[] CA_THRESHOLD = {VarbitID.CA_THRESHOLD_EASY, VarbitID.CA_THRESHOLD_MEDIUM, VarbitID.CA_THRESHOLD_HARD,
		VarbitID.CA_THRESHOLD_ELITE, VarbitID.CA_THRESHOLD_MASTER, VarbitID.CA_THRESHOLD_GRANDMASTER};

	/** one tier of one achievement diary area */
	private static final class Diary
	{
		final String area;
		final String tier;
		final int countVarbit;
		final int doneVarbit;
		/** the value of doneVarbit that means finished (Karamja's first three tiers use 2 for finished) */
		final int doneValue;
		int lastCount;
		boolean lastDone;

		Diary(String area, String tier, int countVarbit, int doneVarbit, int doneValue)
		{
			this.area = area;
			this.tier = tier;
			this.countVarbit = countVarbit;
			this.doneVarbit = doneVarbit;
			this.doneValue = doneValue;
		}

		String claimKey()
		{
			return "d:" + area.toLowerCase(Locale.ROOT) + ":" + tier;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final GoldmanState state;
	private final Payouts payouts = Payouts.load();
	private final List<Diary> diaries = new ArrayList<>();

	private int ticksSinceLogin;
	private boolean baselined;

	@Inject
	GoldmanAchievements(Client client, ClientThread clientThread, GoldmanState state)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.state = state;
		buildDiaries();
	}

	/** gp a collection log item pays (by its name in the game), or -1 if it isn't one we know */
	long clogPayout(String itemName)
	{
		long gp = payouts.clogItem(itemName);
		return gp < 0 ? gp : state.scaled(GoldmanState.Payout.COLLECTION_LOG, gp);
	}

	/** gp a quest pays (by its name in the quest list), or -1 if it isn't one we know */
	long questPayout(String questName)
	{
		long gp = payouts.quest(questName);
		return gp < 0 ? gp : state.scaled(GoldmanState.Payout.QUESTS, gp);
	}

	private void buildDiaries()
	{
		diary("Karamja", VarbitID.KARAMJA_EASY_COUNT, VarbitID.KARAMJA_MED_COUNT, VarbitID.KARAMJA_HARD_COUNT, VarbitID.KARAMJA_ELITE_COUNT,
			VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, 2, 2, 2, 1);
		diary("Ardougne", VarbitID.ARDOUGNE_EASY_COUNT, VarbitID.ARDOUGNE_MED_COUNT, VarbitID.ARDOUGNE_HARD_COUNT, VarbitID.ARDOUGNE_ELITE_COUNT,
			VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
		diary("Desert", VarbitID.DESERT_EASY_COUNT, VarbitID.DESERT_MED_COUNT, VarbitID.DESERT_HARD_COUNT, VarbitID.DESERT_ELITE_COUNT,
			VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
		diary("Falador", VarbitID.FALADOR_EASY_COUNT, VarbitID.FALADOR_MED_COUNT, VarbitID.FALADOR_HARD_COUNT, VarbitID.FALADOR_ELITE_COUNT,
			VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
		diary("Fremennik", VarbitID.FREMENNIK_EASY_COUNT, VarbitID.FREMENNIK_MED_COUNT, VarbitID.FREMENNIK_HARD_COUNT, VarbitID.FREMENNIK_ELITE_COUNT,
			VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
		diary("Kandarin", VarbitID.KANDARIN_EASY_COUNT, VarbitID.KANDARIN_MED_COUNT, VarbitID.KANDARIN_HARD_COUNT, VarbitID.KANDARIN_ELITE_COUNT,
			VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
		diary("Kourend & Kebos", VarbitID.KOUREND_EASY_COUNT, VarbitID.KOUREND_MED_COUNT, VarbitID.KOUREND_HARD_COUNT, VarbitID.KOUREND_ELITE_COUNT,
			VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
		diary("Lumbridge & Draynor", VarbitID.LUMBRIDGE_EASY_COUNT, VarbitID.LUMBRIDGE_MED_COUNT, VarbitID.LUMBRIDGE_HARD_COUNT, VarbitID.LUMBRIDGE_ELITE_COUNT,
			VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
		diary("Morytania", VarbitID.MORYTANIA_EASY_COUNT, VarbitID.MORYTANIA_MED_COUNT, VarbitID.MORYTANIA_HARD_COUNT, VarbitID.MORYTANIA_ELITE_COUNT,
			VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
		diary("Varrock", VarbitID.VARROCK_EASY_COUNT, VarbitID.VARROCK_MED_COUNT, VarbitID.VARROCK_HARD_COUNT, VarbitID.VARROCK_ELITE_COUNT,
			VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
		diary("Western Provinces", VarbitID.WESTERN_EASY_COUNT, VarbitID.WESTERN_MED_COUNT, VarbitID.WESTERN_HARD_COUNT, VarbitID.WESTERN_ELITE_COUNT,
			VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
		diary("Wilderness", VarbitID.WILDERNESS_EASY_COUNT, VarbitID.WILDERNESS_MED_COUNT, VarbitID.WILDERNESS_HARD_COUNT, VarbitID.WILDERNESS_ELITE_COUNT,
			VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE, 1, 1, 1, 1);
	}

	/** counts and done flags are given easy, medium, hard, elite */
	private void diary(String area, int c1, int c2, int c3, int c4, int d1, int d2, int d3, int d4, int v1, int v2, int v3, int v4)
	{
		String[] tier = {"easy", "medium", "hard", "elite"};
		int[] c = {c1, c2, c3, c4}, d = {d1, d2, d3, d4}, v = {v1, v2, v3, v4};
		for (int i = 0; i < 4; i++)
		{
			diaries.add(new Diary(area, tier[i], c[i], d[i], v[i]));
		}
	}

	/** Forget what you had: call whenever you leave the game, so the next login is looked at afresh. */
	public void reset()
	{
		baselined = false;
		ticksSinceLogin = 0;
	}

	// ------------------------------------------------------------------ diaries and combat achievement tiers

	/** Every game tick. */
	public void onGameTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (!baselined)
		{
			if (++ticksSinceLogin >= BASELINE_DELAY)
			{
				baseline();
			}
			return;
		}
		scanMessageBoxes();
		for (Diary d : diaries)
		{
			int count = client.getVarbitValue(d.countVarbit);
			if (count > d.lastCount)
			{
				int tasks = count - d.lastCount;
				Payouts.Tier t = payouts.diary(d.tier);
				if (t != null)
				{
					announceIf(state.award(null, state.scaled(GoldmanState.Payout.DIARIES, t.task * tasks)),
						diaryTasksText(tasks, d.tier, d.area));
				}
			}
			d.lastCount = count;
			boolean done = client.getVarbitValue(d.doneVarbit) >= d.doneValue;
			if (done && !d.lastDone)
			{
				Payouts.Tier t = payouts.diary(d.tier);
				if (t != null)
				{
					announceIf(state.award(d.claimKey(), state.scaled(GoldmanState.Payout.DIARIES, t.bonus)), "completing the " + d.tier + " " + d.area + " diary");
				}
			}
			d.lastDone = done;
		}
		payCombatTiers();
	}

	private void baseline()
	{
		List<String> have = new ArrayList<>();
		for (Diary d : diaries)
		{
			d.lastCount = client.getVarbitValue(d.countVarbit);
			d.lastDone = client.getVarbitValue(d.doneVarbit) >= d.doneValue;
			if (d.lastDone)
			{
				have.add(d.claimKey());
			}
		}
		int points = client.getVarbitValue(VarbitID.CA_POINTS);
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			int need = client.getVarbitValue(CA_THRESHOLD[i]);
			if (need > 0 && points >= need)
			{
				have.add("a:" + CA_TIERS[i]);
			}
		}
		state.claimQuietly(have);
		baselined = true;
	}

	/** A combat achievement tier is complete once your points reach the points that unlock it. */
	private void payCombatTiers()
	{
		int points = client.getVarbitValue(VarbitID.CA_POINTS);
		for (int i = 0; i < CA_TIERS.length; i++)
		{
			int need = client.getVarbitValue(CA_THRESHOLD[i]);
			if (need > 0 && points >= need)
			{
				payCombatTier(CA_TIERS[i]);
			}
		}
	}

	/** Pay a combat achievement tier's bonus (once). */
	private void payCombatTier(String tier)
	{
		Payouts.Tier t = payouts.combat(tier);
		if (t == null)
		{
			log.info("Combat achievement tier not in the payout list: {}", tier);
			return;
		}
		announceIf(state.award("a:" + tier, state.scaled(GoldmanState.Payout.COMBAT_ACHIEVEMENTS, t.bonus)), "completing the " + tier + " combat achievements tier");
	}

	/** Pay a diary tier's bonus (once) when the game announces that every task of that tier in the area is done. */
	private void payDiaryTier(String tier, String area)
	{
		String want = areaKey(area);
		for (Diary d : diaries)
		{
			if (d.tier.equals(tier) && areaKey(d.area).equals(want))
			{
				Payouts.Tier t = payouts.diary(d.tier);
				if (t != null)
				{
					announceIf(state.award(d.claimKey(), state.scaled(GoldmanState.Payout.DIARIES, t.bonus)), "completing the " + d.tier + " " + d.area + " diary");
				}
				d.lastDone = true;
				return;
			}
		}
		log.info("Diary completion message for an area or tier we don't know: {} {}", tier, area);
	}

	/** The diary "Congratulations!" box is a plain message box: read it while it is open (paying is once only, so every tick is fine). */
	private void scanMessageBoxes()
	{
		for (int group : MESSAGE_BOX_GROUPS)
		{
			for (int child = 0; child <= 4; child++)
			{
				Widget w = client.getWidget(group, child);
				if (w == null || w.isHidden() || w.getText() == null || w.getText().isEmpty())
				{
					continue;
				}
				String[] done = parseDiaryTierDone(w.getText());
				if (done != null)
				{
					payDiaryTier(done[0], done[1]);
				}
			}
		}
	}

	/** an area's name without case, "and" versus "&", spaces or punctuation, so "Lumbridge & Draynor" matches however the game writes it */
	static String areaKey(String area)
	{
		return area.toLowerCase(Locale.ROOT).replace("&amp;", "&").replace(" and ", " & ").replaceAll("[^a-z]", "");
	}

	/** message text without colour tags, line breaks (as spaces), non-breaking spaces or "&amp;" */
	private static String plain(String message)
	{
		return Text.removeTags(message.replaceAll("(?i)<br\\s*/?>", " ")).replace('\u00a0', ' ').replace("&amp;", "&")
			.replaceAll("\\s+", " ").trim();
	}

	/** The tier a "completed enough Combat Achievement tasks to unlock ... Tier rewards" message names (lower case), or null. */
	static String parseCombatTierUnlock(String message)
	{
		Matcher m = COMBAT_TIER_UNLOCK.matcher(plain(message));
		return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
	}

	/** {tier, area} from "You have completed all of the easy tasks in the Lumbridge & Draynor area", or null if it is something else. */
	static String[] parseDiaryTierDone(String message)
	{
		Matcher m = DIARY_TIER_DONE.matcher(plain(message));
		return m.matches() ? new String[]{m.group(1).toLowerCase(Locale.ROOT), m.group(2).trim()} : null;
	}

	// ------------------------------------------------------------------ chat messages: combat tasks and collection log

	/** What a combat task message says: {tier, task}, or null if the message is something else. */
	static String[] parseCombatTask(String message)
	{
		Matcher m = COMBAT_TASK.matcher(Text.removeTags(message).replace('\u00a0', ' ').trim());
		return m.matches() ? new String[]{m.group(1).toLowerCase(Locale.ROOT), m.group(2).trim()} : null;
	}

	/** The item a collection log message announces, or null if the message is something else. */
	static String parseCollectionLogItem(String message)
	{
		Matcher m = CLOG_ITEM.matcher(Text.removeTags(message).trim());
		return m.matches() ? m.group(1).trim() : null;
	}

	/** Every chat message. */
	public void onChatMessage(ChatMessageType type, String message)
	{
		if (message == null)
		{
			return;
		}
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.ENGINE)
		{
			if (message.toLowerCase(Locale.ROOT).contains("combat task"))
			{
				log.info("Ignored a {} message about a combat task: {}", type, message);
			}
			return;
		}
		String unlockedTier = parseCombatTierUnlock(message);
		if (unlockedTier != null)
		{
			payCombatTier(unlockedTier);
			return;
		}
		String[] diaryDone = parseDiaryTierDone(message);
		if (diaryDone != null)
		{
			payDiaryTier(diaryDone[0], diaryDone[1]);
			return;
		}
		String[] task = parseCombatTask(message);
		if (task != null)
		{
			Payouts.Tier t = payouts.combat(task[0]);
			if (t == null)
			{
				log.info("Combat task tier not in the payout list: {}", message);
			}
			else
			{
				long paid = state.award("t:" + Payouts.normalise(task[1]), state.scaled(GoldmanState.Payout.COMBAT_ACHIEVEMENTS, t.task));
				if (paid == 0)
				{
					log.info("Combat task not paid (already paid before?): {}", message);
				}
				announceIf(paid, "the " + task[0] + " combat task: " + task[1]);
			}
			return;
		}
		String item = parseCollectionLogItem(message);
		if (item != null)
		{
			long gp = payouts.clogItem(item);
			if (gp < 0)
			{
				log.info("Collection log item not in the payout list: {}", item);
				return;
			}
			announceIf(state.award("c:" + Payouts.normalise(item), state.scaled(GoldmanState.Payout.COLLECTION_LOG, gp)), "the collection log item: " + item);
		}
	}

	// ------------------------------------------------------------------ quests

	/** The quest complete scroll has opened. Its text is filled in a moment later, so look for it for a few ticks. */
	public void onWidgetLoaded(int groupId)
	{
		if (groupId != InterfaceID.QUESTSCROLL)
		{
			return;
		}
		final int[] tries = {0};
		clientThread.invokeLater(() ->
		{
			Widget title = client.getWidget(InterfaceID.Questscroll.QUEST_TITLE);
			String text = title == null ? null : title.getText();
			if ((text == null || text.isEmpty()) && ++tries[0] < 5)
			{
				return false; // not filled in yet: look again next tick
			}
			if (text != null && !text.isEmpty())
			{
				payQuest(text);
			}
			return true;
		});
	}

	void payQuest(String text)
	{
		Payouts.QuestHit q = payouts.questIn(text);
		if (q == null)
		{
			log.info("Quest scroll not in the payout list: {}", text);
			return;
		}
		announceIf(state.award("q:" + q.name, state.scaled(GoldmanState.Payout.QUESTS, q.gp)), "completing the quest: " + questTitle(text, q.name));
	}

	/** the quest's name as the game spells it ("Dragon Slayer II"), picked out of text such as "You have completed Dragon Slayer II!" */
	static String questTitle(String text, String normalisedName)
	{
		String plain = Text.removeTags(text).trim();
		String pattern = Pattern.quote(normalisedName).replace(" ", "\\E\\s+\\Q").replace("'", "\\E['\u2018\u2019]\\Q");
		Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(plain);
		return m.find() ? m.group() : plain;
	}

	// ------------------------------------------------------------------ chat feedback

	/** "an easy Karamja diary task", "a hard Karamja diary task" or "3 easy Karamja diary tasks" */
	static String diaryTasksText(int tasks, String tier, String area)
	{
		String lead = tasks != 1 ? tasks + " " : ("aeiou".indexOf(Character.toLowerCase(tier.charAt(0))) >= 0 ? "an " : "a ");
		return lead + tier + " " + area + " diary task" + (tasks == 1 ? "" : "s");
	}

	private void announceIf(long gp, String what)
	{
		if (gp <= 0)
		{
			return;
		}
		log.info("Goldman GP +{} for {}", gp, what);
		if (Settings.ANNOUNCE_PAYOUTS)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ffffff>Goldman Mode:</col> <col=ffff00>+" + QuantityFormatter.formatNumber(gp) + " gp for " + what + ".</col>", null);
		}
	}
}
