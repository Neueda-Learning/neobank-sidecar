# syntax=docker/dockerfile:1
# Multi-stage build: compile with Maven, run on a slim JRE.
#
# Unlike the module images, this one is PUBLISHED (ghcr.io/gjavolce/neobank-sidecar) and
# pulled by every team — nobody builds it from source in a module repo. Keep it small and
# keep it self-contained: the scenario corpus and the whole UI are baked in, so the image
# plus a MySQL is the entire sidecar.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# curl is used by the compose healthcheck to hit /health.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
