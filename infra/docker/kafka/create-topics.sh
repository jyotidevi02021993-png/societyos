#!/bin/bash
# Creates every topic up front (auto-create is off, as in production). Local: 1 replica, 3 partitions.
# Production counts and retention are in docs/architecture/02 §2 (Terraform).
set -euo pipefail
BS=kafka:29092
KT=/opt/kafka/bin/kafka-topics.sh

create() { # name partitions retention.ms [cleanup]
  $KT --bootstrap-server $BS --create --if-not-exists --topic "$1" --partitions "$2" \
      --replication-factor 1 --config retention.ms="$3" --config cleanup.policy="${4:-delete}"
}

DAY=86400000
CONTEXTS="identity society security billing community ticket asset utility vendor inventory
          compliance notification workflow media ai marketplace"
for ctx in $CONTEXTS; do
  create "sos.${ctx}.events.v1" 3 $((14 * DAY))
done
create sos.notification.commands.v1 3 $((3 * DAY))
create sos.society.snapshots.v1 3 -1 compact

# Dead-letter topics, one per consumer group (add a line with every new group)
GROUPS="identity.society-roles"
for g in $GROUPS; do
  create "sos.dlq.${g}" 1 $((30 * DAY))
done

$KT --bootstrap-server $BS --list
