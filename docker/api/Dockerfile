FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY services/pom.xml services/pom.xml
COPY services/common/pom.xml services/common/pom.xml
COPY services/persistence/pom.xml services/persistence/pom.xml
COPY services/api/pom.xml services/api/pom.xml
COPY services/collab/pom.xml services/collab/pom.xml
COPY services/worker/pom.xml services/worker/pom.xml
COPY services/common/src services/common/src
COPY services/persistence/src services/persistence/src
COPY services/api/src services/api/src
RUN mvn -f services/pom.xml -pl api -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget
WORKDIR /app
COPY --from=build /src/services/api/target/collabdocs-api.jar /app/service.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=12 CMD wget -qO- http://127.0.0.1:8080/health/live || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/service.jar"]
