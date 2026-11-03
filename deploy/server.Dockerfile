FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY server/pom.xml ./pom.xml
COPY server/src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/datalens-server.jar ./app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
