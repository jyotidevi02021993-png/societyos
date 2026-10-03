#!/bin/bash
# One database per service, each with two roles (docs/architecture/03 §1):
#   <svc>_owner  runs Flyway, owns the tables; BYPASSRLS only for narrow SECURITY DEFINER lookups
#   <svc>_app    runtime role: DML only, not the owner, so row-level security applies
# Local passwords equal the role name. Production credentials come from Secrets Manager.
set -euo pipefail

SERVICES="identity society security billing community ticket asset utility vendor inventory
          compliance notification workflow audit media dashboard ai marketplace"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-SQL
  DO \$\$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'debezium') THEN
      CREATE ROLE debezium LOGIN REPLICATION PASSWORD 'debezium';
    END IF;
  END \$\$;
SQL

for svc in $SERVICES; do
  db="${svc}_db"; owner="${svc}_owner"; app="${svc}_app"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-SQL
    CREATE ROLE ${owner} LOGIN BYPASSRLS PASSWORD '${owner}';
    CREATE ROLE ${app}   LOGIN PASSWORD '${app}';
    CREATE DATABASE ${db} OWNER ${owner};
    GRANT CONNECT ON DATABASE ${db} TO ${app}, debezium;
SQL
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-SQL
    ALTER SCHEMA public OWNER TO ${owner};
    GRANT USAGE ON SCHEMA public TO ${app}, debezium;
    ALTER DEFAULT PRIVILEGES FOR ROLE ${owner} IN SCHEMA public
      GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app};
    ALTER DEFAULT PRIVILEGES FOR ROLE ${owner} IN SCHEMA public
      GRANT USAGE, SELECT ON SEQUENCES TO ${app};
    ALTER DEFAULT PRIVILEGES FOR ROLE ${owner} IN SCHEMA public
      GRANT EXECUTE ON FUNCTIONS TO ${app};
SQL
  echo "created ${db}"
done
