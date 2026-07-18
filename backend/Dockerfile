# =============================================================================
#  Trove backend — container image (portable deploy)
# =============================================================================
#  Multi-stage: build the fat jar with Maven, run it on a slim JRE. Makes the API
#  deployable to any container host (Oracle VM with Docker, Fly, Render, Koyeb, …)
#  with the same env vars as .env.example. Nothing durable lives in the container.
# =============================================================================

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# ---- run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# postgresql-client provides pg_dump for the optional nightly DB backup job.
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/trove-backend-0.1.0-SNAPSHOT.jar app.jar
ENV TROVE_BACKUP_PG_DUMP_PATH=pg_dump
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
