/*
 * Copyright (c) 2026, Microbot
 * All rights reserved.
 */
package net.runelite.client.ui.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Test;

public class WidgetOverlaysTest
{
	@Test
	public void preLoginXpTrackerPositionDoesNotReadVarbits()
	{
		Client client = mock(Client.class);
		when(client.isClientThread()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.STARTING);
		when(client.getVarbitValue(anyInt())).thenThrow(new NullPointerException("varps not initialized"));

		WidgetOverlays widgetOverlays = new WidgetOverlays(
			client,
			mock(OverlayManager.class),
			mock(SnapCorners.class));
		Overlay xpTracker = widgetOverlays.createOverlays().stream()
			.filter(overlay -> "EXPERIENCE_TRACKER_WIDGET".equals(overlay.getName()))
			.findFirst()
			.orElseThrow(AssertionError::new);

		try
		{
			assertEquals(OverlayPosition.TOP_RIGHT, xpTracker.getPosition());
		}
		catch (NullPointerException ex)
		{
			fail("XP tracker read a varbit before the client reached the login screen");
		}
	}
}
