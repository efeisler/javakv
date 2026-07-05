# Build stage: uses the project's own Maven Wrapper, so no Maven install is required anywhere.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -q package -DskipTests

# Runtime stage: slim JRE only, no build tooling shipped.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/javakv.jar javakv.jar

# Data lives on a mounted volume, not baked into the image, so `docker run` restarts (or a
# fresh container from the same image) see prior data as long as the volume is reused.
VOLUME ["/data"]
EXPOSE 7379

ENTRYPOINT ["java", "-jar", "javakv.jar"]
CMD ["7379", "/data"]
