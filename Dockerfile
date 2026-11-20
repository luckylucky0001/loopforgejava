# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY loopforge-core/pom.xml loopforge-core/
COPY loopforge-runner/pom.xml loopforge-runner/
COPY loopforge-server/pom.xml loopforge-server/
# Downloads are retried: a transient registry hiccup should not fail the image build.
RUN --mount=type=cache,target=/root/.m2 \
    for attempt in 1 2 3 4 5; do ./mvnw -B -ntp -q --version > /dev/null && break; sleep $((attempt * 5)); done; \
    ./mvnw -B -ntp -q --version > /dev/null
RUN --mount=type=cache,target=/root/.m2 \
    for attempt in 1 2 3; do ./mvnw -B -ntp -q dependency:go-offline -DexcludeReactor=true && break; sleep 10; done || true
COPY loopforge-core/src loopforge-core/src
COPY loopforge-runner/src loopforge-runner/src
COPY loopforge-server/src loopforge-server/src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
    && java -Djarmode=tools -jar loopforge-server/target/loopforge-server-*.jar extract --layers --launcher --destination /extracted \
    && mkdir -p /extracted/runner && cp loopforge-runner/target/loopforge-runner-*-all.jar /extracted/runner/loopforge-runner.jar

FROM eclipse-temurin:25-jre
# Scripts and agent CLIs commonly need git, python3 and a POSIX shell. Agent CLIs themselves are not
# bundled (some are proprietary); extend this image to add the ones you are licensed to use.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git python3 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system loopforge && useradd --system --gid loopforge --create-home --home-dir /home/loopforge loopforge \
    && mkdir -p /data && chown loopforge:loopforge /data
ENV LANG=C.UTF-8 \
    LOOPFORGE_DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
# the execution machine jar, downloadable from the console (执行机器)
COPY --from=build /extracted/runner/ /app/runner/
COPY LICENSE THIRD_PARTY_NOTICES.md /app/
USER loopforge
VOLUME ["/data"]
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
    CMD curl -fsS http://localhost:8090/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
