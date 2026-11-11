# Docker: images, containers, the Dockerfiles and `.dockerignore`

## What it is

**Docker** is a tool that runs a program inside a **container**. A container is a normal program running on your computer, but fenced in by the operating system so that it sees only its own files, its own list of programs and its own network. Think of it as a sealed room: the program inside cannot see the rest of the house.

A container starts from an **image**. An image is a read-only package. It holds a small file system (the program, its libraries, its settings) and a few instructions (which program to start, as which user). An image is built in **layers**. Each step of the build adds one layer on top of the one before, and Docker keeps every layer so it can reuse it next time.

A **Dockerfile** is the recipe for an image: a text file with one instruction per step. A **registry** (for example Docker Hub) is a server that stores images. An image is named `name:tag`, and the **tag** (such as `noble`) picks a version. A tag can be moved to point at a new build. A **digest** (`sha256:` followed by 64 hexadecimal characters) is the fingerprint of the exact bytes of an image. It cannot move.

## Why this repository uses it

The lesson spreads SeatWave over three instances behind two balancers. All of them have to find each other at fixed network addresses, and one of them (the balancer pair) has to lose its network the way a dead machine would. Containers give each piece its own network address and its own life: one `kill` removes exactly one of them.

Two things are built here, so two Dockerfiles exist. The first turns SeatWave's jars (Java library files, see `java.md`) into an image of about 203 MB that the three instances and the load harness all use. The second builds a tiny image (10.7 MB) that holds only the program `keepalived`, which moves the shared balancer address from one balancer to the other (see `keepalived-vrrp.md`). The balancers themselves (`haproxy`) and the database (`postgres`) are used straight from the official images, with no Dockerfile of ours.

## Where it appears

| Path | What it does with Docker |
|---|---|
| `Dockerfile` | The recipe for the SeatWave image `seatwave-balancer-seatwave:1.0.0`, in two stages. It runs the three instances and the harness. |
| `keepalived/Dockerfile` | The recipe for the keepalived image `seatwave-balancer-keepalived:1.0.0`, one stage. |
| `.dockerignore` | Lists what must stay out of the build context (the folder Docker reads when it builds), above all the database password. |
| `compose.yaml` | Builds both images (`build: .` and `build: ./keepalived`) and runs every container. See `docker-compose.md`. |
| `scripts/stack.sh` | Runs `docker compose ... up -d --build`, which builds the images. See `shell-scripts-walkthrough.md`. |
| `build.gradle.kts` | The task `stackFiles` collects the jars into `build/stack/lib/` that the first Dockerfile copies. See `gradle.md`. |

## How it works underneath

1. **Containers are processes.** There is no second computer inside. The Linux kernel runs the container's program like any other program, but gives it three kinds of fences. **Namespaces** give it its own view of process numbers, network cards and file system. **Control groups** (cgroups) cap how much CPU and memory it may use. **Capabilities** are the small pieces of the administrator's power; a container can have some or none of them.
2. **Docker Desktop on a Mac runs a small Linux virtual machine.** Containers need a Linux kernel, and macOS does not have one. So every container runs inside that virtual machine. The keepalived log in this lab says so: `running on Linux 6.4.16-linuxkit`. A request from your Mac to a published port also crosses Docker Desktop's port forwarder.
3. **A build sends a context.** `docker build` (and `docker compose build`) first sends a folder, the **build context**, to the builder. Only files in the context can be copied into the image. `.dockerignore` shrinks the context.
4. **Each instruction makes a layer.** `FROM` chooses the starting layers. `COPY` and `RUN` each add one. The builder caches a layer and reuses it if the instruction and its input files did not change.
5. **A multi-stage build uses several `FROM` lines.** Each `FROM` starts a new stage with a clean file system. A later stage can copy files out of an earlier one with `COPY --from=<stage>`. Only the last stage becomes the final image, so the first stage's compiler and tools are thrown away.
6. **An image is not a running thing.** `docker run` (or Compose) creates a container from it: the image's layers, plus one thin writable layer on top, plus the settings you give (ports, memory, user).
7. **Process 1.** The first program in a container has process number 1. When you stop a container, Docker sends it the signal SIGTERM ("please finish and exit") and, after a grace period, SIGKILL ("you are removed now", which a program cannot catch). A program that is not process 1, or that ignores SIGTERM, never gets the chance to clean up.

