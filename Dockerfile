# syntax=docker/dockerfile:1

# Builds Pollster into a small image that runs it on port 8080, with its data in /data.
#
# Pollster builds Jetlin from source, because Jetlin isn't published to Maven yet. The build stage
# fetches Jetlin from GitHub into a directory next to Pollster's source, which is the layout
# settings.gradle.kts expects (../jetlin).

# ---------------------------------------------------------------------------------------- build
FROM eclipse-temurin:24-jdk AS build

# The Jetlin version to build against: a branch, a tag, or a commit SHA. `main` follows Jetlin as it
# changes. Set a commit SHA to make builds repeatable.
ARG JETLIN_REF=main
ARG JETLIN_REPO=https://github.com/boberj/jetlin.git

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

# Fetching only the one commit keeps the clone small, and works for a SHA as well as a branch or tag.
WORKDIR /src/jetlin
RUN git init -q \
    && git remote add origin "$JETLIN_REPO" \
    && git fetch -q --depth 1 origin "$JETLIN_REF" \
    && git checkout -q FETCH_HEAD

WORKDIR /src/pollster
COPY . .

# installDist builds the application and its dependencies into build/install/pollster: a bin/ script
# and a lib/ directory of JARs. The tests aren't run here. Run `./gradlew build` before pushing.
# The cache mount keeps Gradle's downloads between builds, on builders that support it.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain installDist

# -------------------------------------------------------------------------------------- runtime
FROM eclipse-temurin:24-jre

# Run as an unprivileged user, who owns only the data directory.
RUN useradd --system --create-home --uid 10001 pollster \
    && mkdir /data \
    && chown pollster /data

COPY --from=build /src/pollster/build/install/pollster /app
# Applied at startup, so a deployment that changes the schema migrates the database in /data.
COPY --from=build /src/pollster/db/migrations /app/migrations

# The database and the sign-in sessions live in /data. Mount a volume there, or every redeploy
# starts with no polls and signs everyone out.
ENV PORT=8080 \
    POLLSTER_DATA=/data \
    POLLSTER_MIGRATIONS=/app/migrations
VOLUME /data
EXPOSE 8080

USER pollster
ENTRYPOINT ["/app/bin/pollster"]
