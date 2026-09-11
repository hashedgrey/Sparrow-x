package com.sparrowx.document.observability;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class ContextPropagatingExecutorService
        extends AbstractExecutorService {

    private final ExecutorService delegate;

    public ContextPropagatingExecutorService(
            ExecutorService delegate
    ) {
        this.delegate =
                Objects.requireNonNull(
                        delegate,
                        "delegate must not be null"
                );
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(
                command,
                "command must not be null"
        );

        /*
         * Capture at submission time, not executor construction time.
         *
         * This is important because the same executor handles work
         * belonging to different requests / ingestion jobs.
         */
        Context capturedOtelContext =
                Context.current();

        Map<String, String> capturedMdc =
                MDC.getCopyOfContextMap();

        delegate.execute(() -> {

            /*
             * Pool threads are reusable, so preserve whatever was
             * previously attached to the worker and restore it after.
             */
            Map<String, String> previousMdc =
                    MDC.getCopyOfContextMap();

            try (Scope ignored =
                         capturedOtelContext.makeCurrent()) {

                restoreMdc(capturedMdc);

                command.run();

            } finally {

                restoreMdc(previousMdc);
            }
        });
    }

    private static void restoreMdc(
            Map<String, String> values
    ) {
        if (values == null || values.isEmpty()) {
            MDC.clear();
            return;
        }

        MDC.setContextMap(values);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(
            long timeout,
            TimeUnit unit
    ) throws InterruptedException {
        return delegate.awaitTermination(
                timeout,
                unit
        );
    }
}