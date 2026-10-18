FROM maven:3.10.0-eclipse-temurin-25-noble AS build
WORKDIR /workspace
COPY . .
ARG APP_MODULE
# The CI verification job runs the full native/Testcontainers suite first. Image builds compile/package the selected app.
RUN mvn -B -pl "${APP_MODULE}" -am package -DskipTests && \
    cp "${APP_MODULE}/target/${APP_MODULE}-0.1.0-SNAPSHOT-exec.jar" /tmp/app.jar && \
    javac -d /tmp/health infra/HealthCheck.java

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build --chown=10001:10001 /tmp/app.jar /app/app.jar
COPY --from=build /tmp/health /opt/health
USER 10001:10001
ENV PORT=8080 BIND_ADDRESS=0.0.0.0 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -Duser.timezone=UTC"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 CMD ["java","-cp","/opt/health","HealthCheck","http://localhost:8080/actuator/health/readiness"]
ENTRYPOINT ["java","-jar","/app/app.jar"]
