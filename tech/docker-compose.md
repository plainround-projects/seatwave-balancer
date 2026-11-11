# Docker Compose and YAML: `compose.yaml`

## What it is

**Docker Compose** starts several containers together from one text file. Without it you would type a long `docker run` command for every container, with its ports, memory limit, network and mounted folders. With it you write all of that down once, in a file named `compose.yaml`, and run `docker compose up`. Docker Compose is a command-line tool; the file is its input. (For containers and images, read `docker.md` first.)

The file is written in **YAML**, a format built from indentation. Two spaces under a key mean "this belongs to that key". There are three building blocks:

- `key: value` pairs, which make a **mapping** (a set of named values).
- **Lists**: lines that start with `- `.
- **Comments**: `#` to the end of the line.

Tabs are not allowed for indentation. YAML also has **anchors**: `&name` after a value gives it a name, and `*name` later inserts a copy of it. The special key `<<` merges all the keys of a named mapping into the mapping that holds the `<<`. This file uses all three.

## Why this repository uses it

The lab needs eight long-running programs that must see each other on fixed addresses: one database, three SeatWave instances, two HAProxy balancers and a keepalived beside each balancer. A ninth program, the load harness, starts only on demand. The file pins the images, gives each container a fixed address on a private network, caps its CPU and memory, takes away its rights, and publishes only the balancers' ports, on `127.0.0.1` only, so nothing outside this computer can reach the lab. `scripts/stack.sh` runs it (see `shell-scripts-walkthrough.md`).

## Where it appears

| Path | What it does with Compose |
|---|---|
| `compose.yaml` | The only Compose file: nine services (`postgres`, `seatwave-1`, `seatwave-2`, `seatwave-3`, `lb1`, `lb2`, `keepalived-1`, `keepalived-2`, `harness`), one secret, one volume and one network (`lab`). |
| `scripts/stack.sh`, `scripts/lab.sh`, `scripts/common.sh` | Start, stop, kill and restart services with `docker compose -p seatwave-balancer ...`. |
| `src/main/java/.../deploy/DockerCompose.java` | The rolling-deploy tool runs `docker compose -p <project> stop` and `start` on one service at a time. |
| `haproxy/haproxy.cfg`, `keepalived/lb1.conf`, `keepalived/lb2.conf`, `db/schema.sql`, `build/secrets/db.password` | Mounted into the containers as read-only files. |
| `src/test/java/.../config/InfrastructureRulesTest.java` | Reads `compose.yaml` as text and checks ports, digests and privileges. |

## How it works underneath

1. `docker compose up` reads `compose.yaml` in the current folder and works out the **project**. The project name here is `seatwave-balancer` (the `name:` key). Every object Compose creates carries the label `com.docker.compose.project=<name>`, which is how `stack.sh` later finds what to remove. The option `-p <name>` replaces the name from the file (I used `-p seatwave-balancer-notes` for the runs in these notes).
2. Compose creates the **network** `lab` first. A network is a private virtual switch. Docker gives each container on it an address, here a fixed one. Containers on one network can reach each other by address, and by service name through Docker's built-in name server.
3. For each **service** it creates one container named `<project>-<service>-1`, for example `seatwave-balancer-notes-lb1-1`. It starts them in an order that respects `depends_on`.
4. A **published port** (`"127.0.0.1:21000:8080"`) tells Docker to listen on the host's address and port on the left and pass each connection to the container's port on the right.
5. A **bind mount** shows a file or folder of your computer inside the container. A **tmpfs** is a folder that lives in memory and disappears when the container stops. A **named volume** is storage that Docker manages and keeps when the container is removed.
6. A **secret** (file form) is a file on the host that Compose shows inside the container, read-only, at `/run/secrets/<name>`.
7. `docker compose down -v` stops the containers, removes them, removes the network, and removes the named volumes of the project. Here `-v` removes the database's `pgdata`.
8. **Network namespace sharing.** A container normally gets its own network card and address. With `network_mode: "service:lb1"` it joins the network of the container `lb1` instead, so both programs see the same cards, addresses and ports, like two programs on one machine. The keepalived services do this.

