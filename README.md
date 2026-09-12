
# Event-Driven Order Fulfillment Service

This project implements a robust, asynchronous Order Fulfillment microservice for an e-commerce system. It consumes order placement events from RabbitMQ, processes them, updates order status in MySQL, and publishes order processed events. The service is designed with idempotency, fault tolerance, and clean architecture principles in mind.

## Architecture

The service follows an event-driven architecture where order events are exchanged asynchronously through RabbitMQ.

flowchart TD
    A["External Order Service"]
    B["RabbitMQ<br/>order.events Exchange"]
    C["order.placed.queue"]
    D["Order Fulfillment Service"]
    E["Event Validation"]
    F["Idempotency Check"]
    G[("MySQL<br/>orders table")]
    H["Business Processing"]
    I["Order Event Publisher"]
    J["order.processed Event"]
    K["DLX<br/>dlx.order.events"]
    L["DLQ<br/>order.dlq"]

    A -->|"order.placed"| B
    B -->|"order.placed"| C
    C --> D

    D --> E

    E -->|"Valid"| F
    E -->|"Invalid"| K

    F -->|"Duplicate<br/>PROCESSED"| M["ACK"]
    F -->|"New / Retry<br/>PENDING or FAILED"| G

    G -->|"PROCESSING"| H
    H -->|"Success"| G
    G -->|"PROCESSED"| I

    I -->|"order.processed"| J
    J --> B

    D -->|"Transient Error"| N["Retry"]
    N -->|"Attempts Remaining"| C
    N -->|"Max Attempts Reached"| K

    C -->|"NACK / No Requeue"| K
    K -->|"order.placed"| L

**Components:**

*   **Order Service (External)**: Represents an upstream service that places orders and publishes `OrderPlacedEvent` messages to the `order.events` exchange. (Not implemented in this project).
*   **RabbitMQ**: Acts as the message broker, facilitating asynchronous communication between services. It includes a Dead Letter Exchange (DLX) and Dead Letter Queue (DLQ) for handling failed messages.
*   **Order Fulfillment Service**: This microservice, implemented in Spring Boot, is the core of the project. It consumes `OrderPlacedEvent` messages, validates them, processes orders, and publishes `OrderProcessedEvent` messages.
*   **MySQL Database**: Stores order information, including status, ensuring data persistence and consistency.

**Service Responsibilities:**

*   **Consume Events**: Listens to `order.placed.queue` for `OrderPlacedEvent` messages.
*   **Validate Events**: Ensures incoming event data meets business rules (e.g., positive quantity, valid IDs).
*   **Idempotent Processing**: Prevents duplicate order creation or incorrect state changes if the same event is received multiple times.
*   **Atomic Database Operations**: Uses Spring Data JPA and `@Transactional` to ensure order creation/updates are atomic.
*   **State Management**: Manages order lifecycle through `PENDING`, `PROCESSING`, `PROCESSED`, and `FAILED` states.
*   **Publish Events**: Publishes `OrderProcessedEvent` to `order.events` exchange upon successful order fulfillment.
*   **Error Handling**: Implements robust ACK/NACK, retry, and Dead Letter Queue (DLQ) mechanisms for transient and permanent failures.
*   **Health Check**: Exposes a `/health` endpoint for monitoring.

## Event Flow

1.  An external **Order Service** publishes an `OrderPlacedEvent` to the `order.events` topic exchange with the routing key `order.placed`.
2.  The `order.placed.queue` receives the event via its binding to `order.events` with `order.placed`.
3.  The **Order Fulfillment Service** consumes the `OrderPlacedEvent` from `order.placed.queue`.
4.  The event is validated. If validation fails (permanent error), the message is NACKed without requeue and routed to the `order.dlq`.
5.  An idempotency check is performed. If the order is already `PROCESSED`, the message is ACKed, and no further processing occurs. If it's `PENDING` or `FAILED`, processing continues.
6.  The order status in MySQL is updated to `PROCESSING` within an atomic transaction.
7.  Business logic (simulated) is executed.
8.  The order status in MySQL is updated to `PROCESSED` within the same atomic transaction.
9.  If the database transaction commits successfully, an `OrderProcessedEvent` is published to the `order.events` topic exchange with the routing key `order.processed`.
10. The original `OrderPlacedEvent` message is then ACKed, removing it from `order.placed.queue`.
11. If a transient error occurs during processing (e.g., database temporary outage), the message is NACKed with requeue, triggering a retry. After a configured number of retries, it's routed to the `order.dlq`.

