# Using msgforge in your organisation

This is for a team that wants msgforge in its own environments: testers in the console, pipelines on the command line, one shared instance per test environment. It assumes you have tried the [quick start](../examples/quickstart/).

## Keep msgforge as it is, and your own things next to it

Do not change msgforge's code. Take a released version, and when you want something newer, move to the next version. Everything that is yours goes into a repository of your own:

```
msgforge-config/
  Dockerfile             your image: msgforge plus your packs and configuration
  msgforge.json          connections and scenarios; every password is ${MSGFORGE_…}
  packs/
    orders/
      pack.yml
      templates/
        order-north.json
        order-south.json
  env/
    test.env             addresses per environment, no passwords
    staging.env
  plugins/               only if you write your own connection types or generators
  compose.yaml           for testers' own machines (optional)
  .gitlab-ci.yml         or .github/workflows/…, Jenkinsfile: your pipelines
```

`msgforge.json` names everything that differs between environments as a variable, so one file serves all of them:

```json
{"connections": [
  {"id": "kafka", "name": "Kafka", "type": "kafka", "destinations": ["orders.in", "orders.north", "orders.south"],
   "settings": {"bootstrap": "${MSGFORGE_KAFKA_BOOTSTRAP}", "user": "${MSGFORGE_KAFKA_USER}", "password": "${MSGFORGE_KAFKA_PASSWORD}"}}
 ],
 "scenarios": []}
```

```bash
# env/test.env: what differs per environment. Passwords come from your secret store, not from this file.
MSGFORGE_KAFKA_BOOTSTRAP=kafka-1.test.example:9092,kafka-2.test.example:9092
MSGFORGE_KAFKA_USER=msgforge-test
```

