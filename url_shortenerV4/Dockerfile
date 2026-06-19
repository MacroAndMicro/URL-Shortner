FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

EXPOSE 8080
EXPOSE 5005

#CMD ["java","-jar","target/Url_Shortener-0.0.1-SNAPSHOT.jar"]
ENTRYPOINT ["java","-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005","-jar","target/Url_Shortener-0.0.1-SNAPSHOT.jar"]
