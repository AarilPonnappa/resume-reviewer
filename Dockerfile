# ---------- build stage: compile and package the jar ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# download dependencies first so they are cached between builds when only source code changes
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# ---------- run stage: small image with just the JRE and the jar ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# run as a non-root user
RUN useradd --system --create-home appuser && mkdir -p /app/data && chown appuser /app/data
USER appuser

COPY --from=build /app/target/resumeiq-0.0.1-SNAPSHOT.jar app.jar

# Most hosts (Render, Railway, Fly.io...) set PORT; the app reads it (defaults to 8080)
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
