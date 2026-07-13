FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

EXPOSE 8080
EXPOSE 5005

ENTRYPOINT ["sh", "-c", "java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
    -Dspring.data.mongodb.uri=${SPRING_DATA_MONGODB_URI:-mongodb://mongodb:27017/urlshortener} \
    -Dspring.kafka.bootstrap-servers=${SPRING_KAFKA_BOOTSTRAP_SERVERS:-kafka:9092} \
    -jar target/Url_Shortener-0.0.1-SNAPSHOT.jar"]
