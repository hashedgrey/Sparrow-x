package com.sparrowx.agentic.planning.graph;

import java.util.*;

/**
 * Capability registry for the actual GraphTraversal executor.
 * No query wording or business-specific objects are hardcoded here.
 * A query requiring an unavailable operator is rejected, not weakened.
 */
public final class GraphQueryCapabilities {
    public enum Operator {
        PROJECT, TRAVERSE, EQ,
        CONTAINS, NE, IN, GT, GE, LT, LE,
        AND, OR, NOT_EXISTS, EXISTS, JOIN_EXISTING,
        GROUP_BY, COUNT, COUNT_DISTINCT, SUM, MIN, MAX,
        ORDER_BY, LIMIT, OPTIONAL
    }

    // Deliberately based on the provided GraphTraversal.java implementation.
    private static final Set<Operator> IMPLEMENTED = Set.of(
            Operator.PROJECT, Operator.TRAVERSE, Operator.EQ, Operator.AND
    );

    private GraphQueryCapabilities() { }

    public static void requireSupported(Collection<Operator> required) {
        Objects.requireNonNull(required, "required operators");
        Set<Operator> missing = EnumSet.noneOf(Operator.class);
        for (Operator operator : required) {
            Objects.requireNonNull(operator, "required operator");
            if (!IMPLEMENTED.contains(operator)) missing.add(operator);
        }
        if (!missing.isEmpty())
            throw new UnsupportedOperationException(
                    "Graph query executor does not implement required operators: " + missing);
    }

    public static Set<Operator> supported() { return IMPLEMENTED; }
}
