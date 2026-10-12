package buildingblocks.contracts;

import java.time.Instant;
import java.util.*;

/** Completeness refers to the explicit requested roots, never to an upstream search. */
public record GraphQueryResult(List<Map<String, Object>> rows, boolean truncated,
                               Set<String> evaluatedRootIds, Instant retrievedAt) {
    public GraphQueryResult {
        // A missing declared value remains null. Map.copyOf would reject it.
        rows = rows.stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        evaluatedRootIds = Set.copyOf(evaluatedRootIds);
        Objects.requireNonNull(retrievedAt);
    }
}
