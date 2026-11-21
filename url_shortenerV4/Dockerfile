FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY . .

RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:21-jre

RUN useradd --system --no-create-home appuser

WORKDIR /app

COPY --from=build /app/target/Url_Shortener-0.0.1-SNAPSHOT.jar app.jar

USER appuser

EXPOSE 8080

# Set JAVA_DEBUG_OPTS to enable remote debugging, for example:
# -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_DEBUG_OPTS:-} -jar app.jar"]
