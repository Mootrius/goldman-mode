package com.goldmanmode;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Goldman Mode",
	internalName = "goldman-mode",
	description = "Unlock Gielinor one area at a time by earning and spending Goldman GP!",
	tags = {"ironman", "goldman", "unlock", "areas", "challenge", "restricted"}
)
public class GoldmanModePlugin extends Plugin
{
	@Inject
	private Client client;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private GoldmanState state;
	@Inject
	private GoldmanPanel panel;
	@Inject
	private GoldmanBarrierOverlay sceneOverlay;
	@Inject
	private GoldmanGpOverlay gpOverlay;
	@Inject
	private GoldmanMinimapOverlay minimapOverlay;
	@Inject
	private GoldmanMapOverlay mapOverlay;
	@Inject
	private GoldmanMapWindow mapWindow;
	@Inject
	private GoldmanClickBlocker clickBlocker;
	@Inject
	private GoldmanAchievements achievements;
	@Inject
	private ClogTooltip clogTooltip;

	private NavigationButton navButton;

	@Provides
	GoldmanModeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GoldmanModeConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		Filepath dataDir = getPluginDirectory();
		mapWindow.setDataDirectory(dataDir);
		state.load(dataDir);
		BufferedImage icon = ImageUtil.loadImageResource(GoldmanModePlugin.class, "icon_16.png");
		navButton = NavigationButton.builder()
			.tooltip("Goldman Mode")
			.icon(icon)
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(sceneOverlay);
		overlayManager.add(minimapOverlay);
		overlayManager.add(gpOverlay);
		overlayManager.add(mapOverlay);
		clickBlocker.startUp();
		log.info("Goldman Mode started with {} areas ({})", state.getIndex().getAreas().size(), state.getSource());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(gpOverlay);
		overlayManager.remove(mapOverlay);
		state.flush();
		clickBlocker.shutDown();
		mapWindow.shutDown();
		clientToolbar.removeNavigation(navButton);
		panel.shutDown();
		state.setCurrent(null);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (GoldmanModeConfig.GROUP.equals(e.getGroup()))
		{
			state.configChanged();
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged e)
	{
		state.configChanged();
	}

	// ------------------------------------------------------------------ earning GP from experience

	/** the xp each skill had last time we looked; xp gained after that is paid out */
	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	/** the real level of each skill and the combat level they add up to, for the level-up bonuses */
	private final Map<Skill, Integer> lastLevel = new EnumMap<>(Skill.class);
	private int lastCombat;
	private boolean xpBaselined;

	/** Takes the starting xp of every skill once per login, so the login burst of stats is never paid. */
	private void baselineXp()
	{
		if (xpBaselined || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		lastXp.clear();
		for (Skill s : Skill.values())
		{
			if (!isOverall(s))
			{
				lastXp.put(s, client.getSkillExperience(s));
				lastLevel.put(s, client.getRealSkillLevel(s));
			}
		}
		lastCombat = combatLevel();
		xpBaselined = true;
	}

	/** the game's "all skills" total, which is not a skill we track (Skill.OVERALL is marked deprecated, but still the only way to name it) */
	@SuppressWarnings("deprecation")
	private static boolean isOverall(Skill s)
	{
		return s == Skill.OVERALL;
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		if (!xpBaselined || isOverall(e.getSkill()))
		{
			return;
		}
		Integer prev = lastXp.put(e.getSkill(), e.getXp());
		if (prev != null && e.getXp() > prev)
		{
			long xpGp = state.earnFromXp((long) e.getXp() - prev, Settings.XP_PER_GP);
			// SPAM is the game's "filtered" message type: it only shows when the Game tab is set to All
			announce(ChatMessageType.SPAM, xpGp, e.getSkill().getName() + " xp");
		}

		// level-up bonuses: every skill level gained, and every combat level that follows from it
		Integer prevLevel = lastLevel.put(e.getSkill(), e.getLevel());
		if (prevLevel != null && e.getLevel() > prevLevel)
		{
			long levelGp = state.scaled(GoldmanState.Payout.SKILL_LEVELS, LevelBonuses.forSkillLevels(prevLevel, e.getLevel()));
			if (levelGp > 0)
			{
				state.addBalance(levelGp);
				announce(ChatMessageType.GAMEMESSAGE, levelGp, e.getSkill().getName() + " level " + e.getLevel());
			}
		}
		int combat = combatLevel();
		if (combat > lastCombat)
		{
			long combatGp = state.scaled(GoldmanState.Payout.COMBAT_LEVELS, LevelBonuses.forCombatLevels(lastCombat, combat));
			if (combatGp > 0)
			{
				state.addBalance(combatGp);
				announce(ChatMessageType.GAMEMESSAGE, combatGp, "combat level " + combat);
			}
		}
		lastCombat = combat;
	}

	/** NPCs you have hit; one that dies while it is in here pays out */
	private final Set<NPC> hitByMe = Collections.newSetFromMap(new IdentityHashMap<>());

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		if (e.getHitsplat().isMine() && e.getActor() instanceof NPC)
		{
			hitByMe.add((NPC) e.getActor());
		}
	}

