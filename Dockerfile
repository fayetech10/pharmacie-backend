FROM maven:3.9.5-eclipse-temurin-17-alpine as builder
WORKDIR /app
COPY pom.xml .
COPY src src

# Package without running tests
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
# Conteneur 512 Mo : par défaut la JVM ne prend que ~25% de la RAM en tas.
# MaxRAMPercentage=75 exploite davantage la mémoire ; SerialGC réduit l'empreinte
# du ramasse-miettes sur les petites instances. Surchargeable via JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseSerialGC", "-jar", "app.jar"]
