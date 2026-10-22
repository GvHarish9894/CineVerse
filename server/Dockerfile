# CineVerse server (TMDB proxy + sessions). Build from the repository root:
#   docker build -f server/Dockerfile -t cineverse-server .
# Only the files listed in the root .dockerignore enter the build context (never secrets.properties).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY server server
# settings.gradle.kts includes the client modules; with configure-on-demand only :server is configured,
# so empty directories are enough and no Android SDK is needed. The Gradle daemon JVM (25) is provisioned
# from gradle/gradle-daemon-jvm.properties.
RUN mkdir -p shared tvApp composeApp androidApp \
    && ./gradlew --no-daemon --configure-on-demand --no-configuration-cache :server:installDist \
    && chmod -R a+rX,go-w server/build/install

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home cineverse
COPY --from=build /src/server/build/install/server /app
USER 10001
ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
