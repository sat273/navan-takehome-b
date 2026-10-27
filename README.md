# Travel transactions & invoices API

Java 17 + Spring Boot 3 + SQLite (file DB, WAL). No LLM calls, no secrets.

## Run

```
./mvnw spring-boot:run
```

Listens on `:8080` (`PORT` to change). DB file: `./data/travel.db` (`DB_PATH` to change). Schema is created on startup.

## Test

```
./mvnw test
```

20 tests hit the real HTTP server (random port) with a temp SQLite file, using `fixtures/task-b/`.

## Decisions

- **Duplicate `external_id`**: `409 Conflict`, body includes `id` / `existing_id` of the stored row. Enforced by a UNIQUE index.
- **Money**: integer minor units in the DB, `BigDecimal` scale 2 in JSON. Inputs with more than 2 decimals are rejected (400).
- **Pagination**: `limit` default 50, max 100 (`limit=1000` -> 400), `offset` default 0. Response: `{items, limit, offset, has_more}`.
- **Same transaction twice on one invoice**: `409`.
- **Mixed currency**: `409`.
- **Rollups**: tax grouped by (name, rate), fees grouped by name.

## Example curls

```
B=http://localhost:8080
S=fixtures/task-b/seed-payloads.json
post() { jq ".$1" $S | curl -s -X POST $B/transactions -H 'Content-Type: application/json' -d @-; }

post flight; post hotel; post rail; post trip_fee; post agent_call_fee   # create each type (201)

# duplicate: second POST -> 409 with existing id; only one row
post flight; post duplicate_flight
curl -s "$B/transactions?limit=100" | jq '[.items[] | select(.external_id=="flight-TXL-LHR-2026-03-11")] | length'   # 1

curl -s $B/transactions/<id>            # get
curl -s "$B/transactions?limit=10&offset=0"   # list

# invoice (single or trip)
curl -s -X POST $B/invoices -H 'Content-Type: application/json' -d '{"transaction_id":"<id>"}'
curl -s -X POST $B/invoices -H 'Content-Type: application/json' -d '{"transaction_ids":["<flight>","<hotel>","<rail>","<trip_fee>","<agent_call_fee>"]}'
curl -s $B/invoices/<inv_id>
curl -s "$B/invoices?limit=10"

# patch / delete transaction (409 once invoiced)
curl -s -X PATCH $B/transactions/<id> -H 'Content-Type: application/json' -d '{"total": 230.50}'
curl -s -X DELETE -i $B/transactions/<id>

# patch (replace set + rebuild snapshot) / delete invoice
curl -s -X PATCH $B/invoices/<inv_id> -H 'Content-Type: application/json' -d '{"transaction_ids":["<id1>","<id2>"]}'
curl -s -X DELETE -i $B/invoices/<inv_id>
```

`GET /health` returns `{"status":"ok"}`.
