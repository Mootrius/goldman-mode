package com.goldmanmode;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;

/** Stops walking to, and interacting with, anything that stands inside a locked area. */
@Singleton
class GoldmanClickBlocker
{
	private final Client client;
	private final ClientThread clientThread;
	private final MouseManager mouseManager;
	private final GoldmanModeConfig config;
	private final GoldmanState state;
	private final GoldmanMapWindow mapWindow;

	/** Minimap geometry refreshed every client tick, so the mouse thread can use it without touching the game. */
	private volatile MinimapFrame frame;
	private volatile WorldPoint walkTarget;
	private volatile long lastWarn;

	@Inject
	GoldmanClickBlocker(Client client, ClientThread clientThread, MouseManager mouseManager, GoldmanModeConfig config,
		GoldmanState state, GoldmanMapWindow mapWindow)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.mouseManager = mouseManager;
		this.config = config;
		this.state = state;
		this.mapWindow = mapWindow;
	}

	void startUp()
	{
		mouseManager.registerMouseListener(minimapBlocker);
	}

	void shutDown()
	{
		mouseManager.unregisterMouseListener(minimapBlocker);
		frame = null;
	}

	void onClientTick()
	{
		Player me = client.getLocalPlayer();
		frame = me == null || client.getGameState() != GameState.LOGGED_IN ? null : computeFrame(me);
	}

	/** Every time "Walk here" is offered, remember which tile it would walk to. */
	void onMenuEntryAdded(MenuEntryAdded e)
	{
		if (e.getMenuEntry().getType() == MenuAction.WALK)
		{
			walkTarget = currentWalkTarget();
		}
	}

	void onMenuOptionClicked(MenuOptionClicked e)
	{
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
			if (e.getButton() != MouseEvent.BUTTON1 || !config.blockLockedClicks() || mapWindow.isOpen())
			{
				return e; // while the map is open it has the mouse (its buttons sit where the minimap is)
			}
			WorldPoint tile = tileAt(frame, e.getX(), e.getY());
			GoldmanArea area = tile == null ? null : blockingArea(tile.getX(), tile.getY());
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
		List<BarrierGeometry.Piece> pieces = idx.getBarrier().crossing(x, y);
		if (pieces != null)
		{
			for (BarrierGeometry.Piece pc : pieces)
			{
				if (state.isLockedBorder(pc))
				{
					return idx.getAreas().get(pc.ring);
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

	/** The tile an interaction is aimed at, or null if it is not about a place in the world. */
	private WorldPoint targetOf(MenuOptionClicked e)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}
		switch (e.getMenuAction())
		{
			case WALK:
			{
				// RuneLite does not put the destination in the click event. For a plain left click the mouse is
				// still over the target (viewport tile or minimap), so look at it now; if a right-click menu is
				// open, use what was under the mouse when it was built.
				WorldPoint fresh = client.isMenuOpen() ? null : currentWalkTarget();
				return fresh != null ? fresh : walkTarget;
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

	private WorldPoint currentWalkTarget()
	{
		WorldView wv = client.getTopLevelWorldView();
		Player me = client.getLocalPlayer();
		if (wv == null || me == null)
		{
			return null;
		}
		Point mouse = client.getMouseCanvasPosition();
		WorldPoint onMinimap = mouse == null ? null : tileAt(computeFrame(me), mouse.getX(), mouse.getY());
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
		Point c0 = Perspective.localToMinimap(client, new LocalPoint(cx, cy, WorldView.TOPLEVEL));
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

	/** Pixels moved on the minimap per tile in direction (tx, ty), measured over up to 16 tiles to beat rounding. */
	private double[] minimapStep(Point c0, int cx, int cy, int tx, int ty)
	{
		if (c0 == null)
		{
			return null;
		}
		for (int k : new int[]{16, 8, 4, 2, 1})
		{
			Point p = Perspective.localToMinimap(client,
				new LocalPoint(cx + tx * k * Perspective.LOCAL_TILE_SIZE, cy + ty * k * Perspective.LOCAL_TILE_SIZE, WorldView.TOPLEVEL));
			if (p != null)
			{
				return new double[]{(p.getX() - c0.getX()) / (double) k, (p.getY() - c0.getY()) / (double) k};
			}
		}
		return null;
	}
}
