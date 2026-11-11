# keepalived for the balancer pair, from Alpine's own package. The base is a Docker Official Image,
# pinned by tag and digest, checked on Docker Hub on 11 November 2026 (arm64 variant present).
# Alpine 3.24's package is keepalived 2.3.4-r2; the newest upstream tag was v2.4.3 on that day.
FROM alpine:3.24.4@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf
RUN apk add --no-cache keepalived=2.3.4-r2
# keepalived runs as root: it adds and removes the floating address, which needs the NET_ADMIN
# capability (compose.yaml grants it and NET_RAW, nothing else). --dont-fork keeps it process 1, so
# docker stop's SIGTERM reaches it and it gives the address up before it exits.
ENTRYPOINT ["keepalived", "--dont-fork", "--log-console", "--use-file", "/etc/keepalived/keepalived.conf"]
