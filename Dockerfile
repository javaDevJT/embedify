# syntax=docker/dockerfile:1

FROM maven:3-eclipse-temurin-24-noble@sha256:a137a467ec89b5713d0be817b55bdba6b4d6ef16e3d05565a79bc08d8e775a1c AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src/ ./src/
COPY scripts/ContainerHealthCheck.java /tmp/ContainerHealthCheck.java

RUN set -eu; \
    mvn -B -ntp -Dmaven.test.skip=true package; \
    artifact="$(find /workspace/target -maxdepth 1 -type f -name 'embedify-*.jar' ! -name '*.original' -print -quit)"; \
    test -n "$artifact"; \
    cp "$artifact" /tmp/embedify.jar; \
    mkdir -p /tmp/app; \
    cd /tmp/app; \
    jar --extract --file /tmp/embedify.jar

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

FROM gcr.io/distroless/cc-debian13:nonroot@sha256:54df941ed0d06a1bd95ef5e0ce391fd8d9f94b64782dc9a60062727849ee3f97

ENV JAVA_HOME=/opt/runtime
WORKDIR /app

COPY --from=build /tmp/embedify.passwd /etc/passwd
COPY --from=build /tmp/embedify.group /etc/group
COPY --from=build --chown=10001:10001 /opt/runtime/ /opt/runtime/
COPY --from=build --chown=10001:10001 /tmp/embedify.jar /app/embedify.jar
COPY --from=build --chown=0:0 /opt/healthcheck/ /opt/healthcheck/

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=4s --start-period=45s --retries=3 CMD ["/opt/runtime/bin/java", "-cp", "/opt/healthcheck", "ContainerHealthCheck"]

ENTRYPOINT ["/opt/runtime/bin/java", "-XX:MaxRAMPercentage=60.0", "-Djava.io.tmpdir=/tmp", "-Duser.home=/tmp", "-Djava.awt.headless=true", "-jar", "/app/embedify.jar"]
