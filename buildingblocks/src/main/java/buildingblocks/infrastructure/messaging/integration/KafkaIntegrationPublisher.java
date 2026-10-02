package buildingblocks.infrastructure.messaging.integration;

import buildingblocks.infrastructure.persistence.outbox.model.OutboxMessage;
import buildingblocks.shared.context.CorrelationContext;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(KafkaTemplate.class)
public class KafkaIntegrationPublisher {

    private static final Logger logger = LoggerFactory.getLogger(KafkaIntegrationPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaIntegrationPublisher(
            KafkaTemplate<String, String> kafkaTemplate
    ) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(OutboxMessage message) {

        ProducerRecord<String, String> record =
                new ProducerRecord<>(
                        message.getTopic(),
                        message.getMessageKey(),
                        message.getPayload()
                );

        record.headers().add("event-type", message.getEventType().getBytes());
        record.headers().add("message-id", message.getId().toString().getBytes());
        String correlationId = CorrelationContext.getCorrelationId();
        if (correlationId != null) {
            record.headers().add("correlation-id", correlationId.getBytes());
        }

        kafkaTemplate.send(record).whenComplete((result, ex) -> {

            if (ex != null) {
                throw new RuntimeException("Kafka publish failed", ex);
            }

            logger.debug(
                    "Published message {} to topic {} partition {}",
                    message.getId(),
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition()
            );
        });
    }
}