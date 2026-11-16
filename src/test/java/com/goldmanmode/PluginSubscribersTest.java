package com.goldmanmode;

import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

public class PluginSubscribersTest
{
	/** RuneLite refuses to start a plugin whose @Subscribe methods are not named onEventName: catch that here. */
	@Test
	public void subscribedMethodsAreNamedLikeRuneLiteWantsThem()
	{
		EventBus bus = new EventBus();
		GoldmanModePlugin plugin = new GoldmanModePlugin();
		bus.register(plugin);
		bus.unregister(plugin);
	}
}
