package com.goldmanmode;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.BasicStroke;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;

/** The sidebar: Goldman GP balance, the testing tools and the area list with its Relock padlocks. */
public class GoldmanPanel extends PluginPanel
{
	private static final Color GOLD = new Color(0xE8, 0xC0, 0x4A);
	private static final Color UNLOCKED = new Color(0x4C, 0xAF, 0x50);
	private static final Color LOCKED = new Color(0xE5, 0x39, 0x35);
	/** the text boxes: darker than the sidebar */
	private static final Color FIELD = new Color(0x1A, 0x1A, 0x1A);
	/** the text boxes while the mouse is over them: a touch brighter */
	private static final Color FIELD_HOVER = new Color(0x26, 0x26, 0x26);
	/** the scroll bar of the area list */
	private static final Color SCROLL_THUMB = new Color(0x80, 0x80, 0x80);
	private static final Color SEARCH_ICON = new Color(0x8A, 0x8A, 0x8A);
	private static final Color DIM = new Color(0x70, 0x70, 0x70);

	private final GoldmanState state;

	private final JLabel balanceLabel = new JLabel("", SwingConstants.CENTER);
	/** ticks the balance up to a new value, like the on-screen overlay; the timer runs only while it is moving */
	private final CountUp balanceShown = new CountUp();
	private final Timer balanceTimer = new Timer(20, e -> showBalance());
	private final JLabel progressLabel = new JLabel("", SwingConstants.CENTER);
	private final JTextField amountField = darkField(false, true);
	private final JTextField search = darkField(true, false);
	/** which kinds of areas the list shows (both on = everything); it opens showing only the areas you have unlocked */
	private boolean showLocked = false;
	private boolean showUnlocked = true;
	/** how the list is sorted: 0 = A-Z, 1 = cost, 2 = size; {@code sortUp[k]} is true for A to Z / cheapest first / smallest first */
	private int sortKey = 1; // it opens sorted by cost, cheapest at the top
	private final boolean[] sortUp = {true, true, false};
	private final JPanel list = new JPanel(new GridLayout(0, 1, 0, 5));

	private final Runnable listener = () -> SwingUtilities.invokeLater(this::refresh);

	@Inject
	GoldmanPanel(GoldmanState state)
	{
		super(false);
		this.state = state;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.setBorder(new EmptyBorder(8, 8, 0, 8));

		balanceLabel.setForeground(GOLD);
		balanceLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(22f));
		balanceLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		top.add(balanceLabel);
		top.add(Box(8));

		buildTestBox();
		testBox.setAlignmentX(Component.CENTER_ALIGNMENT);
		top.add(testBox);
		top.add(Box(8));

		// "N / 405 areas unlocked" is the title of the area list
		progressLabel.setForeground(Color.WHITE);
		progressLabel.setFont(FontManager.getRunescapeBoldFont());
		progressLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		top.add(progressLabel);
		top.add(Box(4));

		search.getDocument().addDocumentListener(new DocumentListener()
		{
			public void insertUpdate(DocumentEvent e)
			{
				refreshList();
			}

			public void removeUpdate(DocumentEvent e)
			{
				refreshList();
			}

			public void changedUpdate(DocumentEvent e)
			{
				refreshList();
			}
		});
		search.setAlignmentX(Component.CENTER_ALIGNMENT);
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		search.setPreferredSize(new Dimension(10, 30));
		top.add(search);
		top.add(Box(4));

		JPanel controls = new JPanel(new BorderLayout(4, 0));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		controls.add(filterBar(), BorderLayout.WEST);
		controls.add(sortBar(), BorderLayout.CENTER);
		controls.setAlignmentX(Component.CENTER_ALIGNMENT);
		controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		top.add(controls);
		top.add(Box(6));

		add(top, BorderLayout.NORTH);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.setBorder(new EmptyBorder(0, 8, 8, 8));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(list, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(wrapper);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setViewportBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUI(plainScrollBar());
		scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		state.addListener(listener);
		refresh();
	}

	void shutDown()
	{
		state.removeListener(listener);
		balanceTimer.stop();
	}

	private static Component Box(int h)
	{
		return javax.swing.Box.createRigidArea(new Dimension(0, h));
	}

