# --- Build Stage ---
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build

WORKDIR /app

# Copy pom.xml and download dependencies to leverage Docker cache
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy the rest of the application source code
COPY src ./src

# Package the application into a JAR
RUN mvn package -DskipTests

# --- Run Stage ---
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Create a non-root user and group
RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

# Copy the built JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Expose the port the application runs on
EXPOSE 8080

# Command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
