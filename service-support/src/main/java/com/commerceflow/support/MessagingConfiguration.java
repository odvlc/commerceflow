package com.commerceflow.support;

import com.commerceflow.contracts.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableScheduling
@ConditionalOnProperty(
        name = "commerceflow.messaging.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import({EventCodec.class, ProcessedEvents.class, OutboxPublisher.class, OutboxSchedule.class})
public class MessagingConfiguration {

    @Bean
    ProducerFactory<String, String> producerFactory(
            @Value("${commerceflow.messaging.bootstrap-servers:localhost:9092}") String brokers,
            io.micrometer.core.instrument.MeterRegistry registry) {
        var factory =
                new DefaultKafkaProducerFactory<String, String>(
                        Map.of(
                                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                brokers,
                                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                                StringSerializer.class,
                                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                StringSerializer.class,
                                ProducerConfig.ACKS_CONFIG,
                                "all",
                                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                                true,
                                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                                2000,
                                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                                2000,
                                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                                5000));
        factory.addListener(new MicrometerProducerListener<>(registry));
        return factory;
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producer) {
        return new KafkaTemplate<>(producer);
    }

    @Bean
    ConsumerFactory<String, String> consumerFactory(
            @Value("${commerceflow.messaging.bootstrap-servers:localhost:9092}") String brokers,
            io.micrometer.core.instrument.MeterRegistry registry) {
        var factory =
                new DefaultKafkaConsumerFactory<String, String>(
                        Map.of(
                                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                brokers,
                                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                                StringDeserializer.class,
                                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                StringDeserializer.class,
                                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                                false,
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest",
                                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                                50));
        factory.addListener(new MicrometerConsumerListener<>(registry));
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> producer) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        producer,
                        (record, error) -> new TopicPartition(Topics.DEAD_LETTER, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(8));
        var backoff = new ExponentialBackOffWithMaxRetries(5);
        backoff.setInitialInterval(250);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(2000);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, backoff));
        return factory;
    }

    @Bean
    KafkaAdmin kafkaAdmin(
            @Value("${commerceflow.messaging.bootstrap-servers:localhost:9092}") String brokers) {
        var admin = new KafkaAdmin(Map.of("bootstrap.servers", brokers));
        admin.setOperationTimeout(5);
        return admin;
    }

    @Bean
    KafkaAdmin.NewTopics commerceTopics() {
        return new KafkaAdmin.NewTopics(
                java.util.stream.Stream.of(
                                Topics.ORDER,
                                Topics.INVENTORY,
                                Topics.PAYMENT,
                                Topics.CATALOG,
                                Topics.AI_KNOWLEDGE,
                                Topics.NOTIFICATION,
                                Topics.DEAD_LETTER)
                        .map(name -> TopicBuilder.name(name).partitions(3).replicas(1).build())
                        .toArray(org.apache.kafka.clients.admin.NewTopic[]::new));
    }
}
