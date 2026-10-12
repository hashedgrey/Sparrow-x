package com.sparrowx.internal.data.neo4j.graph;

import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionConfig;

import java.time.Duration;
import java.util.Objects;

/** Owns session lifetime, database selection and server-side query timeout. */
public final class Neo4jGraphSessions {
    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final TransactionConfig transactionConfig;

    public Neo4jGraphSessions(Driver driver, String database, Duration timeout) {
        this.driver = Objects.requireNonNull(driver);
        requireText(database, "database");
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.toMillis() < 1)
            throw new IllegalArgumentException("timeout must be at least one millisecond");
        sessionConfig = SessionConfig.builder().withDatabase(database).build();
        transactionConfig = TransactionConfig.builder().withTimeout(timeout).build();
    }

    public <T> T read(TransactionCallback<T> work) {
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(work, transactionConfig);
        }
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
