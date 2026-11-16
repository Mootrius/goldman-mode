package com.goldmanmode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Color;
import java.awt.Graphics2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** The game's own pixel font (RuneScape Plain 11), drawn pixel by pixel from a glyph table. */
final class BitmapFont
{
	private static final class Glyph
	{
		int topBearing, height, width, advance, leftBearing;
		boolean[] pixels;
	}

	private final Map<Integer, Glyph> glyphs = new HashMap<>();
	private final int ascent, maxDescent;

	private BitmapFont(JsonObject o)
	{
		ascent = o.get("ascent").getAsInt();
		maxDescent = o.get("maxDescent").getAsInt();
		for (JsonElement e : o.getAsJsonArray("glyphs"))
		{
			JsonObject g = e.getAsJsonObject();
			Glyph gl = new Glyph();
			gl.topBearing = g.get("topBearing").getAsInt();
			gl.height = g.get("height").getAsInt();
			gl.width = g.get("width").getAsInt();
			gl.advance = g.get("advance").getAsInt();
			gl.leftBearing = g.get("leftBearing").getAsInt();
			JsonArray px = g.getAsJsonArray("pixels");
			gl.pixels = new boolean[px.size()];
			for (int i = 0; i < gl.pixels.length; i++)
			{
				gl.pixels[i] = px.get(i).getAsInt() != 0;
			}
			glyphs.put(g.get("codePoint").getAsInt(), gl);
		}
	}

	static BitmapFont load()
	{
		try (InputStream in = BitmapFont.class.getResourceAsStream("RuneScape-Plain-11.json");
			Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
		{
			return new BitmapFont(new JsonParser().parse(r).getAsJsonObject());
		}
		catch (IOException | RuntimeException e)
		{
			throw new IllegalStateException("font missing or broken", e);
		}
	}

	/** height of a line of text, pixels */
	int lineHeight()
	{
		return ascent + maxDescent;
	}

	int width(String s)
	{
		int w = 0;
		for (int i = 0; i < s.length(); i++)
		{
			Glyph g = glyphs.get((int) s.charAt(i));
			w += g == null ? 4 : g.advance;
		}
		return w;
	}

	/** Draw s with the top-left of its line at (x, y). */
	void draw(Graphics2D g, String s, int x, int y, Color c)
	{
		g.setColor(c);
		int pen = x;
		for (int i = 0; i < s.length(); i++)
		{
			Glyph gl = glyphs.get((int) s.charAt(i));
			if (gl == null)
			{
				pen += 4;
				continue;
			}
			for (int row = 0; row < gl.height; row++)
			{
				int col = 0;
				while (col < gl.width)
				{
					if (!gl.pixels[row * gl.width + col])
					{
						col++;
						continue;
					}
					int start = col;
					while (col < gl.width && gl.pixels[row * gl.width + col])
					{
						col++;
					}
					g.fillRect(pen + gl.leftBearing + start, y + gl.topBearing + row, col - start, 1);
				}
			}
			pen += gl.advance;
		}
	}
}