	/** a plain gray bar on the dark background: no arrow buttons, no rounded or shaded thumb */
	private static BasicScrollBarUI plainScrollBar()
	{
		return new BasicScrollBarUI()
		{
			@Override
			protected void configureScrollBarColors()
			{
				thumbColor = SCROLL_THUMB;
				trackColor = ColorScheme.DARK_GRAY_COLOR;
			}

			@Override
			protected JButton createDecreaseButton(int orientation)
			{
				return zeroButton();
			}

			@Override
			protected JButton createIncreaseButton(int orientation)
			{
				return zeroButton();
			}

			private JButton zeroButton()
			{
				JButton b = new JButton();
				b.setPreferredSize(new Dimension(0, 0));
				b.setMinimumSize(new Dimension(0, 0));
				b.setMaximumSize(new Dimension(0, 0));
				return b;
			}

			@Override
			protected void paintTrack(Graphics g, javax.swing.JComponent c, Rectangle r)
			{
				g.setColor(ColorScheme.DARK_GRAY_COLOR);
				g.fillRect(r.x, r.y, r.width, r.height);
			}

			@Override
			protected void paintThumb(Graphics g, javax.swing.JComponent c, Rectangle r)
			{
				if (r.isEmpty() || !scrollbar.isEnabled())
				{
					return;
				}
				g.setColor(SCROLL_THUMB);
				g.fillRect(r.x, r.y, r.width, r.height);
			}
		};
	}

	private static JLabel title(String text)
	{
		JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setForeground(Color.WHITE);
		l.setFont(FontManager.getRunescapeBoldFont());
		return l;
	}

