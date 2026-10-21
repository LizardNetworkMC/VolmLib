package art.arcane.volmlib.util.bukkit;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class BukkitExperienceTest {
    private Player player;
    private PluginManager events;

    @Before
    public void setUp() {
        player = mock(Player.class);
        Server server = mock(Server.class);
        events = mock(PluginManager.class);
        when(player.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(events);
    }

    @Test
    public void rewardFiresOneEventBeforeGrantingExperience() {
        doAnswer(invocation -> {
            PlayerExpChangeEvent event = invocation.getArgument(0);
            assertSame(player, event.getPlayer());
            assertEquals(60, event.getAmount());
            return null;
        }).when(events).callEvent(any(PlayerExpChangeEvent.class));

        assertEquals(60, BukkitExperience.award(player, 60));

        InOrder order = inOrder(events, player);
        order.verify(events).callEvent(any(PlayerExpChangeEvent.class));
        order.verify(player).giveExp(60);
    }

    @Test
    public void listenerAdjustedAmountIsGranted() {
        doAnswer(invocation -> {
            invocation.<PlayerExpChangeEvent>getArgument(0).setAmount(9);
            return null;
        }).when(events).callEvent(any(PlayerExpChangeEvent.class));

        assertEquals(9, BukkitExperience.award(player, 60));

        verify(player).giveExp(9);
        verify(player, never()).giveExp(60);
    }

    @Test
    public void zeroedRewardGrantsNothing() {
        doAnswer(invocation -> {
            invocation.<PlayerExpChangeEvent>getArgument(0).setAmount(0);
            return null;
        }).when(events).callEvent(any(PlayerExpChangeEvent.class));

        assertEquals(0, BukkitExperience.award(player, 60));

        verify(player, never()).giveExp(anyInt());
    }

    @Test
    public void negativeListenerAmountDoesNotRemoveExperience() {
        doAnswer(invocation -> {
            invocation.<PlayerExpChangeEvent>getArgument(0).setAmount(-10);
            return null;
        }).when(events).callEvent(any(PlayerExpChangeEvent.class));

        assertEquals(0, BukkitExperience.award(player, 60));

        verify(player, never()).giveExp(anyInt());
    }

    @Test
    public void nonpositiveInputDoesNotPublishAReward() {
        assertEquals(0, BukkitExperience.award(player, 0));
        assertEquals(0, BukkitExperience.award(player, -10));

        verifyNoInteractions(events);
        verify(player, never()).giveExp(anyInt());
    }
}
