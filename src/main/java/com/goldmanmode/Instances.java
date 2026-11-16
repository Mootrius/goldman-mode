package com.goldmanmode;

import net.runelite.api.Client;
import net.runelite.api.WorldView;

final class Instances
{
	private Instances()
	{
	}

	/** true while the player is in an instanced area (where world coordinates mean nothing) */
	static boolean isInstanced(Client client)
	{
		WorldView wv = client.getTopLevelWorldView();
		return wv != null && wv.getScene() != null && wv.getScene().isInstance();
	}
}
