# Registro de cambios

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el versionado es
[semántico](https://semver.org/lang/es/). La versión del artefacto no es la de la API: esta
publica `/api/v1` y seguirá haciéndolo mientras no haya un cambio incompatible de contrato.

## [1.1.0]

### Añadido
- Envelope tipado para los eventos de Kafka: `ReservationEvent`, `ReservationPayload` y `Actor`.
  Sustituye al `Map` plano que se publicaba antes y cumple el contrato de
  `infra/docs/contracts/events/kafka.md`, que hasta ahora describía una forma que el código no
  producía.
- `TipoDeEvento`, que traduce el estado del dominio al nombre del evento en el tema
  (`reservation.created`, `reservation.confirmed`, …). Antes convivían tres vocabularios para la
  misma cosa.
- `KafkaProducerConfig`: configuración explícita del productor con `acks=all`,
  `enable.idempotence=true`, `batch.size` de 32 KB, `linger.ms` de 20 y compresión snappy.
- `KafkaTopicsConfig`: `reservations.events` y `audit.timeline` declarados desde el código, con
  tres particiones, tres réplicas y sus políticas de retención.
- `motivo` opcional en `PUT /api/v1/reservations/{id}/status`, que viaja en el evento de
  cancelación. Es aditivo: los clientes que solo mandan `status` siguen funcionando igual.
- `estadoAnterior` y `duracionEstadiaHoras` en el evento, que auditoría y reportería necesitaban y
  nunca recibían.
- Pruebas del publicador: envelope, vocabulario, clave de partición y comportamiento con la
  bandera apagada.
- Flujo de integración continua que compila y prueba en cada cambio.

### Cambiado
- La propiedad `andesstay.kafka.topic` pasa a `andesstay.kafka.topics`, igual que en
  `ms-andesstay-audit` y `ms-andesstay-report`. Era el mismo valor con dos nombres distintos.

## [1.0.0]
- Primera versión: reservas, máquina de estados, coordinación con el catálogo y comandos de
  RabbitMQ.
