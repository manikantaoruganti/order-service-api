package com.example.orderprocessor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.listener.FatalExceptionStrategy;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ErrorHandler;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMQConfig {

    // --- Exchange and Queue Names ---
    @Value("${rabbitmq.exchange.order-events}")
    private String orderEventsExchange;

    @Value("${rabbitmq.queue.order-placed}")
    private String orderPlacedQueue;

    @Value("${rabbitmq.routing-key.order-placed}")
    private String orderPlacedRoutingKey;

    @Value("${rabbitmq.routing-key.order-processed}")
    private String orderProcessedRoutingKey;

    @Value("${rabbitmq.exchange.dlx-order-events}")
    private String dlxOrderEventsExchange;

    @Value("${rabbitmq.queue.order-dlq}")
    private String orderDlqQueue;

    @Value("${rabbitmq.retry.max-attempts}")
    private int maxRetryAttempts;

    @Value("${rabbitmq.retry.initial-interval-ms}")
    private long initialRetryIntervalMs;

    @Value("${rabbitmq.retry.max-interval-ms}")
    private long maxRetryIntervalMs;

    @Value("${rabbitmq.retry.multiplier}")
    private double retryMultiplier;


    // --- Exchanges ---
    @Bean
    public TopicExchange orderEventsExchange() {
        return new TopicExchange(orderEventsExchange, true, false); // Durable, not auto-delete
    }

    @Bean
    public DirectExchange dlxOrderEventsExchange() {
        return new DirectExchange(dlxOrderEventsExchange, true, false); // Durable, not auto-delete
    }

    // --- Queues ---
    @Bean
    public Queue orderPlacedQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", dlxOrderEventsExchange);
        args.put("x-dead-letter-routing-key", orderPlacedRoutingKey); // Route DLQ messages with original routing key
        return new Queue(orderPlacedQueue, true, false, false, args); // Durable, not exclusive, not auto-delete
    }

    @Bean
    public Queue orderDlqQueue() {
        return new Queue(orderDlqQueue, true, false, false); // Durable, not exclusive, not auto-delete
    }

    // --- Bindings ---
    @Bean
    public Binding orderPlacedBinding(Queue orderPlacedQueue, TopicExchange orderEventsExchange) {
        return BindingBuilder.bind(orderPlacedQueue).to(orderEventsExchange).with(orderPlacedRoutingKey);
    }

    @Bean
    public Binding orderDlqBinding(Queue orderDlqQueue, DirectExchange dlxOrderEventsExchange) {
        return BindingBuilder.bind(orderDlqQueue).to(dlxOrderEventsExchange).with(orderPlacedRoutingKey);
    }

    // --- Message Converter ---
    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule()); // Support for Java 8 Date/Time API
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    // --- RabbitTemplate for publishing ---
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        return rabbitTemplate;
    }

    // --- Rabbit Listener Container Factory for consuming ---
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                               MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL); // Manual ACK/NACK
        factory.setErrorHandler(errorHandler()); // Custom error handler for retries and DLQ
        factory.setMissingQueuesFatal(false); // Don't make startup fail if queue is missing (useful for tests)
        return factory;
    }

    // --- Custom Error Handler for Retries and DLQ ---
    @Bean
    public ErrorHandler errorHandler() {
        // This strategy determines which exceptions are fatal (go to DLQ immediately)
        // and which are transient (trigger retries).
        // By default, Spring AMQP's DefaultErrorHandler will requeue on most exceptions.
        // We want to explicitly control this.
        // InvalidOrderEventException will be considered fatal by our consumer logic.
        // Other exceptions will trigger retries.
        return new ConditionalRejectingErrorHandler(new CustomFatalExceptionStrategy());
    }

    // CustomFatalExceptionStrategy to control which exceptions are fatal
    private static class CustomFatalExceptionStrategy implements FatalExceptionStrategy {
        @Override
        public boolean isFatal(Throwable t) {
            // All exceptions caught by the listener will be handled by our consumer logic
            // which will either throw AmqpRejectAndDontRequeueException (for permanent failures)
            // or let other exceptions propagate for retries.
            // So, here we can simply let the default behavior handle it, or explicitly
            // mark certain exceptions as fatal if they escape our consumer's try-catch.
            // For this setup, we rely on the consumer to throw AmqpRejectAndDontRequeueException
            // for permanent failures, which ConditionalRejectingErrorHandler will then
            // process as a non-requeueable NACK.
            return false; // Let the consumer decide requeue or not
        }
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
