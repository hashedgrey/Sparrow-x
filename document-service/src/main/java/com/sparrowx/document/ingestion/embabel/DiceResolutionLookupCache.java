package com.sparrowx.document.ingestion.embabel;

import com.embabel.agent.filter.PropertyFilter;
import com.embabel.agent.rag.model.NamedEntityData;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.sparrowx.document.config.DiceIngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Memoizes exact duplicate resolver repository searches for one ingestion.
 *
 * DICE runs LLM extraction in parallel, followed by entity resolution on the
 * calling thread. This is an intentionally thread-local, short-lived cache:
 * other tenants, documents and ingestion attempts never share entries.
 *
 * When enabled, tenant filters are attached to the underlying graph searches.
 * Other non-resolver graph paths still require tenant isolation separately.
 */
public final class DiceResolutionLookupCache {

    private static final Logger logger = LoggerFactory.getLogger(DiceResolutionLookupCache.class);

    private final NamedEntityDataRepository delegate;
    private final NamedEntityDataRepository view;
    private final DiceIngestionProperties properties;
    private final ThreadLocal<ScopeState> current = new ThreadLocal<>();

    public DiceResolutionLookupCache(
            NamedEntityDataRepository delegate,
            DiceIngestionProperties properties) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.view = (NamedEntityDataRepository) Proxy.newProxyInstance(
                NamedEntityDataRepository.class.getClassLoader(),
                new Class<?>[]{NamedEntityDataRepository.class},
                this::invoke);
    }

    public NamedEntityDataRepository repositoryView() {
        return view;
    }

    /** Call from the same thread that executes PropositionPipeline.process(). */
    public Scope openScope(String tenantId, String documentId) {
        if (tenantId == null || tenantId.isBlank() || documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("tenantId/documentId must not be blank");
        }
        if (current.get() != null) {
            throw new IllegalStateException("Nested DICE resolution cache scopes are not supported");
        }
        ScopeState state = new ScopeState(tenantId, documentId);
        current.set(state);
        return new Scope(state);
    }

    private Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "DiceResolverRepositoryView(" + delegate.getClass().getName() + ")";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> invokeDelegate(method, args);
            };
        }

        ScopeState state = current.get();
        if (state == null) {
            // No tenant is known outside openScope(); fail closed rather than
            // accidentally exposing the unscoped delegate to future callers.
            if (properties.entityTenantFilterEnabled()
                    && ("findById".equals(method.getName())
                    || "textSearch".equals(method.getName())
                    || "vectorSearch".equals(method.getName()))) {
                throw new IllegalStateException(
                        "DICE resolver lookup attempted without active tenant scope: " + method);
            }
            return invokeDelegate(method, args);
        }

        if (isMutation(method.getName())) {
            state.cache.clear();
            return invokeDelegate(method, args);
        }

        // DICE's by-ID candidate searcher does not use metadata filters.
        // Guard that path explicitly, rather than allowing cross-tenant ID matches.
        if ("findById".equals(method.getName()) && properties.entityTenantFilterEnabled()) {
            if (args == null || args.length != 1 || !(args[0] instanceof String)) {
                throw new IllegalStateException(
                        "Unsupported unscoped DICE findById overload: " + method);
            }
            Object result = invokeDelegate(method, args);
            if (result instanceof NamedEntityData entity
                    && !Objects.equals(state.tenantId, entity.getProperties().get("tenant_id"))) {
                state.rejectedIdMatches++;
                return null;
            }
            return result;
        }

        boolean text = "textSearch".equals(method.getName());
        boolean vector = "vectorSearch".equals(method.getName());

        // DICE's candidate searchers invoke NamedEntityDataRepository's entity-filter
        // overload: (TextSimilaritySearchRequest, PropertyFilter, EntityFilter).
        // The older interceptor only recognized (request, Class<T>) and therefore
        // recorded ZERO calls while the backend executed hundreds of queries.
        if (!text && !vector) {
            return invokeDelegate(method, args);
        }

        // The DICE candidate searchers use the three-argument filtered overload.
        // Fail closed if Embabel changes to an unfilterable signature.
        if (args == null || args.length < 1
                || !List.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalStateException("Unsupported DICE resolver search method: " + method);
        }

        Object[] effectiveArgs = args;
        if (properties.entityTenantFilterEnabled()) {
            if (args.length != 3 || (args[1] != null && !(args[1] instanceof PropertyFilter))) {
                throw new IllegalStateException(
                        "DICE resolver search cannot enforce tenant filter on " + method);
            }
            effectiveArgs = args.clone();
            PropertyFilter tenant = new PropertyFilter.Eq("tenant_id", state.tenantId);
            effectiveArgs[1] = args[1] == null
                    ? tenant
                    : new PropertyFilter.And(List.of((PropertyFilter) args[1], tenant));
        }

        if (text) state.textCalls++;
        else state.vectorCalls++;

        // All supplied arguments, including metadataFilter AND entityFilter,
        // determine whether two searches are equivalent. The parameter list is
        // captured by value; no cross-over between filtered and unfiltered search.
        if (!properties.entityResolutionCacheEnabled() || properties.entityResolutionCacheEntries() == 0
                || !areCacheSafeArguments(effectiveArgs)) {
            return timedDelegate(method, effectiveArgs, state, text);
        }

        SearchKey key = new SearchKey(method.getName(), Arrays.asList(effectiveArgs.clone()));
        List<?> found = state.cache.get(key);
        if (found != null) {
            if (text) state.textHits++;
            else state.vectorHits++;
            return new ArrayList<>(found);
        }

        Object result = timedDelegate(method, effectiveArgs, state, text);
        if (result instanceof List<?> list && state.cache.size() < properties.entityResolutionCacheEntries()) {
            // Preserve order and scores. Cache empty lists, but never errors.
            state.cache.put(key, new ArrayList<>(list));
        }
        return result;
    }

    /**
     * Do not cache calls with identity-only or potentially mutable objects;
     * their equality cannot be trusted across search invocations.
     */
    private static boolean areCacheSafeArguments(Object[] args) {
        if (args[0] == null || !hasValueEquality(args[0])) return false;
        for (Object argument : args) {
            if (argument == null || argument instanceof Class<?> || argument instanceof Enum<?>
                    || argument instanceof String || argument instanceof Number || argument instanceof Boolean) {
                continue;
            }
            if (!hasValueEquality(argument)) return false;
        }
        return true;
    }

    private static boolean hasValueEquality(Object request) {
        try {
            return request.getClass().getMethod("equals", Object.class)
                    .getDeclaringClass() != Object.class;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private Object timedDelegate(Method method, Object[] args, ScopeState state, boolean text)
            throws Throwable {
        long start = System.nanoTime();
        try {
            return invokeDelegate(method, args);
        } finally {
            long elapsed = System.nanoTime() - start;
            if (text) {
                state.textBackendCalls++;
                state.textBackendNanos += elapsed;
            } else {
                state.vectorBackendCalls++;
                state.vectorBackendNanos += elapsed;
            }
        }
    }

    private Object invokeDelegate(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static boolean isMutation(String methodName) {
        return methodName.startsWith("save") || methodName.startsWith("update")
                || methodName.startsWith("delete") || methodName.startsWith("remove")
                || methodName.startsWith("clear") || methodName.startsWith("upsert")
                || methodName.startsWith("addRelationship");
    }

    private record SearchKey(String operation, List<Object> arguments) { }

    private static final class ScopeState {
        final String tenantId;
        final String documentId;
        final Map<SearchKey, List<?>> cache = new HashMap<>();
        long textCalls;
        long vectorCalls;
        long textHits;
        long vectorHits;
        long textBackendCalls;
        long vectorBackendCalls;
        long textBackendNanos;
        long vectorBackendNanos;
        long rejectedIdMatches;

        ScopeState(String tenantId, String documentId) {
            this.tenantId = tenantId;
            this.documentId = documentId;
        }
    }

    public final class Scope implements AutoCloseable {
        private final ScopeState state;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private Scope(ScopeState state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner || current.get() != state) {
                throw new IllegalStateException("DICE cache scope closed on the wrong thread");
            }
            closed = true;
            current.remove();
            if (state.textCalls == 0 && state.vectorCalls == 0) {
                logger.warn("DICE cache intercepted no repository searches: "
                        + "verify DiceIngestionConfiguration.diceEntityResolver uses "
                        + "resolverLookupCache.repositoryView() and inspect search method signatures");
            }
            logger.info("DICE resolver lookup metrics tenantId={} documentId={} "
                            + "textCalls={} textHits={} textBackendCalls={} textBackendMs={} "
                            + "vectorCalls={} vectorHits={} vectorBackendCalls={} vectorBackendMs={} "
                            + "cachedQueries={} enabled={} tenantFilterEnabled={} rejectedIdMatches={}",
                    state.tenantId, state.documentId,
                    state.textCalls, state.textHits, state.textBackendCalls,
                    state.textBackendNanos / 1_000_000,
                    state.vectorCalls, state.vectorHits, state.vectorBackendCalls,
                    state.vectorBackendNanos / 1_000_000,
                    state.cache.size(), properties.entityResolutionCacheEnabled(),
                    properties.entityTenantFilterEnabled(), state.rejectedIdMatches);
        }
    }
}
