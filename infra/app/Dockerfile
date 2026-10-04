# ===== Build stage =====
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# Сначала только pom — кэшируем зависимости Maven
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src src
RUN mvn -q -DskipTests package

# ===== Runtime stage =====
# Полный JDK: нужны jcmd, jstack, jmap для расследования
FROM eclipse-temurin:25-jdk AS runtime
WORKDIR /app

# async-profiler для CPU/allocation-профилирования
ARG ASYNC_PROFILER_VERSION=3.0
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget ca-certificates \
    && wget -q "https://github.com/async-profiler/async-profiler/releases/download/v${ASYNC_PROFILER_VERSION}/async-profiler-${ASYNC_PROFILER_VERSION}-linux-x64.tar.gz" \
    && tar -xzf "async-profiler-${ASYNC_PROFILER_VERSION}-linux-x64.tar.gz" \
    && mv "async-profiler-${ASYNC_PROFILER_VERSION}-linux-x64" /opt/async-profiler \
    && rm "async-profiler-${ASYNC_PROFILER_VERSION}-linux-x64.tar.gz" \
    && apt-get purge -y --auto-remove wget \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

# Volume для артефактов расследования (gc.log, heap dump, JFR, NMT)
RUN mkdir -p /artifacts

EXPOSE 8080

ENTRYPOINT ["java", \
    "-Xlog:gc*:file=/artifacts/gc.log:time,uptime,level,tags", \
    "-XX:NativeMemoryTracking=summary", \
    "-XX:+HeapDumpOnOutOfMemoryError", \
    "-XX:HeapDumpPath=/artifacts", \
    "-jar", "/app/app.jar"]