## Event Schemas

### OrderPlacedEvent (Incoming)

```json
{
  "orderId": "string",
  "productId": "string",
  "quantity": 1,
  "customerId": "string",
  "timestamp": "2023-10-27T10:00:00Z"
}
```

**Validation Rules:**

*   `orderId`: Must be present (not blank).
*   `productId`: Must be present (not blank).
*   `quantity`: Must be a positive integer (min 1).
*   `customerId`: Must be present (not blank).
*   `timestamp`: Must be present and not in the future (ISO-8601 format).

### OrderProcessedEvent (Outgoing)

```json
{
  "orderId": "string",
  "status": "PROCESSED",
  "processedAt": "2023-10-27T10:05:00Z"
}
```

## RabbitMQ Configuration

The service configures the following RabbitMQ resources:

*   **Exchange**: `order.events` (Type: `topic`, Durable)
    *   Used for both incoming `OrderPlacedEvent` and outgoing `OrderProcessedEvent`.
*   **Queue**: `order.placed.queue` (Durable)
    *   Receives `OrderPlacedEvent` messages.
    *   Configured with `x-dead-letter-exchange` pointing to `dlx.order.events` and `x-dead-letter-routing-key` as `order.placed`.
*   **Binding**: `order.placed.queue` is bound to `order.events` with routing key `order.placed`.
*   **Dead Letter Exchange (DLX)**: `dlx.order.events` (Type: `direct`, Durable)
    *   Messages from `order.placed.queue` are routed here upon NACK without requeue or retry exhaustion.
*   **Dead Letter Queue (DLQ)**: `order.dlq` (Durable)
    *   Receives messages from `dlx.order.events`.
*   **DLQ Binding**: `order.dlq` is bound to `dlx.order.events` with routing key `order.placed`.

**Ports:**

*   AMQP: `5672`
*   Management UI: `15672`

## Database Schema

The service uses a MySQL database with an `orders` table:

