# Running scenarios in a pipeline

A scenario saved in the console can be run by a pipeline with `msgforge run`. It starts no web server, runs the scenario, prints every check and exits:

| Exit code | When |
|---|---|
| `0` | the run passed, or it had nothing to verify and finished |
| `1` | it finished and a check failed (`FAIL`) |
| `2` | anything else: no such scenario, a setting or `msgforge.json` that is wrong, a run that failed (a broker that is down) or was cancelled in the console, Java missing |

When the pipeline itself stops msgforge (a cancelled job), the process ends with the signal's code, 130 or 143, and writes no report.

```
$ bin/msgforge run "Kafka loop" --report build/msgforge.xml
Scenario Kafka loop: run 000954c0, 20 messages
  running, sent 0 of 20
  verifying, sent 20 of 20
  done, sent 20 of 20
  PASS order.json → Kafka / demo.orders: sent 20, arrived 20, misrouted 0, missing 0, median 5 ms, max 29 ms
PASS
Report written to /home/ci/work/build/msgforge.xml
```

| Option | |
|---|---|
| `--report <file>` | JUnit XML: one test suite per run, one test case per check, named `template → connection / destination`. A failed check is a `<failure>` with the arrived, misrouted and missing counts. A run without checks is one test case named after the scenario; a run that could not start or did not finish is one `<error>`. A relative path is taken from the folder you started `bin/msgforge` in. |
| `--url http://host:port` | run the scenario on a msgforge that is already running, instead of in this process. See [below](#a-deployed-msgforge). |
| `--count <n>`, `--rate <r>` | this many messages, at this many per second (0: as fast as possible), instead of the scenario's own numbers |

The scenario is named by its id or by its name; `bin/msgforge list` shows both. A name that two scenarios share has to be given as an id.

`run` and `list` read the same settings as the console: `msgforge.json`, the packs and plugins folders, `MSGFORGE_*` variables and `--msgforge.*` arguments, so a password written as `${MSGFORGE_KAFKA_PASSWORD}` in a connection comes from the pipeline's secrets. They never write to `msgforge.json`. Paths given in `MSGFORGE_CONFIG` and the like are taken from msgforge's own folder (the start script changes to it), so in a pipeline give them as absolute paths.

## Where the configuration comes from

Keep the `msgforge.json` with the connections and scenarios the pipeline uses, and your packs, in Git, as [deploy.md](deploy.md) describes. Make the scenarios in the console, against a test broker, and commit the file; passwords stay `${MSGFORGE_…}` references.

## Shell, with the release zip

Needs Java 17 or newer on the machine.

```bash
unzip -q msgforge-0.2.0.zip -d "$RUNNER_TEMP"     # or: ./gradlew dist in a checkout of msgforge
export MSGFORGE_CONFIG="$PWD/msgforge/msgforge.json" MSGFORGE_PACKS="$PWD/msgforge/packs"
export MSGFORGE_KAFKA_PASSWORD="…"                # from the pipeline's secrets

# 1. start the system under test (your own step), then
# 2. run the scenario; the exit code decides the step
"$RUNNER_TEMP/msgforge-0.2.0/bin/msgforge" run "Order routing" --report build/test-results/msgforge.xml
```

`$RUNNER_TEMP` stands for any scratch folder. The bundled `plugins/` folder, with Kafka, is used unless `MSGFORGE_PLUGINS` says otherwise.

## Shell, with Docker

The image's arguments go to msgforge, so `run` works the same in a container. With Compose, a second container next to the system under test:

```bash
docker compose up -d --build --wait
mkdir -p reports && chmod a+w reports             # the container runs as uid 10001
docker compose run --rm -v "$PWD/reports:/reports" msgforge run "Kafka loop" --report /reports/msgforge.xml
status=$?
docker compose down -v
exit $status
```

This is the [quick start](../examples/quickstart/) in a script. In the image the configuration is on `/data`; your own image can carry it, see [deploy.md](deploy.md).

## GitHub Actions

```yaml
jobs:
  routing:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4                   # your configuration repository
      - name: Start the system under test and msgforge
        run: docker compose up -d --build --wait
      - name: Run the scenario
        run: |
          mkdir -p reports && chmod a+w reports
          docker compose run --rm -v "$PWD/reports:/reports" msgforge run "Order routing" --report /reports/msgforge.xml
        env:
          MSGFORGE_KAFKA_PASSWORD: ${{ secrets.KAFKA_PASSWORD }}
      - name: Keep the report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: msgforge
          path: reports/
      - name: Stop
        if: always()
        run: docker compose down -v
```

For the variable to reach the container, the `msgforge` service in your compose file lists it under `environment:` (`MSGFORGE_KAFKA_PASSWORD: ${MSGFORGE_KAFKA_PASSWORD}`). To show the report as test results, give `reports/msgforge.xml` to a JUnit report action of your choice; GitLab (`artifacts: reports: junit:`) and Jenkins (`junit 'reports/*.xml'`) read the file directly.

## A deployed msgforge

When msgforge already runs in a test environment, next to the system under test, the pipeline only needs Java and the zip, not the brokers' addresses or passwords:

```bash
bin/msgforge run "Order routing" --url https://msgforge.test.example --report build/msgforge.xml
bin/msgforge list --url https://msgforge.test.example
```

The scenario, its connections and its packs are the server's. The run is started through the API (`POST /api/runs`, with `--count` and `--rate` applied), followed until it ends, and judged with the same exit codes. If the pipeline is cancelled while the run is going, the run on the server is cancelled too. msgforge answers only to host names in its `MSGFORGE_ALLOWED_HOSTS`; another name gets `answered 403` and exit code 2. A proxy that asks for a login in front of msgforge (see [deploy.md](deploy.md)) has to let the pipeline through, for example on an internal address with its own host name; `--url` sends no credentials.

The server keeps the 20 most recent finished runs. On a busy shared server where more than 20 other runs end while a pipeline follows one, the pipeline loses sight of it and exits 2.

## What has been checked

- Exit codes 0, 1 and 2 and the JUnit content, locally and with `--url`: automated tests (`CliTest`) with Dry run scenarios.
- The release zip's `bin/msgforge run … --report` from another folder, and `--url` against the quick start: by hand, on macOS.
- The "Shell, with Docker" commands: run as written against the quick start on Docker Desktop (arm64), exit code 0, report written.
- The GitHub Actions workflow above has **not** been run on GitHub, nor anything on GitLab or Jenkins. Its steps are the shell commands above. On a Linux runner the `chmod a+w` matters, Docker Desktop does not show that.
