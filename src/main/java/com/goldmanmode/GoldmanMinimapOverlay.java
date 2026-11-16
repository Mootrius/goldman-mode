package com.goldmanmode;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.CameraFocusableEntity;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Shows locked areas on the minimap: the inside is tinted and the border (wherever a locked area meets
 * ground that is not locked) is drawn as a line, the same borders the in-game barrier follows.
 */
@Singleton
public class GoldmanMinimapOverlay extends Overlay
{
	private final Client client;
	private final GoldmanState state;

	/** scratch image the tint is drawn into, so areas that overlap are tinted once, not twice */
	private BufferedImage layer;
	private final double[] tmp = new double[2];

	@Inject
	GoldmanMinimapOverlay(Client client, GoldmanState state)
	{
		this.client = client;
		this.state = state;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	private Widget minimapWidget()
	{
		Widget w;
		if (client.isResized())
		{
			w = client.getVarbitValue(4607) == 1
				? client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP)
				: client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
		}
		else
		{
			w = client.getWidget(InterfaceID.Toplevel.MINIMAP);
		}
		return w == null || w.isHidden() ? null : w;
	}


	/** buttons that sit on top of the minimap (the world map globe, the wiki banner, the compass, the orbs) */
	private static final int[] ROUND_WIDGETS = {
		InterfaceID.Orbs.ORB_WORLDMAP, InterfaceID.Orbs.WORLDMAP, InterfaceID.Orbs.ORB_HEALTH, InterfaceID.Orbs.ORB_PRAYER,
		InterfaceID.Orbs.ORB_RUNENERGY, InterfaceID.Orbs.ORB_SPECENERGY, InterfaceID.Orbs.ORB_STORE, InterfaceID.Orbs.XP_DROPS,
		InterfaceID.Orbs.CR_BUTTON, InterfaceID.Toplevel.COMPASSCLICK, InterfaceID.ToplevelOsrsStretch.COMPASSCLICK,
		InterfaceID.ToplevelPreEoc.COMPASSCLICK, InterfaceID.ToplevelOsm.COMPASSCLICK
	};
	private static final int[] BOX_WIDGETS = {InterfaceID.Orbs.WIKI};

	/** takes the other buttons out of the area we may draw in, so the tint never covers them */
	private void cutOut(Area clip)
	{
		for (int id : ROUND_WIDGETS)
		{
			Rectangle b = visibleBounds(id, 80);
			if (b != null)
			{
				clip.subtract(new Area(new Ellipse2D.Double(b.x, b.y, b.width, b.height)));
			}
		}
		for (int id : BOX_WIDGETS)
		{
			Rectangle b = visibleBounds(id, 120);
			if (b != null)
			{
				clip.subtract(new Area(b));
			}
		}
	}

	private Rectangle visibleBounds(int id, int maxSize)
	{
		Widget wd = client.getWidget(id);
		if (wd == null || wd.isHidden() || wd.isSelfHidden())
		{
			return null;
		}
		Rectangle b = wd.getBounds();
		// a container much bigger than a button is not the button: leave it alone
		return b == null || b.width <= 0 || b.height <= 0 || b.width > maxSize || b.height > maxSize ? null : b;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!Settings.SHOW_MINIMAP || Instances.isInstanced(client))
		{
			return null;
		}
		Widget mm = minimapWidget();
		WorldView wv = client.getTopLevelWorldView();
		CameraFocusableEntity focusEntity = client.getCameraFocusEntity();
		if (mm == null || wv == null || focusEntity == null)
		{
			return null;
		}
		LocalPoint focus = focusEntity.getCameraFocus();
		Point loc = mm.getCanvasLocation();
		if (focus == null || focus.getWorldView() != 0 || loc == null)
		{
			return null;
		}
		final int w = mm.getWidth(), h = mm.getHeight();
		if (w <= 0 || h <= 0)
		{
			return null;
		}
		final double zoom = client.getMinimapZoom();
		if (zoom <= 0)
		{
			return null;
		}
		AreaIndex index = state.getIndex();
		List<GoldmanArea> areas = index.getAreas();
		if (areas.isEmpty())
		{
			return null;
		}

