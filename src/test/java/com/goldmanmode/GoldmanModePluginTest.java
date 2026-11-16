package com.goldmanmode;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;

/**
 * Launches RuneLite with the plugin loaded: ./gradlew runClient (or run main() from your IDE).
 * The MQoL (../../MQoL) and Loot Labels (../../LOOT_LABELS/loot-labels) plugins are loaded alongside it when they
 * are on the classpath.
 */
public class GoldmanModePluginTest
{
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		// loadBuiltin replaces whatever an earlier call registered, so every plugin goes in one call
		List<Class<? extends Plugin>> plugins = new ArrayList<>();
		plugins.add(GoldmanModePlugin.class);
		for (String name : new String[]{"com.mqol.MQoLPlugin", "com.lootlabels.LootLabelsPlugin"})
		{
			try
			{
				plugins.add((Class<? extends Plugin>) Class.forName(name));
			}
			catch (ClassNotFoundException e)
			{
				System.out.println("Not sideloading " + name + " (not on the classpath)");
			}
		}
		ExternalPluginManager.loadBuiltin(plugins.toArray(new Class[0]));
		RuneLite.main(args);
	}
}
