package com.goldmanmode;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseWheelListener;

/**
 * The map, drawn over the whole game view by {@link GoldmanMapOverlay}: smooth zoom (down to the whole world, up to
 * single pixels), smooth drag/keyboard scrolling, Goldman area borders and a tooltip with the name and
 * price of the area under the mouse. While it is open it takes every mouse and key event so nothing reaches the game.
 * Events come from the AWT thread and drawing from the client thread, so everything here is synchronized.
 */
final class GoldmanMapView
{
	/** What the map needs to know about the game and the settings. */
	interface Host
	{
		AreaIndex areas();

		boolean isUnlocked(GoldmanArea a);

		boolean isFree(GoldmanArea a);

		long cost(GoldmanArea a);

		long balance();

		/** the player's tile, or null when not in the surface world */
		WorldPoint player();

		boolean sharpPixels();

		boolean showBorders();

		boolean fillLocked();

		boolean showUnlockedBorders();

		/** mouse wheel zoom speed, percent (100 = normal) */
		int zoomSpeed();

		/** buy (unlock) an area with the Goldman GP balance; called from the AWT thread */
		void buy(GoldmanArea a);
	}

	private static final Font BASE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
	private static final Color OCEAN = new Color(12, 14, 20);
	private static final double MAX_SCALE = 10;
	/** how much one notch of the mouse wheel zooms (natural log) */
	private static final double ZOOM_PER_NOTCH = 0.035;
	private static final Color LOCKED_FILL = new Color(220, 40, 40, 112);
	private static final Color LOCKED_FILL_HOVER = new Color(255, 70, 70, 160);
	private static final Color UNLOCKED_FILL_HOVER = new Color(255, 255, 255, 80);
	private static final Color LOCKED_LINE = new Color(255, 64, 64, 240);
	private static final Color UNLOCKED_LINE = new Color(90, 210, 120, 215);
	private static final Color HOVER_LINE = new Color(255, 255, 255, 250);
	private static final Color BORDER = new Color(0x14, 0x14, 0x14); // #141414, opaque: tooltip border
	private static final Color PANEL = new Color(0x14, 0x14, 0x14, 0xB7); // #B7141414 (ARGB): tooltip background
	private static final Color TIP_RED = new Color(0xff, 0x00, 0x00); // #ff0000: unaffordable price in the tooltip
	private static final Color YELLOW = new Color(255, 255, 0);
	private static final Color GREEN = new Color(0, 255, 0);
	private static final Color RED = new Color(255, 51, 51);
	/** how long the map takes to fade in and out, ms */
	private static final double FADE_MS = 160;
	private static final BitmapFont FONT = BitmapFont.load();

	private final Host host;
	private final MapTiles tiles;
	private final MapRegions regions;
	private final Runnable onClose;
	private final MapViewport vp = new MapViewport();
	private final double[] world;

	private boolean open;
	private boolean initialised;

	// animation state
	private double targetScale = 1;
	private double anchorWx, anchorWy, anchorSx, anchorSy;
	private long flyStart;
	private double flyFromX, flyFromY, flyFromScale, flyToX, flyToY, flyToScale;
	private boolean flying;
	private long lastFrame = System.nanoTime();
	private double alpha; // 0 = gone, 1 = fully shown; follows open with a short fade
	private long alphaNs = System.nanoTime();
	private boolean memoryHeld;

	// mouse
	private int mouseX = -1, mouseY = -1;
	private boolean dragging;
	private int pressX, pressY;
	private boolean dragMoved; // the press turned into a drag, so it is not a click
	/** the area whose "Confirm unlock" tooltip is showing after a first click */
	private GoldmanArea confirming;
	private int lastDragX, lastDragY;
	private long lastDragTime;

	private final Map<GoldmanArea, Path2D[]> paths = new HashMap<>();
	GoldmanMapView(Host host, MapTiles tiles, MapRegions regions, Runnable onClose)
	{
		this.host = host;
		this.tiles = tiles;
		this.regions = regions;
		this.onClose = onClose;
		this.world = regions.worldBounds();
	}

	// ------------------------------------------------------------------ open / close

	synchronized boolean isOpen()
	{
		return open;
	}

