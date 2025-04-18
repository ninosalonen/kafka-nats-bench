FROM docker.io/bitnami/minideb:bookworm

RUN install_packages curl ca-certificates

RUN curl -sf https://binaries.nats.dev/nats-io/nats-server/v2@v2.11.1 | sh