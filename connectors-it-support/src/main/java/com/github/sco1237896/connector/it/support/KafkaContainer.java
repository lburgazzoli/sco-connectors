package com.github.sco1237896.connector.it.support;

import groovy.util.logging.Slf4j;
import io.restassured.RestAssured;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Slf4j
public class KafkaContainer extends RedpandaContainer {
    public static final int CONTAINER_PORT = 19092;
    public static final String CONTAINER_ALIAS = "tc-kafka";
    public static final String SECURITY_PROTOCOL = "SASL_PLAINTEXT";
    public static final String SASL_MECHANISM = "SCRAM-SHA-512";

    private final String username;
    private final String password;

    public KafkaContainer() {
        super(ContainerImages.image("container.image.redpanda"));

        this.username = UUID.randomUUID().toString();
        this.password = UUID.randomUUID().toString();

        withNetworkAliases(CONTAINER_ALIAS);
        withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger(CONTAINER_ALIAS)));
        withListener(() -> CONTAINER_ALIAS + ":" + CONTAINER_PORT);

        enableAuthorization();
        enableSasl();
        withSuperuser(this.username);
    }

    public String getOutsideBootstrapServers() {
        return CONTAINER_ALIAS + ":" + CONTAINER_PORT;
    }

    public String getUsername() {
        return this.username;
    }

    public String getPassword() {
        return this.password;
    }

    @Override
    public void start() {
        super.start();

        String adminUrl = String.format("%s/v1/security/users", getAdminAddress());

        RestAssured
                .given()
                .contentType("application/json")
                .body(Map.of(
                        "username", getUsername(),
                        "password", getPassword(),
                        "algorithm", SASL_MECHANISM))
                .post(adminUrl)
                .then()
                .statusCode(200);
    }

    public String jaasConfig() {
        return String.format(
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";",
                getUsername(),
                getPassword());
    }

    public RecordMetadata send(String topic, String value) {
        return send(topic, null, value, Map.of());
    }

    public RecordMetadata send(String topic, String value, Map<String, String> headers) {
        return send(topic, null, value, headers);
    }

    public RecordMetadata send(String topic, String key, String value) {
        return send(topic, key, value, Map.of());
    }

    public RecordMetadata send(String topic, String key, String value, Map<String, String> headers) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, UUID.randomUUID().toString());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SECURITY_PROTOCOL);
        config.put(SaslConfigs.SASL_MECHANISM, SASL_MECHANISM);
        config.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig());

        try (var kp = new KafkaProducer<String, String>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);

            headers.forEach((k, v) -> {
                record.headers().add(k, v.getBytes(StandardCharsets.UTF_8));
            });

            logger().info("Sending message to Kafka | Topic {} | Key: {} | Value: {}", topic, key, value);

            RecordMetadata rm = kp.send(record).get();

            logger().info("Message sent to Kafka | Topic {} | Key: {} | Value: {} | Offset: {} | Partition: {}",
                    topic, key, value, rm.offset(), rm.partition());

            return rm;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public KafkaConsumer<String, String> consumer(String groupId, String topic) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, true);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, OffsetResetStrategy.EARLIEST.name().toLowerCase(Locale.US));
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SECURITY_PROTOCOL);
        config.put(SaslConfigs.SASL_MECHANISM, SASL_MECHANISM);
        config.put(SaslConfigs.SASL_JAAS_CONFIG, jaasConfig());

        KafkaConsumer<String, String> kp = new KafkaConsumer<>(config);
        kp.subscribe(List.of(topic));

        return kp;
    }

    public ConsumerRecords<String, String> poll(String topic) {
        return poll(UUID.randomUUID().toString(), topic);
    }

    public ConsumerRecords<String, String> poll(String groupId, String topic) {
        return poll(groupId, topic, 30);
    }

    public ConsumerRecords<String, String> poll(String groupId, String topic, int secondsTimeout) {
        try (var kp = consumer(groupId, topic)) {
            logger().info("Polling message from Kafka | GroupId {} | Topic: {}", groupId, topic);

            var answer = kp.poll(Duration.ofSeconds(secondsTimeout));
            kp.commitSync();

            logger().info("Message polled from Kafka | GroupId {} | Topic: {} | Message count: {}",
                    groupId, topic, answer.count());

            return answer;
        }
    }

    public ConsumerRecords<String, String> poll(KafkaConsumer<String, String> consumer) {
        var answer = consumer.poll(Duration.ofSeconds(30));
        consumer.commitSync();
        return answer;
    }
}
