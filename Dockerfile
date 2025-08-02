#Use an official Maven image to build the Spring Boot App
FROM maven:3.9.9-eclipse-temurin-21 AS build

#Set the working directory
WORKDIR /app

#Copy he pom.xml and install. Dependencies
COPY pom.xml .
RUN mvn dependency:go-offline

#Copy the source code and build the application
COPY src ./src
RUN mvn clean package -DskipTests

#Use an official OpenJDK image to run the application
FROM openjdk:17-jdk-slim

#Set the working directory
WORKDIR /app

#Copy the built JAR file from the build stage
COPY --from=build /app/target/sb-ecom-0.0.1-SNAPSHOT.jar .

#Expose port 8080
EXPOSE 8080

#Specify the command to run the application
ENTRYPOINT ["java", "-jar", "/app/sb-ecom-0.0.1-SNAPSHOT.jar"]