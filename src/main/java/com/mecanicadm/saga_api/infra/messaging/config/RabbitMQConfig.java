package com.mecanicadm.saga_api.infra.messaging.config;

import com.fasterxml.jackson.databind.JavaType;
import com.mecanicadm.saga_api.core.saga.event.SagaEvent;
import com.mecanicadm.saga_api.infra.messaging.outbox.RabbitOutboxMessageSender;
import com.mecanicadm.saga_api.infra.messaging.serialization.MessageJsonSerializer;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
public class RabbitMQConfig {
    private static final List<Class<?>> EVENTS = Arrays.asList(SagaEvent.class.getPermittedSubclasses());

    @Bean
    public Jackson2JsonMessageConverter eventMessageConverter() {
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper() {
            @Override
            public String getClassIdFieldName() {
                return RabbitOutboxMessageSender.EVENT_TYPE_HEADER;
            }

            @Override
            public JavaType toJavaType(MessageProperties properties) {
                Object eventType = properties.getHeaders().get(getClassIdFieldName());
                if (eventType == null || !getIdClassMapping().containsKey(eventType.toString())) {
                    throw new MessageConversionException("eventType desconhecido: " + eventType);
                }
                return super.toJavaType(properties);
            }
        };
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.TYPE_ID);
        typeMapper.setIdClassMapping(EVENTS.stream().collect(Collectors.toMap(Class::getSimpleName, Function.identity())));

        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(MessageJsonSerializer.newObjectMapper());
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory eventListenerContainerFactory(
            ConnectionFactory connectionFactory,
            @Qualifier("eventMessageConverter") MessageConverter eventMessageConverter,
            @Value("${messaging.consumer.prefetch:10}") int prefetch) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(eventMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(prefetch);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        return new RabbitTemplate(connectionFactory);
    }
}
