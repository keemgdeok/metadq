FROM eclipse-temurin:17-jdk AS build
WORKDIR /source
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src src
COPY LICENSE NOTICE ./
RUN ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /source/build/libs/metadq.jar metadq.jar
ENTRYPOINT ["java", "-jar", "/app/metadq.jar"]