```sql
CREATE TABLE IF NOT EXISTS orders (
    id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    status ENUM('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED') NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

*   `id`: Maps to `orderId` from the event, serving as the primary key to enforce uniqueness.
*   `status`: Stores the current state of the order (`PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`).
*   `created_at`, `updated_at`: Automatically managed timestamps for auditing.

## Idempotency

Idempotency is crucial to handle duplicate `OrderPlacedEvent` messages safely. The service ensures this by:

1.  **Unique Business Identifier**: `orderId` is used as the primary key in the `orders` table, enforcing uniqueness at the database level.
2.  **Pre-processing Check**: Before processing, the `OrderProcessingService` checks if an order with the given `orderId` already exists:
    *   If an order with `orderId` exists and its `status` is `PROCESSED`, the incoming duplicate event is recognized, the message is ACKed, and no further processing or state changes occur. This prevents duplicate `OrderProcessedEvent` publications and unintended side effects.
    *   If an order with `orderId` exists and its `status` is `PROCESSING`, it's also treated as a duplicate (potentially a race condition or a very fast retry) and ACKed.
    *   If an order exists with `PENDING` or `FAILED` status, it will be updated.
3.  **Atomic Transactions**: All database updates for a single order processing operation are wrapped in a `@Transactional` block, ensuring consistency.

This design guarantees that processing the same `OrderPlacedEvent` multiple times will not create duplicate orders or lead to an inconsistent state.

## Retry Strategy

The service differentiates between transient and permanent errors:

*   **Transient Errors**: These are temporary, recoverable issues (e.g., temporary database connection loss, RabbitMQ network issues, external service timeouts).
    *   When a transient error occurs during message processing, the `OrderEventConsumer` throws a generic exception (wrapped in `AmqpRejectAndDontRequeueException` to signal a retry).
    *   Spring AMQP's `SimpleRabbitListenerContainerFactory` is configured with a retry mechanism (exponential backoff).
    *   **Retry Policy**:
        *   `max-attempts`: 3 (including the initial attempt)
        *   `initial-interval`: 1000 ms
        *   `max-interval`: 10000 ms
        *   `multiplier`: 2.0
    *   After the maximum number of retry attempts is exhausted, the message is routed to the `order.dlq`.
*   **Permanent Errors**: These are non-recoverable issues related to the event data itself (e.g., malformed JSON, missing required fields, invalid quantity, unsupported data).
    *   When a permanent error (specifically `InvalidOrderEventException`) is caught by the `OrderEventConsumer`, it immediately NACKs the message with `requeue=false`.
    *   This action routes the message directly to the `order.dlq` without any retries, preventing endless processing loops.

## ACK/NACK Strategy

*   **ACK (Acknowledge)**:
    *   A message is ACKed only after it has been **successfully processed**, meaning:
        *   Event validation passed.
        *   Order created/updated in MySQL to `PROCESSED` state.
        *   `OrderProcessedEvent` successfully published.
    *   Also, if an `OrderPlacedEvent` is identified as a duplicate for an already `PROCESSED` or `PROCESSING` order (idempotency check), it is safely ACKed to remove it from the queue.
*   **NACK (Negative Acknowledge)**:
    *   **NACK with Requeue (`requeue=true`)**: Used for **transient failures**. The message is returned to the queue for retry. This is handled by Spring AMQP's default error handler when a non-fatal exception is thrown.
    *   **NACK without Requeue (`requeue=false`)**: Used for **permanent failures** (e.g., `InvalidOrderEventException`). The message is immediately routed to the Dead Letter Exchange (`dlx.order.events`) and then to the `order.dlq`.

## Transaction Strategy

The service employs Spring's `@Transactional` annotation to ensure atomic database operations.

1.  **Database Consistency**: All operations related to creating or updating an `Order` entity (setting status to `PROCESSING`, then `PROCESSED`) are encapsulated within a single `@Transactional` method (`OrderProcessingService.processOrderPlacedEvent`). This guarantees that the database state remains consistent; either all changes commit, or none do. If any part of the database operation fails, the transaction is rolled back, and the order status is not left in an inconsistent intermediate state.
2.  **Message Publishing**: The `OrderProcessedEvent` is published *after* the database transaction successfully commits.

**Trade-offs:**

This approach prioritizes database consistency. However, it introduces a potential for inconsistency between the database and the message broker:

*   **Scenario**: The database transaction commits successfully, but the service crashes or a network issue prevents the `OrderProcessedEvent` from being published to RabbitMQ.
*   **Result**: The order is marked `PROCESSED` in the database, but the `OrderProcessedEvent` is not emitted.
*   **Mitigation**:
    *   **Idempotency**: If the original `OrderPlacedEvent` is redelivered (e.g., due to a consumer crash before ACK), the idempotency check will recognize the order as `PROCESSED` in the database and safely ACK the duplicate message without re-publishing the `OrderProcessedEvent`. This prevents duplicate events but doesn't guarantee the *first* `OrderProcessedEvent` was sent.
    *   **Monitoring/Reconciliation**: For critical systems, an outbox pattern or a reconciliation process (e.g., a batch job that checks for `PROCESSED` orders without corresponding `OrderProcessedEvent`s) would be necessary to ensure eventual consistency.

For this assignment, the chosen strategy provides strong database consistency and handles duplicate incoming events idempotently, which is a sensible balance given the requirements.

## API

### GET /health

**Description**: Provides a simple health check endpoint for monitoring the application's status.

**Request**:

```http
GET /health
Host: localhost:8080
```

**Response**:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "status": "UP"
}
```

## Configuration

All configurable properties are managed via environment variables, with sensible defaults provided in `application.yml`. A `.env.example` file is provided for easy setup.

| Environment Variable             | Description                                     | Default (if applicable) |
| :------------------------------- | :---------------------------------------------- | :---------------------- |
| `SERVER_PORT`                    | Port on which the Spring Boot application runs. | `8080`                  |
| `DB_HOST`                        | MySQL database host.                            | `mysql`                 |
| `DB_PORT`                        | MySQL database port.                            | `3306`                  |
| `DB_NAME`                        | MySQL database name.                            | `orderdb`               |
| `DB_USERNAME`                    | MySQL database username.                        | `orderuser`             |
| `DB_PASSWORD`                    | MySQL database password.                        | `orderpass`             |
| `MYSQL_ROOT_PASSWORD`            | MySQL root password (for `docker-compose`).     | `rootpassword`          |
| `RABBITMQ_HOST`                  | RabbitMQ host.                                  | `rabbitmq`              |
| `RABBITMQ_PORT`                  | RabbitMQ port.                                  | `5672`                  |
| `RABBITMQ_USERNAME`              | RabbitMQ username.                              | `guest`                 |
| `RABBITMQ_PASSWORD`              | RabbitMQ password.                              | `guest`                 |
| `RABBITMQ_RETRY_MAX_ATTEMPTS`    | Maximum number of retry attempts for messages.  | `3`                     |
| `RABBITMQ_RETRY_INITIAL_INTERVAL_MS` | Initial interval for message retries (ms).      | `1000`                  |
| `RABBITMQ_RETRY_MAX_INTERVAL_MS` | Maximum interval for message retries (ms).      | `10000`                 |
| `RABBITMQ_RETRY_MULTIPLIER`      | Multiplier for exponential backoff retry.       | `2.0`                   |

