package art.arcane.volmlib.util.inventorygui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class BukkitInventoryShutdown {
    private final Plugin plugin;
    private final List<CloseTask> tasks;
    private final CompletableFuture<Void> completion;
    private final AtomicBoolean started = new AtomicBoolean();

    public BukkitInventoryShutdown(Plugin plugin, Collection<View> views) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.tasks = new ArrayList<>(views.size());
        List<CompletableFuture<Void>> completions = new ArrayList<>(views.size());
        for (View view : List.copyOf(views)) {
            CloseTask task = new CloseTask(view);
            this.tasks.add(task);
            completions.add(task.completion);
        }
        this.completion = CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new));
    }

    public static void drain(Plugin plugin, Collection<View> views) {
        new BukkitInventoryShutdown(plugin, views).closeOwned().whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not close every inventory during plugin shutdown", failure);
            }
        });
    }

    public CompletableFuture<Void> closeAsync() {
        if (this.started.compareAndSet(false, true)) {
            for (CloseTask task : this.tasks) {
                task.schedule();
            }
        }
        return this.completion();
    }

    public CompletableFuture<Void> closeOwned() {
        this.started.set(true);
        for (CloseTask task : this.tasks) {
            task.closeOwned();
        }
        return this.completion();
    }

    public CompletableFuture<Void> completion() {
        return this.completion.copy();
    }

    public record View(Player player, Inventory inventory) {
        public View {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(inventory, "inventory");
        }
    }

    private final class CloseTask {
        private static final int PENDING = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;

        private final View view;
        private final AtomicInteger state = new AtomicInteger(PENDING);
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private CloseTask(View view) {
            this.view = view;
        }

        private void schedule() {
            if (this.state.get() != PENDING) {
                return;
            }
            try {
                if (FoliaScheduler.isOwnedByCurrentRegion(this.view.player())) {
                    this.run();
                } else {
                    AtomicBoolean accepted = new AtomicBoolean();
                    AtomicBoolean retired = new AtomicBoolean();
                    boolean scheduled = FoliaScheduler.runEntity(plugin, this.view.player(), this::run, 0L, () -> {
                        retired.set(true);
                        if (accepted.get()) {
                            this.retire();
                        }
                    });
                    if (!scheduled) {
                        this.reject("The inventory owner rejected cleanup");
                    } else {
                        accepted.set(true);
                        if (retired.get()) {
                            this.retire();
                        }
                    }
                }
            } catch (RuntimeException | LinkageError failure) {
                this.fail(failure);
            }
        }

        private void closeOwned() {
            if (this.state.get() != PENDING) {
                return;
            }
            try {
                if (FoliaScheduler.isOwnedByCurrentRegion(this.view.player())) {
                    this.run();
                } else {
                    this.reject("Plugin shutdown cannot close an inventory owned by another region");
                }
            } catch (RuntimeException | LinkageError failure) {
                this.fail(failure);
            }
        }

        private void run() {
            if (!this.state.compareAndSet(PENDING, RUNNING)) {
                return;
            }
            try {
                if (BukkitInventoryViews.top(this.view.player().getOpenInventory()) == this.view.inventory()) {
                    this.view.player().closeInventory();
                }
                this.completion.complete(null);
            } catch (RuntimeException | LinkageError failure) {
                this.completion.completeExceptionally(failure);
            } finally {
                this.state.set(FINISHED);
            }
        }

        private void retire() {
            if (this.state.compareAndSet(PENDING, FINISHED)) {
                this.completion.complete(null);
            }
        }

        private void reject(String message) {
            this.fail(new RejectedExecutionException(message));
        }

        private void fail(Throwable failure) {
            if (this.state.compareAndSet(PENDING, FINISHED)) {
                this.completion.completeExceptionally(failure);
            }
        }
    }
}
