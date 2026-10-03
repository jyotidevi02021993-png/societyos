-- Platform migration shipped with platform-events: transactional outbox and consumer inbox.
-- These are infrastructure tables (no RLS): the relay and listeners work across societies.

CREATE TABLE outbox_event (
  id             UUID PRIMARY KEY,               -- = CloudEvents id
  aggregate_type TEXT        NOT NULL,           -- bounded context, routes to sos.<ctx>.events.v1
  aggregate_id   UUID        NOT NULL,           -- Kafka key
  type           TEXT        NOT NULL,           -- e.g. gate.entry.requested
  society_id     UUID,                           -- NULL only for platform-level events
  payload        JSONB       NOT NULL,           -- full CloudEvent
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at   TIMESTAMPTZ                     -- set by the polling relay; unused with Debezium
);
CREATE INDEX ix_outbox_event_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE inbox_event (
  consumer     TEXT        NOT NULL,             -- consumer group
  event_id     UUID        NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);

-- Debezium (pgoutput) reads outbox inserts through this publication.
CREATE PUBLICATION sos_outbox FOR TABLE outbox_event WITH (publish = 'insert');

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'debezium') THEN
    GRANT SELECT ON outbox_event TO debezium;
  END IF;
END
$$;
