package com.goldmanmode;

import java.awt.Color;

/**
 * The fixed settings of Goldman Mode. These used to be options in the plugin's settings menu; they are baked in now, so
 * everyone plays with the same rules and the same look. (Only the click blocking is still an option.)
 */
final class Settings
{
	private Settings()
	{
	}

	// ---- the barrier around locked areas in the game world
	static final boolean SHOW_BARRIER = true;
	/** #64FF0000 (alpha, red, green, blue) */
	static final Color BARRIER_COLOUR = new Color(0x64FF0000, true);
	static final int BARRIER_HEIGHT = 250;
	static final int BARRIER_DISTANCE = 15;
	static final int BARRIER_FADE_WIDTH = 5;
	static final int PULSE_STRENGTH = 60;
	static final int PULSE_PERIOD_MS = 1500;

	// ---- the minimap
	static final boolean SHOW_MINIMAP = true;
	/** #4BFF0000 */
	static final Color MINIMAP_TINT_COLOUR = new Color(0x4BFF0000, true);
	/** #FFFF0000 */
	static final Color MINIMAP_BORDER_COLOUR = new Color(0xFFFF0000, true);

	// ---- prices
	static final int PRICE_MULTIPLIER = 100;
	static final String STARTING_AREAS = "Tutorial Island, Lumbridge Castle";

	// ---- the Goldman world map window
	static final boolean MAP_SHARP_PIXELS = true;
	static final boolean MAP_SHOW_BORDERS = true;
	static final boolean MAP_FILL_LOCKED = true;
	static final boolean MAP_SHOW_UNLOCKED_BORDERS = true;
	static final int MAP_ZOOM_SPEED = 60;
	static final boolean MAP_REPLACES_ORB = true;

	// ---- earning
	static final boolean SHOW_GP_OVERLAY = true;
	static final int XP_PER_GP = 1;
	static final int GP_PER_KILL_LEVEL = 10;
	static final boolean ANNOUNCE_PAYOUTS = true;
	static final boolean SHOW_CLOG_TOOLTIP = true;
	static final boolean SHOW_QUEST_TOOLTIP = true;

	// ---- the side panel
	static final boolean TESTING_TOOLS = true;
}
