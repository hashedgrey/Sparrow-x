package com.sparrowx.document.ingestion.embabel;

import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.dice.proposition.Proposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Precompute proposition vectors using embed(List<String>) while preserving the
 * current (unmodified) DICE PropositionRepository.save() implementation.
 *
 * Each persistence worker installs its ingestion-specific batch via ThreadLocal.
 * The *same* Embabel EmbeddingService bean that DICE uses intercepts embed(String)
 * during save and returns the precomputed vector. Other embedding calls retain
 * their normal behavior. No cross-job or cross-tenant global content cache.
 *
 * The wrapper is intentionally limited to Embabel's public EmbeddingService API.
 */
public final class DiceBatchEmbeddingCoordinator {
    private static final Logger log = LoggerFactory.getLogger(DiceBatchEmbeddingCoordinator.class);

    private final ThreadLocal<PreparedBatch> active = new ThreadLocal<>();
    private volatile EmbeddingService delegate;

    /** Called once from the DeepInfra embedding @Bean factory. */
    public synchronized EmbeddingService wrap(EmbeddingService actual) {
        Objects.requireNonNull(actual, "actual");
        if (delegate != null) {
            throw new IllegalStateException("EmbeddingService already wrapped");
        }
        delegate = actual;
        return (EmbeddingService) Proxy.newProxyInstance(
                EmbeddingService.class.getClassLoader(),
                new Class<?>[]{EmbeddingService.class},
                this::invoke);
    }

    private Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("embed".equals(method.getName()) && args != null && args.length == 1) {
            PreparedBatch batch = active.get();
            if (batch != null) {
                if (args[0] instanceof String text) {
                    float[] vector = batch.vectors.get(text);
                    if (vector != null) {
                        batch.hits.increment();
                        return vector.clone();
                    }
                    batch.misses.increment();
                } else if (args[0] instanceof List<?> texts) {
                    // Embabel's EmbeddingOperations.embed(String) forwards to
                    // delegate.embed(List.of(text)). Intercept BOTH overloads.
                    // Only serve fully cached lists to avoid changing fallback
                    // ordering or embedding semantics for mixed/unrelated calls.
                    List<float[]> cached = new ArrayList<>(texts.size());
                    boolean allCached = true;
                    long missing = 0;
                    for (Object item : texts) {
                        if (!(item instanceof String text)) {
                            allCached = false;
                            missing++;
                            continue;
                        }
                        float[] vector = batch.vectors.get(text);
                        if (vector == null) {
                            allCached = false;
                            missing++;
                        } else {
                            cached.add(vector.clone());
                        }
                    }
                    if (allCached) {
                        batch.hits.add(texts.size());
                        return cached;
                    }
                    batch.misses.add(missing);
                }
            }
        }
        try {
            return method.invoke(requiredDelegate(), args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    public PreparedBatch precompute(List<Proposition> propositions, int batchSize, String documentId) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
        Objects.requireNonNull(propositions, "propositions");
        EmbeddingService model = requiredDelegate();

        // Distinct texts avoid reembedding duplicates extracted from different chunks.
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Proposition p : propositions) {
            String text = Objects.requireNonNull(p.getText(), "proposition text");
            if (!text.isBlank()) unique.add(text);
        }
        List<String> texts = new ArrayList<>(unique);
        Map<String, float[]> vectors = new HashMap<>(texts.size());
        long totalStarted = System.nanoTime();
        int requests = 0;

        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> chunk = texts.subList(i, Math.min(i + batchSize, texts.size()));
            long started = System.nanoTime();
            // Call delegate directly, to avoid a ThreadLocal cache hit and to
            // ensure one genuine batch HTTP request per chunk (if provider
            // supports batch requests, as DeepInfra does).
            List<float[]> result = model.embed(chunk);
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (result == null || result.size() != chunk.size()) {
                throw new IllegalStateException("Expected " + chunk.size()
                        + " embedding vectors; got " + (result == null ? "null" : result.size()));
            }
            for (int j = 0; j < chunk.size(); j++) {
                float[] vector = Objects.requireNonNull(result.get(j), "embedding vector");
                if (vector.length != model.getDimensions()) {
                    throw new IllegalStateException("Embedding dimension mismatch for document "
                            + documentId + ": expected=" + model.getDimensions()
                            + " actual=" + vector.length);
                }
                vectors.put(chunk.get(j), vector.clone());
            }
            requests++;
            log.info("DICE_BATCH_EMBEDDING_REQUEST documentId={} request={} texts={} durationMs={}",
                    documentId, requests, chunk.size(), millis);
        }
        log.info("DICE_BATCH_EMBEDDING_COMPLETE documentId={} propositions={} uniqueTexts={} "
                        + "embeddingBatchCalls={} durationMs={}",
                documentId, propositions.size(), vectors.size(), requests,
                (System.nanoTime() - totalStarted) / 1_000_000);
        return new PreparedBatch(Map.copyOf(vectors), requests);
    }

    public <T> T withPrepared(PreparedBatch batch, Supplier<T> action) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(action, "action");
        PreparedBatch previous = active.get();
        active.set(batch);
        try {
            return action.get();
        } finally {
            if (previous == null) active.remove();
            else active.set(previous);
        }
    }

    public void logUsage(PreparedBatch batch, String documentId) {
        if (batch != null) {
            log.info("DICE_BATCH_EMBEDDING_USAGE documentId={} cacheHits={} "
                            + "cacheMisses={} embeddingBatchCalls={}",
                    documentId, batch.hits.sum(), batch.misses.sum(), batch.batchCalls);
        }
    }

    private EmbeddingService requiredDelegate() {
        EmbeddingService current = delegate;
        if (current == null) throw new IllegalStateException(
                "DeepInfra EmbeddingService was not registered; check DOCUMENT_EMBEDDING_ADAPTER");
        return current;
    }

    /** Safe to share across persistence workers: the vector map is read-only. */
    public static final class PreparedBatch {
        private final Map<String, float[]> vectors;
        private final int batchCalls;
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();

        private PreparedBatch(Map<String, float[]> vectors, int batchCalls) {
            this.vectors = vectors;
            this.batchCalls = batchCalls;
        }

        public long cacheHits() { return hits.sum(); }
        public long cacheMisses() { return misses.sum(); }
        public int batchCalls() { return batchCalls; }
    }
}
