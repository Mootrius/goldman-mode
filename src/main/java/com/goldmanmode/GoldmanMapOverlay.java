package com.goldmanmode;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws the Goldman world map over the whole game window while it is open. */
@Singleton
public class GoldmanMapOverlay extends Overlay
{
	private final Client client;
	private volatile GoldmanMapView view;

	@Inject
	GoldmanMapOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_HIGHEST);
	}

	void setView(GoldmanMapView view)
	{
		this.view = view;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		GoldmanMapView v = view;
		if (v == null || !v.isVisible())
		{
			return null;
		}
		v.render(g, client.getCanvasWidth(), client.getCanvasHeight());
		return null;
	}
}
