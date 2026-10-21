# syntax=docker/dockerfile:1

FROM otel/opentelemetry-collector-contrib:0.163.2@sha256:7f22d2a6748110ffb6a59bc0df448bccbf501cdb667d610ede4c61d3863d5849 AS collector

FROM maven:3.9.16-eclipse-temurin-25-noble@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src/ ./src/
COPY LICENSE ./
COPY scripts/ContainerHealthCheck.java /tmp/ContainerHealthCheck.java
COPY scripts/OtelSmoke.java /tmp/OtelSmoke.java
COPY scripts/otel-smoke.sh /tmp/otel-smoke.sh
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

RUN install -D -m 0644 /etc/ssl/certs/ca-certificates.crt /tmp/ca-certificates.crt

RUN set -eu; \
    mvn -B -ntp -Dmaven.test.skip=true package; \
    artifact="$(find /workspace/target -maxdepth 1 -type f -name 'embedify-*.jar' ! -name '*.original' -print -quit)"; \
    test -n "$artifact"; \
    cp "$artifact" /tmp/embedify.jar; \
    mkdir -p /tmp/app; \
    cd /tmp/app; \
    jar --extract --file /tmp/embedify.jar

RUN set -eu; \
    dependency_classpath="$(find /tmp/app/BOOT-INF/lib -maxdepth 1 -type f -name '*.jar' -printf '%p:')"; \
    test -n "$dependency_classpath"; \
    mkdir -p /tmp/otel-smoke; \
    javac --release 25 -classpath "$dependency_classpath" -d /tmp/otel-smoke /tmp/OtelSmoke.java; \
    bash /tmp/otel-smoke.sh /tmp/otelcol-contrib /tmp/collector.yaml /tmp/opentelemetry-javaagent.jar /tmp/embedify.jar "/tmp/otel-smoke:/tmp/app/BOOT-INF/lib/*"

RUN set -eu; \
    mkdir -p /opt/healthcheck; \
    javac --release 25 -d /opt/healthcheck /tmp/ContainerHealthCheck.java; \
    dependency_classpath="$(find /tmp/app/BOOT-INF/lib -maxdepth 1 -type f -name '*.jar' -printf '%p:')"; \
    test -n "$dependency_classpath"; \
    missing_dependencies="$(jdeps --multi-release 25 --missing-deps --class-path "$dependency_classpath" /tmp/app/BOOT-INF/classes)"; \
    if [ -n "$missing_dependencies" ]; then \
      printf '%s\n' 'jdeps found unresolved application dependencies:' "$missing_dependencies" >&2; \
      exit 1; \
    fi; \
    modules="$(jdeps --multi-release 25 --recursive --ignore-missing-deps --print-module-deps --class-path "$dependency_classpath" /tmp/app/BOOT-INF/classes)"; \
    test -n "$modules"; \
    modules="${modules},jdk.crypto.ec"; \
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
    install -m 0644 "$cacerts" /opt/runtime/lib/security/cacerts; \
    printf 'embedify:x:10001:10001:Embedify:/tmp:/sbin/nologin\n' > /tmp/embedify.passwd; \
    printf 'embedify:x:10001:\n' > /tmp/embedify.group; \
    /opt/runtime/bin/java --version

# Minimal glibc/C++ runtime; retain its package metadata for full-image scanning.
FROM cgr.dev/chainguard/glibc-dynamic:latest@sha256:82edc253a57efee78d0fb504e11a93b7c74687b1b736110ad3a2a4f3edf632ab

ENV JAVA_HOME=/opt/runtime
WORKDIR /app

COPY --from=build /tmp/embedify.passwd /etc/passwd
COPY --from=build /tmp/embedify.group /etc/group
COPY --from=build --chown=10001:10001 /opt/runtime/ /opt/runtime/
COPY --from=build --chown=10001:10001 /tmp/embedify.jar /app/embedify.jar
COPY --from=build --chown=0:0 /tmp/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar
COPY --from=build --chown=0:0 /tmp/otelcol-contrib /opt/otelcol-contrib
COPY --from=build --chown=0:0 /tmp/collector.yaml /etc/embedify-otel/collector.yaml
COPY --from=build --chown=0:0 /tmp/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build --chown=0:0 /opt/healthcheck/ /opt/healthcheck/

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=4s --start-period=45s --retries=3 CMD ["/opt/runtime/bin/java", "-cp", "/opt/healthcheck", "ContainerHealthCheck"]

ENTRYPOINT ["/opt/runtime/bin/java", "-javaagent:/opt/opentelemetry-javaagent.jar", "-XX:MaxRAMPercentage=60.0", "-Djava.io.tmpdir=/tmp", "-Duser.home=/tmp", "-Djava.awt.headless=true", "-jar", "/app/embedify.jar"]
