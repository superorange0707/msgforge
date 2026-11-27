# Load runs, invalid messages, watched destinations and reports

A run can be more than "so many messages at such a rate": it can ramp up, run for a time, send from several connections at once, make a share of its messages invalid on purpose, watch a reject queue, and leave a report behind. This page describes each part, the rules it follows, and at the end what has been tested and what has not.

Everything here is optional. A run or scenario that uses none of it behaves as it did before.

## The plan

| Setting | Console | API (`load.…`) | Meaning |
|---|---|---|---|
| Messages | Load: Messages | `count` | how many to send. `0` means no count: the run then needs a duration, or has to be allowed to run until stopped. |
| Rate | Load: Per second | `rate` | the target rate. `0` sends as fast as possible. |
| Ramp-up | Load: Ramp-up (s) | `rampUpSeconds` | the rate rises in a straight line from nothing to the target over this long. Needs a rate. |
| Duration | Load: Duration (s) | `durationSeconds` | stop sending after this long. With a count as well, whichever comes first ends the run. |
| Senders | Load: Senders (empty is 0, the run decides) | `senders` | how many send at once, each over a connection of its own. Default 1. `0` lets the run decide. |
| Until stopped | Load › More: Until stopped | `unlimited: true` | a run with neither a count nor a duration. It has to say so; it ends when it is cancelled. |
| Seed | Load: Seed | `seed` | see [Seeds](#seeds) |

Limits: a count up to 1,000,000,000, a duration up to 7 days, a ramp-up up to 24 hours, up to 64 senders.

**Pacing.** The plan says how many messages are due by each moment. Senders take the next message, in order, when it is due, and send it in parallel; so the rate holds across senders, and four senders at 200 a second send 200 a second, not 800. When the run cannot keep up, it does not make up for it later with a burst: at most one second's worth of messages is ever owed, and the shortfall shows as an actual rate below the target.

**Senders.** A connection type's sender is not assumed to be safe to share, so each sender thread opens its own connection to every target. With `senders: 0` and a rate, the run starts with one and adds one each second while messages are due before a sender is free to take them, up to 16. With `senders: 0` and no rate it uses up to four. If the broker refuses another connection, the run carries on with the senders it has.

**Memory.** A run keeps no state per message unless it verifies arrivals, so a long or unlimited run stays the same size. What it measures is a set of counters, a histogram of send times, and a series over time that holds 600 points: when it is full, neighbouring points are merged and each then covers twice as long (1 s, 2 s, 4 s …). A run that verifies arrivals keeps every message until it has arrived and is limited to 20,000 messages, as before; it therefore needs a count.

**The Sent list** shows a run's first 50 messages and after that at most 50 a second, so for a fast run it is a sample.

## When a send fails

One refused message is a result; a broker that is gone is not worth a million more attempts. So:

- A send that fails is counted, its error is kept, and the run goes on. Up to 20 different errors are kept with their counts, each cut at 300 characters; further ones are counted together.
- The run ends as `FAILED` when the connection is plainly gone:
  - 100 sends in a row failed, or
  - every send failed for 10 seconds (and at least two did), or
  - the run reached its end and not one message went out.
- A run that completed with some failed sends is `DONE` with the verdict `FAIL`, also when it had nothing to verify. The command line exits 1 for it.
- A message that cannot be made (a placeholder that fails, an error in a generator plugin) still ends the run at once: the next one would fail the same way.

Connections are opened and checked before the first message, as before, so a broker that is down fails the run up front. A send that hangs inside a broker's client still blocks its sender until the client's own timeout.

## What a run measures

`GET /api/runs` (the list the console polls) gives per run:

| Field | |
|---|---|
| `sent`, `failed`, `invalid` | messages that went out, messages a send failed for, and of those sent the ones made invalid on purpose |
| `targetRate` | what the plan asks for at this moment; null when the run sends as fast as it can |
| `actualRate` | messages a second over the last two seconds; once the run has ended, its average |
| `elapsedMs` | how long it has been sending, without the wait for arrivals |
| `latency` | `p50`, `p95`, `p99`, `max` in milliseconds: the time inside the connection type's `send`. From a histogram, right to about 6%. |
| `errors` | `message`, `count`, `lastAt` for each different send error |
| `senders`, `seed`, `rate`, `rampUpSeconds`, `durationSeconds` | the plan as it ran |
| `faults` | invalid messages by kind |
| `watched` | per watched destination: `count`, `rate` (arrivals a second over the last 10 seconds), `lastAt` |

`GET /api/runs/{id}` adds the `series` (points of `t`, `seconds`, `target`, `sent`, `failed`, `invalid`; `sent / seconds` is the rate) and `watches` with their tables.

In the console the run started in Load is under the message while it goes and after: sent, rate, send time p95, failed, invalid, the rate over time against its target, and a bar of where its messages ended up (delivered, rejected as expected, valid but rejected, accepted although invalid, misrouted, missing). A click on its name, or on any run under Reports, opens it in full: all the numbers, the chart, the checks, the errors, the watched destinations, the messages still kept, and the report downloads, updated every second. `http://localhost:8585/#run=<id>` opens it directly.

## Invalid messages

A run can make a share of its messages invalid to see what the system under test does with them.

```json
"invalid": {"percent": 5, "kinds": ["empty", "truncated", "field-removed"],
            "expect": {"connection": "broker", "destination": "ORDERS.REJECTED"}}
```

| Kind | What the message becomes |
|---|---|
| `empty` | no body |
| `garbage` | a line of text that is no format at all |
| `truncated` | the rendered message cut off at a random place |
| `field-removed` | JSON: one field taken out of an object. XML: one element or attribute removed. |
| `type-changed` | JSON: a text value becomes a number, any other value becomes text. XML: a value that reads as a number, boolean or date becomes letters; without such a value, a simple element gets a child element. |

`percent` is a probability per message, not an exact share: 5% of 1,000 is about 50. `kinds` left out means all five, picked evenly. The headers are sent as rendered.

`field-removed` and `type-changed` parse the message, so they need JSON or XML. A run that asks for them with a template that is neither (or that does not parse, or has a DOCTYPE) is refused before it starts, with the template's name. The faulted message is written out again by the parser, so its layout can differ from the template's (JSON numbers keep their text).

In a run that verifies, `field-removed` and `type-changed` leave alone the field the message is recognised by (the one holding its token, and anything that contains it). A system that accepts such a message forwards it with that field intact, and the run reports it as accepted although invalid; with the field gone it could only have been reported as missing. A template in which that field is the only one is refused for these two kinds before the run starts. `empty`, `garbage` and `truncated` cannot keep the token whatever is done; those are recognised by a header or by their correlation id, as described below.

Invalid messages are counted apart (`invalid`, and `faults` by kind), and the Sent list shows which fault a message was given.

### Verifying them

An invalid message is expected to be rejected. In a run that verifies:

- With `invalid.expect` (console: Check rejects in Load, and the queue it then asks for), invalid messages are expected on that destination.
- Without it they are expected nowhere. The run then waits its whole timeout, because their staying away is the result.

Each part that verifies gets a second check for its invalid messages (`"invalid": true`):

| | Valid messages | Invalid messages |
|---|---|---|
| `arrived` | on the part's expected destination | rejected as expected: on the invalid destination, or nowhere when none is named |
| `accepted` | | accepted although invalid: they arrived where the valid ones go |
| `unrecognised` | | accepted, unrecognisable: see below |
| `rejected` | valid but rejected: they arrived where the invalid ones go | |
| `misrouted` | on another watched destination | on another watched destination |
| `missing` | nowhere | nowhere, when a destination was named |

The verdict is `PASS` when in every check `arrived` equals `sent` and no send failed.

"Nowhere" can only be said of a message that would have been recognised had it arrived. An emptied, replaced or cut-off message that went out with its token in no header has nothing left to know it by, and a system that lets it through delivers something nobody can place. So a run whose invalid messages are expected nowhere counts the arrivals it cannot place on the destinations where its valid messages are expected, and for each of them one such invalid message is not credited as rejected: it is reported as `unrecognised` ("accepted, unrecognisable") and the check fails. A run cannot tell these from somebody else's traffic, so it also fails when other senders use that destination during the run; name a destination for the invalid messages (`invalid.expect`), put the token in a header, or keep the destination to the run. A second copy of a message that has already arrived is not counted.

### How an arrival is recognised

An emptied or cut-off message no longer carries its token in its body. So an arrival is matched to a sent message by, in this order:

1. the token in its body (as before),
2. the token in one of its header values or in its correlation id,
3. its correlation id being the id the transport gave the sent message.

The third is the usual request and reply convention on JMS and IBM MQ: a reply's correlation id is the request's message id. It needs nothing set up, but the transport has to report an id for what it sends (see the table in [Tested](#tested-and-not-tested)). The second works on any transport that keeps headers: put the token in a header, `"headers": {"ref": "{{ref}}"}` with `{{ref=uuid}}` in the template and `"match": "{{ref}}"`, and have the system under test keep that header on what it rejects.

If the system under test does neither, a rejected message cannot be told from any other, and the invalid check reports them as missing.

## Seeds

Every run has a seed, shown with the run and in its reports. Given one (`"seed": 42`, Seed in Load, `--seed`), a run makes the same bodies and the same faults for the same message numbers, whatever the number of senders: `{{int}}`, `{{decimal}}`, `{{pick}}`, `{{digits}}`, `{{alnum}}`, pack fields built from them, which messages are faulted, with which kind, and where.

Not repeated, on purpose: `{{uuid}}` and `{{id}}`. An id is what a message is recognised by when it arrives. If a seed repeated it, a message the last run left on a queue would pass for an arrival of this run, and an id drawn from a seed could be guessed. They come from the system's secure random source in every run, seeded or not. So two runs with one seed differ in their ids and in nothing else that is random; a message cut off inside its id is cut at the same place.

Also not repeatable: `{{now}}` and `{{today}}`, generators from plugins, which sender sends which message and so the order in which messages reach the broker, and anything about timing. A seed above 2^53 loses digits in the console (a browser's numbers); the API and the command line take any 64-bit number.

## Raw

Raw in Send (API: `"raw": true`) makes the message editable, starting from the sample shown, and sends the text and the headers exactly as typed. No placeholder is filled in, in the body or in a header, and the field controls do not apply. Over the API a raw message needs no pack and no template:

```bash
curl -X POST localhost:8585/api/runs -H 'content-type: application/json' \
  -d '{"raw":true,"body":"{{not a placeholder}}","headers":{"kind":"probe"},"connection":"dry-run","destination":"DEMO.QUEUE","count":1}'
```

A raw run cannot verify arrivals: every copy is the same text. In the lists of runs, messages and reports its template is `raw`, whichever template was open when the text was typed.

## Watching other destinations

A scenario, or a run over the API, can listen on further destinations (a reject, dead-letter or error queue) and sort what arrives there:

```json
"watches": [{"connection": "broker", "destination": "ORDERS.REJECTED",
             "buckets": [{"pointer": "/reason"}, {"name": "kind", "regex": "\"type\":\"(\\w+)\""}, {"header": "origin"}]}]
```

A bucket pulls one value out of each arrival: `pointer` is a JSON pointer into the body, `regex` a regular expression whose first group is the value (the whole match when it has no group), `header` the name of a header. `name` titles its table; without one the expression does. In the console a bucket is one text: `/reason`, `header:origin`, or anything else as a regular expression.

Per watched destination the run reports the count, the rate over the last 10 seconds, the time of the last arrival, a table per bucket, and the newest 50 arrivals with their bucket values, the number of the sent message they were recognised as (in a run that verifies), and their size. Destinations a run expects arrivals on are counted the same way, without buckets.

**How long a run listens.** Where a run expects arrivals it knows when it is done: everything has arrived. On a destination it only watches, nobody can say when the last reject has come. So a run that watches a destination it expects nothing on goes on listening after its last send for its whole timeout (`timeoutSeconds`, 10 unless set; it shows as `VERIFYING` meanwhile, and cancelling ends the wait). Set the timeout to how long the system under test may take. A run whose watched destinations are all ones it expects arrivals on ends as soon as everything has arrived, as before. Before this, a watching run closed its receiver right after the last send and missed whatever was still on its way.

**What a receiver takes when it closes.** A receiver that is closed while the broker has already handed it messages must not lose them:

| Type | At close |
|---|---|
| RabbitMQ | each message is acknowledged after it has been handed over (at most 200 are sent ahead), so what was sent ahead and not handed over goes back to the queue. Until this change messages were acknowledged automatically and those were lost. |
| IBM MQ, AMQP 1.0 (JMS) | a message is acknowledged when the listener returns, and closing waits for the listener; what the client holds unconsumed is released by the provider. With IBM MQ read-ahead on a non-persistent queue, read-ahead messages can be discarded at close: that is the queue's setting, not msgforge's. |
| Amazon SQS | a batch is handed over and then deleted. A close in between leaves messages undeleted; they reappear after the visibility timeout, so a message can be seen twice, not lost. |
| Kafka, Pulsar, NATS, MQTT, Dry run | they read copies; nothing is taken from anybody. What arrives after the close is simply not seen. |

None of this was run against the real brokers here; the RabbitMQ change is tested against a mocked channel (acknowledged after handing over, in order).

What arrives is somebody else's data, and a bucket expression is typed by a user. Neither is trusted:

- Bodies are not kept, apart from the newest three per destination, each cut at 2,000 characters, shown under "Newest bodies" in the console. They are never logged and never part of a report.
- A bucket value is cut at 80 characters, and a table holds 50 different values; further ones are counted as `(other)`.
- A regular expression is at most 200 characters, sees the first 64 KB of a body, and gets 50 ms per arrival. One that runs out of time counts as `(too slow)`, and after three times in a row it is not asked again until the watch is reset. JSON is parsed up to 1 MB.
- At most 10 watched destinations per run and 5 buckets per destination.

**Receiving takes messages off a queue** on the connection types that consume (IBM MQ, AMQP 1.0, SQS, RabbitMQ queues; see the README's table). Watch queues that nothing else reads.

### Monitors

A monitor is the same thing outside a run: an always-on view of a reject queue, say. The console calls them reject queues: the Rejects page shows them, and Settings › Reject queues (or the empty Rejects page) adds one: an environment that can receive, a queue, what to group by, and a name if you like. It counts until it is stopped; "reset" under the count starts the count again, while the total beside it runs on. Up to 10 at a time.

Each arrival in its list says which run it belonged to: a monitor shares the queue's subscription with the runs that listen there, and when one of them recognises an arrival as a message it sent, the list shows the run and what it made of it: "invalid, expected" for a reject that was meant to be one, "was valid" for a valid message that should not have been rejected, "not ours" for everything no run sent. (API: `run` and `outcome` on each entry of `recent`; `outcome` is `valid-expected`, `invalid-expected`, `valid-unexpected` or `invalid-unexpected`.)

**Saved monitors.** A monitor can be kept in `msgforge.json`, in a `monitors` section beside `connections` and `scenarios`, and then starts with the console:

```json
"monitors": [
  {"id": "rejects", "name": "Rejected", "connection": "broker", "destination": "${MSGFORGE_REJECT_QUEUE}",
   "buckets": [{"pointer": "/reason"}, {"header": "origin"}]}
]
```

`connection` is a connection's id; `id`, `name` and `buckets` may be left out. `"start": false` keeps a saved monitor listed but stopped until somebody starts it (from 0.3.1): a monitor takes what arrives off its queue, which on a queue other people test on is theirs to decide. In the console, the Saved switch (when adding a reject queue, and in its row under Settings › Reject queues) writes it there, and switching it off takes it out (a monitor that is listening goes on listening). Stopping is a different thing: a saved monitor that is stopped stays in the file and in the list, shown as stopped with what it counted, starts again with the next start of msgforge, and can be started again before that (Start, where Stop was). A monitor that is not saved is gone when it is stopped, and a stopped one that is taken out of the file is gone too. A file without a `monitors` section starts none. Counts are kept in memory and start from nothing after a restart.

**A monitor that cannot listen** does not keep msgforge from starting. Saved monitors start after the console is up, one after the other on a thread of their own, and one whose broker does not answer (or whose connection is missing, or whose variable is not set) is listed as down with the reason. It tries again after 5, 10, 20, 40 and then every 60 seconds until it is up or stopped. The same happens when receiving breaks later: the monitor goes down, keeps what it counted, and comes back by itself. An entry that only an edit can mend (a bucket that is not a regular expression, no destination) is listed as down with what is wrong and is not retried. A monitor started by hand that cannot listen is refused with the reason and not created.

**A monitor whose credentials are refused stops.** Trying a wrong password again changes nothing, and enough failed logins lock the account. So when the broker refuses the credentials, or the right to the destination, the monitor does not book another attempt: it is listed as stopped, with "The credentials were refused", the broker's reason, and "It will not try again until it is started." It stays in `msgforge.json` if it was saved. Mend the connection, then start it again (Start in its row, `POST /api/monitors/{id}/start`); a restart of msgforge also tries once. This holds when it starts and when it is refused later, while listening. Every other failure keeps the waits above.

What counts as a refusal, by the client's exception type where it has one and by its reason otherwise:

| Transport | Recognised by |
|---|---|
| IBM MQ, JMS | `JMSSecurityException`; reason 2035 `MQRC_NOT_AUTHORIZED` |
| AMQP 1.0 | `JMSSecurityException` (a failed SASL login); `amqp:unauthorized-access` |
| RabbitMQ | `AuthenticationFailureException`; `ACCESS_REFUSED`, reply code 403 |
| Kafka | `AuthenticationException` and `AuthorizationException` with their subclasses (`SaslAuthenticationException`, `TopicAuthorizationException`, …) |
| MQTT | `MqttSecurityException`; "bad user name or password", "not authorized" |
| NATS | `AuthenticationException`; "Authorization Violation" |
| Pulsar | `PulsarClientException.AuthenticationException` and `.AuthorizationException` |
| SQS | the error codes `AccessDenied`, `InvalidClientTokenId`, `SignatureDoesNotMatch`, `UnrecognizedClientException` |

A plugin of your own is covered when its client throws one of these or its message carries one of these reasons. A connection that merely drops during login (RabbitMQ's "possibly caused by authentication failure") is not taken for a refusal and is tried again. A run is not affected: it opens its connections once and fails with the reason.

`msgforge run` and `msgforge list` start no monitors: they have no console to show them, and a pipeline should not take messages off a queue that somebody is watching. With `--url` the run happens on the server, beside its monitors.

**Sharing a destination.** Receiving takes messages off a queue, so two consumers on one queue would each get some of the messages. msgforge therefore opens one subscription per connection and destination, however many listen, and hands every arrival to each of them. A monitor on the reject queue goes on counting everything while a run expects its invalid messages there; the run matches the same arrivals; and neither is refused. The subscription closes when its last listener has gone: a run that ends leaves a monitor's subscription open, and a monitor that is stopped does not cut off a run.

- This is within one msgforge. Another process on the same queue (a second msgforge, `msgforge run` without `--url`, any other consumer) still competes for the messages.
- A listener that joins uses the subscription as it was opened: an edit to the connection reaches it when the last listener has left and the next one opens a new one.
- When receiving breaks, every listener is told: a run ends as failed, a monitor goes down and retries.
- A monitor sees everything on its destination, a run's messages included; that is the point of it.
- Two runs that both *expect* arrivals on the same destination are still not allowed at once: the second is refused, with the first's name. Each would see the other's messages arrive, and a match expression that is not unique across runs (a counter, say) would take one for its own. A run that only watches a destination (`watches`) joins freely.

API: `GET|POST /api/monitors` (`connection`, `destination`, `buckets`, `name`, `save`), `POST /api/monitors/{id}/reset`, `PUT|DELETE /api/monitors/{id}/saved` (into the file, out of it), `DELETE /api/monitors/{id}` (stop), `POST /api/monitors/{id}/start`. Each monitor has a `state` (`starting`, `up`, `down`, `stopped`), and when down an `error` and the time of its next try in `retryAt`.

Starting a run and starting a monitor each check and register under one lock of their own (two runs that expect on one destination; two monitors on one destination), so two starts at the same moment cannot both pass. A run and a monitor do not exclude each other, so there is nothing to check between the two.

## Trying it without a broker

The Dry run connection has an optional Route setting that stands in for a system under test:

```
ORDERS.IN > ORDERS.OUT, invalid > ORDERS.REJECTED
```

What is sent to `ORDERS.IN` is delivered on `ORDERS.OUT` when it is well-formed JSON or XML. Otherwise a reject, `{"reason":"empty"}` (or `not-json`, `not-xml`, `not-a-format`), is delivered on `ORDERS.REJECTED` with the original's headers, a `reason` header, and the original's message id as its correlation id. Several routes are separated by semicolons; without `, invalid > …` what is not well-formed is dropped.

"Valid" here means well-formed and nothing more, so a message with a field removed passes, and a run that sends `field-removed` through it reports those as accepted although invalid. That is what a system without a schema does, too.

## Reports

A run can be downloaded from its view in the console, or from the API, at any time:

| | |
|---|---|
| `GET /api/runs/{id}/report?format=html` | one page with its chart drawn in. It loads nothing and runs no script, has a dark and a light look, and prints as it looks. |
| `…?format=json` | everything: the run, the series, the watched destinations' tables |
| `…?format=csv` | the summary as `name,value`, then the invalid kinds, checks, errors and bucket tables, each as a block with its own header row |
| `…?format=series` | the series over time: `t_seconds,seconds,target_rate,sent,failed,invalid,sent_rate` |

A report holds names, counts, errors and bucket values, never a message body. Text that came from outside is escaped in the HTML, and in the CSV a cell that a spreadsheet would take for a formula gets a `'` in front. Reports are served as downloads, not as pages of the console.

With `MSGFORGE_REPORTS=/some/folder` every run that ends is saved there in all four formats, as `msgforge-<UTC start>-<run id>-<label>.<format>`. The folder keeps the newest `MSGFORGE_REPORTS_KEEP` runs (50 unless set) and removes older ones; it only ever removes files with names of that form. A run is shown as ended only after its reports have been written, so a script that waits for a run to end can read them at once. The Reports page lists them under the runs msgforge still has in memory (`GET /api/reports`, `GET /api/reports/{file}`).

**One folder per msgforge.** The retention removes the oldest files with names of that form whoever wrote them, so two instances that share a folder would delete each other's reports. Give each its own. (Telling the files apart would take an identity that survives a restart, which a container does not have; a per-process memory of what was written would let the folder grow with every restart.) The folder is written by msgforge with the rights it runs with; reports are not cleaned of anything a label or a destination name says, so treat the folder like the console.

From the command line, `--save <folder>` writes the four files for that run (`msgforge-<run id>.html` and so on), with or without `--url`. `--report` still writes JUnit XML.

## Metrics

`GET /metrics` answers in the Prometheus text format:

| Metric | |
|---|---|
| `msgforge_messages_sent_total`, `…_failed_total`, `…_invalid_total` | since msgforge started |
| `msgforge_runs_total{outcome}` | runs that ended: `pass`, `fail` (verdicts), `done` (nothing to verify), `failed`, `cancelled` |
| `msgforge_runs_active`, `msgforge_senders` | runs going on now, and their sender threads |
| `msgforge_send_rate`, `msgforge_target_rate` | messages a second now, over all active runs: actual and planned |
| `msgforge_arrivals_total{connection,destination,source}` | arrivals on a destination a run watched (`source="run"`, since start) or a monitor is on (`source="monitor"`, since its reset; the series ends when the monitor stops) |

Connections are labelled by name. Two connections with the same name are one series, their counts added up: the same labels twice would make Prometheus refuse the whole scrape.

It holds numbers and names only: no message content, no connection setting, nothing read from the environment. A destination is labelled as it was written, so `${MSGFORGE_QUEUE}` stays that text; label values are escaped, cut at 200 characters, and capped at 200 destinations (further ones are counted as `(other)`). The endpoint has no login of its own. It is under the same rule as the rest: only requests addressed to `localhost` or a name in `MSGFORGE_ALLOWED_HOSTS` are answered, so a Prometheus on another machine has to scrape a name that is allowed.

```yaml
scrape_configs:
  - job_name: msgforge
    static_configs: [{targets: ["localhost:8585"]}]
```

## Names from the environment

A destination in a run, a scenario (targets, expectations, watches, the invalid destination) or a monitor may contain `${MSGFORGE_NAME}`, under the same rule as a connection's settings (`MSGFORGE_*`, or a name in `MSGFORGE_ENV_ALLOW`). It is filled in when the run starts, so one `msgforge.json` serves several environments. A destination may also use the connection's own `vars` (`${Input}`), filled in first, so one msgforge can hold several environments whose queues are named differently (README, "Several environments in one msgforge"). The console, the reports and the metrics show the text as written, except that the console shows a queue by the name it stands for when every variable in it is listed in `MSGFORGE_SHOW` (see the README). Unlike a setting, the resolved name is handed to the broker as a destination and may come back in a broker's error message, so a variable used for a destination should hold a name and nothing secret.

## A scenario with everything

As it is saved in `msgforge.json` (`"scenarios": [ … ]`) and accepted by `POST /api/scenarios`:

```json
{
  "name": "Order soak",
  "parts": [
    {"pack": "demo", "template": "order.json", "weight": 3,
     "expect": {"connection": "broker", "destination": "${MSGFORGE_ORDERS_OUT}"}},
    {"pack": "demo", "template": "shipment.xml", "weight": 1, "vars": {"carrier": "Blue Harbor"},
     "expect": {"connection": "broker", "destination": "SHIPMENTS.OUT"}}
  ],
  "targets": [{"connection": "broker", "destination": "ORDERS.IN"}],
  "mode": "spread",
  "count": 6000,
  "rate": 300,
  "headers": {"ref": "{{ref}}", "origin": "{{pick edge-1 edge-2}}"},
  "timeoutSeconds": 30,
  "match": "{{ref}}",
  "load": {"rampUpSeconds": 10, "durationSeconds": 120, "senders": 3, "unlimited": false},
  "invalid": {"percent": 5, "kinds": ["empty", "truncated", "field-removed"],
              "expect": {"connection": "broker", "destination": "ORDERS.REJECTED"}},
  "seed": 2026,
  "watches": [
    {"connection": "broker", "destination": "ORDERS.REJECTED",
     "buckets": [{"pointer": "/reason"}, {"name": "kind", "regex": "\"type\":\"(\\w+)\""}, {"header": "origin"}]},
    {"connection": "broker", "destination": "ORDERS.DEAD", "buckets": []}
  ]
}
```

`"match": "{{ref}}"` needs `{{ref=uuid}}` (or another binding named `ref`) in each template; leave `match` and the `ref` header out to match on the first `{{uuid}}` or `{{id}}` in the body and, for rejects, on the correlation id. `connection` is a connection's id. A send-only soak drops `expect`, `timeoutSeconds` and `match`, and may then set `"count": 0`. A run request (`POST /api/runs`) takes the same fields plus `label` and `raw`, and the single-template shorthand.

Changed in 0.3: a value in `vars` that contains `{{` is rendered for each message (`"vars": {"total": "{{decimal 5 50 2}}"}` narrows a range). Up to 0.2 every override was used as it stood. A request that needs `{{` to arrive as text has to put it in the template or send the message raw.

The whole file, with a monitor that is on all day and the scenario beside it:

```json
{
  "connections": [
    {"id": "broker", "name": "Test broker", "type": "ibm-mq",
     "settings": {"hosts": "${MSGFORGE_MQ_HOSTS}", "channel": "APP.SVRCONN", "user": "app", "password": "${MSGFORGE_MQ_PASSWORD}"},
     "destinations": ["ORDERS.IN", "ORDERS.REJECTED"]}
  ],
  "monitors": [
    {"id": "rejects", "name": "Rejected", "connection": "broker", "destination": "ORDERS.REJECTED",
     "buckets": [{"pointer": "/reason"}, {"header": "origin"}]}
  ],
  "scenarios": [ { "name": "Order soak", "…": "as above" } ]
}
```

The monitor and the scenario's `invalid.expect` and `watches` name the same queue; they share one subscription, so the panel keeps counting while the load test verifies.

## Command line

```bash
bin/msgforge run "Order soak" --duration 600 --ramp-up 30 --senders 0 --seed 2026 --invalid 2 --save build/soak
```

`--count`, `--rate`, `--duration`, `--ramp-up`, `--senders`, `--seed` and `--invalid` (a percentage) replace the scenario's own values for that run. `--env <connection>` (an id or a name, in any letter case) runs it on that environment: its first target's connection is replaced wherever the scenario names it, as the console does with a preset. The output adds a line with the average rate, the send times and the seed, and each different send error with its count.

## Tested and not tested

Tested automatically (`./gradlew test`), with Dry run and fakes unless said otherwise:

- Ramp-up, rate across senders, duration, count, unlimited, cancel; senders added while a run falls behind (a fake sender that takes 20 ms).
- The failure rule; the constant size of the statistics; percentiles against a known distribution.
- Every kind of fault on JSON and XML; refusals; the field holding the token never removed or changed, and accepted invalid messages all reported as accepted; the same seed giving the same bodies and faults with one sender and with four, with ids that differ.
- A run that only watches waiting its timeout and counting rejects that arrive after its last send, with and without a monitor on the same destination.
- A run's reports being in the folder at the moment it is shown as ended.
- The four outcomes for invalid messages, with a stand-in system under test, and the token in a header.
- **Recognising a reply by its correlation id over real JMS**, against the embedded ActiveMQ Artemis broker: `JmsSender` reports the `JMSMessageID`, `JmsReceiver` hands over properties and `JMSCorrelationID`, and empty replies are matched.
- Buckets, their limits, an expression that backtracks without end.
- Monitors: saved ones loaded from the file and started, one whose broker is down retrying until it is up, one whose receiving breaks coming back, entries with mistakes listed and not retried, saving and forgetting from the API with the file checked each time.
- The shared subscription, against a fake queue that gives each message to one consumer only: a monitor and a run on one destination both see every arrival through one subscription, the run's end does not close it, the monitor's stop does not cut the run off, the last listener closes it, a broken one tells everyone; a second run expecting on the same destination is refused.
- Reports in each format, escaping, the reports folder and its retention; metrics and label escaping; the API and the command line for all of it.

Checked by hand on the built release, on Dry run: a ramped run with four senders, invalid messages and a seed; a verified run with rejects expected on a reject destination; a monitor; a saved monitor that started with the console and counted the rejects of a verified run on the same destination; each report format; `/metrics`; the console in a browser, dark and light.

Not tested:

- **IBM MQ.** Correlation-id matching uses the same `JmsSender` and `JmsReceiver` as the Artemis test, and the IBM MQ client presents a reply's CorrelId, when it is a copy of the request's MsgId, as the same `ID:…` text as the request's `JMSMessageID`. That is how the client is documented to behave; it has not been run against a queue manager here. Nor has a load run.
- **Every plugin against a real broker.** What each plugin hands over on arrival and reports as a message id is in the table below; their tests use fakes or mocks, and the tests against real servers only run when one is named.
- Sharing a subscription on a real consuming queue (IBM MQ, SQS, a RabbitMQ queue). The sharing happens above the connection types and is tested with a fake that behaves like one; Dry run, where it was checked by hand, hands a copy to every listener anyway and would not show a split.
- Throughput. No number here is a benchmark; Dry run on one machine says nothing about a broker.
- Runs longer than a few minutes, and very high rates. The statistics are built to stay small, and that is tested by feeding them 2,000 seconds of data, not by running for a week.
- The console in browsers other than Chrome, and with a screen reader: controls have labels, switches and toggles say their state, menus work with the arrow keys and Escape, the charts have a text description, and everything can be reached by keyboard, but no assistive technology was run. Below about 860 px wide the console stacks into one column; that layout was not reviewed.

What each connection type gives a run:

| Type | Headers on arrival | Correlation id on arrival | Message id on send |
|---|---|---|---|
| Dry run | as sent, plus `JMSMessageID` | `JMSCorrelationID` or `correlation_id` header | generated |
| IBM MQ | JMS properties, `JMSCorrelationID`, `JMSType`, `JMSMessageID` | `JMSCorrelationID` | `JMSMessageID` |
| AMQP 1.0 | the same, through the same JMS classes | `JMSCorrelationID` | `JMSMessageID` |
| Kafka | record headers, and the record key as `key` | none | none |
| RabbitMQ | the headers table, and `content_type`, `content_encoding`, `correlation_id`, `message_id`, `reply_to`, `type`, `app_id` when set | the `correlation_id` property | none: the broker assigns no id |
| MQTT | version 5: user properties. 3.1.1: none | version 5: correlation data. 3.1.1: none | none |
| Amazon SQS | message attributes | none | none (SQS returns one, but no arrival could carry it back) |
| NATS | message headers | none | none |
| Apache Pulsar | message properties, and the key as `key` | none | none (the broker returns one, but no arrival could carry it back) |
| HTTP | cannot receive | | none |

Matching by correlation id needs both of the last two columns, so it works on Dry run, IBM MQ and AMQP 1.0. Everywhere else, and wherever the system under test does not copy the id into the correlation field, put the token in a header as described above. For Dry run this was run end to end; for AMQP 1.0 and MQTT against the embedded Artemis broker; for JMS in the core likewise. Kafka, RabbitMQ, SQS, NATS and Pulsar were checked against mocks or fakes of their client libraries only; that SQS returns all attributes when asked for `All` is an assumption from its documentation.

**AMQP 1.0, a known gap.** When the Qpid JMS client cannot turn an arriving AMQP message into a JMS message (annotations in the `x-opt-jms-*` family that do not hold what it expects, for one), it logs a warning and drops the message before any listener sees it. The message is off the queue and msgforge neither counts it nor reports an error, because nothing reaches it. This is the client's behaviour and older than this work; it was reported by a review and has not been reproduced or worked around here. If a watched AMQP queue counts fewer messages than were put on it, look for that warning in msgforge's log.
