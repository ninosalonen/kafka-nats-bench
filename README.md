# Kafka-nats-bench

## Prerequisites

- Java 21
- Docker & Docker compose

## Setup

1. Build the custom NATS image `docker build -t minideb-nats:1.0 .`

2. Build both clients `mvn -f clients/nats/pom.xml clean package && mvn -f clients/kafka/pom.xml clean package.`

## Run

1. `docker compose -f <file> up -d`

2. `./run.sh kafka.jar kafka-broker-1 kafka-broker-2 kafka-broker-3` or `./run.sh nats.jar nats-server-1 nats-server-2 nats-server-3`

## Stop

1. `docker compose -f <file> down -v`
