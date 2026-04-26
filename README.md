# payment-service

Handles payment processing via Midtrans Core API using QRIS. Listens for `order.awaiting.payment` events and creates a QRIS charge. A scheduler periodically expires unpaid payments.

**Tech:** Spring Boot · Java 21 · PostgreSQL · Spring Data JPA · Spring AMQP (RabbitMQ) · Midtrans Core API

**Internal port:** `3003`

---

## API Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/payments/order/:orderId` | JWT (via header) | Get payment status and QR code for an order |
| POST | `/api/v1/payments/webhook/qris` | Public (Midtrans) | Payment status callback from Midtrans |

> All endpoints are exposed via the API gateway at `/api/payment/*`

---

## Payment Lifecycle

```
CREATED → PENDING → PAID / EXPIRED / FAILED
```

| Status | Description |
|---|---|
| `CREATED` | QRIS charge created, awaiting scan |
| `PENDING` | QR scanned, payment in progress |
| `PAID` | Midtrans confirmed payment — triggers order COMPLETED |
| `EXPIRED` | Payment window passed — scheduler auto-expires |
| `FAILED` | Payment failed or declined |

---

## RabbitMQ Events

| Direction | Event | Trigger | Action |
|---|---|---|---|
| Consumes | `order.awaiting.payment` | Order stock reserved | Create Midtrans QRIS charge, save payment record |

---

## System Flow

### Consume order.awaiting.payment → Create QRIS Charge

```
[ Order Service ] ──── publish order.awaiting.payment ────▶ [ RabbitMQ ]
                                                                  │
                                                                  ▼
                                                       [ payment-service ]
                                                         Consume event
                                                         Call Midtrans Core API
                                                           POST /v2/charge (QRIS)
                                                         Save payment record
                                                           { orderId, transactionId,
                                                             status: CREATED,
                                                             qrImageUrl, amount }
```

### GET /api/v1/payments/order/:orderId — Poll Payment Status

```
Client (User JWT)
  │
  ▼
[ API Gateway ] → [ payment-service ]
                      │
                      ├── Find payment by orderId in PostgreSQL
                      └── Return { status, qrImageUrl, amount, expiresAt }
```

QR image URL pattern:
```
{midtrans_base_url}/v2/qris/{transactionId}/qr-code
```

### POST /api/v1/payments/webhook/qris — Midtrans Callback

```
[ Midtrans ]
  │
  ▼
[ payment-service ] POST /webhook/qris
  │
  ├── Verify Midtrans signature key
  ├── Find payment by transaction_id
  ├── Update payment status based on transaction_status
  └── On settlement → update order status → COMPLETED
                       (via internal call or RabbitMQ publish)
```

### Scheduler — Payment Expiry

```
[ Spring Scheduler ] (runs on fixed interval)
  │
  ├── Find all payments with status CREATED / PENDING
  │     and expiresAt < NOW()
  └── Update status → EXPIRED
```

---

## Project Structure

```
payment-service/
└── src/main/java/com/prodmicro/payment_service/
    ├── payment/
    │   ├── controller/PaymentController.java   # REST endpoints
    │   ├── service/
    │   │   ├── PaymentService.java             # Interface
    │   │   ├── impl/PaymentServiceImpl.java    # Business logic
    │   │   └── MidtransService.java            # Midtrans Core API client
    │   ├── entity/
    │   │   ├── Payment.java                    # Payment entity
    │   │   └── PaymentStatus.java              # Enum: CREATED, PENDING, PAID, EXPIRED, FAILED
    │   ├── repository/PaymentRepository.java
    │   ├── scheduler/PaymentExpiryScheduler.java # Auto-expire unpaid payments
    │   └── dto/
    │       ├── PaymentResponse.java
    │       ├── OrderAwaitingPaymentEvent.java
    │       └── OrderItemDto.java
    ├── rabbitmq/
    │   ├── RabbitMQConfig.java                 # Exchange, queue, binding config
    │   ├── RabbitMQConsumer.java               # Consumes order.awaiting.payment
    │   └── RabbitMQPublisher.java
    ├── config/SecurityConfig.java              # Open webhook endpoint, secure others
    └── DotenvEnvironmentPostProcessor.java     # Load .env file
```

---

## Environment Variables

```env
PORT=3003

DB_URL=jdbc:postgresql://localhost:5432/microserv_db
DB_USERNAME=postgres
DB_PASSWORD=postgres

RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest
RABBITMQ_EXCHANGE=orders.event
RABBITMQ_QUEUE_PAYMENT=payment-service

MIDTRANS_SERVER_KEY=your_midtrans_server_key
MIDTRANS_IS_PRODUCTION=false
```

---

## Running Locally

```bash
./mvnw spring-boot:run
```

Service runs on `http://localhost:3003`.

> Note: Spring Boot startup takes ~30–60s. The Docker healthcheck uses `start_period: 60s` to account for this.

## Example Requests

### Get Payment by Order ID
```bash
curl http://localhost:3003/api/v1/payments/order/<order_uuid> \
  -H "x-user-id: <user_uuid>"
```

### Simulate Midtrans Webhook (local testing)
```bash
curl -X POST http://localhost:3003/api/v1/payments/webhook/qris \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "<midtrans_transaction_id>",
    "order_id": "<order_uuid>",
    "transaction_status": "settlement",
    "payment_type": "qris"
  }'
```

## Docker

```bash
docker build -t payment-service .
docker run --env-file .env -p 3003:3003 payment-service
```

## Part of

[E-Commerce Microservices Platform](../README.md)
