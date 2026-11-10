# syntax=docker/dockerfile:1

FROM otel/opentelemetry-collector-contrib:0.165.0@sha256:e40d61f10c9a617cca1ffd1be4d7e2b3a4b0f0001798ff07e58cabe82e618cea AS collector

FROM maven:3.9.16-eclipse-temurin-25-noble@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src/ ./src/
COPY LICENSE ./
COPY deploy/otel/collector.yaml /tmp/collector.yaml
COPY --from=collector /otelcol-contrib /tmp/otelcol-contrib
ADD --checksum=sha256:f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82 \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.32.0/opentelemetry-javaagent.jar \
    /tmp/opentelemetry-javaagent.jar

RUN set -eu; \
    printf '%s' 'fixture-only-not-an-openwoof-key' > /tmp/openwoof-test-token; \
    OPENWOOF_INGEST_KEY_FILE=/tmp/openwoof-test-token \
      /tmp/otelcol-contrib validate --config=/tmp/collector.yaml; \
    rm -f /tmp/openwoof-test-token

RUN set -eu; \
    mvn -B -ntp -Dmaven.test.skip=true package; \
    artifact="$(find /workspace/target -maxdepth 1 -type f -name 'embedify-*.jar' ! -name '*.original' -print -quit)"; \
    test -n "$artifact"; \
    cp "$artifact" /tmp/embedify.jar

FROM eclipse-temurin:25-jdk-alpine-3.24@sha256:c7c3d0bdf7f2bbc5a05a6712ed334d198360105634ea9adf76fca94f381b1e3f AS jdk

FROM bash:5.3.20-alpine3.24@sha256:589adbc588d8ef3c1ec3c68333faf4baf5dccb65d3cb50f6cf3d78cad2d6e1b0 AS runtime

COPY --from=jdk /usr/lib/ /usr/lib/
COPY --from=jdk /usr/bin/objcopy /usr/bin/objcopy
COPY --from=jdk /opt/java/openjdk/ /opt/java/openjdk/
COPY --from=jdk /opt/java/openjdk/lib/security/cacerts /opt/java/openjdk/lib/security/cacerts

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:${PATH}

WORKDIR /workspace

COPY --from=build /tmp/embedify.jar /tmp/embedify.jar
COPY --from=build /tmp/opentelemetry-javaagent.jar /tmp/opentelemetry-javaagent.jar
COPY --from=build /tmp/otelcol-contrib /tmp/otelcol-contrib
COPY --from=build /tmp/collector.yaml /tmp/collector.yaml
COPY scripts/ContainerHealthCheck.java /tmp/ContainerHealthCheck.java
COPY scripts/OtelSmoke.java /tmp/OtelSmoke.java
COPY scripts/otel-smoke.sh /tmp/otel-smoke.sh

RUN set -eu; \
    mkdir -p /tmp/app; \
    cd /tmp/app; \
    jar --extract --file /tmp/embedify.jar