	/** Killing an NPC you hit pays {@link Settings#GP_PER_KILL_LEVEL} gp for each of its combat levels. */
	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		NPC npc = e.getNpc();
		if (!hitByMe.remove(npc) || !npc.isDead())
		{
			return;
		}
		long gp = state.scaled(GoldmanState.Payout.KILLS, LevelBonuses.forKill(npc.getCombatLevel(), Settings.GP_PER_KILL_LEVEL));
		if (gp > 0)
		{
			state.addBalance(gp);
			announce(ChatMessageType.SPAM, gp, "killing " + Text.removeTags(npc.getName() == null ? "an NPC" : npc.getName()));
		}
	}

	/** Writes "+N gp for what" in the chatbox. */
	private void announce(ChatMessageType type, long gp, String what)
	{
		if (gp > 0)
		{
			client.addChatMessage(type, "",
				"<col=ffffff>Goldman Mode:</col> <col=ffff00>+" + QuantityFormatter.formatNumber(gp) + " gp for " + what + ".</col>", null);
		}
	}

	/** combat level from the real levels we are tracking */
	private int combatLevel()
	{
		return Experience.getCombatLevel(
			lastLevel.getOrDefault(Skill.ATTACK, 1), lastLevel.getOrDefault(Skill.STRENGTH, 1),
			lastLevel.getOrDefault(Skill.DEFENCE, 1), lastLevel.getOrDefault(Skill.HITPOINTS, 10),
			lastLevel.getOrDefault(Skill.MAGIC, 1), lastLevel.getOrDefault(Skill.RANGED, 1),
			lastLevel.getOrDefault(Skill.PRAYER, 1));
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		achievements.onChatMessage(e.getType(), e.getMessage());
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		achievements.onWidgetLoaded(e.getGroupId());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState gs = e.getGameState();
		if (gs == GameState.LOGIN_SCREEN || gs == GameState.LOGGING_IN || gs == GameState.HOPPING || gs == GameState.CONNECTION_LOST)
		{
			xpBaselined = false; // look at everyone's xp again once we are back in the game
			hitByMe.clear();
			achievements.reset();
		}
		if (gs == GameState.LOGIN_SCREEN || gs == GameState.HOPPING)
		{
			mapWindow.setPlayer(null);
			state.setCurrent(null);
			mapWindow.closeLater();
		}
		if (gs == GameState.LOGIN_SCREEN)
		{
			mapWindow.resetView(); // next login starts on the whole map again
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		baselineXp();
		achievements.onGameTick();
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		WorldPoint wp = me.getWorldLocation();
		state.setCurrent(wp == null ? null : state.getIndex().areaAt(wp.getX(), wp.getY()));
		mapWindow.setPlayer(wp == null || Instances.isInstanced(client) ? null : wp);
	}

	@Subscribe
	public void onClientTick(ClientTick t)
	{
		clickBlocker.onClientTick();
		clogTooltip.onClientTick();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded e)
	{
		clickBlocker.onMenuEntryAdded(e);
		mapWindow.onMenuEntryAdded(e);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		mapWindow.onMenuOptionClicked(e);
		clickBlocker.onMenuOptionClicked(e);
	}
}