## Walkthrough of this repository's use

### The header, the project name and the anchor for the image

`compose.yaml`, lines 1 to 14:

```yaml
# SeatWave on three stateless instances behind a redundant pair of load balancers (Module 10).
# Start and stop it with scripts/stack.sh, which uses the project name seatwave-balancer and brings
# everything down with "down -v".
# Images are pinned by tag and digest, checked on Docker Hub on 11 November 2026, each with an
# arm64 variant: haproxy:3.4.7 (the 3.4 long-term-support line, Debian trixie) and postgres:18.6
# (Debian trixie), both Docker Official Images; SeatWave's image and the keepalived image are built
# here from pinned official images (Dockerfile, keepalived/Dockerfile).
# Ports are published on 127.0.0.1 only, from Module 10's range 21000 to 21099, and only the
# balancers' ports: no SeatWave instance and not the database can be reached from outside the lab
# network. Nothing runs privileged, nothing uses the host network or the Docker socket, and the only
# folders mounted are this project's own, read-only.
name: seatwave-balancer

x-haproxy-image: &haproxy-image haproxy:3.4.7@sha256:f6d2ee87b47192d9cc8c22e88fef405122ff339859644883edc2a7d1827f87b3
```
- Lines 1 to 11 are comments. They say that `scripts/stack.sh` starts and stops the stack under the project name `seatwave-balancer` and brings everything down with `down -v`. The images are pinned by tag **and** digest (checked on Docker Hub on 11 November 2026): `haproxy:3.4.7` (the 3.4 long-term-support line, built on Debian trixie) and `postgres:18.6` (also Debian trixie), both Docker Official Images. SeatWave's image and the keepalived image are built here from pinned official images. Ports are published on `127.0.0.1` only, from Module 10's range 21000 to 21099, and only the balancers' ports are published: no SeatWave instance and not the database can be reached from outside the lab network. Nothing runs privileged, nothing uses the host network or the Docker socket, and the only folders mounted are this project's own, read-only.
- `name: seatwave-balancer` is the project name described above.
- `x-haproxy-image` is a top-level key that begins with `x-`: an **extension field**. Compose ignores it, so you can park reusable values there. `&haproxy-image` after the colon attaches the anchor name `haproxy-image` to the value that follows, the image reference `haproxy:3.4.7@sha256:f6d2ee...`. Defining it once, and using `*haproxy-image` where it is needed, means the two balancers cannot drift apart.

### The four reusable blocks

`compose.yaml`, lines 16 to 19:

```yaml
x-hardening: &hardening
  security_opt:
    - no-new-privileges:true
  read_only: true
```
`x-hardening` (anchor `hardening`) is a mapping of two settings every service shares:

- `security_opt: - no-new-privileges:true` forbids a process in the container, and its children, from gaining more rights than it started with (for example through a program with the setuid bit).
- `read_only: true` makes the container's root file system read-only. I checked it in a SeatWave instance: `touch /app/x` answered `Read-only file system`. A program that must write somewhere gets a `tmpfs` or a mounted folder for that one place.

`compose.yaml`, lines 21 to 41:

```yaml
x-seatwave: &seatwave
  <<: *hardening
  image: seatwave-balancer-seatwave:1.1.0
  cap_drop:
    - ALL
  # Two CPUs and 512 MB each: three instances, two balancers, the database and the harness share
  # the Docker virtual machine's 12 CPUs (Section 8 of the module).
  cpus: 2
  mem_limit: 512m
  tmpfs:
    - /tmp
  secrets:
    - db_password
  depends_on:
    postgres:
      condition: service_healthy
  # docker stop sends SIGTERM, waits this long, then SIGKILL. The worst-case stop of an instance is
  # drain.deadlineMillis (20 s, config/seatwave-container.properties) plus the 5 s it then gives
  # its worker threads (SeatWaveServer.WORKER_STOP_WAIT): 25 s, inside these 30 s, so a draining
  # instance is never killed by docker. ServiceSettingsTest checks the arithmetic.
  stop_grace_period: 30s
```
`x-seatwave` (anchor `seatwave`) holds everything the three instances share.

