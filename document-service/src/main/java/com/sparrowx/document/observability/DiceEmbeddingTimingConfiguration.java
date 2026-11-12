package com.sparrowx.document.observability;

import com.embabel.common.ai.model.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Temporary dev-only timing probe for calls to Embabel's EmbeddingService.
 * Does not alter the text, model response, or DICE repository semantics.
 *
 * Turn on with sparrowx.document.dice.embedding-timing-enabled=true.
 * Logs EMBEDDING_TIMING for both embed(String) and embed(List<String>).
 * Disable after benchmarking: per-call logging can add overhead.
 */
@Configuration(proxyBeanMethods = false)
public class DiceEmbeddingTimingConfiguration {
    private static final Logger log =
            LoggerFactory.getLogger(DiceEmbeddingTimingConfiguration.class);

    @Bean
    @ConditionalOnProperty(
            prefix = "sparrowx.document.dice",
            name = "embedding-timing-enabled",
            havingValue = "true")
    public static BeanPostProcessor diceEmbeddingTimingPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof EmbeddingService)) {
                    return bean;
                }
                log.info("EMBEDDING_TIMING instrumentedBean={}", beanName);
                return Proxy.newProxyInstance(
                        bean.getClass().getClassLoader(),
                        new Class<?>[] { EmbeddingService.class },
                        (proxy, method, args) -> invokeTimed(bean, beanName, method, args));
            }
        };
    }

    private static Object invokeTimed(Object bean, String beanName, Method method, Object[] args)
            throws Throwable {
        if (!method.getName().equals("embed") || args == null || args.length != 1) {
            return invoke(bean, method, args);
        }

        int inputs = args[0] instanceof List<?> values ? values.size() : 1;
        long start = System.nanoTime();
        boolean success = false;
        try {
            Object result = invoke(bean, method, args);
            success = true;
            return result;
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            // Existing SparrowX MDC/tracing log pattern adds trace and span identifiers.
            log.info("EMBEDDING_TIMING bean={} inputCount={} durationMs={} success={}",
                    beanName, inputs, millis, success);
        }
    }

    private static Object invoke(Object bean, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(bean, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
