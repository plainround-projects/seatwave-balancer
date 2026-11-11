# SeatWave as a container image, in two stages, adapted from the Module 9 project (seatwave-proxy).
# Both base images are Docker Official Images, pinned by tag and digest, checked on Docker Hub on
# 11 November 2026 (both have arm64). Build the jars first: ./gradlew stackFiles (scripts/stack.sh
# does it). The same image runs the three instances and the harness.

# Stage 1: a JDK, used only to work out which Java modules SeatWave needs and to link a runtime
# with just those, so the final image carries no compiler and no unused modules.
FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS runtime
COPY build/stack/lib/ /app/lib/
RUN modules="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25 \
        --class-path '/app/lib/*' /app/lib/seatwave-balancer-1.0.0.jar /app/lib/postgresql-42.7.14.jar)" \
    && echo "linking modules: ${modules}" \
    && jlink --add-modules "${modules}" --strip-debug --no-man-pages --no-header-files \
        --output /opt/seatwave-java

# Stage 2: the plain operating system image and the linked runtime, run by a user without a
# login, a home folder or any rights beyond reading the application.
FROM ubuntu:noble@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin seatwave
COPY --from=runtime /opt/seatwave-java /opt/seatwave-java
COPY --from=runtime /app/lib/ /app/lib/
COPY config/ /app/config/
USER 10001
# The exec form makes Java process 1, so docker stop's SIGTERM reaches it and its shutdown hook
# drains the instance. TCP_NODELAY on every accepted connection (the JDK server's own switch, off
# by default), which Module 9 found holds small responses about 40 ms without it.
ENTRYPOINT ["/opt/seatwave-java/bin/java", "-XX:MaxRAMPercentage=50", "-Dsun.net.httpserver.nodelay=true", "-cp", "/app/lib/*"]
CMD ["io.plainround.seatwave.balancer.seatwave.SeatWaveCli", "/app/config/seatwave-container.properties", "seatwave-1"]
