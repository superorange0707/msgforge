# Builds the same zip a release ships, then runs what is in it.
#   docker build -t msgforge .
#   docker run -p 8585:8585 -v msgforge-data:/data msgforge

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
# The JDK's jar tool unpacks the zip, so the image needs no unzip.
RUN ./gradlew --no-daemon dist \
 && mkdir /out && cd /out \
 && jar xf /src/build/distributions/msgforge-*.zip \
 && mv msgforge-* msgforge

FROM eclipse-temurin:17-jre
RUN groupadd --system --gid 10001 msgforge \
 && useradd --system --uid 10001 --gid msgforge --home-dir /app msgforge \
 && mkdir /data \
 && chown msgforge:msgforge /data
COPY --from=build --chown=msgforge:msgforge /out/msgforge /app
WORKDIR /app
# Reachable from outside the container; who may reach it is the platform's job (msgforge has no login).
# Everything the console changes is on the volume: msgforge.json and the packs.
ENV SERVER_ADDRESS=0.0.0.0 \
    MSGFORGE_CONFIG=/data/msgforge.json \
    MSGFORGE_PACKS=/data/packs
VOLUME /data
EXPOSE 8585
USER msgforge
# On a volume that has no packs yet, start it off with the bundled ones: copies there can be edited and deleted,
# and survive a new image. Later starts leave the folder alone. Arguments to `docker run` go to msgforge.
ENTRYPOINT ["sh", "-c", "[ -e /data/packs ] || cp -R /app/packs /data/packs; exec java -jar msgforge.jar \"$@\"", "msgforge"]