## Walkthrough of this repository's use

### `Dockerfile`: the header

`Dockerfile`, lines 1 to 4:

```dockerfile
# SeatWave as a container image, in two stages, adapted from the Module 9 project (seatwave-proxy).
# Both base images are Docker Official Images, pinned by tag and digest, checked on Docker Hub on
# 11 November 2026 (both have arm64). Build the jars first: ./gradlew stackFiles (scripts/stack.sh
# does it). The same image runs the three instances and the harness.
```
Lines that start with `#` are comments, which Docker ignores. They say that the file is adapted from the Module 9 project, that both base images are **Docker Official Images** (images that Docker reviews and publishes under a short name such as `ubuntu`), that both are pinned by tag and digest, that they were checked on Docker Hub on 11 November 2026, and that both have an `arm64` build (the kind an ARM-based Mac needs). The jars must be built first with `./gradlew stackFiles`, and `scripts/stack.sh` does that. The last comment line says that one image runs the three instances and the harness: the harness is the Java program that sends the load (see `java-harness-walkthrough-1.md` and `java-harness-walkthrough-2.md`).

### Stage 1: link a small Java runtime

`Dockerfile`, lines 6 to 14:

```dockerfile
# Stage 1: a JDK, used only to work out which Java modules SeatWave needs and to link a runtime
# with just those, so the final image carries no compiler and no unused modules.
FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS runtime
COPY build/stack/lib/ /app/lib/
RUN modules="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25 \
        --class-path '/app/lib/*' /app/lib/seatwave-balancer-1.0.0.jar /app/lib/postgresql-42.7.14.jar)" \
    && echo "linking modules: ${modules}" \
    && jlink --add-modules "${modules}" --strip-debug --no-man-pages --no-header-files \
        --output /opt/seatwave-java
```
- The two comment lines give the goal: work out which Java **modules** SeatWave needs and link a runtime with only those. A module is a named part of the JDK, such as `java.net.http`. The JDK has dozens. Leaving out the unused ones makes the image smaller, and leaving out the compiler leaves nothing for an attacker to use.
- `FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350... AS runtime` starts a stage. `eclipse-temurin` is the image name (a free build of the JDK). The tag `25.0.5_8-jdk-noble` reads: Java 25.0.5, build 8, the full JDK (not only a runtime), on the Ubuntu 24.04 release named "noble". `@sha256:...` pins the exact bytes, so a later change of the tag cannot change this build. `AS runtime` gives the stage a name so stage 2 can copy from it. This stage holds the whole JDK, but the only thing taken from it is the linked runtime and the jars.
- `COPY build/stack/lib/ /app/lib/` copies from the build context into the image. The left side is a path inside the context (the folder that holds `Dockerfile`). The right side is a path inside the image, and the folder is created if it does not exist. `build/stack/lib/` is written by `./gradlew stackFiles` and holds SeatWave's jar and every library it needs at run time: Jackson, SLF4J, Logback, result4j, HikariCP and the PostgreSQL driver.
- `RUN` runs a command while building and keeps the changes as a new layer. This one is a shell command: `modules="$(jdeps ...)"` runs the command inside `$( ... )` and stores what it prints in the shell variable `modules`.
  - `jdeps` is a JDK tool that reads jars and lists the modules their classes use.
  - `--print-module-deps` prints just those modules, as one comma-separated line.
  - `--ignore-missing-deps` does not stop when a library refers to something that is not present. Optional dependencies are normal.
  - `--multi-release 25` chooses which version of a "multi-release jar" to read. Some jars hold a different copy of a class for each Java version. 25 is the one this image runs.
  - `--class-path '/app/lib/*'` tells `jdeps` where to find the libraries. The single quotes stop the shell from expanding the `*` itself. The tool understands `*` as "every jar in this folder".
  - The last two words are the jars to analyze: SeatWave's own jar and the PostgreSQL driver. The driver is named separately because nothing in SeatWave's code mentions its classes: SeatWave asks for a `jdbc:postgresql:` address and the driver is found at run time. Without it in the list, the modules that only the driver needs could be missing from the runtime.
  - The version in the jar's name comes from `version = "1.0.0"` in `build.gradle.kts`, and the driver's version from `gradle/libs.versions.toml`. When either changes, this line must change too.
