FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY plugin/ plugin/
RUN mvn -q -B -f plugin/pom.xml package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl python3 && rm -rf /var/lib/apt/lists/*
WORKDIR /mingle
COPY start.sh build.sh ./
COPY server/ server/
COPY --from=build /src/plugin/target/Mingle.jar plugin/target/Mingle.jar
EXPOSE 25565
CMD ["./start.sh"]