	synchronized void open()
	{
		open = true;
		lastFrame = System.nanoTime();
		alphaNs = lastFrame;
		memoryHeld = true;
	}

	/** forget where the map was looking: the next open starts from the whole map again (called on logout) */
	synchronized void resetView()
	{
		initialised = false;
	}

	/** open, or still fading out: the overlay keeps drawing until this is false */
	synchronized boolean isVisible()
	{
		return open || alpha > 0;
	}

	synchronized void close()
	{
		open = false;
		confirming = null;
		alphaNs = System.nanoTime();
		dragging = false;
		flying = false;
	}

	// ------------------------------------------------------------------ input

	final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			synchronized (GoldmanMapView.this)
			{
				mouseX = e.getX();
				mouseY = e.getY();
				if (e.getButton() == MouseEvent.BUTTON1)
				{
					dragging = true;
					dragMoved = false;
					pressX = e.getX();
					pressY = e.getY();
					flying = false;
					lastDragX = e.getX();
					lastDragY = e.getY();
					lastDragTime = System.nanoTime();
				}
			}
			e.consume();
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			GoldmanArea toBuy = null;
			synchronized (GoldmanMapView.this)
			{
				if (dragging)
				{
					dragging = false; // the map stops where you let go
					if (e.getButton() == MouseEvent.BUTTON1 && !dragMoved)
					{
						toBuy = clicked(e.getX(), e.getY());
					}
				}
			}
			if (toBuy != null)
			{
				host.buy(toBuy);
			}
			e.consume();
			return e;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent e)
		{
			synchronized (GoldmanMapView.this)
			{
				mouseX = e.getX();
				mouseY = e.getY();
				if (dragging)
				{
					if (Math.abs(e.getX() - pressX) > 3 || Math.abs(e.getY() - pressY) > 3)
					{
						dragMoved = true;
						confirming = null;
					}
					long now = System.nanoTime();
					int dx = e.getX() - lastDragX, dy = e.getY() - lastDragY;
					lastDragX = e.getX();
					lastDragY = e.getY();
					lastDragTime = now;
					vp.panPixels(dx, dy);
					vp.clampCentre(world, 64);
				}
			}
			e.consume();
			return e;
		}

		@Override
		public MouseEvent mouseMoved(MouseEvent e)
		{
			synchronized (GoldmanMapView.this)
			{
				mouseX = e.getX();
				mouseY = e.getY();
			}
			e.consume();
			return e;
		}

		@Override
		public MouseEvent mouseExited(MouseEvent e)
		{
			synchronized (GoldmanMapView.this)
			{
				mouseX = mouseY = -1;
			}
			e.consume();
			return e;
		}

		@Override
		public MouseEvent mouseEntered(MouseEvent e)
		{
			e.consume();
			return e;
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent e)
		{
			e.consume();
			return e;
		}
	};

	final MouseWheelListener wheel = e ->
	{
		synchronized (GoldmanMapView.this)
		{
			flying = false;
			// from the target, so quick scrolling keeps adding up while the view eases toward it
			// a fast flick can report several notches in one event: cap it so one event never jumps far
			double rot = Math.max(-3, Math.min(3, e.getPreciseWheelRotation()));
			zoomAt(e.getX(), e.getY(), Math.max(targetScale, 1e-6) * Math.exp(-rot * ZOOM_PER_NOTCH * GoldmanMapView.this.host.zoomSpeed() / 100.0));
		}
		e.consume();
		return e;
	};

	final KeyListener keys = new KeyListener()
	{
		@Override
		public void keyTyped(KeyEvent e)
		{
			e.consume();
		}

		@Override
		public void keyPressed(KeyEvent e)
		{
			// the map swallows every key so nothing reaches the game; only Esc does anything (it closes the map)
			e.consume();
			if (e.getKeyCode() == KeyEvent.VK_ESCAPE)
			{
				onClose.run();
			}
		}

		@Override
		public void keyReleased(KeyEvent e)
		{
			e.consume();
		}
	};

	// ------------------------------------------------------------------ navigation

	private double minScale()
	{
		return Math.max(0.02, vp.fitScale(world, 24) * 0.9);
	}

	private double clampScale(double s)
	{
		return Math.max(minScale(), Math.min(MAX_SCALE, s));
	}

	/** ease toward a new zoom, keeping the world point under screen point (sx, sy) still */
	private void zoomAt(double sx, double sy, double scale)
	{
		flying = false;
		targetScale = clampScale(scale);
		anchorSx = sx;
		anchorSy = sy;
		anchorWx = vp.wx(sx);
		anchorWy = vp.wy(sy);
	}

	synchronized void showWholeMap(boolean animate)
	{
		double s = vp.fitScale(world, 24);
		flyTo((world[0] + world[2]) / 2, (world[1] + world[3]) / 2, s, animate);
	}

	synchronized void showPlayer(boolean animate)
	{
		WorldPoint p = host.player();
		if (p == null)
		{
			return;
		}
		flyTo(p.getX() + 0.5, p.getY() + 0.5, Math.max(targetScale, 6), animate);
	}

	/** jump to a view (also used by tests) */
	synchronized void setView(double x, double y, double scale)
	{
		flyTo(x, y, scale, false);
		initialised = true;
	}

	synchronized void setMouse(int x, int y)
	{
		mouseX = x;
		mouseY = y;
	}

	private void flyTo(double x, double y, double scale, boolean animate)
	{
		scale = clampScale(scale);
		if (!animate)
		{
			vp.cx = x;
			vp.cy = y;
			vp.scale = scale;
			targetScale = scale;
			flying = false;
			return;
		}
		flying = true;
		flyStart = System.nanoTime();
		flyFromX = vp.cx;
		flyFromY = vp.cy;
		flyFromScale = vp.scale;
		flyToX = x;
		flyToY = y;
		flyToScale = scale;
		targetScale = scale;
	}

	// ------------------------------------------------------------------ animation (once per drawn frame)

	private void step()
	{
		long now = System.nanoTime();
		double dt = Math.min(50, (now - lastFrame) / 1e6);
		lastFrame = now;
		if (flying)
		{
			double t = Math.min(1, (now - flyStart) / 1e6 / 450.0);
			double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; // ease in-out
			vp.cx = flyFromX + (flyToX - flyFromX) * e;
			vp.cy = flyFromY + (flyToY - flyFromY) * e;
			vp.scale = flyFromScale * Math.pow(flyToScale / flyFromScale, e);
			if (t >= 1)
			{
				flying = false;
				vp.scale = flyToScale;
			}
		}
		else
		{
			if (Math.abs(Math.log(targetScale / vp.scale)) > 0.0008)
			{
				double k = 1 - Math.exp(-dt / 85.0);
				vp.zoomKeeping(anchorWx, anchorWy, anchorSx, anchorSy, vp.scale * Math.pow(targetScale / vp.scale, k));
			}
			else if (vp.scale != targetScale)
			{
				vp.zoomKeeping(anchorWx, anchorWy, anchorSx, anchorSy, targetScale);
			}
		}
		vp.clampCentre(world, 64);
	}

	// ------------------------------------------------------------------ painting

	/** Draw the whole map into g, w x h pixels. */
	synchronized void render(Graphics2D g0, int w, int h)
	{
		Graphics2D g = (Graphics2D) g0.create();
		try
		{
			vp.w = Math.max(1, w);
			vp.h = Math.max(1, h);
			if (!initialised)
			{
				// the first time the map opens after logging in it shows the whole world
				showWholeMap(false);
				initialised = true;
			}
			step();
			long nowNs = System.nanoTime();
			double fadeDt = Math.min(100, (nowNs - alphaNs) / 1e6);
			alphaNs = nowNs;
			alpha = Math.max(0, Math.min(1, alpha + (open ? fadeDt : -fadeDt) / FADE_MS));
			if (alpha <= 0)
			{
				if (memoryHeld && !open)
				{
					memoryHeld = false;
					tiles.releaseMemory(); // the fade-out is over: let go of the pictures
				}
				return;
			}
			if (alpha < 1)
			{
				g.setComposite(java.awt.AlphaComposite.SrcOver.derive((float) (alpha * alpha * (3 - 2 * alpha))));
			}
			g.setClip(0, 0, vp.w, vp.h);
			g.setColor(OCEAN);
			g.fillRect(0, 0, vp.w, vp.h);
			double dpr = g.getDeviceConfiguration().getDefaultTransform().getScaleX();
			if (dpr <= 0)
			{
				dpr = 1;
			}
			drawTiles(g, dpr);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			GoldmanArea hover = hoverArea();
			if (confirming != null && !dragging && hover != confirming)
			{
				confirming = null; // moved off the area: the confirmation lapses
			}
			drawAreas(g, hover);
			drawHud(g, hover);
		}
		finally
		{
			g.dispose();
		}
	}

	private void drawTiles(Graphics2D g, double dpr)
	{
		int z = MapTiles.levelFor(vp.scale * dpr);
		int span = MapTiles.span(z);
		int tx0 = Math.floorDiv((int) Math.floor(vp.wx(0)), span), tx1 = Math.floorDiv((int) Math.floor(vp.wx(vp.w)), span);
		int ty0 = Math.floorDiv((int) Math.floor(vp.wy(vp.h)), span), ty1 = Math.floorDiv((int) Math.floor(vp.wy(0)), span);
		double ccx = vp.cx / span, ccy = vp.cy / span;
		List<int[]> need = new ArrayList<>();
		for (int tx = tx0; tx <= tx1; tx++)
		{
			for (int ty = ty0; ty <= ty1; ty++)
			{
				if (regions.hasTile(z, tx, ty))
				{
					need.add(new int[]{tx, ty});
				}
			}
		}
		if (need.size() > 4000)
		{
			return;
		}
		// middle of the screen first
		need.sort((a, b) -> Double.compare(Math.hypot(a[0] + 0.5 - ccx, a[1] + 0.5 - ccy), Math.hypot(b[0] + 0.5 - ccx, b[1] + 0.5 - ccy)));
		// the biggest zoom-out pictures also show dungeons beyond the surface world: only draw inside it
		java.awt.Shape oldClip = g.getClip();
		// round inward: a clip that sticks out a fraction of a pixel lets a sliver of the dungeon / blank picture beyond the
		// world edge show up as a thin white line
		int cx0 = (int) Math.ceil(vp.sx(world[0])), cx1 = (int) Math.floor(vp.sx(world[2]));
		int cy0 = (int) Math.ceil(vp.sy(world[3])), cy1 = (int) Math.floor(vp.sy(world[1]));
		g.clipRect(cx0, cy0, cx1 - cx0, cy1 - cy0);
		boolean upscaling = vp.scale * dpr > Math.pow(2, z) * 1.0001; // more screen pixels than picture pixels
		Object interp = upscaling && host.sharpPixels() ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
			: RenderingHints.VALUE_INTERPOLATION_BILINEAR;
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp);
		for (int[] t : need)
		{
			int dx0 = (int) Math.round(vp.sx((double) t[0] * span));
			int dx1 = (int) Math.round(vp.sx((double) (t[0] + 1) * span));
			int dy0 = (int) Math.round(vp.sy((double) (t[1] + 1) * span));
			int dy1 = (int) Math.round(vp.sy((double) t[1] * span));
			BufferedImage img = tiles.want(z, t[0], t[1]);
			if (img != null)
			{
				g.drawImage(img, dx0, dy0, dx1, dy1, 0, 0, MapTiles.SIZE, MapTiles.SIZE, null);
			}
			else if (tiles.exists(z, t[0], t[1]))
			{
				fallback(g, z, t[0], t[1], dx0, dy0, dx1, dy1);
			}
		}
		g.setClip(oldClip);
		tiles.endFrame();
	}

	/** While a square loads, show the same ground from a coarser (or finer) zoom level so nothing blinks out. */
	private void fallback(Graphics2D g, int z, int tx, int ty, int dx0, int dy0, int dx1, int dy1)
	{
		for (int dz = 1; dz <= 5 && z - dz >= MapTiles.MIN_Z; dz++)
		{
			BufferedImage p = tiles.peek(z - dz, tx >> dz, ty >> dz);
			if (p != null)
			{
				int mask = (1 << dz) - 1;
				int sub = MapTiles.SIZE >> dz;
				int sx = (tx & mask) * sub;
				int sy = (mask - (ty & mask)) * sub; // y runs north, picture rows run south
				g.drawImage(p, dx0, dy0, dx1, dy1, sx, sy, sx + sub, sy + sub, null);
				return;
			}
		}
		if (z + 1 <= MapTiles.MAX_Z)
		{
			int mx = (dx0 + dx1) / 2, my = (dy0 + dy1) / 2;
			for (int i = 0; i < 2; i++)
			{
				for (int j = 0; j < 2; j++)
				{
					BufferedImage c = tiles.peek(z + 1, tx * 2 + i, ty * 2 + j);
					if (c != null)
					{
						int cx0 = i == 0 ? dx0 : mx, cx1 = i == 0 ? mx : dx1;
						int cy0 = j == 1 ? dy0 : my, cy1 = j == 1 ? my : dy1; // j=1 is the northern half: top
						g.drawImage(c, cx0, cy0, cx1, cy1, 0, 0, MapTiles.SIZE, MapTiles.SIZE, null);
					}
				}
			}
		}
	}

	private Path2D[] pathsOf(GoldmanArea a)
	{
		return paths.computeIfAbsent(a, k ->
		{
			List<Path2D> out = new ArrayList<>();
			for (List<double[][]> part : k.getParts())
			{
				Path2D p = new Path2D.Double(Path2D.WIND_EVEN_ODD);
				for (double[][] ring : part)
				{
					for (int i = 0; i < ring.length; i++)
					{
						if (i == 0)
						{
							p.moveTo(ring[i][0], ring[i][1]);
						}
						else
						{
							p.lineTo(ring[i][0], ring[i][1]);
						}
					}
					p.closePath();
				}
				out.add(p);
			}
			return out.toArray(new Path2D[0]);
		});
	}

	private boolean affordable(GoldmanArea a)
	{
		return !host.isUnlocked(a) && host.balance() >= host.cost(a);
	}

	/**
	 * A click-and-release (not a drag) on the map. The first click on a locked area you can afford arms its "Confirm
	 * unlock" tooltip; a second click on the same area buys it (returned). Anything else just clears the confirmation.
	 */
	private GoldmanArea clicked(int x, int y)
	{
		GoldmanArea a = host.areas().areaAt((int) Math.floor(vp.wx(x)), (int) Math.floor(vp.wy(y)));
		if (a == null || !affordable(a))
		{
			confirming = null;
			return null;
		}
		if (confirming == a)
		{
			confirming = null;
			return a;
		}
		confirming = a;
		return null;
	}

	private GoldmanArea hoverArea()
	{
		if (mouseX < 0 || dragging)
		{
			return null;
		}
		int x = (int) Math.floor(vp.wx(mouseX)), y = (int) Math.floor(vp.wy(mouseY));
		return host.areas().areaAt(x, y);
	}

	private void drawAreas(Graphics2D g, GoldmanArea hover)
	{
		boolean borders = host.showBorders(), fill = host.fillLocked(), unl = host.showUnlockedBorders();
		AffineTransform at = new AffineTransform();
		at.translate(vp.w / 2.0 - vp.cx * vp.scale, vp.h / 2.0 + vp.cy * vp.scale);
		at.scale(vp.scale, -vp.scale);
		double left = vp.wx(0), right = vp.wx(vp.w), top = vp.wy(0), bottom = vp.wy(vp.h);
		// lines get thinner as you zoom out (full thickness from about 4 pixels per tile up)
		double thin = Math.max(0.22, Math.min(1.0, Math.sqrt(vp.scale / 4)));
		BasicStroke lockedStroke = new BasicStroke((float) Math.max(0.6, 2.25 * thin), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		BasicStroke unlockedStroke = new BasicStroke((float) Math.max(0.5, 1.5 * thin), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		BasicStroke hoverStroke = new BasicStroke((float) Math.max(1.4, 3.0 * thin), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		// three passes, so a red border always sits above a green one where they meet:
		// the red fill of locked areas, then the green outlines of unlocked areas, then the red outlines
		List<GoldmanArea> lockedInView = new ArrayList<>();
		List<GoldmanArea> unlockedInView = new ArrayList<>();
		for (GoldmanArea a : host.areas().getAreas())
		{
			if (a == hover || a.getMaxX() < left || a.getMinX() > right || a.getMaxY() < bottom || a.getMinY() > top)
			{
				continue;
			}
			(host.isUnlocked(a) ? unlockedInView : lockedInView).add(a);
		}
		if (fill)
		{
			g.setColor(LOCKED_FILL);
			for (GoldmanArea a : lockedInView)
			{
				for (Path2D p : pathsOf(a))
				{
					g.fill(at.createTransformedShape(p));
				}
			}
		}
		if (unl)
		{
			g.setStroke(unlockedStroke);
			g.setColor(UNLOCKED_LINE);
			for (GoldmanArea a : unlockedInView)
			{
				for (Path2D p : pathsOf(a))
				{
					g.draw(at.createTransformedShape(p));
				}
			}
		}
		if (borders)
		{
			g.setStroke(lockedStroke);
			g.setColor(LOCKED_LINE);
			for (GoldmanArea a : lockedInView)
			{
				for (Path2D p : pathsOf(a))
				{
					g.draw(at.createTransformedShape(p));
				}
			}
		}
		// the hovered area last, so its white outline sits on top of every neighbour's line
		if (hover != null)
		{
			boolean unlocked = host.isUnlocked(hover);
			for (Path2D p : pathsOf(hover))
			{
				Shape s = at.createTransformedShape(p);
				g.setColor(unlocked ? UNLOCKED_FILL_HOVER : LOCKED_FILL_HOVER);
				g.fill(s);
				g.setStroke(hoverStroke);
				g.setColor(HOVER_LINE);
				g.draw(s);
			}
		}
	}

	private void drawBox(Graphics2D g, int x, int y, int w, int h)
	{
		g.setColor(PANEL);
		g.fillRect(x, y, w, h);
		g.setStroke(new BasicStroke(1.5f));
		g.setColor(BORDER);
		g.draw(new java.awt.geom.Rectangle2D.Double(x + 0.75, y + 0.75, w - 1.5, h - 1.5));
	}

	private void drawHud(Graphics2D g, GoldmanArea hover)
	{
		// bottom right: map download status
		String status = null;
		if (tiles.offline())
		{
			status = "Can't reach the map server - showing saved pictures only";
		}
		if (status != null)
		{
			int sw = FONT.width(status) + 16, sh = FONT.lineHeight() + 12;
			int sx = vp.w - 12 - sw, sy = vp.h - 12 - sh;
			drawBox(g, sx, sy, sw, sh);
			FONT.draw(g, status, sx + 8, sy + 6, tiles.offline() ? RED : Color.WHITE);
		}

		if (hover != null)
		{
			drawTooltip(g, hover);
		}
	}

	/** "Unlock <area> (<cost> gp)": Unlock white, the area yellow, the price green if you can afford it and red if not. */
	private void drawTooltip(Graphics2D g, GoldmanArea a)
	{
		String[] parts;
		Color[] cols;
		if (host.isUnlocked(a))
		{
			parts = new String[]{a.getName()};
			cols = new Color[]{YELLOW};
		}
		else
		{
			long c = host.cost(a);
			boolean confirm = confirming == a && host.balance() >= c;
			parts = new String[]{confirm ? "Confirm unlock " : "Unlock ", a.getName(), String.format(Locale.ROOT, " (%,d gp)", c)};
			cols = new Color[]{Color.WHITE, YELLOW, host.balance() >= c ? GREEN : TIP_RED};
		}
		int tw = 0;
		for (String p : parts)
		{
			tw += FONT.width(p);
		}
		int w = tw + 8, h = FONT.lineHeight() + 10;
		// a bit below and a bit to the left of the mouse
		int x = mouseX - 4, y = mouseY + 22;
		if (y + h > vp.h - 8)
		{
			y = mouseY - 10 - h;
		}
		x = Math.max(8, Math.min(x, vp.w - 8 - w));
		y = Math.max(8, y);
		drawBox(g, x, y, w, h);
		// a black drop shadow one pixel to the south east, then the text itself
		for (int pass = 0; pass < 2; pass++)
		{
			int tx = x + 4;
			for (int i = 0; i < parts.length; i++)
			{
				FONT.draw(g, parts[i], tx + (1 - pass), y + 5 + (1 - pass), pass == 0 ? Color.BLACK : cols[i]);
				tx += FONT.width(parts[i]);
			}
		}
	}
}
