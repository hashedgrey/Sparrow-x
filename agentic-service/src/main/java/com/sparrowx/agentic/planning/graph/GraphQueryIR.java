package com.sparrowx.agentic.planning.graph;

import java.util.List;
import java.util.Objects;

/**
 * Typed logical query IR: operators are executor semantics, not LLM instructions.
 * A schema binder validates every path ID and field before anything is executed.
 * A separate IntSvc-backed executor must implement the operators and completeness.
 */
public final class GraphQueryIR {
    private GraphQueryIR() { }

    public enum Compare { EQ, NE, CONTAINS, IN, GT, GE, LT, LE }
    public enum AggregateFunction { COUNT, COUNT_DISTINCT, SUM, MIN, MAX }

    public sealed interface Expr permits Roots, Traverse, Filter, Project, Join, AntiJoin,
            GroupBy, OrderBy, Limit, Existential { }

    public record Roots(String rootBinding) implements Expr {
        public Roots { Objects.requireNonNull(rootBinding); }
    }
    /** Path options use schema IDs from GraphSchemaCatalog. */
    public record Traverse(Expr source, String sourceVariable, String resultVariable,
                           String selectedPathId) implements Expr {
        public Traverse {
            Objects.requireNonNull(source);
            Objects.requireNonNull(sourceVariable);
            Objects.requireNonNull(resultVariable);
            Objects.requireNonNull(selectedPathId);
        }
    }
    public record Predicate(String variable, String field, Compare operator, String value) {
        public Predicate {
            Objects.requireNonNull(variable);
            Objects.requireNonNull(field);
            Objects.requireNonNull(operator);
            Objects.requireNonNull(value);
        }
    }
    public record Filter(Expr source, List<Predicate> predicates) implements Expr {
        public Filter { Objects.requireNonNull(source); predicates = List.copyOf(predicates); }
    }
    public record Field(String variable, String field, String alias) {
        public Field { Objects.requireNonNull(variable); Objects.requireNonNull(field); Objects.requireNonNull(alias); }
    }
    public record Project(Expr source, List<Field> fields) implements Expr {
        public Project { Objects.requireNonNull(source); fields = List.copyOf(fields); }
    }
    /** Use explicit shared variable bindings to avoid accidental Cartesian joins. */
    public record Join(Expr left, Expr right, List<String> onVariables) implements Expr {
        public Join { Objects.requireNonNull(left); Objects.requireNonNull(right); onVariables = List.copyOf(onVariables); }
    }
    /** A valid NOT EXISTS requires exhaustive expansion of the right side. */
    public record AntiJoin(Expr left, Expr right, List<String> onVariables) implements Expr {
        public AntiJoin { Objects.requireNonNull(left); Objects.requireNonNull(right); onVariables = List.copyOf(onVariables); }
    }
    public record Aggregation(AggregateFunction function, Field field, String outputAlias) {
        public Aggregation { Objects.requireNonNull(function); Objects.requireNonNull(outputAlias); }
    }
    public record GroupBy(Expr source, List<Field> keys, List<Aggregation> aggregations) implements Expr {
        public GroupBy {
            Objects.requireNonNull(source); keys = List.copyOf(keys); aggregations = List.copyOf(aggregations);
        }
    }
    public record Sort(Field field, boolean ascending) {
        public Sort { Objects.requireNonNull(field); }
    }
    public record OrderBy(Expr source, List<Sort> sorts) implements Expr {
        public OrderBy { Objects.requireNonNull(source); sorts = List.copyOf(sorts); }
    }
    public record Limit(Expr source, int count) implements Expr {
        public Limit { Objects.requireNonNull(source); if (count < 1) throw new IllegalArgumentException("limit"); }
    }
    public record Existential(Expr source, boolean exists) implements Expr {
        public Existential { Objects.requireNonNull(source); }
    }

    /**
     * Completeness is separate from successful RPC status. No count/absence/full list
     * may claim completion if an upstream root search or graph fan-out was truncated.
     */
    public record ExecutionResult(List<java.util.Map<String, Object>> rows,
                                  boolean rootSearchComplete, boolean graphExpansionComplete,
                                  boolean operatorEvaluationComplete) {
        public ExecutionResult { rows = List.copyOf(rows); }
        public boolean exhaustive() {
            return rootSearchComplete && graphExpansionComplete && operatorEvaluationComplete;
        }
        public void requireExhaustive() {
            if (!exhaustive()) throw new IllegalStateException("Query result is not exhaustive");
        }
    }
}