RUN set -eu; \
    dependency_classpath=; \
    for dependency in /tmp/app/BOOT-INF/lib/*.jar; do \
      [ -f "$dependency" ] || continue; \
      dependency_classpath="${dependency_classpath}${dependency}:"; \
    done; \
    test -n "$dependency_classpath"; \
    mkdir -p /tmp/otel-smoke; \
    javac --release 25 -classpath "$dependency_classpath" -d /tmp/otel-smoke /tmp/OtelSmoke.java

RUN set -eu; \
    mkdir -p /opt/healthcheck; \
    javac --release 25 -d /opt/healthcheck /tmp/ContainerHealthCheck.java; \
    dependency_classpath=; \
    for dependency in /tmp/app/BOOT-INF/lib/*.jar; do \
      [ -f "$dependency" ] || continue; \
      dependency_classpath="${dependency_classpath}${dependency}:"; \
    done; \
    test -n "$dependency_classpath"; \
    missing_dependencies="$(jdeps --multi-release 25 --missing-deps --class-path "$dependency_classpath" /tmp/app/BOOT-INF/classes)"; \
    if [ -n "$missing_dependencies" ]; then \
      printf '%s\n' 'jdeps found unresolved application dependencies:' "$missing_dependencies" >&2; \
      exit 1; \
    fi; \
    modules="$(jdeps --multi-release 25 --recursive --ignore-missing-deps --print-module-deps --class-path "$dependency_classpath" /tmp/app/BOOT-INF/classes)"; \
    test -n "$modules"; \
    modules="${modules},jdk.crypto.ec,java.instrument"; \
    printf 'jlink modules: %s\n' "$modules"; \
    jlink \
      --add-modules "$modules" \
      --strip-debug \
      --no-header-files \
      --no-man-pages \
      --compress=2 \
      --output /opt/runtime; \
    cacerts="$(readlink -f "$JAVA_HOME/lib/security/cacerts")"; \
    test -s "$cacerts"; \
    rm -f /opt/runtime/lib/security/cacerts; \
    mkdir -p /opt/runtime/lib/security; \
    cp "$cacerts" /opt/runtime/lib/security/cacerts; \
    chmod 0644 /opt/runtime/lib/security/cacerts; \
    printf 'embedify:x:10001:10001:Embedify:/tmp:/sbin/nologin\n' > /tmp/embedify.passwd; \
    printf 'embedify:x:10001:\n' > /tmp/embedify.group; \
    /opt/runtime/bin/java --version

RUN EMBEDIFY_OTEL_SMOKE_APP_JAVA=/opt/runtime/bin/java \
    bash /tmp/otel-smoke.sh /tmp/otelcol-contrib /tmp/collector.yaml /tmp/opentelemetry-javaagent.jar /tmp/embedify.jar "/tmp/otel-smoke:/tmp/app/BOOT-INF/lib/*"

# Minimal musl runtime with package metadata retained for full-image scanning.
FROM alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf

ARG QB_SOURCE_COMMIT=unknown
ENV QB_SOURCE_COMMIT=${QB_SOURCE_COMMIT}

ENV JAVA_HOME=/opt/runtime
WORKDIR /app

RUN apk add --no-cache ca-certificates-bundle=20261020-r0 libgcc=15.2.0-r5 libstdc++=15.2.0-r5 zlib=1.4-r0 \
    && test -s /etc/ssl/certs/ca-certificates.crt

COPY --from=runtime /tmp/embedify.passwd /etc/passwd
COPY --from=runtime /tmp/embedify.group /etc/group
COPY --from=runtime --chown=0:0 /opt/runtime/ /opt/runtime/
COPY --from=build --chown=10001:10001 /tmp/embedify.jar /app/embedify.jar
COPY --from=build --chown=0:0 --chmod=0444 /tmp/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar
COPY --from=build --chown=0:0 /tmp/otelcol-contrib /opt/otelcol-contrib
COPY --from=build --chown=0:0 /tmp/collector.yaml /etc/embedify-otel/collector.yaml
COPY --from=runtime --chown=0:0 /opt/healthcheck/ /opt/healthcheck/

USER 10001:10001

# The application reads the linked JRE but cannot replace its executable tree.
RUN --network=none test ! -w /opt/runtime && test ! -w /opt/runtime/bin/java

# Remote ADD inputs can carry owner-only permissions; prove the final-copy agent loads as the app user.
RUN --network=none ["/opt/runtime/bin/java", "-javaagent:/opt/opentelemetry-javaagent.jar", "-version"]

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=4s --start-period=45s --retries=3 CMD ["/opt/runtime/bin/java", "-cp", "/opt/healthcheck", "ContainerHealthCheck"]

ENTRYPOINT ["/opt/runtime/bin/java", "-javaagent:/opt/opentelemetry-javaagent.jar", "-XX:MaxRAMPercentage=60.0", "-Djava.io.tmpdir=/tmp", "-Duser.home=/tmp", "-Djava.awt.headless=true", "-jar", "/app/embedify.jar"]
