# ms-andesstay-reservations

Microservicio de **reservas** del sistema AndesStay. Es el corazón del dominio: administra el
ciclo de vida de una reserva de hospedaje y coordina al resto del sistema.

## Responsabilidad

| Módulo | Qué hace |
|---|---|
| CRUD de reservas | Crear, consultar y listar con filtros por estado y fecha |
| Máquina de estados | Valida cada transición y rechaza las inválidas con `409` |
| Coordinación de cupo | Llama a `catalog` para reservar y liberar disponibilidad |
| Publicación de eventos | Produce a Kafka en cada transición, y encola comandos en RabbitMQ |

## Máquina de estados

```
CREADA ──► CONFIRMADA ──► CHECKIN_PENDIENTE ──► EN_ESTADIA ──► CHECKOUT
   │            │                 │                  │
   └────────────┴─────────────────┴──────────────────┘
                        └──► CANCELADA
```

Dos reglas invariables, que son las que pide el caso:

- **No hay check-in sin confirmar.** Pasar de `CREADA` a `CHECKIN_PENDIENTE` o `EN_ESTADIA` está
  prohibido.
- **Confirmar sin cupo falla.** Si `catalog` rechaza el `hold`, la respuesta es `409` y la
  reserva **permanece en `CREADA`**. Esta es la regla que resuelve el overbooking.

`CHECKOUT` y `CANCELADA` son estados finales.

El enum usa `EN_ESTADIA` sin tilde, para evitar problemas de encoding en JSON, URLs y Oracle; se
acepta `EN_ESTADÍA` al deserializar por compatibilidad con el enunciado.

La tabla completa de transiciones, con roles y efectos laterales, está en
[`estados.md`](https://github.com/AndesStay-Duoc/infra/blob/develop/docs/contracts/estados.md).

## Endpoints

| Método | Ruta | Rol |
|---|---|---|
| `POST` | `/api/reservations` | Admin, Recepcionista, Huésped |
| `GET` | `/api/reservations?status=&from=&to=` | Admin, Recepcionista, Huésped (solo propias) |
| `GET` | `/api/reservations/{id}` | Admin, Recepcionista, Huésped (solo propias) |
| `PUT` | `/api/reservations/{id}/status` | Admin, Recepcionista, Huésped (solo cancelar) |

Un huésped que pide una reserva ajena recibe `403`, no `404`: no se revela la existencia del
recurso a alguien autenticado.

Se accede siempre a través del BFF. Ver
[`rutas-gateway.md`](https://github.com/AndesStay-Duoc/infra/blob/develop/docs/contracts/rutas-gateway.md).

## Lo que publica

| Destino | Cuándo |
|---|---|
| Kafka `reservations.events` | En cada transición de estado, si `ANDESSTAY_EVENTS_KAFKA_ENABLED=true` |
| RabbitMQ `email.send` y `voucher.gen` | Al confirmar |
| RabbitMQ `housekeeping.ticket` | Al pasar a `CHECKIN_PENDIENTE` y al hacer checkout |

Todos los mensajes usan el
[envelope común](https://github.com/AndesStay-Duoc/infra/blob/develop/docs/contracts/events/envelope.md).
El `correlationId` se genera al crear la reserva y se reutiliza en todos sus eventos, así que
filtrar por él reconstruye el timeline completo.

`audit.timeline` no lo escribe este servicio: lo deriva la topología de Kafka Streams de
`ms-andesstay-audit` a partir de `reservations.events`.

### La bandera de Kafka

`andesstay.events.kafka.enabled` (variable `ANDESSTAY_EVENTS_KAFKA_ENABLED`) vale `false` por
defecto en `application.yml`, y se deja así a propósito: permite arrancar el servicio con
`./mvnw spring-boot:run` sin ningún clúster, sin que cada reserva espere el `max.block.ms` del
productor ni que `KafkaAdmin` llene el log de avisos. Los compose de `infra` que levantan el
servicio junto al clúster (`apps/compose.yml` y `deploy/compose.yml`) la ponen en `true`. La de
RabbitMQ, `ANDESSTAY_EVENTS_RABBIT_ENABLED`, es independiente.

## Stack

Java 21 · Spring Boot 3.5.3 · Spring Data JPA · Oracle · Spring Kafka · Spring AMQP ·
Spring Security como resource server.

## Variables de entorno

| Variable | Descripción |
|---|---|
| `DB_URL` | JDBC del esquema `RESERVATIONS` |
| `DB_USER` | Usuario del esquema `RESERVATIONS` |
| `DB_PASSWORD` | Contraseña del esquema |
| `SVC_CATALOG_URL` | URL de `ms-andesstay-catalog` |
| `KAFKA_BOOTSTRAP` | Lista de brokers |
| `ANDESSTAY_EVENTS_KAFKA_ENABLED` | Publica en Kafka. `false` por defecto; ver "La bandera de Kafka" |
| `RABBITMQ_HOST` · `RABBITMQ_PORT` | Clúster RabbitMQ |
| `JWT_ISSUER` | Issuer del tenant |
| `JWT_AUDIENCE_STAFF` · `JWT_AUDIENCE_GUEST` | Audiences de cada aplicación |

Se configuran en un `.env` que **no se versiona**. Ver `.env.example`.

## Cómo levantarlo

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Requiere Oracle y `catalog` en marcha. Los compose están en el repositorio
[`infra`](https://github.com/AndesStay-Duoc/infra).

## Estado

Repositorio inicializado. La implementación corresponde a la Fase 5 del plan.

## Cómo contribuir

Ver [`CONTRIBUTING.md`](CONTRIBUTING.md).