- `<<: *hardening` merges the two settings above into this block.
- `image: seatwave-balancer-seatwave:1.1.0` is the image the `Dockerfile` builds (only `seatwave-1` has the `build:` key; the other two reuse the image the first one builds).
- `cap_drop: - ALL` removes every Linux capability from the container. SeatWave needs none: it opens ports above 1024.
- The comment says why the limits are what they are: three instances, two balancers, the database and the harness share the Docker virtual machine's 12 CPUs. `cpus: 2` caps the container at two CPUs' worth of time, and `mem_limit: 512m` caps its memory at 512 megabytes (the Java heap may use half of it, see `docker.md`).
- `tmpfs: - /tmp` gives the container an in-memory `/tmp`, because the root file system is read-only and a program may want a writable temporary folder.
- `secrets: - db_password` mounts the secret named at the bottom of the file, at `/run/secrets/db_password`. The settings file points at it with `db.passwordFile` (see `java-properties.md`).
- `depends_on:` with `postgres:` and `condition: service_healthy` makes Compose start an instance only after the database's health check (below) reports healthy. The plain list form of `depends_on` would only order the starts.
- The last comment and `stop_grace_period: 30s` go together. `docker stop` sends SIGTERM (the polite "please stop" signal), waits this long, then sends SIGKILL (the signal that ends a process at once, with no clean-up). The comment adds up the worst case of a stopping instance: `drain.deadlineMillis` in `config/seatwave-container.properties` is 20 seconds, the longest the instance waits for its in-flight requests, and after that it gives its worker threads 5 more seconds to finish (the constant `WORKER_STOP_WAIT` in `SeatWaveServer`). 20 + 5 = 25 seconds, which is inside the 30, so a draining instance is never killed by Docker before its own deadline. `ServiceSettingsTest` reads both files and checks this sum, so changing one number without the other fails the build (`tests-seatwave-walkthrough-1.md` explains the test).

`compose.yaml`, lines 43 to 54:

```yaml
x-balancer: &balancer
  <<: *hardening
  image: *haproxy-image
  cap_drop:
    - ALL
  # One CPU each, and nbthread 1 in haproxy.cfg: every balancer number is per core.
  cpus: 1
  mem_limit: 256m
  tmpfs:
    - /tmp
  volumes:
    - ./haproxy/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro
```
`x-balancer` (anchor `balancer`) is the shape of both HAProxy containers.

- `image: *haproxy-image` inserts the pinned image reference from the first anchor.
- `cap_drop: - ALL` again: HAProxy listens on ports above 1024 and needs no capability. The image's own user is `haproxy` (user number 99); I checked with `id` in the running container.
- `cpus: 1` and `mem_limit: 256m` are the limits. The comment ties the one CPU to `nbthread 1` in `haproxy.cfg`: HAProxy runs one thread on one CPU, so every balancer number in the module is per core.
- `volumes: - ./haproxy/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro` is a bind mount, written `host path : container path : options`. `:ro` means read-only. `/usr/local/etc/haproxy/haproxy.cfg` is the path where the official image looks for its configuration, so no command line is needed.

`compose.yaml`, lines 56 to 73:

```yaml
x-keepalived: &keepalived
  <<: *hardening
  build: ./keepalived
  image: seatwave-balancer-keepalived:1.1.0
  # NET_ADMIN adds and removes the floating address; NET_RAW sends VRRP; SETUID and SETGID let it
  # run its check script as the script user. Nothing else.
  cap_drop:
    - ALL
  cap_add:
    - NET_ADMIN
    - NET_RAW
    - SETUID
    - SETGID
  cpus: 0.25
  mem_limit: 64m
  tmpfs:
    - /run
    - /tmp
```
`x-keepalived` (anchor `keepalived`) is the shape of both keepalived containers.

