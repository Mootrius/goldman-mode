package com.goldmanmode;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

/** The Goldman GP balance on screen: "Goldman GP" on the first row, the amount on the second, both centred.
 * The amount ticks up over 0.6 seconds whenever it goes up. */
@Singleton
public class GoldmanGpOverlay extends OverlayPanel
{
	private final Client client;
	private final GoldmanState state;
	private final GoldmanModeConfig config;
	private final CountUp shown = new CountUp();

	@Inject
	GoldmanGpOverlay(Client client, GoldmanState state, GoldmanModeConfig config)
	{
		this.client = client;
		this.state = state;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showGpOverlay() || client.getGameState() != GameState.LOGGED_IN)
		{
			shown.reset(); // the first number after logging in (or turning the overlay on) shows at once
			return null;
		}
		long now = System.currentTimeMillis();
		shown.update(state.getBalance(), now);
		panelComponent.setPreferredSize(new Dimension(110, 0));
		panelComponent.getChildren().add(TitleComponent.builder().text("Goldman GP").color(Color.WHITE).build());
		panelComponent.getChildren().add(TitleComponent.builder()
			.text(QuantityFormatter.formatNumber(shown.current(now))).color(Color.YELLOW).build());
		return super.render(g);
	}
}
