package com.sparrowx.document.ingestion.embabel;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.graph.DrivineNamedEntityDataRepository;
import com.embabel.agent.rag.graph.GraphRagServiceProperties;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import org.drivine.autoconfigure.EnableDrivine;
import org.drivine.autoconfigure.EnableDrivinePropertiesConfig;
import org.drivine.manager.GraphObjectManager;
import org.drivine.manager.GraphObjectManagerFactory;
import org.drivine.manager.PersistenceManager;
import org.drivine.manager.PersistenceManagerFactory;
import org.drivine.transaction.DrivineTransactionManager;
import org.drivine.transaction.TransactionContextHolder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableDrivine
@EnableDrivinePropertiesConfig
@EnableConfigurationProperties(GraphRagServiceProperties.class)
public class DiceGraphConfiguration {

    private static final String GRAPH_DATASOURCE = "graph";

    @Bean("drivineTransactionManager")
    @Primary
    public PlatformTransactionManager drivineTransactionManager(
            TransactionContextHolder transactionContextHolder
    ) {
        return new DrivineTransactionManager(
                transactionContextHolder
        );
    }

    @Bean(GRAPH_DATASOURCE)
    public PersistenceManager graphPersistenceManager(
            PersistenceManagerFactory persistenceManagerFactory
    ) {
        return persistenceManagerFactory.get(
                GRAPH_DATASOURCE
        );
    }

    @Bean
    public GraphObjectManager graphObjectManager(
            GraphObjectManagerFactory graphObjectManagerFactory
    ) {
        return graphObjectManagerFactory.get(
                GRAPH_DATASOURCE
        );
    }

    @Bean
    @Primary
    public NamedEntityDataRepository namedEntityDataRepository(
            @Qualifier(GRAPH_DATASOURCE)
            PersistenceManager persistenceManager,
            GraphObjectManager graphObjectManager,
            GraphRagServiceProperties graphRagServiceProperties,
            @Qualifier("diceDataDictionary")
            DataDictionary dataDictionary,
            Ai ai
    ) {
        return new DrivineNamedEntityDataRepository(
                persistenceManager,
                graphRagServiceProperties,
                dataDictionary,
                ai.withDefaultEmbeddingService(),
                graphObjectManager
        );
    }
}