- `build: ./keepalived` tells Compose to build the image from the folder `keepalived/` (its `Dockerfile`, see `docker.md`), and `image:` names the result. Both services use the same image; Compose builds it once.
- The comment explains the four capabilities that `cap_add` gives back after `cap_drop: ALL`. `NET_ADMIN` lets the program add and remove the floating address. `NET_RAW` lets it send the VRRP messages, which are raw network packets (see `keepalived-vrrp.md`). `SETUID` and `SETGID` let it switch to another user to run its check script. Nothing else is given.
- `cpus: 0.25` and `mem_limit: 64m`: a quarter of a CPU and 64 megabytes are plenty for a program that sends one small message per second.
- `tmpfs:` gives it `/run` (where it keeps its process-number files) and `/tmp`, because the root file system is read-only.

### The `services:` key and the database

`compose.yaml`, lines 75 to 112:

```yaml
services:
  postgres:
    <<: *hardening
    image: postgres:18.6@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42
    environment:
      POSTGRES_USER: seatwave
      POSTGRES_DB: seatwave
      # The password is a file generated at run time into build/secrets (scripts/stack.sh).
      POSTGRES_PASSWORD_FILE: /run/secrets/db_password
    secrets:
      - db_password
    # The entrypoint starts as root to own the data folder, then runs PostgreSQL as its own user.
    cap_drop:
      - ALL
    cap_add:
      - CHOWN
      - DAC_OVERRIDE
      - FOWNER
      - SETGID
      - SETUID
    cpus: 2
    mem_limit: 1g
    shm_size: 128m
    tmpfs:
      - /var/run/postgresql
      - /tmp
    volumes:
      - pgdata:/var/lib/postgresql
      # Runs once, when the data volume is new: the tables and Module 1's 20,000 seats.
      - ./db/schema.sql:/docker-entrypoint-initdb.d/schema.sql:ro
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "seatwave", "-d", "seatwave"]
      interval: 2s
      timeout: 2s
      retries: 30
    networks:
      lab:
        ipv4_address: 10.210.10.5
```
- `services:` starts the list of containers.
- `postgres:` is the shared database (see `postgresql-and-h2.md`). `<<: *hardening` gives it the read-only file system and `no-new-privileges`. The image is the pinned `postgres:18.6`.
- `environment:` sets environment variables inside the container (named values that a program can read). `POSTGRES_USER` and `POSTGRES_DB` make the image create a user and a database both named `seatwave`. `POSTGRES_PASSWORD_FILE: /run/secrets/db_password` tells the image to read the password from a file instead of a variable, so the password never shows in `docker inspect`. The comment says the file is generated at run time into `build/secrets` by `scripts/stack.sh`. `secrets: - db_password` mounts it.
- The comment before `cap_drop` explains the capabilities. The image's start script runs as root for a moment to take ownership of the data folder, and then runs the database as its own user. So `cap_drop: ALL` is followed by `cap_add` of five: `CHOWN` (change owners), `DAC_OVERRIDE` and `FOWNER` (bypass file permission checks that the start script needs on the data folder), and `SETGID` and `SETUID` (switch to the database's user).
- `cpus: 2`, `mem_limit: 1g` and `shm_size: 128m` set the limits. `shm_size` is the size of `/dev/shm`, the shared memory area PostgreSQL uses for some work. Docker's default of 64 MB is small for a database.
- `tmpfs:` gives it two in-memory folders, `/var/run/postgresql` (where its socket file lives) and `/tmp`.
- `volumes:` has two entries. `pgdata:/var/lib/postgresql` mounts the **named volume** `pgdata` (declared at the bottom of the file). PostgreSQL 18 keeps its data in `/var/lib/postgresql/18/docker` (`$PGDATA` printed exactly that in the container), which is below the mount point, so the data survives a container being removed but is deleted by `down -v`. The second entry mounts `db/schema.sql` into `/docker-entrypoint-initdb.d/`, a folder whose `.sql` files the image runs once, when the data folder is new. The comment says what it holds: the tables and Module 1's 20,000 seats. I checked it: `select count(*) from seats` in the running database printed `20000`, and `ls /docker-entrypoint-initdb.d` printed `schema.sql`.
- `healthcheck:` tells Docker how to ask whether the container is healthy. `test: ["CMD", "pg_isready", "-U", "seatwave", "-d", "seatwave"]` runs `pg_isready`, a PostgreSQL tool that exits with 0 when the server accepts connections; `"CMD"` means "run this program directly, without a shell". `interval: 2s` is how often it runs, `timeout: 2s` how long one run may take, and `retries: 30` how many failures in a row make the container `unhealthy`. This is what `depends_on ... service_healthy` waits for.
- `networks:` joins the container to the network `lab` and gives it the fixed address `10.210.10.5` (`ipv4_address`). The fixed address is the one in `config/seatwave-container.properties` (`db.url=jdbc:postgresql://10.210.10.5:5432/seatwave`).