- The `\` at the end of a line means "the command continues on the next line".
- `&& echo "linking modules: ${modules}" \` : `&&` means "run the next command only if the one before it succeeded". If `jdeps` fails, the build stops. The `echo` prints the list so you can see it in the build output. The real output on this machine was `linking modules: java.base,java.desktop,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.httpserver`.
- `&& jlink --add-modules "${modules}" --strip-debug --no-man-pages --no-header-files \` runs `jlink`, the JDK tool that builds a custom runtime.
  - `--add-modules "${modules}"` names the modules to include. `jlink` also adds the modules those depend on. `java --list-modules` run in the finished image lists fourteen: `java.base`, `java.datatransfer`, `java.desktop`, `java.logging`, `java.management`, `java.naming`, `java.net.http`, `java.prefs`, `java.security.jgss`, `java.security.sasl`, `java.sql`, `java.transaction.xa`, `java.xml` and `jdk.httpserver`.
  - `--strip-debug` removes debugging information from the classes. `--no-man-pages` and `--no-header-files` leave out manual pages and C header files. All three only save space.
- `--output /opt/seatwave-java` is the folder where the new runtime is written. It will contain `bin/java`.

### Stage 2: the plain operating system and the runtime

`Dockerfile`, lines 16 to 23:

```dockerfile
# Stage 2: the plain operating system image and the linked runtime, run by a user without a
# login, a home folder or any rights beyond reading the application.
FROM ubuntu:noble@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin seatwave
COPY --from=runtime /opt/seatwave-java /opt/seatwave-java
COPY --from=runtime /app/lib/ /app/lib/
COPY config/ /app/config/
USER 10001
```
- The two comment lines say that the final image is the plain operating system image plus the linked runtime, run by a user "without a login, a home folder or any rights beyond reading the application".
- `FROM ubuntu:noble@sha256:fe8156...` starts stage 2 with Ubuntu 24.04 and no Java. Nothing from stage 1 is here yet.
- `RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin seatwave` creates an operating-system user named `seatwave`.
  - `--uid 10001` fixes the numeric user id. A high number collides with no real account.
  - `--system` marks it as a system account (the kind used by services). Ubuntu keeps the numbers up to 999 for system accounts, and 10001 is above that range, so the tool prints a warning on every build: `useradd warning: seatwave's uid 10001 is greater than SYS_UID_MAX 999`. I saw that line in the build output. It is harmless. `--system` also gave the user's group the number 999, not 10001: `id` in the container prints `uid=10001(seatwave) gid=999(seatwave) groups=999(seatwave)`.
  - `--no-create-home` does not create a home folder. The user's line in `/etc/passwd` still names `/home/seatwave`, a folder that does not exist (`grep seatwave /etc/passwd` printed `seatwave:x:10001:999::/home/seatwave:/usr/sbin/nologin`).
  - `--shell /usr/sbin/nologin` means nobody can log in as this user.
- `COPY --from=runtime /opt/seatwave-java /opt/seatwave-java` takes the linked runtime from stage 1 (named `runtime` on the `FROM` line there), not from the build context. This is how the runtime arrives without the JDK. The next line copies the jars from stage 1 the same way.
- `COPY config/ /app/config/` copies the folder `config/` from the build context: the settings files. The container uses `config/seatwave-container.properties` for a SeatWave instance (see `java-properties.md`) and the harness reads the lab files from the same folder.
- `USER 10001` makes everything after it, and the container's main program, run as user 10001. The number (not the name) is used so a platform can check that the user is not root without reading the image's `/etc/passwd`.

### How the program starts

`Dockerfile`, lines 24 to 28:

