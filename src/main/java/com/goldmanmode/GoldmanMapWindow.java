package com.goldmanmode;

import java.awt.Canvas;
import java.awt.event.MouseEvent;
import java.io.File;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import okhttp3.OkHttpClient;

/** Opens and closes the Goldman world map and wires it up to the game. The map itself is {@link GoldmanMapView}. */
@Singleton
class GoldmanMapWindow
{
	private final Client client;
	private final ClientThread clientThread;
	private final MouseManager mouseManager;
	private final KeyManager keyManager;
	private final OkHttpClient okHttpClient;
	private final GoldmanState state;
	private final GoldmanMapOverlay overlay;

	private volatile WorldPoint playerPos;
	private MapTiles mapTiles;
	private GoldmanMapView mapView;

	@Inject
	GoldmanMapWindow(Client client, ClientThread clientThread, MouseManager mouseManager, KeyManager keyManager,
		OkHttpClient okHttpClient, GoldmanState state, GoldmanMapOverlay overlay)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.mouseManager = mouseManager;
		this.keyManager = keyManager;
		this.okHttpClient = okHttpClient;
		this.state = state;
		this.overlay = overlay;
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
			mapTiles = new MapTiles(okHttpClient, new File(new File(RuneLite.RUNELITE_DIR, "goldman-mode"), "maptiles"),
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
