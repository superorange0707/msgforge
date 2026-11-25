# Quick start: msgforge and Kafka in Docker

One command starts msgforge (built from this repository's `Dockerfile`) and a single-node Kafka. msgforge comes with a Kafka connection and a scenario, `Kafka loop`, that sends 20 demo orders to the topic `demo.orders` and checks that they arrive there.

```bash
cd examples/quickstart
docker compose up --build
```

The first build takes a few minutes. When the log says `msgforge is ready`, open http://localhost:8585 and press the play button next to `Kafka loop` under Scenarios in the sidebar. The run ends with `PASS`.

The same from the command line, in a second container next to the running one:

```bash
docker compose run --rm msgforge run "Kafka loop"     # exit code 0, 1 or 2; see docs/ci.md
docker compose run --rm msgforge list
```

To stop and remove everything, including what you changed in the console:

```bash
docker compose down -v
```

## What is where

| | |
|---|---|
| `msgforge.json` in this folder | the connections and scenarios msgforge starts with. It is copied to the `data` volume on the first start, and the console only ever changes the copy, so this file stays as it is. To start over from it, `docker compose down -v`. |
| packs | the bundled `demo` pack, copied to the volume on the first start |
| ports | only msgforge's, on `127.0.0.1:8585`. Kafka is reachable from the other containers as `kafka:9092` and is not published. Another host port: `MSGFORGE_PORT=8686 docker compose up`. |

## IBM MQ (optional)

The `ibm-mq` profile adds IBM's MQ developer image (`icr.io/ibm-messaging/mq`), and `msgforge.json` already has a connection to it and a scenario, `IBM MQ loop`. That image may only be run once you have accepted IBM's licence. This file does not accept it for you: read the licence, then set the variable yourself.

```bash
docker run --rm -e LICENSE=view icr.io/ibm-messaging/mq:9.4.5.0-r1   # shows the licence
export MQ_LICENSE=accept MQ_APP_PASSWORD=<a password of your choice>
docker compose --profile ibm-mq up --build
```

Without `MQ_LICENSE=accept` the `mq` container stops at once and says what to set. IBM publishes the image for x86-64 only; on Apple silicon Docker runs it emulated, which is slow.

## What has been checked

On Docker Desktop (Docker 28, arm64), by hand: `docker compose up --build`, the scenario run through the API, with `docker compose run … run "Kafka loop"` and with `--url` from outside, each `PASS`; the console's changes kept on the volume and this folder's `msgforge.json` unchanged; then `docker compose down -v`. The `ibm-mq` profile was only checked as far as `docker compose --profile ibm-mq config` and its licence message; the MQ image was not pulled or run.