The scenarios are easiest to make in the console against a test environment (see [Testers](#testers) below) and then commit with the rest of `msgforge.json`. A variable msgforge may read has to start with `MSGFORGE_`, or be listed in `MSGFORGE_ENV_ALLOW`.

### Your image

Build msgforge's own image from the release you use (`docker build -t registry.example.internal/msgforge:0.1.0 .` in a checkout of that tag) and push it to your registry once per version. Your image adds your files on top:

```dockerfile
FROM registry.example.internal/msgforge:0.1.0
# Owned by root, so msgforge can use these packs but not change them: changes come through Git.
COPY packs /app/team-packs
# Copied to /data/msgforge.json on a volume that does not have one yet.
COPY msgforge.json /app/msgforge.json
ENV MSGFORGE_PACKS=/data/packs,/app/team-packs
```

New packs made in the console go to `/data/packs`, the first folder; your packs from Git are shown as read-only. Plugin jars go to `/app/plugins` (`COPY plugins/*.jar /app/plugins/`). Upgrading msgforge is then a change to the `FROM` line.

`/app/msgforge.json` is copied only onto a `/data` that has no `msgforge.json`. With a persistent volume, what the console saves there is kept, and a newer file from Git does not replace it until you remove the one on the volume. With no persistent volume (Kubernetes' `emptyDir`, or `docker run` without `-v`), every start begins from the Git version, and changes made in the console last until the next restart. For a shared instance whose content comes from Git, the second is usually what you want.

## Without internet access

msgforge needs nothing from the internet at run time. Building it does: Gradle, the libraries from Maven Central, and the `eclipse-temurin` images.

- **Release zip.** `./gradlew dist` on a machine that has access gives `build/distributions/msgforge-<version>.zip`, which holds everything: the application, the plugins, the bundled packs and the start scripts. Copy it in; it needs only Java 17 or newer.
- **Image.** Build the image outside and push it to your internal registry (or `docker save` and `docker load` it). Your own `Dockerfile` then starts `FROM` that internal name, as above.
- **Building inside.** Point Gradle at your Maven mirror (Artifactory, Nexus) in an init script in `~/.gradle/init.d/`, and mirror `eclipse-temurin:17-jdk` and `eclipse-temurin:17-jre` under the names the `Dockerfile` uses. This has not been tried here.

## A shared instance

The console has no login. Whoever reaches it can send messages with every saved connection and read the `MSGFORGE_*` variables those connections may name. So:

- Put it behind a reverse proxy that requires your single sign-on, for example [oauth2-proxy](https://oauth2-proxy.github.io/oauth2-proxy/) or your ingress controller's authentication, and do not expose msgforge's port any other way.
- Set `MSGFORGE_ALLOWED_HOSTS` to the name people open, for example `msgforge.test.example`. The proxy must pass the original `Host` header on; msgforge accepts a change from the browser only when its `Origin` matches that host.
- Use one instance per test environment, each with that environment's variables. One instance holds its runs in memory: run one replica.
- Make the shared packs read-only (in the image, or a read-only mount) and keep their content in Git. People change templates on their own machines and send a merge request; the next image brings the change.
- A pipeline that uses `--url` (see [ci.md](ci.md)) sends no credentials. Give it a way past the proxy that only your build agents can use, such as the internal Service address with that name in `MSGFORGE_ALLOWED_HOSTS`, or run that pipeline inside the cluster. Any pod in a cluster can reach a Service by default, so limit it to the build agents' namespace with a NetworkPolicy.

This guide has not been tried behind oauth2-proxy or any other single sign-on proxy. What has been checked is msgforge's side: a request with a host name that is not allowed gets 403.

## Kubernetes

A minimal set: your image with the packs inside, a Secret for the password, a Deployment and a Service. The proxy or ingress with sign-on in front of the Service is your platform's and is not shown.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: msgforge
type: Opaque
stringData:
  MSGFORGE_KAFKA_PASSWORD: change-me           # or from your secret manager
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: msgforge
spec:
  replicas: 1                                  # runs are kept in the process: one replica
  strategy:
    type: Recreate
  selector:
    matchLabels: {app: msgforge}
  template:
    metadata:
      labels: {app: msgforge}
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001                         # makes the emptyDir volumes writable for msgforge
      containers:
        - name: msgforge
          image: registry.example.internal/team/msgforge:2026.11.1
          ports:
            - containerPort: 8585
          env:
            - {name: MSGFORGE_ALLOWED_HOSTS, value: msgforge.test.example}
            - {name: MSGFORGE_KAFKA_BOOTSTRAP, value: "kafka-1.test.example:9092"}
            - {name: MSGFORGE_KAFKA_USER, value: msgforge-test}
          envFrom:
            - secretRef: {name: msgforge}
          readinessProbe:
            httpGet:
              path: /api/types
              port: 8585
              httpHeaders:                     # without it the probe's Host is the pod's IP, which msgforge refuses
                - {name: Host, value: localhost}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: [ALL]}
          volumeMounts:
            - {name: data, mountPath: /data}
            - {name: tmp, mountPath: /tmp}
      volumes:
        - {name: data, emptyDir: {}}           # starts from the image's msgforge.json every time; a PersistentVolumeClaim keeps console changes
        - {name: tmp, emptyDir: {}}
---
apiVersion: v1
kind: Service
metadata:
  name: msgforge
spec:
  selector: {app: msgforge}
  ports:
    - port: 80
      targetPort: 8585
```

Packs from a ConfigMap instead of the image: a ConfigMap is flat, so give each file its path with `items`, and mount it read-only. `msgforge.json` can come from a ConfigMap the same way.

```yaml
      # in the container
          env:
            - {name: MSGFORGE_PACKS, value: "/data/packs,/config/packs"}
          volumeMounts:
            - {name: packs, mountPath: /config/packs, readOnly: true}
            - {name: config, mountPath: /app/msgforge.json, subPath: msgforge.json, readOnly: true}
      # in the pod
      volumes:
        - name: packs
          configMap:
            name: msgforge-packs                # kubectl create configmap msgforge-packs --from-file=orders.pack.yml=packs/orders/pack.yml …
            items:
              - {key: orders.pack.yml, path: orders/pack.yml}
              - {key: orders.order-north.json, path: orders/templates/order-north.json}
        - name: config
          configMap:
            name: msgforge-config
```

A ConfigMap is limited to 1 MiB and needs an entry per file, so packs in the image scale better.

What has been checked: the image and the settings this Deployment relies on, with `docker run` on Docker Desktop: user 10001, a read-only root file system with `/tmp` and `/data` as empty writable mounts, packs and `msgforge.json` mounted read-only, the configuration copied to `/data` on start, a scenario run with `PASS`; `/api/types` answers 200 with `Host: localhost` and 403 with a pod-style `Host: 10.1.2.3:8585`; a pack folder laid out the way Kubernetes mounts a ConfigMap (`..data` and symbolic links) is read correctly. These manifests have not been applied to a Kubernetes cluster.

## Testers

**The shared console.** Open the instance's address and sign in through the proxy. Pick a template or a scenario and send; the run shows each check. The team's packs are read-only there: to change a template, work locally (below) and send the change to the configuration repository. Connections and scenarios are shared by everyone who uses that instance.

**Your own machine.** With Docker, clone the configuration repository and start your team's image with a local volume:

```bash
docker run --rm -p 127.0.0.1:8585:8585 -v msgforge-data:/data --env-file env/test.env \
  -e MSGFORGE_KAFKA_PASSWORD registry.example.internal/team/msgforge:2026.11.1
```

or, to edit packs in the repository directly, the release zip: `MSGFORGE_PACKS=$PWD/packs MSGFORGE_CONFIG=$PWD/msgforge.json msgforge-0.1.0/bin/msgforge`. Changes made in the console are then files in your checkout, to commit like any other. Keep passwords as `${MSGFORGE_…}` references in the console too: a password typed in is saved into `msgforge.json`.

**Pipelines.** `msgforge run <scenario> --report msgforge.xml` with the same configuration, or `--url` against the shared instance; [ci.md](ci.md) has the details and examples.
