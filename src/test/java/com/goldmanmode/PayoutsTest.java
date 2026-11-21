package com.goldmanmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class PayoutsTest
{
	private final Payouts p = Payouts.load();

	@Test
	public void totalsMatchTheSpreadsheet()
	{
		assertEquals(191, p.questCount());
		assertEquals(2 * 50_530_000L, p.questTotal()); // quests pay double
		assertEquals(2 * 50_000L, p.diary("easy").bonus); // diary tasks and tier bonuses both pay double
		assertEquals(2 * 10_000L, p.diary("easy").task);
		assertEquals(1_000_000L, p.combat("easy").bonus); // combat achievement tier bonuses: 1m up to 100m, curved between
		assertEquals(100_000_000L, p.combat("grandmaster").bonus);
		long bonusTotal = 0;
		for (String t : new String[]{"easy", "medium", "hard", "elite", "master", "grandmaster"})
		{
			bonusTotal += p.combat(t).bonus;
		}
		assertEquals(165_480_000L, bonusTotal);
		assertEquals(1_027_044_600L, p.clogTotal()); // tools/scale_clog.py: pets 5m (Beef 1m), 3rd age 10m, gilded 5m, the rest scaled to fit a billion, plus the 131 hand-priced November items
		assertNotNull(p.diary("easy"));
		assertNotNull(p.diary("elite"));
		assertNotNull(p.combat("easy"));
		assertNotNull(p.combat("grandmaster"));
	}

	@Test
	public void longestQuestNameWins()
	{
		Payouts.QuestHit one = p.questIn("Congratulations! Quest complete: Dragon Slayer I");
		Payouts.QuestHit two = p.questIn("Dragon Slayer II");
		assertNotNull(one);
		assertNotNull(two);
		assertEquals("dragon slayer ii", two.name);
		assertEquals("dragon slayer i", one.name);
	}

	@Test
	public void unknownTextPaysNothing()
	{
		assertNull(p.questIn("Welcome to the game"));
		assertEquals(-1, p.clogItem("Definitely not an item"));
	}

	@Test
	public void parsesCombatTaskMessages()
	{
		assertArrayEquals(new String[]{"elite", "Perfect Zulrah"},
			GoldmanAchievements.parseCombatTask("Congratulations, you've completed an elite combat task: <col=00ff00>Perfect Zulrah</col> (5 points)."));
		assertArrayEquals(new String[]{"easy", "Defence? What Defence?"},
			GoldmanAchievements.parseCombatTask("Congratulations, you've completed an easy combat task: Defence? What Defence?"));
		assertArrayEquals(new String[]{"hard", "Sneaky Sheep"},
			GoldmanAchievements.parseCombatTask("Congratulations, you\u2019ve completed a hard combat task: <col=06600c>Sneaky Sheep</col> (2 points)."));
		assertNull(GoldmanAchievements.parseCombatTask("You have completed a thing."));
	}

	@Test
	public void parsesCombatTierUnlockMessage()
	{
		assertEquals("easy", GoldmanAchievements.parseCombatTierUnlock(
			"You've completed enough Combat Achievement tasks to unlock Easy Tier rewards! You can now claim your rewards from Ghommal."));
		assertEquals("grandmaster", GoldmanAchievements.parseCombatTierUnlock(
			"<col=ef1020>You\u2019ve completed enough Combat Achievement tasks to unlock Grandmaster Tier rewards! You can now claim your rewards from Ghommal.</col>"));
		assertNull(GoldmanAchievements.parseCombatTierUnlock("Congratulations, you've completed an easy combat task: Defence? What Defence?"));
	}

	@Test
	public void parsesDiaryTierDoneMessage()
	{
		assertArrayEquals(new String[]{"easy", "Lumbridge & Draynor"}, GoldmanAchievements.parseDiaryTierDone(
			"Congratulations! You have completed all of the easy tasks in the Lumbridge & Draynor area. Speak to Mikasi outside of Lumbridge Castle to claim your reward."));
		assertArrayEquals(new String[]{"easy", "Lumbridge & Draynor"}, GoldmanAchievements.parseDiaryTierDone(
			"Congratulations! You have completed all of the easy tasks in the<br>Lumbridge &amp; Draynor area. Speak to Mikasi outside of Lumbridge<br>Castle to claim your reward."));
		assertArrayEquals(new String[]{"elite", "Karamja"}, GoldmanAchievements.parseDiaryTierDone(
			"<col=ef1020>Congratulations! You have completed all of the elite tasks in the Karamja area.</col>"));
		assertNull(GoldmanAchievements.parseDiaryTierDone("You have completed a thing."));
		assertEquals(GoldmanAchievements.areaKey("Kourend & Kebos"), GoldmanAchievements.areaKey("Kourend and Kebos"));
		assertEquals(GoldmanAchievements.areaKey("Lumbridge & Draynor"), GoldmanAchievements.areaKey("lumbridge &amp; draynor"));
	}

	@Test
	public void parsesCollectionLogMessages()
	{
		assertEquals("Abyssal whip", GoldmanAchievements.parseCollectionLogItem("New item added to your collection log: <col=ff0000>Abyssal whip</col>"));
		assertNull(GoldmanAchievements.parseCollectionLogItem("Something else"));
	}

	@Test
	public void newestQuestsPayLikeTheirLengthAndDifficulty()
	{
		// Mudfoot's Big Adventure is Novice / Very Short (like X Marks the Spot), The Writing's on the Wall Intermediate / Short (like The Corsair Curse)
		assertEquals(p.quest("X Marks the Spot"), p.quest("Mudfoot's Big Adventure"));
		assertEquals(p.quest("The Corsair Curse"), p.quest("The Writing's on the Wall"));
		assertEquals(20_000, p.quest("Mudfoot's Big Adventure"));
		assertEquals(130_000, p.quest("The Writing's on the Wall"));
	}

	@Test
	public void questsAddedInNovemberPayLikeTheirLengthAndDifficulty()
	{
		// Daddy's Home and The Birds and the Wasps are Novice / Short (like Romeo & Juliet), A Shell of a Friend Intermediate / Very Short (like Vampyre Slayer)
		assertEquals(p.quest("Romeo & Juliet"), p.quest("Daddy's Home"));
		assertEquals(p.quest("Romeo & Juliet"), p.quest("The Birds and the Wasps"));
		assertEquals(p.quest("Vampyre Slayer"), p.quest("A Shell of a Friend"));
		assertEquals(100_000, p.quest("Daddy's Home"));
		assertEquals(26_000, p.quest("A Shell of a Friend"));
	}

	@Test
	public void questMonthWeekThreeQuestsPayLikeTheirLengthAndDifficulty()
	{
		// A New Leaf is Intermediate / Short (like The Corsair Curse), Rag and Bone Man III Experienced / Medium (like Rag and Bone Man II)
		assertEquals(p.quest("The Corsair Curse"), p.quest("A New Leaf"));
		assertEquals(p.quest("Rag and Bone Man II"), p.quest("Rag and Bone Man III"));
		assertEquals(130_000, p.quest("A New Leaf"));
		assertEquals(540_000, p.quest("Rag and Bone Man III"));
		assertEquals(540_000, p.questIn("Quest complete: Rag and Bone Man III").gp);
		assertEquals(270_000 * 2, p.questIn("Quest complete: Rag and Bone Man II").gp);
	}

	@Test
	public void questPayoutByExactListName()
	{
		assertEquals(p.questIn("Dragon Slayer II").gp, p.quest("<col=ff0000>Dragon Slayer II</col>"));
		assertEquals(-1, p.quest("Free Quests"));
	}

	@Test
	public void questTitleKeepsTheGamesSpelling()
	{
		assertEquals("Dragon Slayer II", GoldmanAchievements.questTitle("You have completed <col=ff0000>Dragon Slayer II</col>!", "dragon slayer ii"));
		assertEquals("Cook\u2019s Assistant", GoldmanAchievements.questTitle("Congratulations! Cook\u2019s Assistant", "cook's assistant"));
	}

	@Test
	public void clogTooltipIsWhitePaysThenYellowAmount()
	{
		assertEquals("<col=ffffff>Pays </col><col=ffff00>38,900 gp</col>", ClogTooltip.text(38_900));
		assertEquals(87_800, p.clogItem("Abyssal whip"));
		assertEquals(5_000_000, p.clogItem("Olmlet"));
		assertEquals(1_000_000, p.clogItem("Beef"));
		assertEquals(10_000_000, p.clogItem("3rd age bow"));
		assertEquals(5_000_000, p.clogItem("Gilded scimitar"));
		assertEquals(5_000_000, p.clogItem("Orion"));
		assertEquals(1_100_000, p.clogItem("Obligator (uncharged)"));
		assertEquals(57_800, p.clogItem("Seren halo"));
		assertEquals(40_000, p.clogItem("Nardah teleport"));
	}

	@Test
	public void diaryTaskAnnouncementsUseTheRightArticle()
	{
		assertEquals("an easy Karamja diary task", GoldmanAchievements.diaryTasksText(1, "easy", "Karamja"));
		assertEquals("an elite Karamja diary task", GoldmanAchievements.diaryTasksText(1, "elite", "Karamja"));
		assertEquals("a medium Karamja diary task", GoldmanAchievements.diaryTasksText(1, "medium", "Karamja"));
		assertEquals("a hard Karamja diary task", GoldmanAchievements.diaryTasksText(1, "hard", "Karamja"));
		assertEquals("2 easy Karamja diary tasks", GoldmanAchievements.diaryTasksText(2, "easy", "Karamja"));
	}
}
