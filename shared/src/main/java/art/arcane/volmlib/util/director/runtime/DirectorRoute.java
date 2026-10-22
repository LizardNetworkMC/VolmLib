package art.arcane.volmlib.util.director.runtime;

import java.util.ArrayList;
import java.util.List;

public record DirectorRoute(DirectorRuntimeNode node, List<String> remainingArgs) {
    public DirectorRoute {
        remainingArgs = remainingArgs == null ? List.of() : List.copyOf(remainingArgs);
    }

    public boolean invocable() {
        return node != null && node.isInvocable();
    }

    public List<String> path() {
        List<String> names = new ArrayList<>();
        for (DirectorRuntimeNode cursor = node; cursor != null && cursor.getParent() != null; cursor = cursor.getParent()) {
            names.add(0, cursor.getDescriptor().getName());
        }
        return List.copyOf(names);
    }
}
