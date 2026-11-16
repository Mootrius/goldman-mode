package com.goldmanmode;

/**
 * Where the map is looking: the world point (tiles, y north) in the middle of the screen and how many screen pixels
 * one tile is. All the pixel/tile maths in one place.
 */
final class MapViewport
{
	double cx, cy;
	double scale = 4;
	int w = 800, h = 600;

	double sx(double wx)
	{
		return w / 2.0 + (wx - cx) * scale;
	}

	double sy(double wy)
	{
		return h / 2.0 - (wy - cy) * scale;
	}

	double wx(double sx)
	{
		return cx + (sx - w / 2.0) / scale;
	}

	double wy(double sy)
	{
		return cy - (sy - h / 2.0) / scale;
	}

	void panPixels(double dx, double dy)
	{
		cx -= dx / scale;
		cy += dy / scale;
	}

	/** Zoom so that world point (ax, ay) stays under screen point (sx, sy). */
	void zoomKeeping(double ax, double ay, double sx, double sy, double newScale)
	{
		scale = newScale;
		cx = ax - (sx - w / 2.0) / scale;
		cy = ay + (sy - h / 2.0) / scale;
	}

	/** The scale at which the box (world tiles) just fits the screen, with a margin in pixels. */
	double fitScale(double[] box, double margin)
	{
		double sw = Math.max(1, w - 2 * margin), sh = Math.max(1, h - 2 * margin);
		return Math.min(sw / (box[2] - box[0]), sh / (box[3] - box[1]));
	}

	void fit(double[] box, double margin)
	{
		scale = fitScale(box, margin);
		cx = (box[0] + box[2]) / 2;
		cy = (box[1] + box[3]) / 2;
	}

	/** Keep the middle of the screen inside the box (plus a little slack). */
	void clampCentre(double[] box, double slackTiles)
	{
		cx = Math.max(box[0] - slackTiles, Math.min(box[2] + slackTiles, cx));
		cy = Math.max(box[1] - slackTiles, Math.min(box[3] + slackTiles, cy));
	}
}