```dockerfile
# The exec form makes Java process 1, so docker stop's SIGTERM reaches it and its shutdown hook
# drains the instance. TCP_NODELAY on every accepted connection (the JDK server's own switch, off
# by default), which Module 9 found holds small responses about 40 ms without it.
ENTRYPOINT ["/opt/seatwave-java/bin/java", "-XX:MaxRAMPercentage=50", "-Dsun.net.httpserver.nodelay=true", "-cp", "/app/lib/*"]
CMD ["io.plainround.seatwave.balancer.seatwave.SeatWaveCli", "/app/config/seatwave-container.properties", "seatwave-1"]
```
- The first comment line gives the reason for the exec form (see below). The rest is a measured fact from Module 9: the JDK's built-in HTTP server leaves **Nagle's algorithm** on by default. Nagle's algorithm makes the operating system wait a short time to join small writes into one packet. A response's headers and body leave as two small writes, and Module 9 found that the body waited about 40 ms for every request. The switch `-Dsun.net.httpserver.nodelay=true` turns it off.
- `ENTRYPOINT [...]` is in **exec form**: a JSON list. Docker starts exactly this program with exactly these arguments, with no shell in between. That makes Java **process 1** in the container. `docker stop` sends SIGTERM to process 1, and the Java program has a shutdown hook (a piece of code that runs when the program is asked to stop) that drains the instance: it admits no new request and waits for the running ones. With a shell in the way the signal might not reach Java. I checked: `cat /proc/1/cmdline` in the running container printed the `java` command line.
  - `/opt/seatwave-java/bin/java` is the runtime that `jlink` made.
  - `-XX:MaxRAMPercentage=50` lets the Java heap grow to half of the container's memory limit. `compose.yaml` gives each instance `mem_limit: 512m`, so the heap may grow to 256 MiB. I checked: `-XX:+PrintFlagsFinal` printed `MaxHeapSize = 268435456`, which is 256 MiB.
  - `-Dsun.net.httpserver.nodelay=true` sets a **system property**, a named setting the JDK's HTTP server reads. It is the switch from the comment.
  - `-cp`, `"/app/lib/*"` is the class path. Java expands `/app/lib/*` itself to every jar in the folder. This is why no shell is needed.
- `CMD [...]` supplies the default **arguments** after the entry point: the main class `io.plainround.seatwave.balancer.seatwave.SeatWaveCli`, the settings file, and the instance name `seatwave-1`. If you pass arguments to `docker run`, they replace `CMD`. In `compose.yaml` each service's `command:` does exactly that, so `seatwave-2` and `seatwave-3` get their own names. The `harness` service replaces the entry point as well.

### `keepalived/Dockerfile`

`keepalived/Dockerfile`, lines 1 to 9:

```dockerfile
# keepalived for the balancer pair, from Alpine's own package. The base is a Docker Official Image,
# pinned by tag and digest, checked on Docker Hub on 11 November 2026 (arm64 variant present).
# Alpine 3.24's package is keepalived 2.3.4-r2; the newest upstream tag was v2.4.3 on that day.
FROM alpine:3.24.4@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf
RUN apk add --no-cache keepalived=2.3.4-r2
# keepalived runs as root: it adds and removes the floating address, which needs the NET_ADMIN
# capability (compose.yaml grants it and NET_RAW, nothing else). --dont-fork keeps it process 1, so
# docker stop's SIGTERM reaches it and it gives the address up before it exits.
ENTRYPOINT ["keepalived", "--dont-fork", "--log-console", "--use-file", "/etc/keepalived/keepalived.conf"]
```
- The three comment lines at the top say: the program comes from Alpine's own package; the base image is a Docker Official Image pinned by tag and digest; Alpine 3.24 ships keepalived 2.3.4-r2, while the newest upstream release on 11 November 2026 was v2.4.3. The lab pins the distribution's package on purpose: a package from the system's own repository is built and patched together with the system.
- `FROM alpine:3.24.4@sha256:f59abe...` starts from **Alpine Linux**, a very small distribution (the image is 8.66 MB before anything is added). It uses `apk` as its package manager and a minimal toolbox called busybox for commands such as `wget` and `ip`.
- `RUN apk add --no-cache keepalived=2.3.4-r2` installs one package at an exact version. `--no-cache` does not keep the downloaded package index in the image. The layer is 2.04 MB. The reason for the exact version: a newer package would change the program's behavior silently.
- The next three comment lines explain the user: keepalived runs as root because adding and removing the floating address needs the `NET_ADMIN` capability (a Linux right to change network settings), and `compose.yaml` grants that and `NET_RAW` and nothing else. `--dont-fork` keeps it process 1, so `docker stop`'s SIGTERM reaches it and it gives the address up before it exits.
- `ENTRYPOINT [...]` is the exec form again:
  - `keepalived` is the program.
  - `--dont-fork` means: do not turn into a background service ("daemon"); stay in the foreground as process 1. (`ps` inside the container shows process 1 and a second process 6: keepalived's own VRRP helper process.)
  - `--log-console` writes the log to the standard output, which `docker compose logs` shows.
  - `--use-file /etc/keepalived/keepalived.conf` names the settings file. `compose.yaml` mounts `keepalived/lb1.conf` or `lb2.conf` at that path. See `keepalived-vrrp.md`.
- There is no `USER` line, so the program runs as root inside the container. That is the exception this file explains, and `compose.yaml` fences it with `cap_drop: ALL`, four added capabilities, a read-only file system and a memory limit.

### `.dockerignore`

`.dockerignore`, lines 1 to 5:

```text
# The image needs only the collected jars and the settings; everything else stays out of the
# build context, above all build/secrets, whose database password must never enter an image.
*
!build/stack/lib/
!config/
```
- The two comment lines say that the image needs only the collected jars and the settings, and that everything else stays out of the build context, above all `build/secrets`, whose database password must never enter an image. Anything copied into an image stays in its layers for good, even if a later step deletes it.
- `*` ignores every file and folder in the context.
- `!build/stack/lib/` puts a path back: a `!` means "except". The jars are allowed.
- `!config/` puts the settings back.

I checked the result in the running instance: `ls /app` printed `config` and `lib` only, and `ls /run/tls` answered `No such file or directory`. The database password reaches a container only at run time, as a file Compose mounts at `/run/secrets/db_password` (see `docker-compose.md`). `ls /run/secrets` in an instance printed `db_password`.

## Try it yourself

I ran these in a copy of the repository on 11 November 2026 with Docker 24.0.6 and Compose v2.23.0-desktop.1. I used the Compose project name `seatwave-balancer-notes` and renamed the image tags in my copy (`seatwave-balancer-notes-seatwave:1.0.0`, `seatwave-balancer-notes-keepalived:1.0.0`) so that I would not touch the images of the real project; with the repository as it is, use `seatwave-balancer-seatwave:1.0.0` and `seatwave-balancer-keepalived:1.0.0` and leave `-p` out. Timings and sizes differ on every machine. The stack was already up (`scripts/stack.sh up --run --yes`).

1. Collect the jars, then rebuild the SeatWave image without the cache and show the interesting lines:

```
./gradlew -q stackFiles
docker compose -p seatwave-balancer-notes build --no-cache --progress plain seatwave-1 2>&1 | grep -E 'linking modules|useradd warning|naming to'
```

Output (the build takes about a minute):

```
useradd warning: seatwave's uid 10001 is greater than SYS_UID_MAX 999
linking modules: java.base,java.desktop,java.management,java.naming,java.net.http,java.security.jgss,java.sql,jdk.httpserver
#14 naming to docker.io/library/seatwave-balancer-notes-seatwave:1.0.0 done
```

2. Read the settings Docker stored in the image:

```
docker image inspect seatwave-balancer-notes-seatwave:1.0.0 --format '{{.Config.User}} | {{.Config.Entrypoint}} | {{.Config.Cmd}} | {{.Size}}'
```

Output:

```
10001 | [/opt/seatwave-java/bin/java -XX:MaxRAMPercentage=50 -Dsun.net.httpserver.nodelay=true -cp /app/lib/*] | [io.plainround.seatwave.balancer.seatwave.SeatWaveCli /app/config/seatwave-container.properties seatwave-1] | 203258099
```

3. See the layers, newest first. Each of your instructions is one row:

```
docker image history seatwave-balancer-notes-seatwave:1.0.0 --format '{{.Size}}  {{.CreatedBy}}' | cut -c1-80 | head -8
```

Output:

```
0B  CMD ["io.plainround.seatwave.balancer.seatwa…
0B  ENTRYPOINT ["/opt/seatwave-java/bin/java" "-…
0B  USER 10001
9.07kB  COPY config/ /app/config/ # buildkit
5.22MB  COPY /app/lib/ /app/lib/ # buildkit
97.2MB  COPY /opt/seatwave-java /opt/seatwave-java #…
4.75kB  RUN /bin/sh -c useradd --system --uid 10001 …
0B  /bin/sh -c #(nop)  CMD ["/bin/bash"]
```

4. Look inside a running instance (this needs the stack up). It shows that the application folder holds only `config` and `lib`, that no key folder exists, which user runs, that the secret is a file, that the file system is read-only, and which Java it is:

```
docker compose -p seatwave-balancer-notes exec -T seatwave-1 sh -c 'ls /app; ls /run/tls 2>&1; id; ls /run/secrets; touch /app/x 2>&1; /opt/seatwave-java/bin/java -version 2>&1 | head -1'
```

Output:

```
config
lib
ls: cannot access '/run/tls': No such file or directory
uid=10001(seatwave) gid=999(seatwave) groups=999(seatwave)
db_password
touch: cannot touch '/app/x': Read-only file system
openjdk version "25.0.5" 2026-10-20 LTS
```

5. The keepalived image is small, runs one program and has the pinned package:

```
docker image inspect seatwave-balancer-notes-keepalived:1.0.0 --format '{{.Config.User}}|{{.Config.Entrypoint}}|{{.Size}}'
docker compose -p seatwave-balancer-notes exec -T keepalived-1 keepalived --version 2>&1 | head -1
```

Output (the user is empty because the image does not set one, so it is root):

```
|[keepalived --dont-fork --log-console --use-file /etc/keepalived/keepalived.conf]|10696628
Keepalived v2.3.4 (06/10,2025)
```

## Mistakes people make

- **Using a moving tag as the base.** `ubuntu:noble` today and `ubuntu:noble` next month are different bytes. This repository adds `@sha256:...` to every base image, and `InfrastructureRulesTest` fails the build if the digest is missing from the SeatWave builder `FROM`, the keepalived `FROM`, or the two pinned images in `compose.yaml` (see `tests-config-walkthrough.md`). It does not check the `ubuntu` line of the second stage: that line is pinned by hand.
- **Copying secrets into an image.** A `COPY .` with no `.dockerignore` would take `build/secrets`, with the database password, into a layer. The `*` rule with `!` exceptions is a safe list, not a block list.
- **Running as root without need.** Without `USER`, the program runs as root inside the container. SeatWave's image sets `USER 10001`. The keepalived image is root on purpose, and the file around it says why.
- **Shell form of `ENTRYPOINT`.** Writing `ENTRYPOINT java -cp ...` without brackets starts `/bin/sh -c "java ..."`, and the shell can become process 1. The shell may not pass SIGTERM on, so `docker stop` can wait for its whole grace period (here 30 s), kill the container, and the drain never runs. Both Dockerfiles use the exec form.
- **Expecting `RUN` to remember the shell state between lines.** Each `RUN` is a new shell. That is why the `jdeps` result is stored in `modules` and used in the same `RUN`, joined by `&&`.
- **A fat final image.** Keeping the JDK stage as the final image would add the compiler and every module. The second `FROM` leaves them behind.
- **Forgetting that `docker build` and `docker run` are different times.** Files copied at build time are in the image. Files mounted by Compose (the password, `haproxy.cfg`) exist only while the container runs.

## Check yourself

1. What is the difference between an image and a container?
   *An image is the read-only package; a container is a running (or stopped) instance made from it, with a thin writable layer.*
2. Why does `Dockerfile` have two `FROM` lines?
   *Stage 1 uses the full JDK to find and link only the needed Java modules. Stage 2 starts again from plain Ubuntu and copies in just the linked runtime, the jars and the settings.*
3. What does `@sha256:...` after a tag change?
   *It pins the exact image bytes. A tag can be moved to a new build; a digest cannot.*
4. Why are both `ENTRYPOINT` lines written as JSON lists?
   *The exec form starts the program directly as process 1, so `docker stop`'s SIGTERM reaches it: SeatWave drains, and keepalived gives up the floating address.*
5. What do the lines of `.dockerignore` do?
   *`*` ignores everything, and the two `!` lines put back only `build/stack/lib/` and `config/`, so `build/secrets` can never enter the build context.*

## Further reading

- Dockerfile reference (instructions `FROM`, `COPY`, `RUN`, `USER`, `ENTRYPOINT`, `CMD`): https://docs.docker.com/reference/dockerfile/
- Multi-stage builds: https://docs.docker.com/build/building/multi-stage/
- `.dockerignore` and the build context: https://docs.docker.com/build/concepts/context/
- Pulling an image by digest: https://docs.docker.com/reference/cli/docker/image/pull/
- `jdeps` and `jlink` (JDK 25): https://docs.oracle.com/en/java/javase/25/docs/specs/man/jdeps.html and https://docs.oracle.com/en/java/javase/25/docs/specs/man/jlink.html
- Alpine package manager `apk`: https://wiki.alpinelinux.org/wiki/Alpine_Package_Keeper
- keepalived 2.3 manual page `keepalived.conf(5)`: https://www.keepalived.org/manpage.html