### The three SeatWave instances

`compose.yaml`, lines 114 to 134:

```yaml
  seatwave-1:
    <<: *seatwave
    build: .
    command: ["io.plainround.seatwave.balancer.seatwave.SeatWaveCli", "/app/config/seatwave-container.properties", "seatwave-1"]
    networks:
      lab:
        ipv4_address: 10.210.10.11

  seatwave-2:
    <<: *seatwave
    command: ["io.plainround.seatwave.balancer.seatwave.SeatWaveCli", "/app/config/seatwave-container.properties", "seatwave-2"]
    networks:
      lab:
        ipv4_address: 10.210.10.12

  seatwave-3:
    <<: *seatwave
    command: ["io.plainround.seatwave.balancer.seatwave.SeatWaveCli", "/app/config/seatwave-container.properties", "seatwave-3"]
    networks:
      lab:
        ipv4_address: 10.210.10.13
```
- Each service merges `*seatwave` (all of the block above, which itself merged `*hardening`).
- Only `seatwave-1` has `build: .`, which builds the `Dockerfile` in this folder. The other two use the image it builds. `docker compose up --build` builds before it starts anything, so the image exists by the time the others start.
- `command:` replaces the image's `CMD`. It is the same three words in each service except the last: the main class `SeatWaveCli`, the settings file, and the instance name. The name decides the `X-Served-By` header an instance writes on every answer, and which logs are whose.
- Each service has its own address on `lab`: `10.210.10.11`, `.12` and `.13`. `haproxy.cfg` lists exactly these addresses.
- None of the three has a `ports:` key. Nothing outside the lab network can reach an instance. `InfrastructureRulesTest` checks this.

### The two balancers

`compose.yaml`, lines 136 to 164:

```yaml
  lb1:
    <<: *balancer
    environment:
      BALANCER_NAME: lb1
    networks:
      lab:
        ipv4_address: 10.210.10.21
    ports:
      - "127.0.0.1:21000:8080"
      - "127.0.0.1:21001:8081"
      - "127.0.0.1:21002:8082"
      - "127.0.0.1:21003:8083"
      - "127.0.0.1:21004:8404"
      - "127.0.0.1:21005:9999"

  lb2:
    <<: *balancer
    environment:
      BALANCER_NAME: lb2
    networks:
      lab:
        ipv4_address: 10.210.10.22
    ports:
      - "127.0.0.1:21010:8080"
      - "127.0.0.1:21011:8081"
      - "127.0.0.1:21012:8082"
      - "127.0.0.1:21013:8083"
      - "127.0.0.1:21014:8404"
      - "127.0.0.1:21015:9999"
```
- Both merge `*balancer`. `environment: BALANCER_NAME: lb1` (or `lb2`) is read by `haproxy.cfg`, which writes it into the `X-Balancer` header of every answer (`${BALANCER_NAME}`). The pair lab counts answers by that header.
- The addresses are `10.210.10.21` and `10.210.10.22`. They are also in the keepalived files.
- `ports:` publishes six container ports. The text form is `"host address : host port : container port"`. The quotes matter: YAML could otherwise read `21000:8080` as a number written in base 60. The six: 8080 is the PRODUCTION door (round robin), 8081 the least-connections door, 8082 the active-checks-only door, 8083 the source-hashing door, 8404 the status page, and 9999 the runtime API. All are on `127.0.0.1`. The second balancer's host ports are the first's plus 10. Container port 8084 (the weighted door) is not published: it is reachable only inside the lab network, by the harness.
- I checked the result: `docker compose port lb1 8080` printed `127.0.0.1:21000` and `docker compose port lb1 9999` printed `127.0.0.1:21005`.

