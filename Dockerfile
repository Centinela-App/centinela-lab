# =============================================================================
# centinela-lab — banco de pruebas de Centinela.
#
# Multietapa por la misma razon que en Centinela: lo que compila no viaja a
# produccion, y no por tamano sino porque las capas conservan todo lo que
# existio en ellas. Borrar el repositorio de Maven en una capa posterior no lo
# elimina de la imagen; no copiarlo si.
# =============================================================================

FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests \
    && mv target/*.jar /build/application.jar

# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S lab && adduser -S lab -G lab

WORKDIR /app
COPY --from=build --chown=lab:lab /build/application.jar ./application.jar

USER lab
EXPOSE 8081

# Sin credenciales. La identidad de esta aplicacion la resuelve
# DefaultAzureCredential contra Managed Identity cuando corre en Azure; el
# unico dato de configuracion son las URL, que no son secretos.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC" \
    SERVER_PORT=8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -q -O- http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]