## Running Locally

### Prerequisites

*   Docker and Docker Compose installed.
*   Maven (if running tests or building outside Docker).
*   Java 17 (if running outside Docker).

### Steps

1.  **Clone the repository** (if applicable, for a real project).
2.  **Create `.env` file**: Copy `.env.example` to `.env` in the project root and customize environment variables if needed.
    ```bash
    cp .env.example .env
    ```
3.  **Start services with Docker Compose**:
    ```bash
    docker compose up --build
    ```
    This command will:
    *   Build the `order-processor-service` Docker image.
    *   Start MySQL, RabbitMQ, and the `order-processor-service` containers.
    *   Wait for MySQL and RabbitMQ to be healthy before starting the application.
    *   Initialize the MySQL database with the `db_init/init.sql` script.

4.  **Verify application health**:
    Open your browser or use `curl`:
    ```bash
    curl http://localhost:8080/health
    ```
    Expected output: `{"status":"UP"}`

5.  **Stop services**:
    ```bash
    docker compose down
    ```
    To remove volumes (and thus MySQL data) as well:
    ```bash
    docker compose down -v
    ```

## Testing

Comprehensive unit and integration tests are provided.

### Running Tests

1.  **Ensure Docker is running** (for integration tests using Testcontainers).
2.  **Execute Maven tests**:
    ```bash
    mvn test
    ```
    This command will run all unit tests (e.g., `OrderProcessingServiceTest`, `OrderEventConsumerTest`) and integration tests (`OrderFulfillmentIntegrationTest`). Integration tests will spin up temporary MySQL and RabbitMQ containers using Testcontainers.

## RabbitMQ Management UI

You can access the RabbitMQ Management UI to monitor queues, exchanges, and messages:

