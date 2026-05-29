# dbsearch

CLI tool for searching database schemas by owner, table, and column name. Supports PostgreSQL and Oracle. Results can come from a local cache (fast, offline) or a live DB connection.

## Setup

Copy `connections.ini.example` to `connections.ini` and fill in your values:

```bash
cp connections.ini.example connections.ini
```

Each `[section]` is a named connection. `[default]` is used when no `--conn` flag is given:

```ini
[default]
url=jdbc:oracle:thin:@myhost:1521:mydb
user=myuser
password=secret
cache=/home/dp/dbsearch/db.ser

[dev]
url=jdbc:postgresql://localhost:5432/devdb
user=devuser
password=secret
cache=/home/dp/dbsearch/db.dev.ser

[staging]
url=jdbc:oracle:thin:@staginghost:1521:stagingdb
user=staginguser
password=secret
cache=/home/dp/dbsearch/db.staging.ser
```

Each connection has its own `cache` path so loading one never overwrites another.

`connections.ini` is gitignored since it contains passwords.

## Build

Requirements: Java 21+, Maven

```bash
mvn clean package
```

Output: `target/dbsearch-0.1.0.jar` + `target/lib/`

The `dbsearch` shell script in the repo root builds automatically on first run if the JAR is missing.

## Usage

```
dbsearch [--conn <name>] load
dbsearch [--conn <name>] [--live] <owner> <table> <column>
dbsearch connections
```

All three filter arguments are case-insensitive substring matches. Use `%` as a wildcard meaning "match anything".

### Load the schema cache

```bash
dbsearch load
dbsearch --conn dev load
```

Connects to the database, fetches all non-system tables and their columns, and writes the result to the configured cache file. Also writes a `.meta` sidecar recording the connection URL and timestamp so you always know what the cache came from.

### Search

```bash
# Search the local cache (fast, no DB connection needed)
dbsearch sales invoice %           # tables in schema 'sales' with 'invoice' in the name
dbsearch % % customer_id           # any table with a column containing 'customer_id'
dbsearch sales % %                 # all tables in schema 'sales'

# Search live against the DB (skips cache, always fresh)
dbsearch --live sales invoice %
dbsearch --conn dev --live % % customer_id
```

Cache searches print a header showing where the cache came from and when it was loaded:

```
[Cache] jdbc:oracle:thin:@myhost:1521:mydb  loaded 2026-05-29 08:14:03
```

Live searches print the active connection URL:

```
[Live] jdbc:oracle:thin:@myhost:1521:mydb
```

This makes it easy to tell whether you're looking at the right database.

### List connections

```bash
dbsearch connections
```

Prints all connections configured in `.env`:

```
  (default)            jdbc:oracle:thin:@myhost:1521:mydb
  --conn dev           jdbc:postgresql://localhost:5432/devdb
```

## Performance note

`--live` pushes the owner and table filters down to JDBC as SQL `LIKE` patterns, so a targeted search like `--live sales invoice %` only fetches matching tables from the DB. Broad searches like `--live % % column_name` scan all tables and are as slow as running `load`.

For day-to-day use, run `load` once per connection and search the cache. Use `--live` when you need to verify freshness or haven't loaded yet.

## Supported databases

- PostgreSQL (via `postgresql-42.x` driver)
- Oracle (via `ojdbc11`)
