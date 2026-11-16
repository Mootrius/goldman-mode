package com.goldmanmode;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.GradientPaint;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws a low barrier along the outside of every locked area: the line you are not supposed to cross. */
@Singleton
public class GoldmanBarrierOverlay extends Overlay
{
	private static final int HALF = Perspective.LOCAL_TILE_SIZE / 2;

	private final Client client;
	private final GoldmanState state;

	@Inject
	GoldmanBarrierOverlay(Client client, GoldmanState state)
	{
		this.client = client;
		this.state = state;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS); // after the models are drawn; the code below decides per wall whether you are in front or behind
		setPriority(PRIORITY_LOW);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || wv.getScene() == null)
		{
			return null;
		}
		AreaIndex index = state.getIndex();
		if (index.getAreas().isEmpty())
		{
			return null;
		}

		LocalPoint centre = LocalPoint.fromWorld(wv, me.getWorldLocation());
		if (centre == null)
		{
			centre = me.getLocalLocation();
		}
		final int cx = centre.getSceneX();
		final int cy = centre.getSceneY();
		final int reach = Settings.BARRIER_DISTANCE;
		final int fadeWidth = Math.max(1, Math.min(Settings.BARRIER_FADE_WIDTH, reach));
		final int r = reach + 1;
		final int plane = wv.getPlane();
		final boolean instanced = Instances.isInstanced(client);
		final int baseX = wv.getBaseX();
		final int baseY = wv.getBaseY();
		final int sizeX = Math.min(wv.getSizeX(), Constants.SCENE_SIZE);
		final int sizeY = Math.min(wv.getSizeY(), Constants.SCENE_SIZE);
		final int height = Settings.BARRIER_HEIGHT;
		// smooth position, so the fade changes continuously while you walk
		final LocalPoint me2 = me.getLocalLocation();

		// pulse: 1.0 at its brightest, (1 - strength) at its dimmest
		double strength = Settings.PULSE_STRENGTH / 100.0;
		double period = Math.max(300, Settings.PULSE_PERIOD_MS);
		double phase = (System.currentTimeMillis() % (long) period) / period;
		final double pulse = 1.0 - strength * (0.5 + 0.5 * Math.sin(phase * 2 * Math.PI));

		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		this.base = Settings.BARRIER_COLOUR;
		this.pulseNow = pulse;

		// Occlusion by your own character: where the barrier is BEHIND you (further from the camera)
		// your model's exact outline is cut out of it; where it is IN FRONT of you it is drawn over you.
		this.origClip = g.getClip();
		this.sil = null;
		this.silBounds = null;
		this.outsideBounds = null;
		this.layerG = null;
		double camX = client.getCameraX(), camY = client.getCameraY();
		double fx = me2.getX() - camX, fy = me2.getY() - camY;
		double fl = Math.sqrt(fx * fx + fy * fy);
		if (fl > 1)
		{
			this.camX = camX;
			this.camY = camY;
			this.fwdX = fx / fl;
			this.fwdY = fy / fl;
			this.playerDepth = fl;
			this.sil = silhouette(wv, me, plane);
			if (sil != null)
			{
				Rectangle sb = sil.getBounds();
				sb.grow(8, 8);
				sb = sb.intersection(new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight()));
				if (sb.isEmpty())
				{
					this.sil = null;
				}
				else
				{
					this.silBounds = sb;
					Area out = new Area(new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight()));
					out.subtract(new Area(sb));
					this.outsideBounds = out;
				}
			}
		}

		if (!instanced)
		{
			// the barrier follows the straight outline of each area (not tile edges), so diagonals are straight
			BarrierGeometry geo = index.getBarrier();
			final double ppx = baseX + me2.getX() / (double) Perspective.LOCAL_TILE_SIZE;
			final double ppy = baseY + me2.getY() / (double) Perspective.LOCAL_TILE_SIZE;
			final int maxLocalX = sizeX * Perspective.LOCAL_TILE_SIZE, maxLocalY = sizeY * Perspective.LOCAL_TILE_SIZE;
			for (int rx = (int) Math.floor(ppx - reach) >> 6; rx <= (int) Math.floor(ppx + reach) >> 6; rx++)
			{
				for (int ry = (int) Math.floor(ppy - reach) >> 6; ry <= (int) Math.floor(ppy + reach) >> 6; ry++)
				{
					List<BarrierGeometry.Piece> pieces = geo.region(rx, ry);
					if (pieces == null)
					{
						continue;
					}
					for (BarrierGeometry.Piece pc : pieces)
					{
						// a barrier stands where exactly one side is locked, on the outline of the locked area
						boolean l1 = state.isLockedIdx(pc.o1), l2 = state.isLockedIdx(pc.o2);
						if (l1 == l2 || pc.ring != (l1 ? pc.o1 : pc.o2))
						{
							continue;
						}
						int ax = (int) Math.round((pc.x1 - baseX) * Perspective.LOCAL_TILE_SIZE);
						int ay = (int) Math.round((pc.y1 - baseY) * Perspective.LOCAL_TILE_SIZE);
						int bx = (int) Math.round((pc.x2 - baseX) * Perspective.LOCAL_TILE_SIZE);
						int by = (int) Math.round((pc.y2 - baseY) * Perspective.LOCAL_TILE_SIZE);
						if (ax < 0 || ay < 0 || bx < 0 || by < 0 || ax > maxLocalX || bx > maxLocalX || ay > maxLocalY || by > maxLocalY)
						{
							continue;
						}
						wall(g, me2, plane, ax, ay, bx, by, height, reach, fadeWidth);
					}
				}
			}
		}

		g.setClip(origClip);
		if (layerG != null)
		{
			// cut your model's silhouette out of the "behind you" layer, then lay it on the screen
			layerG.setComposite(AlphaComposite.DstOut);
			layerG.setColor(Color.WHITE);
			layerG.fill(sil);
			layerG.dispose();
			layerG = null;
			int w = silBounds.width, h = silBounds.height;
			g.drawImage(layer, silBounds.x, silBounds.y, silBounds.x + w, silBounds.y + h, 0, 0, w, h, null);
		}
		if (aa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		}
		return null;
	}

	// ---- your character's silhouette, built from the real model triangles ----

	private int[] px = new int[0], py = new int[0];

	private Path2D.Double silhouette(WorldView wv, Player me, int plane)
	{
		Model model = me.getModel();
		LocalPoint ml = me.getLocalLocation();
		if (model == null || ml == null)
		{
			return null;
		}
		int n = model.getVerticesCount();
		int fc = model.getFaceCount();
		if (n <= 0 || fc <= 0)
		{
			return null;
		}
		if (px.length < n)
		{
			px = new int[n];
			py = new int[n];
		}
		int z = Perspective.getFootprintTileHeight(client, ml, plane, me.getFootprintSize()) - me.getAnimationHeightOffset();
		// same call RuneLite's own model outline uses (note the Y/Z swap)
		Perspective.modelToCanvas(client, wv, n, ml.getX(), ml.getY(), z, me.getCurrentOrientation(),
			model.getVerticesX(), model.getVerticesZ(), model.getVerticesY(), px, py);
		int[] i1 = model.getFaceIndices1(), i2 = model.getFaceIndices2(), i3 = model.getFaceIndices3();
		byte[] tr = model.getFaceTransparencies();
		Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
		boolean any = false;
		for (int f = 0; f < fc; f++)
		{
			if (tr != null && (tr[f] & 0xFF) >= 250)
			{
				continue; // invisible face
			}
			int a = i1[f], b = i2[f], c = i3[f];
			if (py[a] == Integer.MIN_VALUE || py[b] == Integer.MIN_VALUE || py[c] == Integer.MIN_VALUE)
			{
				continue;
			}
			long cross = (long) (px[b] - px[a]) * (py[c] - py[a]) - (long) (px[c] - px[a]) * (py[b] - py[a]);
			if (cross == 0)
			{
				continue;
			}
			path.moveTo(px[a], py[a]);
			if (cross > 0)
			{
				path.lineTo(px[b], py[b]);
				path.lineTo(px[c], py[c]);
			}
			else
			{
				path.lineTo(px[c], py[c]);
				path.lineTo(px[b], py[b]);
			}
			path.closePath();
			any = true;
		}
		return any ? path : null;
	}

	// ---- offscreen layer for the parts of the barrier that are behind you ----

	private BufferedImage layer;
	private Graphics2D layerG;

	private Graphics2D layerGraphics()
	{
		if (layerG == null)
		{
			int w = silBounds.width, h = silBounds.height;
			if (layer == null || layer.getWidth() < w || layer.getHeight() < h)
			{
				layer = new BufferedImage(Math.max(w, layer == null ? 0 : layer.getWidth()),
					Math.max(h, layer == null ? 0 : layer.getHeight()), BufferedImage.TYPE_INT_ARGB);
			}
			Graphics2D lg = layer.createGraphics();
			lg.setComposite(AlphaComposite.Clear);
			lg.fillRect(0, 0, layer.getWidth(), layer.getHeight());
			lg.setComposite(AlphaComposite.SrcOver);
			lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			lg.translate(-silBounds.x, -silBounds.y);
			// the layer image can be bigger than this frame's box (it is reused): only ever use the box
			lg.clipRect(silBounds.x, silBounds.y, w, h);
			layerG = lg;
		}
		return layerG;
	}

	private java.awt.Shape origClip;
	private Path2D.Double sil;
	private Rectangle silBounds;
	private Area outsideBounds;
	private double playerDepth = Double.NaN, camX, camY, fwdX, fwdY;
	private Color base = Color.RED;
	private double pulseNow = 1;

	private static Color alpha(Color c, double a)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.max(0, Math.min(255, Math.round(a))));
	}

	private void wall(Graphics2D g, LocalPoint me, int plane, int ax, int ay, int bx, int by, int height, int reach, int fadeWidth)
	{
		// distance from the player to the middle of this edge, in tiles
		double dx = (ax + bx) / 2.0 - me.getX();
		double dy = (ay + by) / 2.0 - me.getY();
		double d = Math.sqrt(dx * dx + dy * dy) / Perspective.LOCAL_TILE_SIZE;
		// fully visible up to (reach - fadeWidth), fading to nothing at reach
		double fade = Math.max(0, Math.min(1, (reach - d) / fadeWidth));
		if (fade <= 0.01)
		{
			return;
		}
		int ha = Perspective.getTileHeight(client, new LocalPoint(ax, ay, WorldView.TOPLEVEL), plane);
		int hb = Perspective.getTileHeight(client, new LocalPoint(bx, by, WorldView.TOPLEVEL), plane);
		// in OSRS, smaller z is higher up
		Point a0 = Perspective.localToCanvas(client, ax, ay, ha);
		Point b0 = Perspective.localToCanvas(client, bx, by, hb);
		Point b1 = Perspective.localToCanvas(client, bx, by, hb - height);
		Point a1 = Perspective.localToCanvas(client, ax, ay, ha - height);
		if (a0 == null || b0 == null || a1 == null || b1 == null)
		{
			return;
		}
		double k = fade * pulseNow;
		double A = base.getAlpha();

		// glowing wall: strongest at the top rail, fading towards the ground
		Path2D.Double quad = new Path2D.Double();
		quad.moveTo(a0.getX(), a0.getY());
		quad.lineTo(b0.getX(), b0.getY());
		quad.lineTo(b1.getX(), b1.getY());
		quad.lineTo(a1.getX(), a1.getY());
		quad.closePath();
		// is this bit of barrier further from the camera than the player? then the player is drawn over it
		boolean behind = false;
		if (sil != null)
		{
			double wDepth = ((ax + bx) / 2.0 - camX) * fwdX + ((ay + by) / 2.0 - camY) * fwdY;
			if (wDepth > playerDepth + 8)
			{
				java.awt.geom.Rectangle2D qb = quad.getBounds2D();
				qb.setRect(qb.getX() - 6, qb.getY() - 6, qb.getWidth() + 12, qb.getHeight() + 12);
				behind = qb.intersects(silBounds);
			}
		}
		g.setClip(origClip);
		if (!behind)
		{
			paint(g, quad, a0, b0, a1, b1, k, A);
			return;
		}
		// outside the player's bounding box: draw normally; inside it: into the layer that gets the cut-out
		g.clip(outsideBounds);
		paint(g, quad, a0, b0, a1, b1, k, A);
		g.setClip(origClip);
		paint(layerGraphics(), quad, a0, b0, a1, b1, k, A);
	}

	private void paint(Graphics2D g, Path2D.Double quad, Point a0, Point b0, Point a1, Point b1, double k, double A)
	{
		float mx = (a1.getX() + b1.getX()) / 2f, my = (a1.getY() + b1.getY()) / 2f;
		float gx = (a0.getX() + b0.getX()) / 2f, gy = (a0.getY() + b0.getY()) / 2f;
		if (Math.abs(my - gy) < 1f)
		{
			gy = my + 1f;
		}
		g.setPaint(new GradientPaint(mx, my, alpha(base, A * 0.95 * k), gx, gy, alpha(base, A * 0.35 * k)));
		g.fill(quad);

		// glowing top rail: wide soft halo, then a bright core
		Line2D.Double rail = new Line2D.Double(a1.getX(), a1.getY(), b1.getX(), b1.getY());
		g.setStroke(new BasicStroke(9f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(base, A * 0.10 * k));
		g.draw(rail);
		g.setStroke(new BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(base, A * 0.22 * k));
		g.draw(rail);
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(alpha(lighten(base), Math.min(255, (A + 100) * k)));
		g.draw(rail);
	}

	private static Color lighten(Color c)
	{
		return new Color(Math.min(255, c.getRed() + 30), Math.min(255, c.getGreen() + 60), Math.min(255, c.getBlue() + 60), c.getAlpha());
	}
}
