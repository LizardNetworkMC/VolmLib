package art.arcane.volmlib.projection;

import org.junit.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProjectionViewRegistryTest {
    @Test
    public void nearbyQueriesChooseTheClosestViewAndSeparateWorlds() {
        AtomicLong clock = new AtomicLong();
        ProjectionViewRegistry registry = new ProjectionViewRegistry(null, clock::get);
        UUID viewer = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        registry.publish(viewer, UUID.randomUUID(), world, -65, 10, 0);
        registry.publish(viewer, UUID.randomUUID(), world, -63, 10, 0);
        registry.publish(UUID.randomUUID(), UUID.randomUUID(), other, -64, 10, 0);
        registry.publish(UUID.randomUUID(), UUID.randomUUID(), world, -64, 100, 0);
        assertEquals(Map.of(viewer, 1.0D), registry.viewers(world, -64, 10, 0, 2));
        assertEquals(1.0D, registry.distanceSquared(viewer, world, -64, 10, 0), 0.0D);
        assertEquals(Double.POSITIVE_INFINITY, registry.distanceSquared(viewer, other, -64, 10, 0), 0.0D);
    }

    @Test
    public void movingViewsExpireAndExplicitRemovalRetiresEveryIndex() {
        AtomicLong clock = new AtomicLong();
        ProjectionViewRegistry registry = new ProjectionViewRegistry(null, clock::get);
        UUID viewer = UUID.randomUUID();
        UUID view = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        registry.publish(viewer, view, world, 1, 2, 3);
        registry.publish(viewer, view, world, 1000, 2, 3);
        assertTrue(registry.viewers(world, 1, 2, 3, 10).isEmpty());
        clock.set(1_999_999_999L);
        assertEquals(Map.of(viewer, 0.0D), registry.viewers(world, 1000, 2, 3, 0));
        clock.incrementAndGet();
        assertTrue(registry.viewers(world, 1000, 2, 3, 10).isEmpty());
        assertEquals(Double.POSITIVE_INFINITY, registry.distanceSquared(viewer, world, 1000, 2, 3), 0.0D);
        registry.publish(viewer, view, world, 1, 2, 3);
        registry.removeView(viewer, view);
        assertTrue(registry.viewers(world, 1, 2, 3, 10).isEmpty());
        registry.publish(viewer, view, world, 1, 2, 3);
        registry.publish(viewer, UUID.randomUUID(), world, 4, 5, 6);
        registry.removeViewer(viewer);
        assertTrue(registry.viewers(world, 1, 2, 3, 10).isEmpty());
        registry.publish(viewer, view, world, 1, 2, 3);
        registry.close();
        registry.publish(viewer, view, world, 1, 2, 3);
        assertTrue(registry.viewers(world, 1, 2, 3, 10).isEmpty());
    }
}