		// where the player is, in world tiles, and the minimap's centre on the canvas
		final double px = wv.getBaseX() + focus.getX() / (double) Perspective.LOCAL_TILE_SIZE;
		final double py = wv.getBaseY() + focus.getY() / (double) Perspective.LOCAL_TILE_SIZE;
		final double cx = loc.getX() + w / 2.0, cy = loc.getY() + h / 2.0;
		final int yaw = client.getCameraYawTarget();
		final double reach = Math.max(w, h) / 2.0 / zoom + 3; // tiles from the player to the edge of the minimap

		Shape oldClip = g.getClip();
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		double rad = Math.min(w, h) / 2.0; // the visible minimap is exactly the circle inscribed in its draw area
		Area clip = new Area(new Ellipse2D.Double(cx - rad, cy - rad, rad * 2, rad * 2));
		cutOut(clip);
		g.setClip(clip);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// ---- tint
		Color tint = Settings.MINIMAP_TINT_COLOUR;
		if (tint.getAlpha() > 0)
		{
			if (layer == null || layer.getWidth() != w || layer.getHeight() != h)
			{
				layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			}
			Graphics2D lg = layer.createGraphics();
			lg.setComposite(AlphaComposite.Clear);
			lg.fillRect(0, 0, w, h);
			lg.setComposite(AlphaComposite.Src);
			lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			lg.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 255));
			boolean any = false;
			for (int i = 0; i < areas.size(); i++)
			{
				if (!state.isLockedIdx(i))
				{
					continue;
				}
				GoldmanArea a = areas.get(i);
				if (a.getMaxX() + 1 < px - reach || a.getMinX() > px + reach || a.getMaxY() + 1 < py - reach || a.getMinY() > py + reach)
				{
					continue;
				}
				Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
				for (double[][] ring : a.getRings())
				{
					for (int k = 0; k < ring.length; k++)
					{
						MinimapMath.project(ring[k][0] - px, ring[k][1] - py, zoom, yaw, tmp);
						double sx = cx + tmp[0] - loc.getX(), sy = cy + tmp[1] - loc.getY();
						if (k == 0)
						{
							path.moveTo(sx, sy);
						}
						else
						{
							path.lineTo(sx, sy);
						}
					}
					path.closePath();
				}
				lg.fill(path);
				any = true;
			}
			lg.dispose();
			if (any)
			{
				java.awt.Composite oldComp = g.getComposite();
				g.setComposite(AlphaComposite.SrcOver.derive(tint.getAlpha() / 255f));
				g.drawImage(layer, loc.getX(), loc.getY(), null);
				g.setComposite(oldComp);
			}
		}

		// ---- border lines: where exactly one side is locked, on the outline of the locked area
		Color lineColour = Settings.MINIMAP_BORDER_COLOUR;
		if (lineColour.getAlpha() > 0)
		{
			BarrierGeometry geo = index.getBarrier();
			g.setColor(lineColour);
			g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Line2D.Double line = new Line2D.Double();
			for (int rx = (int) Math.floor(px - reach) >> 6; rx <= (int) Math.floor(px + reach) >> 6; rx++)
			{
				for (int ry = (int) Math.floor(py - reach) >> 6; ry <= (int) Math.floor(py + reach) >> 6; ry++)
				{
					List<BarrierGeometry.Piece> pieces = geo.region(rx, ry);
					if (pieces == null)
					{
						continue;
					}
					for (BarrierGeometry.Piece pc : pieces)
					{
						boolean l1 = state.isLockedIdx(pc.o1), l2 = state.isLockedIdx(pc.o2);
						if (l1 == l2 || pc.ring != (l1 ? pc.o1 : pc.o2))
						{
							continue;
						}
						MinimapMath.project(pc.x1 - px, pc.y1 - py, zoom, yaw, tmp);
						double ax = cx + tmp[0], ay = cy + tmp[1];
						MinimapMath.project(pc.x2 - px, pc.y2 - py, zoom, yaw, tmp);
						line.setLine(ax, ay, cx + tmp[0], cy + tmp[1]);
						g.draw(line);
					}
				}
			}
		}

		g.setClip(oldClip);
		if (aa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		}
		return null;
	}
}
