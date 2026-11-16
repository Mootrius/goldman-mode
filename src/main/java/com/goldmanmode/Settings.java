package com.goldmanmode;

import java.awt.Color;

/** The fixed look and rules of Goldman Mode (the options you can change are in {@link GoldmanModeConfig}). */
final class Settings
{
	private Settings()
	{
	}

	// the barrier around locked areas in the game world
	static final Color BARRIER_COLOUR = new Color(0x64FF0000, true); // alpha, red, green, blue
	static final int BARRIER_HEIGHT = 250;
	static final int BARRIER_DISTANCE = 15;
	static final int BARRIER_FADE_WIDTH = 5;
	static final int PULSE_STRENGTH = 60;
	static final int PULSE_PERIOD_MS = 1500;

	// the minimap
	static final Color MINIMAP_TINT_COLOUR = new Color(0x4BFF0000, true);
	static final Color MINIMAP_BORDER_COLOUR = new Color(0xFFFF0000, true);

	static final String STARTING_AREAS = "Tutorial Island, Lumbridge Castle";

	// earning
	static final int XP_PER_GP = 1;
	static final int GP_PER_KILL_LEVEL = 10;
}
