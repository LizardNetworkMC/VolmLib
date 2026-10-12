package art.arcane.volmlib.util.inventorygui;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class BukkitInventoryShutdownTest {
    private Plugin plugin;
    private Player player;
    private Entity entity;
    private Inventory inventory;
    private InventoryView open;
    private BukkitInventoryShutdown shutdown;

    @Before
    public void setUp() {
        plugin = mock(Plugin.class);
        player = mock(Player.class, withSettings().extraInterfaces(Entity.class));
        entity = (Entity) player;
        inventory = mock(Inventory.class);
        open = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(open);
        when(open.getTopInventory()).thenReturn(inventory);
        shutdown = new BukkitInventoryShutdown(plugin, List.of(new BukkitInventoryShutdown.View(player, inventory)));
    }

    @Test
    public void ownedInventoryClosesImmediatelyEvenWhenPluginDisabled() {
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.isOwnedByCurrentRegion(entity)).thenReturn(true);
            shutdown.closeOwned().join();
        }
        verify(player).closeInventory();
    }

    @Test
    public void anotherInventoryOpenedBeforeCleanupIsPreserved() {
        when(open.getTopInventory()).thenReturn(mock(Inventory.class));
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.isOwnedByCurrentRegion(entity)).thenReturn(true);
            shutdown.closeAsync().join();
        }
        verify(player, never()).closeInventory();
    }

    @Test(timeout = 2000L)
    public void managedCloseReturnsBeforeOwnerRunsAndObserverCancellationDoesNotCancelCleanup() {
        AtomicReference<Runnable> cleanup = new AtomicReference<>();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            this.captureCleanup(scheduler, cleanup);
            CompletableFuture<Void> observer = shutdown.closeAsync();
            assertFalse(observer.isDone());
            observer.cancel(true);
            assertFalse(shutdown.completion().isDone());
            verify(player, never()).getOpenInventory();
            cleanup.get().run();
            shutdown.completion().join();
            verify(player).closeInventory();
        }
    }

    @Test(timeout = 2000L)
    public void emergencyCloseRejectsAcceptedWorkAndSuppressesLateCallback() {
        AtomicReference<Runnable> cleanup = new AtomicReference<>();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            this.captureCleanup(scheduler, cleanup);
            CompletableFuture<Void> managed = shutdown.closeAsync();
            CompletionException failure = assertThrows(CompletionException.class, () -> shutdown.closeOwned().join());
            assertTrue(failure.getCause() instanceof RejectedExecutionException);
            assertTrue(managed.isCompletedExceptionally());
            cleanup.get().run();
            verify(player, never()).getOpenInventory();
        }
    }

    @Test(timeout = 2000L)
    public void emergencyCloseCanTakeOverPendingWorkWhenCurrentThreadOwnsPlayer() {
        AtomicReference<Runnable> cleanup = new AtomicReference<>();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            this.captureCleanup(scheduler, cleanup);
            CompletableFuture<Void> managed = shutdown.closeAsync();
            scheduler.when(() -> FoliaScheduler.isOwnedByCurrentRegion(entity)).thenReturn(true);
            shutdown.closeOwned().join();
            cleanup.get().run();
            managed.join();
            verify(player).closeInventory();
        }
    }

    @Test(timeout = 2000L)
    public void retiredOwnerCompletesWithoutInventoryAccess() {
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(same(plugin), same(entity), any(Runnable.class),
                    eq(0L), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.getArgument(4, Runnable.class).run();
                return true;
            });
            shutdown.closeAsync().join();
        }
        verify(player, never()).getOpenInventory();
    }

    @Test(timeout = 2000L)
    public void rejectedDispatchIsFailureEvenWhenSchedulerInvokesRetiredCallback() {
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(same(plugin), same(entity), any(Runnable.class),
                    eq(0L), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.getArgument(4, Runnable.class).run();
                return false;
            });
            CompletionException failure = assertThrows(CompletionException.class, () -> shutdown.closeAsync().join());
            assertTrue(failure.getCause() instanceof RejectedExecutionException);
        }
        verify(player, never()).getOpenInventory();
    }

    @Test(timeout = 2000L)
    public void emergencyCloseNeverSchedulesUnownedWork() {
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            assertThrows(CompletionException.class, () -> shutdown.closeOwned().join());
            scheduler.verify(() -> FoliaScheduler.runEntity(same(plugin), same(entity), any(Runnable.class),
                    eq(0L), any(Runnable.class)), never());
        }
        verify(player, never()).getOpenInventory();
    }

    @Test(timeout = 2000L)
    public void closeFailureIsReportedToManagedCaller() {
        IllegalStateException cause = new IllegalStateException("Inventory close failed");
        doThrow(cause).when(player).closeInventory();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.isOwnedByCurrentRegion(entity)).thenReturn(true);
            CompletionException failure = assertThrows(CompletionException.class, () -> shutdown.closeAsync().join());
            assertSame(cause, failure.getCause());
        }
    }

    private void captureCleanup(MockedStatic<FoliaScheduler> scheduler, AtomicReference<Runnable> cleanup) {
        scheduler.when(() -> FoliaScheduler.runEntity(same(plugin), same(entity), any(Runnable.class),
                eq(0L), any(Runnable.class))).thenAnswer(invocation -> {
            cleanup.set(invocation.getArgument(2, Runnable.class));
            return true;
        });
    }
}
