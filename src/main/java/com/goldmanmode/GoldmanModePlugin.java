package com.goldmanmode;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MenuAction;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.NPC;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.Perspective;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.StatChanged;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.ClientTick;
import net.runelite.api.widgets.Widget;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.RuneLite;
import okhttp3.OkHttpClient;
import java.io.File;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Goldman Mode",
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
	private GoldmanModeConfig config;
	@Inject
	private GoldmanPanel panel;
	@Inject
	private GoldmanBarrierOverlay sceneOverlay;
	@Inject
	private GoldmanGpOverlay gpOverlay;

	@Inject
	private GoldmanMinimapOverlay minimapOverlay;
	@Inject
	private GoldmanAchievements achievements;
	@Inject
	private ClogTooltip clogTooltip;
	@Inject
	private ClientThread clientThread;
	@Inject
	private MouseManager mouseManager;
	@Inject
	private KeyManager keyManager;
	@Inject
	private OkHttpClient okHttpClient;

	private volatile WorldPoint playerPos;
	private MapRegions mapRegions;
	private MapTiles mapTiles;
	private GoldmanMapView mapView;
	@Inject
	private GoldmanMapOverlay mapOverlay;
	private final GoldmanMapView.Host mapHost = new GoldmanMapView.Host()
	{
		@Override
		public AreaIndex areas()
		{
			return state.getIndex();
		}

		@Override
		public boolean isUnlocked(GoldmanArea a)
		{
			return state.isUnlocked(a);
		}

		@Override
		public boolean isFree(GoldmanArea a)
		{
			return state.isFree(a);
		}

		@Override
		public long cost(GoldmanArea a)
		{
			return state.costOf(a);
		}

		@Override
		public long balance()
		{
			return state.getBalance();
		}

		@Override
		public WorldPoint player()
		{
			return playerPos;
		}

		@Override
		public void buy(GoldmanArea a)
		{
			String err = state.buy(a);
			String msg = err == null
				? "<col=ffffff>Goldman Mode:</col> <col=00ff00>" + a.getName() + " has been unlocked.</col>"
				: "Goldman Mode: " + err + ".";
			clientThread.invoke(() ->
			{
				if (client.getGameState() == GameState.LOGGED_IN)
				{
					client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
				}
			});
		}
	};

	/** Minimap geometry refreshed every client tick, so the mouse thread can use it without touching the game. */
	private volatile MinimapFrame frame;

	private NavigationButton navButton;

	@Provides
	GoldmanModeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GoldmanModeConfig.class);
	}

	@Override
	protected void startUp()
	{
		state.load();
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
		mouseManager.registerMouseListener(minimapBlocker);
		log.info("Goldman Mode started with {} areas ({})", state.getIndex().getAreas().size(), state.getSource());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(gpOverlay);
		state.flush();
		mouseManager.unregisterMouseListener(minimapBlocker);
		SwingUtilities.invokeLater(() ->
		{
			closeMap();
			if (mapTiles != null)
			{
				mapTiles.shutdown();
				mapTiles = null;
			}
			mapView = null;
			mapOverlay.setView(null);
		});
		overlayManager.remove(mapOverlay);
		frame = null;
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
			announce(ChatMessageType.SPAM, gp, "killing " + net.runelite.client.util.Text.removeTags(npc.getName() == null ? "an NPC" : npc.getName()));
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
		return net.runelite.api.Experience.getCombatLevel(
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
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			playerPos = null;
			state.setCurrent(null);
			SwingUtilities.invokeLater(this::closeMap);
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN && mapView != null)
		{
			mapView.resetView(); // next login starts on the whole map again
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
		WorldPoint shown = wp == null || Instances.isInstanced(client) ? null : wp;
		if (!java.util.Objects.equals(shown, playerPos))
		{
			playerPos = shown;
		}
	}

	// ------------------------------------------------------------------ world map window

	private void openMap()
	{
		SwingUtilities.invokeLater(this::openMapNow);
	}

	/** AWT thread. The map is drawn over the game by an overlay and takes the mouse and keys while it is open. */
	private void openMapNow()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (mapView == null)
		{
			mapRegions = MapRegions.load();
			mapTiles = new MapTiles(okHttpClient, new File(new File(RuneLite.RUNELITE_DIR, "goldman-mode"), "maptiles"),
				mapRegions, () ->
				{
				});
			mapView = new GoldmanMapView(mapHost, mapTiles, mapRegions, this::closeMap);
			mapOverlay.setView(mapView);
		}
		if (mapView.isOpen())
		{
			return;
		}
		mapView.open();
		// RuneLite draws its sidebar caret whenever the game last saw the mouse near the right edge. The map swallows
		// mouse movement, so the game would keep the spot where the world map icon was clicked and show the caret the
		// whole time: tell the game the mouse has left the window (before our own listeners are in line).
		java.awt.Canvas gameCanvas = client.getCanvas();
		if (gameCanvas != null)
		{
			gameCanvas.dispatchEvent(new java.awt.event.MouseEvent(gameCanvas, java.awt.event.MouseEvent.MOUSE_EXITED,
				System.currentTimeMillis(), 0, -1, -1, 0, false));
		}
		// Not first in line: Stretched Mode's translator is registered first and rescales the mouse position to the
		// game's own pixels for whoever comes after it, and that is the position the map is drawn in.
		mouseManager.registerMouseListener(mapView.mouse);
		mouseManager.registerMouseWheelListener(mapView.wheel);
		keyManager.registerKeyListener(mapView.keys);
		// the sidebar button had the keyboard: give it back to the game so the map can hear keys
		java.awt.Canvas canvas = client.getCanvas();
		if (canvas != null)
		{
			canvas.requestFocusInWindow();
		}
	}

	/** AWT thread. */
	private void closeMap()
	{
		GoldmanMapView v = mapView;
		if (v == null || !v.isOpen())
		{
			return;
		}
		v.close();
		mouseManager.unregisterMouseListener(v.mouse);
		mouseManager.unregisterMouseWheelListener(v.wheel);
		keyManager.unregisterKeyListener(v.keys);
		// the map's pictures are released by the view itself once it has finished fading out
	}

	// ------------------------------------------------------------------ click blocking

	private volatile long lastWarn;
	private volatile WorldPoint walkTarget;

	/** Every time "Walk here" is offered, remember which tile it would walk to. */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded e)
	{
		if (e.getMenuEntry().getType() == MenuAction.WALK)
		{
			walkTarget = currentWalkTarget();
		}
	}

	private WorldPoint currentWalkTarget()
	{
		WorldView wv = client.getTopLevelWorldView();
		Player me = client.getLocalPlayer();
		if (wv == null || me == null)
		{
			return null;
		}
		WorldPoint onMinimap = minimapTileUnderMouse(me);
		if (onMinimap != null)
		{
			return onMinimap;
		}
		Tile t = wv.getSelectedSceneTile();
		return t == null ? null : WorldPoint.fromLocalInstance(client, t.getLocalLocation());
	}

	/** One snapshot of how the minimap maps tiles to pixels: c0 is the player's tile, u one tile east, v one north. */
	private static final class MinimapFrame
	{
		double c0x, c0y, ux, uy, vx, vy;
		WorldPoint base;
		Rectangle bounds;
	}

	private MinimapFrame computeFrame(Player me)
	{
		LocalPoint here = me.getLocalLocation();
		if (here == null || Instances.isInstanced(client))
		{
			return null;
		}
		Rectangle bounds = null;
		for (int id : new int[]{InterfaceID.ToplevelOsrsStretch.MINIMAP,
			InterfaceID.ToplevelPreEoc.MINIMAP, InterfaceID.Toplevel.MINIMAP})
		{
			Widget w = client.getWidget(id);
			if (w != null && !w.isHidden() && w.getBounds().width > 0)
			{
				bounds = w.getBounds();
				break;
			}
		}
		if (bounds == null)
		{
			return null;
		}
		int cx = (here.getX() >> 7 << 7) + 64, cy = (here.getY() >> 7 << 7) + 64;
		net.runelite.api.Point c0 = Perspective.localToMinimap(client, new LocalPoint(cx, cy, WorldView.TOPLEVEL));
		double[] u = minimapStep(c0, cx, cy, 1, 0);
		double[] v = minimapStep(c0, cx, cy, 0, 1);
		if (u == null || v == null || Math.abs(u[0] * v[1] - u[1] * v[0]) < 1e-6)
		{
			return null;
		}
		MinimapFrame f = new MinimapFrame();
		f.c0x = c0.getX();
		f.c0y = c0.getY();
		f.ux = u[0];
		f.uy = u[1];
		f.vx = v[0];
		f.vy = v[1];
		f.bounds = bounds;
		f.base = WorldPoint.fromLocalInstance(client, new LocalPoint(cx, cy, WorldView.TOPLEVEL));
		return f;
	}

	/** The tile under canvas point (mx, my) if that point is on the minimap, else null. */
	private static WorldPoint tileAt(MinimapFrame f, int mx, int my)
	{
		if (f == null)
		{
			return null;
		}
		double hw = f.bounds.width / 2.0, hh = f.bounds.height / 2.0;
		double nx = (mx - f.bounds.getCenterX()) / hw, ny = (my - f.bounds.getCenterY()) / hh;
		if (nx * nx + ny * ny > 1)
		{
			return null;
		}
		double px = mx - f.c0x, py = my - f.c0y;
		double det = f.ux * f.vy - f.uy * f.vx;
		int dx = (int) Math.round((px * f.vy - py * f.vx) / det);
		int dy = (int) Math.round((f.ux * py - f.uy * px) / det);
		return new WorldPoint(f.base.getX() + dx, f.base.getY() + dy, f.base.getPlane());
	}

	private WorldPoint minimapTileUnderMouse(Player me)
	{
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		MinimapFrame f = computeFrame(me);
		return mouse == null ? null : tileAt(f, mouse.getX(), mouse.getY());
	}

	/** pixels moved on the minimap per tile in direction (tx, ty), measured over up to 16 tiles to beat rounding */
	private double[] minimapStep(net.runelite.api.Point c0, int cx, int cy, int tx, int ty)
	{
		if (c0 == null)
		{
			return null;
		}
		for (int k : new int[]{16, 8, 4, 2, 1})
		{
			net.runelite.api.Point p = Perspective.localToMinimap(client,
				new LocalPoint(cx + tx * k * Perspective.LOCAL_TILE_SIZE, cy + ty * k * Perspective.LOCAL_TILE_SIZE, WorldView.TOPLEVEL));
			if (p != null)
			{
				return new double[]{(p.getX() - c0.getX()) / (double) k, (p.getY() - c0.getY()) / (double) k};
			}
		}
		return null;
	}

	@Subscribe
	public void onClientTick(ClientTick t)
	{
		Player me = client.getLocalPlayer();
		frame = me == null || client.getGameState() != GameState.LOGGED_IN ? null : computeFrame(me);
		clogTooltip.onClientTick();
	}

	/**
	 * The client walks to a minimap click by itself (no menu action, so no MenuOptionClicked we could cancel), so the
	 * only way to stop it is to swallow the mouse click before the game sees it.
	 */
	private final MouseAdapter minimapBlocker = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			return block(e, true);
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			return block(e, false);
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent e)
		{
			return block(e, false);
		}

		private MouseEvent block(MouseEvent e, boolean warn)
		{
			if (e.getButton() != MouseEvent.BUTTON1 || !config.blockLockedClicks())
			{
				return e;
			}
			GoldmanMapView map = mapView;
			if (map != null && map.isOpen())
			{
				return e; // the map has the mouse (its buttons sit where the minimap is)
			}
			WorldPoint tile = tileAt(frame, e.getX(), e.getY());
			if (tile == null)
			{
				return e;
			}
			GoldmanArea area = blockingArea(tile.getX(), tile.getY());
			if (area == null)
			{
				return e;
			}
			e.consume();
			if (warn)
			{
				warnLocked(area);
			}
			return e;
		}
	};

	/**
	 * The locked area that stops you using this tile, or null. That is the area the tile is in, or a locked area
	 * whose barrier cuts through the tile (so you can't stand half out of bounds on a diagonal border).
	 */
	private GoldmanArea blockingArea(int x, int y)
	{
		AreaIndex idx = state.getIndex();
		GoldmanArea in = idx.areaAt(x, y);
		if (in != null && !state.isUnlocked(in))
		{
			return in;
		}
		java.util.List<BarrierGeometry.Piece> pieces = idx.getBarrier().crossing(x, y);
		if (pieces != null)
		{
			for (BarrierGeometry.Piece pc : pieces)
			{
				boolean l1 = state.isLockedIdx(pc.o1), l2 = state.isLockedIdx(pc.o2);
				if (l1 != l2 && pc.ring == (l1 ? pc.o1 : pc.o2))
				{
					return idx.getAreas().get(l1 ? pc.o1 : pc.o2);
				}
			}
		}
		return null;
	}

	private void warnLocked(GoldmanArea area)
	{
		long now = System.currentTimeMillis();
		if (now - lastWarn > 1500)
		{
			lastWarn = now;
			clientThread.invoke(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ffffff>Goldman Mode:</col> <col=ff0000>" + area.getName() + " has not been unlocked yet.</col>", null));
		}
	}

	/** Stops walking to, and interacting with, anything that stands inside a locked area. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		// the world map icon beside the minimap opens our map instead of the game's
		if ((e.getParam1() == InterfaceID.Orbs.ORB_WORLDMAP || e.getParam1() == InterfaceID.Orbs.WORLDMAP)
			&& e.getMenuOption() != null && net.runelite.client.util.Text.removeTags(e.getMenuOption()).toLowerCase(java.util.Locale.ROOT).contains("world map"))
		{
			e.consume();
			openMap();
			return;
		}
		if (!config.blockLockedClicks() || Instances.isInstanced(client) || e.isConsumed())
		{
			return;
		}
		WorldPoint target = targetOf(e);
		if (target == null)
		{
			return;
		}
		GoldmanArea area = blockingArea(target.getX(), target.getY());
		if (area == null)
		{
			return;
		}
		e.consume();
		warnLocked(area);
	}

	/** The tile an interaction is aimed at, or null if it is not about a place in the world. */
	private WorldPoint targetOf(MenuOptionClicked e)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}
		MenuAction a = e.getMenuAction();
		switch (a)
		{
			case WALK:
			{
				// RuneLite does not put the destination in the click event. For a plain left click the mouse is
				// still over the target (viewport tile or minimap), so look at it now; if a right-click menu is
				// open, use what was under the mouse when it was built.
				boolean menuOpen = client.isMenuOpen();
				WorldPoint fresh = menuOpen ? null : currentWalkTarget();
				WorldPoint t = fresh != null ? fresh : walkTarget;
				return t;
			}
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case ITEM_USE_ON_NPC:
			case WIDGET_TARGET_ON_NPC:
			{
				NPC npc = wv.npcs().byIndex(e.getId());
				return npc == null ? null : npc.getWorldLocation();
			}
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
			case ITEM_USE_ON_PLAYER:
			case WIDGET_TARGET_ON_PLAYER:
			{
				Player p = wv.players().byIndex(e.getId());
				return p == null ? null : p.getWorldLocation();
			}
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case ITEM_USE_ON_GAME_OBJECT:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case ITEM_USE_ON_GROUND_ITEM:
			case WIDGET_TARGET_ON_GROUND_ITEM:
				return WorldPoint.fromScene(wv, e.getParam0(), e.getParam1(), wv.getPlane());
			default:
				return null; // examine, cancel, inventory, interface clicks etc. are always allowed
		}
	}
}
