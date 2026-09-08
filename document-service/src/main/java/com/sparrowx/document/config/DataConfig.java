package com.sparrowx.document.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableJpaRepositories(
        basePackages = {
                "com.sparrowx.document.data.postgres.repositories",
                "buildingblocks.infrastructure.messaging.inbox",
                "buildingblocks.infrastructure.persistence.outbox"
        },
        transactionManagerRef = "transactionManager"
)
@EntityScan(basePackages = {
        "com.sparrowx.document.data.postgres.entities",
        "buildingblocks.infrastructure.messaging.inbox",
        "buildingblocks.infrastructure.persistence.outbox"
})
public class DataConfig {

    /**
     * SparrowX / BuildingBlocks transaction manager.
     *
     * CommandBus -> UnitOfWork uses the conventional bean name
     * "transactionManager", so keep that name bound to JPA/Postgres.
     */
    @Bean("transactionManager")
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory
    ) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}