### The keepalived services

`compose.yaml`, lines 166 to 183:

```yaml
  # Each keepalived shares its balancer's network namespace, as a second process on the same
  # machine would: the floating address lands on the balancer's own interface, and when the
  # balancer's container dies, its keepalived loses the network with it.
  keepalived-1:
    <<: *keepalived
    network_mode: "service:lb1"
    depends_on:
      - lb1
    volumes:
      - ./keepalived/lb1.conf:/etc/keepalived/keepalived.conf:ro

  keepalived-2:
    <<: *keepalived
    network_mode: "service:lb2"
    depends_on:
      - lb2
    volumes:
      - ./keepalived/lb2.conf:/etc/keepalived/keepalived.conf:ro
```
- The comment gives the design. Each keepalived shares its balancer's network namespace, as a second process on the same machine would. So the floating address lands on the balancer's own network card, and when the balancer's container dies, its keepalived loses the network with it.
- `network_mode: "service:lb1"` is the key that does it (see "How it works underneath"). A service in this mode cannot have its own `networks:` or `ports:`: it has lb1's. I checked: `ip -4 -o addr show dev eth0` inside `keepalived-1` printed the address `10.210.10.21`, which is lb1's. The address `10.210.10.100` appeared in whichever container held it.
- `depends_on: - lb1` (list form) starts keepalived after its balancer. It does not wait for the balancer to be ready. The check script inside keepalived handles that: `haproxy_answers` failed once at start-up in my run and then passed, before keepalived became a backup.
- `volumes:` mounts the right file: `lb1.conf` for `keepalived-1`, `lb2.conf` for `keepalived-2`, both as `/etc/keepalived/keepalived.conf` read-only. This is the path the image's `ENTRYPOINT` names with `--use-file`.
- One consequence to know: if `lb1` is stopped and started again, Docker builds it a new network namespace, and the running keepalived is left holding the old one. That is why `scripts/lab.sh` restarts the keepalived service after it restarts a killed balancer.

### The harness

`compose.yaml`, lines 185 to 202:

```yaml
  # The harness, inside the lab network: the floating address exists only there, and timings
  # taken here do not cross Docker Desktop's port forwarder. Started only on demand
  # (docker compose run --rm harness <settings>); it publishes no port.
  harness:
    <<: *hardening
    image: seatwave-balancer-seatwave:1.1.0
    profiles:
      - tools
    entrypoint: ["/opt/seatwave-java/bin/java", "-cp", "/app/lib/*", "io.plainround.seatwave.balancer.harness.HarnessCli"]
    cap_drop:
      - ALL
    cpus: 2
    mem_limit: 1g
    tmpfs:
      - /tmp
    networks:
      lab:
        ipv4_address: 10.210.10.30
```
- The comment gives the reason: the floating address `10.210.10.100` exists only on the lab network, and timings taken inside the network do not cross Docker Desktop's port forwarder. So the load generator runs as a container.
- `profiles: - tools` hides the service from a plain `docker compose up`. It starts only on demand (`docker compose run --rm harness <settings>`), or when the command names the profile (`--profile tools`), which is why `scripts/common.sh` has a second command array that includes it.
- `entrypoint:` replaces the image's `ENTRYPOINT` with `java -cp /app/lib/* ...HarnessCli`. The settings file is the argument given to `run`.
- It reuses the SeatWave image, drops all capabilities, gets `cpus: 2`, `mem_limit: 1g` and an in-memory `/tmp`, and has the fixed address `10.210.10.30`. It publishes no port.

### The secret, the volume and the network

`compose.yaml`, lines 204 to 215:

