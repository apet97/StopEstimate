FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline || true
COPY src/ src/
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache curl \
    && addgroup -S app \
    && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/stop-at-estimate-0.1.0-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