*   **URL**: [http://localhost:15672](http://localhost:15672)
*   **Credentials**: Use the `RABBITMQ_USER` and `RABBITMQ_PASSWORD` defined in your `.env` file (default: `guest`/`guest`).

From the UI, you can:
*   Verify that `order.events`, `dlx.order.events` exchanges exist.
*   Verify that `order.placed.queue` and `order.dlq` queues exist.
*   Publish sample `OrderPlacedEvent` messages to `order.events` exchange with routing key `order.placed`.

## Sample Event

You can publish the following `OrderPlacedEvent` JSON to the `order.events` exchange with routing key `order.placed` using the RabbitMQ Management UI (or any AMQP client):

```json
{
  "orderId": "new-order-123",
  "productId": "product-X",
  "quantity": 3,
  "customerId": "customer-A",
  "timestamp": "2023-10-27T10:00:00Z"
}
```

**To test idempotency, publish the same event multiple times.** The first time, it will be processed. Subsequent times, it will be acknowledged without re-processing.

**To test permanent failure, publish an invalid event (e.g., missing `orderId` or `quantity` < 1):**

```json
{
  "productId": "product-invalid",
  "quantity": 0,
  "customerId": "customer-B",
  "timestamp": "2023-10-27T10:00:00Z"
}
```
This message should end up in the `order.dlq`.

## Observability

The service uses SLF4J with Logback for structured logging. Key lifecycle events are logged with relevant context, including `orderId` (via MDC) for easy tracing:

*   Message received
*   Order validation status
*   Order creation/update
*   State transitions (`PENDING` -> `PROCESSING` -> `PROCESSED`)
*   Event published
*   ACK/NACK actions
*   Retry attempts
*   DLQ routing
*   Errors (transient/permanent)

Log levels are configured in `application.yml` (`INFO` for root, `DEBUG` for `com.example.orderprocessor`).

## Failure Handling

*   **Transient Failures**: Any `Exception` (other than `InvalidOrderEventException` or `OrderAlreadyProcessedException`) caught during `OrderPlacedEvent` processing is considered transient. The message is NACKed with requeue, and Spring AMQP's retry mechanism attempts re-processing with exponential backoff. After `RABBITMQ_RETRY_MAX_ATTEMPTS` (default 3), the message is routed to the `order.dlq`.
*   **Permanent Failures**: `InvalidOrderEventException` (e.g., due to validation errors) is considered a permanent failure. The message is immediately NACKed without requeue and routed to the `order.dlq`.
*   **Idempotent Success**: `OrderAlreadyProcessedException` indicates a duplicate event for an already processed order. The message is ACKed to remove it from the queue without further processing.

## Design Decisions

*   **Java Records for DTOs**: Used for `OrderPlacedEvent` and `OrderProcessedEvent` to create immutable, concise data transfer objects, aligning with modern Java practices.
*   **Spring Data JPA**: Simplifies database interactions and provides a robust ORM solution.
*   **`@EnableJpaAuditing`**: Automatically manages `createdAt` and `updatedAt` timestamps in the `Order` entity, reducing boilerplate.
*   **`@Transactional`**: Ensures atomicity of database operations, preventing inconsistent states.
*   **`Clock` Bean**: Provides a testable way to get the current time, making `Instant.now()` mockable in tests.
*   **`MDC` for Logging**: `Mapped Diagnostic Context` is used to inject `orderId` into log messages, making it easier to trace events related to a specific order across different log lines.
*   **`ConditionalRejectingErrorHandler`**: Configured in `RabbitMQConfig` to provide fine-grained control over message rejection and requeue behavior, working in conjunction with custom exceptions.
*   **Multi-stage Dockerfile**: Optimizes image size and build time by separating build and runtime environments.
*   **Non-root User in Docker**: Enhances security by running the application as a non-root user within the container.
*   **Testcontainers**: Used for integration tests to provide real, isolated instances of MySQL and RabbitMQ, ensuring tests are reliable and reflect production behavior.

## Limitations

*   **Transaction Outbox Pattern**: The current transaction strategy does not implement a full outbox pattern for guaranteed message delivery. While it prioritizes database consistency and handles idempotency for incoming events, there's a small window where a database commit succeeds but the `OrderProcessedEvent` fails to publish. For extremely high-fidelity eventing, an outbox pattern would be required.
*   **Complex Business Logic**: The "business processing" is simulated. A real-world service might involve calls to other microservices, inventory checks, payment processing, etc., which would introduce more complex error handling and distributed transaction considerations.
*   **Schema Registry**: Event schemas are defined as Java records. In a larger ecosystem, a schema registry (e.g., Confluent Schema Registry) would be beneficial for managing schema evolution and ensuring compatibility between producers and consumers.
*   **Monitoring & Alerting**: While structured logging is implemented, a full production system would integrate with a centralized logging system, metrics collection (e.g., Prometheus), and alerting tools.

## Future Improvements

*   **Outbox Pattern**: Implement the Transactional Outbox Pattern to ensure atomic updates to the database and reliable publishing of `OrderProcessedEvent` messages.
*   **Distributed Tracing**: Integrate with a distributed tracing system (e.g., Zipkin, Jaeger) to trace requests across multiple microservices.
*   **Metrics**: Add custom metrics (e.g., using Micrometer) to monitor processing times, error rates, queue depths, etc.
*   **Advanced Retry Policies**: Implement more sophisticated retry policies, such as circuit breakers or backpressure mechanisms, especially when interacting with external services.
*   **Idempotency Store**: For more complex idempotency requirements, consider a dedicated idempotency store (e.g., Redis) to track processed events.
*   **Schema Registry Integration**: Integrate with a schema registry to manage and validate event schemas, ensuring backward and forward compatibility.
*   **Configuration Management**: Externalize configuration further using Spring Cloud Config or Kubernetes ConfigMaps/Secrets.
*   **Security Enhancements**: Implement OAuth2/JWT for API security if the service were to expose more endpoints.
*   **Container Orchestration**: Deploy to Kubernetes or similar platforms for better scalability, resilience, and management.
```