```yaml
secrets:
  db_password:
    file: ./build/secrets/db.password

volumes:
  pgdata:

networks:
  lab:
    ipam:
      config:
        - subnet: 10.210.10.0/24
```
- `secrets:` declares `db_password`. `file: ./build/secrets/db.password` says it comes from a file on the host, which `scripts/stack.sh up` writes with random characters if it does not exist. The folder `build/` is ignored by git, so the password is never committed.
- `volumes:` with `pgdata:` and nothing after it declares an ordinary named volume with Docker's defaults.
- `networks:` declares `lab`. `ipam` (IP address management) has one `config` entry, `subnet: 10.210.10.0/24`: addresses from `10.210.10.1` to `10.210.10.254`. Docker keeps `.1` for the gateway, the host's side of the network. HAProxy's log lines for requests that came from the Mac show the client `10.210.10.1` for that reason. `docker network inspect seatwave-balancer-notes_lab` listed the six fixed addresses `.5`, `.11`, `.12`, `.13`, `.21` and `.22`. The harness appears only while it runs.

## Try it yourself

I ran these on 11 November 2026 with Docker 24.0.6 and Compose v2.23.0-desktop.1 in a copy of the repository at version 1.0.0 (the old run), using the project name `seatwave-balancer-notes` and renamed image tags (see `docker.md`). Steps 1 and 2 show the re-measured run of version 1.1.0 instead, made with the real project name `seatwave-balancer`. With the repository as it is, leave `-p` out or use `-p seatwave-balancer`. The stack was up. Timings differ on every machine.

1. Start the stack (the first time it also builds the images; later runs take seconds) and read the last line:

```
scripts/stack.sh up --run --yes 2>&1 | tail -1
```

Output:

```
stack is up after 4 s: 3 of 3 instances up on lb1 and lb2; 10.210.10.100 on keepalived-1
```

(The last word can be `keepalived-2` on another start: the keepalived that starts first usually takes the address, see `keepalived-vrrp.md`.)

2. List the containers, with the digests cut off:

```
docker compose -p seatwave-balancer ps --format '{{.Service}}  {{.Image}}  {{.Status}}' | sed 's/@sha256:[0-9a-f]*//'
```

Output:

```
keepalived-1  seatwave-balancer-keepalived:1.1.0  Up 7 seconds
keepalived-2  seatwave-balancer-keepalived:1.1.0  Up 7 seconds
lb1  haproxy:3.4.7  Up 7 seconds
lb2  haproxy:3.4.7  Up 7 seconds
postgres  postgres:18.6  Up 7 seconds (healthy)
seatwave-1  seatwave-balancer-seatwave:1.1.0  Up 4 seconds
seatwave-2  seatwave-balancer-seatwave:1.1.0  Up 4 seconds
seatwave-3  seatwave-balancer-seatwave:1.1.0  Up 4 seconds
```

3. See what the anchors and merges produced. `docker compose config` prints the file after Compose has applied them. Here the limits of three services:

```
docker compose -p seatwave-balancer-notes config --format json | python3 -c '
import json,sys
d=json.load(sys.stdin)
for n in ("lb1","seatwave-2","keepalived-1"):
    s=d["services"][n]
    print(n, {k:s.get(k) for k in ("read_only","cap_drop","cap_add","cpus","mem_limit","network_mode","stop_grace_period")})
'
```

Output:

```
lb1 {'read_only': True, 'cap_drop': ['ALL'], 'cap_add': None, 'cpus': 1, 'mem_limit': '268435456', 'network_mode': None, 'stop_grace_period': None}
seatwave-2 {'read_only': True, 'cap_drop': ['ALL'], 'cap_add': None, 'cpus': 2, 'mem_limit': '536870912', 'network_mode': None, 'stop_grace_period': '30s'}
keepalived-1 {'read_only': True, 'cap_drop': ['ALL'], 'cap_add': ['NET_ADMIN', 'NET_RAW', 'SETUID', 'SETGID'], 'cpus': 0.25, 'mem_limit': '67108864', 'network_mode': 'service:lb1', 'stop_grace_period': None}
```

`read_only` comes from `hardening`, the limits and `cap_drop` from the other blocks. `cap_add` and `network_mode` are `None` where a service does not set them. `mem_limit` is in bytes (268,435,456 is 256 MiB).

