#!/bin/bash

if [ $# -lt 2 ]; then
  echo "Usage: $0 <path-to-jar> <container1> [<container2> <container3>]"
  exit 1
fi

JAR_FILE=$1; shift

CONTAINERS=($@)

FIRST_CONTAINER="${CONTAINERS[0]}"
if [[ "$FIRST_CONTAINER" == *kafka* ]]; then
    PREFIX="kafka"
    PATHS="/opt/bitnami/kafka /opt/bitnami/scripts/kafka /bitnami/kafka"
else
    PREFIX="nats"
    PATHS="/nats-server /tmp/nats"
fi

CLUSTER_PREFIX=""
if [ "${#CONTAINERS[@]}" -gt 1 ]; then
  CLUSTER_PREFIX="_cluster"
fi

TIMESERIES="${PREFIX}${CLUSTER_PREFIX}_metrics.csv"
DISKUSAGE="${PREFIX}${CLUSTER_PREFIX}_disk_usage.csv"

echo "timestamp,node,cpu,mem,disk_read,disk_write" > "$TIMESERIES"
echo "node,disk_before_mb,disk_after_mb" > "$DISKUSAGE"

for CONTAINER_NAME in "${CONTAINERS[@]}"; do
    safe_name="${CONTAINER_NAME//-/_}"
    before_disk=$(docker exec "$CONTAINER_NAME" du -s -B MB $PATHS | awk '{ sum += $1 } END { print sum }')
    BEFORE_DISK["$safe_name"]=$before_disk
done

log_stats() {
  CPU_CORES=$(nproc)
  CONTAINER_LIST_JSON=$(printf '%s\n' "${CONTAINERS[@]}" | jq -R . | jq -s .)

  while true; do
    TIMESTAMP=$(date --iso-8601=seconds)

    docker stats --no-stream --format "{{json .}}" | jq -r \
      --arg timestamp "$TIMESTAMP" \
      --argjson containers "$CONTAINER_LIST_JSON" \
      --argjson cores "$CPU_CORES" '
        select(.Name as $name | $containers | index($name)) |
        {
          name: .Name,
          cpu: ((.CPUPerc | sub("%"; "") | tonumber) / $cores),
          mem: (.MemUsage | split("/") | .[0] | gsub(" "; "")),
          block_in: (.BlockIO | split("/") | .[0] | gsub(" "; "")),
          block_out: (.BlockIO | split("/") | .[1] | gsub(" "; ""))
        } |
        "\($timestamp),\(.name),\(.cpu),\(.mem),\(.block_in),\(.block_out)"
      ' >> "$TIMESERIES"
  done
}

log_stats &
LOGGER_PID=$!

trap 'kill $LOGGER_PID 2>/dev/null' EXIT

sleep 5

java -Xms4g -Xmx8g -XX:+UseG1GC -jar "$JAR_FILE"

sleep 10

if ps -p "$LOGGER_PID" > /dev/null; then
    kill "$LOGGER_PID"
fi

for CONTAINER_NAME in "${CONTAINERS[@]}"; do
    safe_name="${CONTAINER_NAME//-/_}"
    after_disk=$(docker exec "$CONTAINER_NAME" du -s -B MB $PATHS | awk '{ sum += $1 } END { print sum }')
    echo "$CONTAINER_NAME,${BEFORE_DISK["$safe_name"]},$after_disk" >> "$DISKUSAGE"
done