package com.goldmanmode;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import javax.swing.JComponent;

/**
 * A row of equal buttons joined into one bar (a "segmented control"). What each segment looks like is up to the
 * {@link Cell}; which ones count as selected comes from {@code selected}; a click calls {@code onClick}. It draws
 * everything itself (no text glyphs a font might lack) and has no tooltips.
 */
final class SegmentBar extends JComponent
{
	interface Cell
	{
		/** draws the content of one segment inside {@code r} */
		void paint(Graphics2D g, Rectangle r, boolean selected);
	}

	static final Color OFF = new Color(0x1E, 0x1E, 0x1E);
	static final Color ON = new Color(0x55, 0x55, 0x55);
	/** the same two colours while the mouse is over a segment: a touch brighter */
	static final Color OFF_HOVER = new Color(0x2C, 0x2C, 0x2C);
	static final Color ON_HOVER = new Color(0x68, 0x68, 0x68);
	static final Color EDGE = new Color(0x3A, 0x3A, 0x3A);

	private final Cell[] cells;
	private final IntPredicate selected;
	private int hover = -1;

	SegmentBar(Cell[] cells, IntPredicate selected, IntConsumer onClick)
	{
		this.cells = cells;
		this.selected = selected;
		setPreferredSize(new Dimension(10, 28));
		setOpaque(false);
		MouseAdapter mouse = new MouseAdapter()
		{
			private int at(MouseEvent e)
			{
				int i = e.getX() * cells.length / Math.max(1, getWidth());
				return i >= 0 && i < cells.length ? i : -1;
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				int i = at(e);
				if (i >= 0)
				{
					onClick.accept(i);
					repaint();
				}
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				setHover(at(e));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setHover(-1);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	private void setHover(int i)
	{
		if (i != hover)
		{
			hover = i;
			repaint();
		}
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int w = getWidth();
		int h = getHeight();
		for (int i = 0; i < cells.length; i++)
		{
			int x0 = i * w / cells.length;
			int x1 = (i + 1) * w / cells.length;
			Rectangle r = new Rectangle(x0, 0, x1 - x0, h);
			boolean on = selected.test(i);
			g.setColor(i == hover ? (on ? ON_HOVER : OFF_HOVER) : (on ? ON : OFF));
			g.fillRect(r.x, r.y, r.width, r.height);
			g.setColor(EDGE);
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
			Graphics2D cell = (Graphics2D) g.create(); // each cell gets its own graphics, so its icon's settings don't leak into the next border
			cell.clipRect(r.x, r.y, r.width, r.height);
			cells[i].paint(cell, r, on);
			cell.dispose();
		}
		g.dispose();
	}

	// ------------------------------------------------------------------ icons

	/** a padlock drawn as thin line art (like the search icon): closed (locked) or with its shackle lifted (unlocked) */
	static void lock(Graphics2D g, Rectangle r, boolean closed, Color colour)
	{
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		double cx = r.x + r.width / 2.0;
		double cy = r.y + r.height / 2.0;
		g.setColor(colour);
		g.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		// the body, with a keyhole
		double bx = cx - 5.5;
		double by = cy - 1.5;
		g.draw(new java.awt.geom.RoundRectangle2D.Double(bx, by, 11, 8.5, 2, 2));
		g.fill(new java.awt.geom.Ellipse2D.Double(cx - 1, by + 2.6, 2, 2));
		// the shackle: the same loop either way. Unlocked has the lower part of its right side erased
		g.draw(new java.awt.geom.Line2D.Double(bx + 2, by - 2, bx + 2, by));
		if (closed)
		{
			g.draw(new java.awt.geom.Arc2D.Double(bx + 2, by - 6.5, 7, 9, 0, 180, java.awt.geom.Arc2D.OPEN));
			g.draw(new java.awt.geom.Line2D.Double(bx + 9, by - 2, bx + 9, by));
		}
		else
		{
			g.draw(new java.awt.geom.Arc2D.Double(bx + 2, by - 6.5, 7, 9, 40, 140, java.awt.geom.Arc2D.OPEN));
		}
		g.setStroke(new java.awt.BasicStroke(1f));
	}

	/** a small triangle pointing up or down with its centre at (cx, cy) */
	static void arrow(Graphics2D g, int cx, int cy, boolean up, Color colour)
	{
		int[] xs = {cx - 4, cx + 4, cx};
		int[] ys = up ? new int[]{cy + 3, cy + 3, cy - 3} : new int[]{cy - 3, cy - 3, cy + 3};
		g.setColor(colour);
		g.fillPolygon(xs, ys, 3);
	}
}
