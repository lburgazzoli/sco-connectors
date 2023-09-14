package com.github.sco1237896.connector.it.misc.datagenerator

import com.github.sco1237896.connector.it.support.KafkaConnectorSpec
import com.github.sco1237896.connector.it.support.KafkaContainer
import groovy.util.logging.Slf4j
import spock.lang.Unroll

@Slf4j
class DataGeneratorConnectorIT extends KafkaConnectorSpec {
    @Unroll
    def "data-generator source"() {
        setup:
            def topic = topic()
            def group = uid()
            def payload = '''{ "foo": "bar" }'''

            def cnt = forDefinition('data_generator_source_v1.yaml')
                .withSinkProperties([
                    'topic': topic,
                    'bootstrapServers': kafka.outsideBootstrapServers,
                    'consumerGroup': uid(),
                    'user': kafka.username,
                    'password': kafka.password,
                    'securityProtocol': KafkaContainer.SECURITY_PROTOCOL,
                    'saslMechanism': KafkaContainer.SASL_MECHANISM,
                ])
                .withSourceProperties([
                    'period': '1s',
                    'message': payload,
                    'contentType': 'application/json',
                ])
                .build()

            cnt.start()
        when:
            cnt.start()
        then:
            def records = kafka.poll(group, topic)
            records.size() >= 1
            records.first().value() == payload
        cleanup:
            closeQuietly(cnt)
    }
}
