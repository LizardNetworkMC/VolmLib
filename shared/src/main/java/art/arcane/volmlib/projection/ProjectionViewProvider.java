package art.arcane.volmlib.projection;

import java.util.Map;
import java.util.UUID;

public interface ProjectionViewProvider {
    Map<UUID, Double> viewers(UUID world, double x, double y, double z, double range);

    double distanceSquared(UUID viewer, UUID world, double x, double y, double z);
}