	/**
	 * RuneLite's look and feel only binds Ctrl+A / C / V / X in text boxes, so on a Mac Cmd+A (select all) and the rest do
	 * nothing. Bind the platform's own shortcut key (Cmd on a Mac, Ctrl elsewhere) to the same actions.
	 */
	private static void addMacShortcuts(JTextField f)
	{
		int mask;
		try
		{
			mask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		}
		catch (java.awt.HeadlessException e)
		{
			mask = java.awt.event.InputEvent.CTRL_DOWN_MASK;
		}
		javax.swing.InputMap in = f.getInputMap(JComponent.WHEN_FOCUSED);
		in.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, mask), javax.swing.text.DefaultEditorKit.selectAllAction);
		in.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, mask), javax.swing.text.DefaultEditorKit.copyAction);
		in.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, mask), javax.swing.text.DefaultEditorKit.pasteAction);
		in.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_X, mask), javax.swing.text.DefaultEditorKit.cutAction);
	}

	/** a text box darker than the sidebar, with white text; it can have a magnifying glass or a "#" on its left */
	private static JTextField darkField(boolean magnifier, boolean hash)
	{
		JTextField f = new JTextField()
		{
			@Override
			protected void paintComponent(Graphics g0)
			{
				super.paintComponent(g0);
				if (hash)
				{
					Graphics2D g = (Graphics2D) g0.create();
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
					// a "#" in the same thin line style as the search icon: two slanted uprights, two crossbars
					double cy = getHeight() / 2.0;
					g.setColor(SEARCH_ICON);
					g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					g.draw(new java.awt.geom.Line2D.Double(13.2, cy - 6, 11.6, cy + 6));
					g.draw(new java.awt.geom.Line2D.Double(17.6, cy - 6, 16, cy + 6));
					g.draw(new java.awt.geom.Line2D.Double(9.5, cy - 2.4, 19.5, cy - 2.4));
					g.draw(new java.awt.geom.Line2D.Double(8.7, cy + 2.4, 18.7, cy + 2.4));
					g.dispose();
				}
				if (magnifier)
				{
					Graphics2D g = (Graphics2D) g0.create();
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
					// a thin gray ring with a diagonal handle, the shape of the little icon in the game's search boxes
					double cy = getHeight() / 2.0;
					g.setColor(SEARCH_ICON);
					g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					// ring centre (14, cy - 3), radius 4.5; the handle starts just outside the ring along the 45 degree line
					g.draw(new java.awt.geom.Ellipse2D.Double(9.5, cy - 7.5, 9, 9));
					g.draw(new java.awt.geom.Line2D.Double(17.5, cy + 0.5, 21.2, cy + 4.2));
					g.dispose();
				}
			}
		};
		f.setBackground(FIELD);
		f.setForeground(Color.WHITE);
		f.setCaretColor(Color.WHITE);
		f.setSelectionColor(GOLD); // highlighted text: the gold of the balance
		f.setSelectedTextColor(FIELD); // dark text, so it stays readable on the gold
		f.setOpaque(true);
		f.setBorder(new CompoundBorder(new MatteBorder(1, 1, 1, 1, SegmentBar.EDGE),
			new EmptyBorder(3, magnifier || hash ? 28 : 6, 3, 6)));
		addMacShortcuts(f);
		f.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				f.setBackground(FIELD_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				f.setBackground(FIELD);
			}
		});
		return f;
	}

	/** the Locked / Unlocked filter: two padlock buttons, each can be on or off */
	private JComponent filterBar()
	{
		SegmentBar.Cell[] cells = {
			(g, r, on) -> SegmentBar.lock(g, r, true, on ? LOCKED : DIM),
			(g, r, on) -> SegmentBar.lock(g, r, false, on ? UNLOCKED : DIM)};
		SegmentBar bar = new SegmentBar(cells, i -> i == 0 ? showLocked : showUnlocked, i ->
		{
			if (i == 0)
			{
				showLocked = !showLocked;
			}
			else
			{
				showUnlocked = !showUnlocked;
			}
			refreshList();
		});
		bar.setPreferredSize(new Dimension(64, 28));
		return bar;
	}

	/** the sort control: A-Z, $$$ and SIZE; the chosen one shows an arrow, and clicking it again flips the direction */
	private JComponent sortBar()
	{
		String[] names = {"A-Z", "$$$", "SIZE"};
		SegmentBar.Cell[] cells = new SegmentBar.Cell[3];
		for (int k = 0; k < 3; k++)
		{
			final int key = k;
			cells[k] = (g, r, on) ->
			{
				g.setFont(FontManager.getRunescapeSmallFont());
				java.awt.FontMetrics fm = g.getFontMetrics();
				int textW = fm.stringWidth(names[key]);
				int arrowW = on ? 12 : 0;
				int x = r.x + (r.width - textW - arrowW) / 2;
				g.setColor(on ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
				g.drawString(names[key], x, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
				if (on)
				{
					SegmentBar.arrow(g, x + textW + 8, r.y + r.height / 2, sortUp[key], Color.WHITE);
				}
			};
		}
		return new SegmentBar(cells, i -> i == sortKey, i ->
		{
			if (i == sortKey)
			{
				sortUp[i] = !sortUp[i];
			}
			else
			{
				sortKey = i;
			}
			refreshList();
		});
	}

	private final JPanel testBox = new JPanel();

	private void buildTestBox()
	{
		testBox.setLayout(new BoxLayout(testBox, BoxLayout.Y_AXIS));
		testBox.setOpaque(false);

		amountField.setAlignmentX(Component.CENTER_ALIGNMENT);
		amountField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		amountField.setPreferredSize(new Dimension(10, 28));
		testBox.add(amountField);
		testBox.add(Box(4));

		JButton plus = grayButton("+");
		JButton minus = grayButton("-");
		JButton set = grayButton("SET");
		JPanel buttons = new JPanel(new GridLayout(1, 3, 3, 0));
		buttons.setOpaque(false);
		buttons.add(plus);
		buttons.add(minus);
		buttons.add(set);
		buttons.setAlignmentX(Component.CENTER_ALIGNMENT);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		testBox.add(buttons);

		plus.addActionListener(e -> withAmount(v -> state.addBalance(v)));
		minus.addActionListener(e -> withAmount(v -> state.addBalance(-v)));
		set.addActionListener(e -> withAmount(v -> state.setBalance(v)));
	}

	/** a gray button with white text and the same border as the segmented controls; a touch brighter under the mouse */
	private static JButton grayButton(String text)
	{
		JButton b = new JButton(text);
		b.setBackground(SegmentBar.ON);
		b.setForeground(Color.WHITE);
		b.setOpaque(true);
		b.setContentAreaFilled(true);
		b.setFocusPainted(false);
		b.setRolloverEnabled(false);
		b.setBorder(new CompoundBorder(new MatteBorder(1, 1, 1, 1, SegmentBar.EDGE), new EmptyBorder(3, 10, 3, 10)));
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (b.isEnabled())
				{
					b.setBackground(SegmentBar.ON_HOVER);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setBackground(b.isEnabled() ? SegmentBar.ON : SegmentBar.OFF);
			}
		});
		return b;
	}

	/** the Relock button: just a padlock, the gray of an unselected filter padlock, red under the mouse */
	private static JButton lockButton()
	{
		boolean[] hot = {false};
		JButton b = new JButton()
		{
			@Override
			protected void paintComponent(Graphics g0)
			{
				Graphics2D g = (Graphics2D) g0.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				SegmentBar.lock(g, new java.awt.Rectangle(0, 0, getWidth(), getHeight()), true, hot[0] ? LOCKED : DIM);
				g.dispose();
			}
		};
		b.setPreferredSize(new Dimension(28, 28));
		b.setContentAreaFilled(false);
		b.setBorderPainted(false);
		b.setFocusPainted(false);
		b.setOpaque(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				hot[0] = true;
				b.repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hot[0] = false;
				b.repaint();
			}
		});
		return b;
	}

	private void withAmount(java.util.function.LongConsumer action)
	{
		Long v = parseAmount(amountField.getText());
		if (v != null)
		{
			action.accept(v);
			amountField.setText("");
		}
	}

	/** "250000", "250,000", "250k", "1.5m", "2b" */
	static Long parseAmount(String s)
	{
		if (s == null)
		{
			return null;
		}
		s = s.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").replace(" ", "");
		if (s.isEmpty())
		{
			return null;
		}
		double mult = 1;
		char last = s.charAt(s.length() - 1);
		if (last == 'k')
		{
			mult = 1_000;
		}
		else if (last == 'm')
		{
			mult = 1_000_000;
		}
		else if (last == 'b')
		{
			mult = 1_000_000_000;
		}
		if (mult != 1)
		{
			s = s.substring(0, s.length() - 1);
		}
		try
		{
			return Math.round(Double.parseDouble(s) * mult);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	// ------------------------------------------------------------------ refresh

	void refresh()
	{
		showBalance();
		int total = state.getIndex().getAreas().size();
		progressLabel.setText(state.unlockedCount() + " / " + total + " areas unlocked");
		refreshList();
	}

	private void showBalance()
	{
		long now = System.currentTimeMillis();
		long balance = state.getBalance();
		balanceShown.update(balance, now);
		long shown = balanceShown.current(now);
		balanceLabel.setText(QuantityFormatter.formatNumber(shown) + " gp");
		if (shown == balance)
		{
			balanceTimer.stop();
		}
		else if (!balanceTimer.isRunning())
		{
			balanceTimer.start();
		}
	}

	private void refreshList()
	{
		String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
		long balance = state.getBalance();

		List<GoldmanArea> shown = new ArrayList<>();
		for (GoldmanArea a : state.getIndex().getAreas())
		{
			if (!q.isEmpty() && !a.getName().toLowerCase(Locale.ROOT).contains(q))
			{
				continue;
			}
			boolean unlocked = state.isUnlocked(a);
			if (unlocked ? !showUnlocked : !showLocked)
			{
				continue;
			}
			shown.add(a);
		}
		Comparator<GoldmanArea> cmp;
		if (sortKey == 1)
		{
			cmp = Comparator.comparingLong(state::costOf);
		}
		else if (sortKey == 2)
		{
			cmp = Comparator.comparingInt(GoldmanArea::getSize);
		}
		else
		{
			cmp = Comparator.comparing(a -> a.getName().toLowerCase(Locale.ROOT));
		}
		if (!sortUp[sortKey])
		{
			cmp = cmp.reversed();
		}
		shown.sort(cmp);

		list.removeAll();
		for (GoldmanArea a : shown)
		{
			list.add(row(a, balance));
		}
		if (shown.isEmpty())
		{
			JLabel none = new JLabel("No areas match.", SwingConstants.CENTER);
			none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			list.add(none);
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel row(GoldmanArea a, long balance)
	{
		boolean unlocked = state.isUnlocked(a);
		long cost = state.costOf(a);
		Color swatch = unlocked ? UNLOCKED : LOCKED;

		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new CompoundBorder(new MatteBorder(0, 4, 0, 0, swatch), new EmptyBorder(6, 8, 6, 6)));

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		JLabel name = new JLabel(a.getName());
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(unlocked ? Color.WHITE : new Color(0xB0, 0xB0, 0xB0));
		text.add(name);
		JLabel sub = new JLabel(QuantityFormatter.formatNumber(cost) + " gp");
		sub.setFont(FontManager.getRunescapeSmallFont());
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.add(sub);
		row.add(text, BorderLayout.CENTER);

		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		east.setOpaque(false);
		if (unlocked)
		{
			if (!state.isFree(a))
			{
				JButton relock = lockButton();
				relock.addActionListener(e -> state.relock(a));
				east.add(relock);
			}
		}
		row.add(east, BorderLayout.EAST);
		return row;
	}
}
