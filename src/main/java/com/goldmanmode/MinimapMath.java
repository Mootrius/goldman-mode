package com.goldmanmode;

import net.runelite.api.Perspective;

/**
 * Tile -> minimap pixel maths, the same as RuneLite's {@code Perspective.localToMinimap}
 * (minimap zoom in pixels per tile, rotated by the camera yaw), but without its distance cut-off, so whole
 * area outlines can be projected even when some of their corners are far off the minimap.
 */
final class MinimapMath
{
	private MinimapMath()
	{
	}

	/**
	 * @param dxTiles   tiles east of the camera focus (the player)
	 * @param dyTiles   tiles north of the camera focus
	 * @param zoom      client.getMinimapZoom(): minimap pixels per tile (4 by default)
	 * @param yawTarget client.getCameraYawTarget()
	 * @param out       receives the pixel offset from the centre of the minimap: out[0] right, out[1] down
	 */
	static void project(double dxTiles, double dyTiles, double zoom, int yawTarget, double[] out)
	{
		int yaw = yawTarget & 0x3FFF;
		double sin = Perspective.SINE14[yaw] / 65536.0;
		double cos = Perspective.COSINE14[yaw] / 65536.0;
		double xs = dxTiles * zoom;
		double ys = dyTiles * zoom;
		out[0] = cos * xs + sin * ys;
		out[1] = sin * xs - cos * ys;
	}
}