4. Find the published ports and the network:

```
docker compose -p seatwave-balancer-notes port lb1 8080
docker compose -p seatwave-balancer-notes port lb1 9999
docker network inspect seatwave-balancer-notes_lab --format '{{range .IPAM.Config}}{{.Subnet}}{{end}} {{range $k,$v := .Containers}}{{$v.Name}}={{$v.IPv4Address}} {{end}}' | tr ' ' '\n' | sort
```

Output:

```
127.0.0.1:21000
127.0.0.1:21005

10.210.10.0/24
seatwave-balancer-notes-lb1-1=10.210.10.21/24
seatwave-balancer-notes-lb2-1=10.210.10.22/24
seatwave-balancer-notes-postgres-1=10.210.10.5/24
seatwave-balancer-notes-seatwave-1-1=10.210.10.11/24
seatwave-balancer-notes-seatwave-2-1=10.210.10.12/24
seatwave-balancer-notes-seatwave-3-1=10.210.10.13/24
```

5. Bring it all down, and prove nothing is left (this is the same check `stack.sh down` does):

```
scripts/stack.sh down --run --yes 2>&1 | tail -1
docker ps -a --filter label=com.docker.compose.project=seatwave-balancer-notes --format '{{.Names}}' | wc -l
```

Output (the `0` means no container carries the project label; the same check for networks and volumes is in `shell-scripts-walkthrough.md`):

```
stack is down: no container, network or volume of seatwave-balancer-notes is left
0
```

## Mistakes people make

- **Unquoted port mappings.** `- 21000:8080` can be read by YAML as a base-60 number. This file quotes every one.
- **Publishing on every address.** `"21000:8080"` without `127.0.0.1:` listens on all of the machine's addresses. Every port here names `127.0.0.1`, and `InfrastructureRulesTest` fails the build if any does not, or if a published port is outside 21000 to 21099.
- **Thinking `depends_on` waits for readiness.** The list form only orders the starts. The instances use `condition: service_healthy` for the database; `keepalived` leaves readiness to its check script.
- **Forgetting `-v` on `down`.** Without it, the `pgdata` volume stays, with the old password inside it, and the next `up` would start a database whose password no longer matches the new `build/secrets/db.password`. `stack.sh down` uses `down -v` and then checks that nothing is left.
- **Expecting a shared network namespace to survive a restart.** See the note under the keepalived services.
- **Trusting `latest`.** Every image here is pinned by tag and digest. A reviewer can compare the digest with the registry.
- **Tabs in YAML.** Only spaces are allowed for indentation.

## Check yourself

1. What does `<<: *hardening` do?
   *It merges the mapping named by the anchor `&hardening` (`security_opt` and `read_only`) into the service that holds it.*
2. Why is no SeatWave instance given a `ports:` key?
   *So nothing outside the lab network can reach an instance. Only the balancers publish ports, and only on `127.0.0.1`.*
3. What does `network_mode: "service:lb1"` give `keepalived-1`?
   *The network of the `lb1` container: the same card, address and ports. The floating address therefore lands on the balancer's own interface, and a dead balancer takes its keepalived's network with it.*
4. Why does `depends_on` for the instances say `condition: service_healthy`?
   *So an instance starts only after the database's `pg_isready` check passes.*
5. What does `down -v` remove that `down` alone does not?
   *The named volume `pgdata`, with the database's data.*

## Further reading

- Compose file reference: https://docs.docker.com/reference/compose-file/
- Services, `depends_on`, `healthcheck`, `network_mode`, `profiles`: https://docs.docker.com/reference/compose-file/services/
- Secrets in Compose: https://docs.docker.com/compose/how-tos/use-secrets/
- Fragments, anchors and extension fields: https://docs.docker.com/reference/compose-file/fragments/ and https://docs.docker.com/reference/compose-file/extension/
- `docker compose` command reference (`up`, `down`, `ps`, `config`, `port`, `run`): https://docs.docker.com/reference/cli/docker/compose/
- YAML 1.2: https://yaml.org/spec/1.2.2/
