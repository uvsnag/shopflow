# Shared multi-stage Dockerfile for every Spring Boot service.
# Build with: docker build --build-arg SERVICE=order-service .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /build
COPY pom.xml .
COPY libs/common/pom.xml libs/common/pom.xml
COPY services services
COPY libs/common/src libs/common/src
RUN mvn -q -pl services/${SERVICE} -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /build/services/${SERVICE}/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s \
  CMD wget -qO- http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
