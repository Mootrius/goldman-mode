package com.goldmanmode;

import java.awt.Point;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Hovering an item in the in-game collection log, or a quest in the quest list, shows "Pays 123,400" (white "Pays", yellow amount and gp).
 */
@Singleton
class ClogTooltip
{
	private final Client client;
	private final ItemManager itemManager;
	private final TooltipManager tooltipManager;
	private final GoldmanAchievements achievements;

	@Inject
	ClogTooltip(Client client, ItemManager itemManager, TooltipManager tooltipManager, GoldmanAchievements achievements)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.tooltipManager = tooltipManager;
		this.achievements = achievements;
	}

	/** the tooltip text for a payout: white "Pays ", yellow amount */
	static String text(long gp)
	{
		return "<col=ffffff>Pays </col><col=ffff00>" + QuantityFormatter.formatNumber(gp) + " gp</col>";
	}

	/** call once a client tick (client thread) */
	void onClientTick()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (Settings.SHOW_QUEST_TOOLTIP)
		{
			questTooltip();
		}
		if (Settings.SHOW_CLOG_TOOLTIP)
		{
			clogTooltip();
		}
	}

	/**
	 * the quest list on the quest tab: every row is a text widget holding the quest's name. Rather than trust which
	 * container holds the rows, look through everything under the quest list for the text under the mouse.
	 */
	private void questTooltip()
	{
		Widget root = client.getWidget(InterfaceID.Questlist.UNIVERSE);
		if (root == null || root.isHidden())
		{
			return;
		}
		net.runelite.api.Point m = client.getMouseCanvasPosition();
		Point mouse = new Point(m.getX(), m.getY());
		if (!root.getBounds().contains(mouse))
		{
			return;
		}
		Widget hit = textUnder(root, mouse, 0);
		if (hit != null)
		{
			long gp = achievements.questPayout(hit.getText());
			if (gp >= 0)
			{
				tooltipManager.add(new Tooltip(text(gp)));
			}
		}
	}

	private static Widget[][] groups(Widget w)
	{
		return new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()};
	}

	/** the visible text widget at the mouse whose text is a quest we pay for (deepest first) */
	private Widget textUnder(Widget w, Point mouse, int depth)
	{
		if (w == null || w.isSelfHidden() || depth > 8)
		{
			return null;
		}
		for (Widget[] group : groups(w))
		{
			if (group == null)
			{
				continue;
			}
			for (Widget c : group)
			{
				Widget r = textUnder(c, mouse, depth + 1);
				if (r != null)
				{
					return r;
				}
			}
		}
		String t = w.getText();
		if (t != null && !t.isEmpty() && w.getBounds().contains(mouse) && achievements.questPayout(t) >= 0)
		{
			return w;
		}
		return null;
	}

	private void clogTooltip()
	{
		Widget items = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (items == null || items.isHidden())
		{
			return;
		}
		Widget[] kids = items.getDynamicChildren();
		if (kids == null)
		{
			return;
		}
		net.runelite.api.Point m = client.getMouseCanvasPosition();
		Point mouse = new Point(m.getX(), m.getY());
		for (Widget w : kids)
		{
			if (w == null || w.isHidden() || w.getItemId() <= 0 || !w.getBounds().contains(mouse))
			{
				continue;
			}
			// only what is actually visible in the scrolling list (the scroll viewport is the parent's bounds)
			if (!items.getBounds().contains(mouse))
			{
				return;
			}
			String name = itemManager.getItemComposition(itemManager.canonicalize(w.getItemId())).getName();
			long gp = achievements.clogPayout(name);
			if (gp >= 0)
			{
				tooltipManager.add(new Tooltip(text(gp)));
			}
			return;
		}
	}
}
