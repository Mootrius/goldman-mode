package com.goldmanmode;

import java.awt.Canvas;
import java.awt.event.MouseEvent;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

/** Opens and closes the Goldman world map and wires it up to the game. The map itself is {@link GoldmanMapView}. */
@Singleton
class GoldmanMapWindow
{
	private static final String MENU_OPTION = "Open";
	/** orange, like the other interface names in the menu */
	private static final String MENU_TARGET = "<col=ff9040>Goldman Map</col>";

	private final Client client;
	private final ClientThread clientThread;
	private final MouseManager mouseManager;
	private final KeyManager keyManager;
	private final OkHttpClient okHttpClient;
	private final GoldmanState state;
	private final GoldmanModeConfig config;
	private final GoldmanMapOverlay overlay;

	private volatile WorldPoint playerPos;
	private Filepath dataDir;
	private MapTiles mapTiles;
	private GoldmanMapView mapView;

	@Inject
	GoldmanMapWindow(Client client, ClientThread clientThread, MouseManager mouseManager, KeyManager keyManager,
		OkHttpClient okHttpClient, GoldmanState state, GoldmanModeConfig config, GoldmanMapOverlay overlay)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.mouseManager = mouseManager;
		this.keyManager = keyManager;
		this.okHttpClient = okHttpClient;
		this.state = state;
		this.config = config;
		this.overlay = overlay;
	}

	/** Adds "Open Goldman Map" to the world map icon's right-click menu. */
	void onMenuEntryAdded(MenuEntryAdded e)
	{
		if (!isWorldMapEntry(e.getMenuEntry()))
		{
			return;
		}
		for (MenuEntry m : client.getMenu().getMenuEntries())
		{
			if (MENU_TARGET.equals(m.getTarget()))
			{
				return;
			}
		}
		client.getMenu().createMenuEntry(-1).setOption(MENU_OPTION).setTarget(MENU_TARGET).setType(MenuAction.RUNELITE).onClick(m -> openLater());
	}

	/** With "Default to Goldman Map" on, a plain left click on the world map icon opens the Goldman map instead. */
	void onMenuOptionClicked(MenuOptionClicked e)
	{
		if (config.defaultToGoldmanMap() && !client.isMenuOpen() && isWorldMapEntry(e.getMenuEntry()))
		{
			e.consume();
			openLater();
		}
	}

	/** One of the world map icon's own menu entries ("Floating World Map" and so on), not ours. */
	private static boolean isWorldMapEntry(MenuEntry entry)
	{
		boolean icon = entry.getParam1() == InterfaceID.Orbs.ORB_WORLDMAP || entry.getParam1() == InterfaceID.Orbs.WORLDMAP;
		return icon && Text.removeTags(entry.getOption() + " " + entry.getTarget()).toLowerCase(Locale.ROOT).contains("world map");
	}

	/** The plugin's data folder, where the map pictures are cached. */
	void setDataDirectory(Filepath dir)
	{
		dataDir = dir;
	}

	/** The player's tile for the "you are here" marker, or null when not in the surface world. */
	void setPlayer(WorldPoint tile)
	{
		playerPos = tile;
	}

	boolean isOpen()
	{
		GoldmanMapView v = mapView;
		return v != null && v.isOpen();
	}

	void openLater()
	{
		SwingUtilities.invokeLater(this::open);
	}

	void closeLater()
	{
		SwingUtilities.invokeLater(this::close);
	}

	/** Forget where the map was looking, so the next login starts on the whole map again. */
	void resetView()
	{
		if (mapView != null)
		{
			mapView.resetView();
		}
	}

	void shutDown()
	{
		SwingUtilities.invokeLater(() ->
		{
			close();
			if (mapTiles != null)
			{
				mapTiles.shutdown();
				mapTiles = null;
			}
			mapView = null;
			overlay.setView(null);
		});
	}

	/** AWT thread. */
	private void open()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (mapView == null)
		{
			MapRegions regions = MapRegions.load();
			mapTiles = new MapTiles(okHttpClient, dataDir.joinSegment("maptiles"),
				regions, () ->
				{
				});
			mapView = new GoldmanMapView(host, mapTiles, regions, this::close);
			overlay.setView(mapView);
		}
		if (mapView.isOpen())
		{
			return;
		}
		mapView.open();
		Canvas canvas = client.getCanvas();
		if (canvas != null)
		{
			// RuneLite draws its sidebar caret whenever the game last saw the mouse near the right edge. The map swallows
			// mouse movement, so the game would keep the spot where the world map icon was clicked and show the caret the
			// whole time: tell the game the mouse has left the window (before our own listeners are in line).
			canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -1, -1, 0, false));
		}
		// Not first in line: Stretched Mode's translator is registered first and rescales the mouse position to the
		// game's own pixels for whoever comes after it, and that is the position the map is drawn in.
		mouseManager.registerMouseListener(mapView.mouse);
		mouseManager.registerMouseWheelListener(mapView.wheel);
		keyManager.registerKeyListener(mapView.keys);
		if (canvas != null)
		{
			canvas.requestFocusInWindow(); // the sidebar button had the keyboard: the map needs it to hear keys
		}
	}

	/** AWT thread. The view releases its pictures itself once it has finished fading out. */
	private void close()
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
	}

	private final GoldmanMapView.Host host = new GoldmanMapView.Host()
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
}
