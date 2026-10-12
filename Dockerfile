# ─────────────────────────────────────────────────────────────────────────────
# Image unique pour les 7 applications du backend (coeur, passerelle, 5 services).
#
# Le module a embarquer est choisi au build :
#     docker build --build-arg MODULE=service-identite -t sencsu/identite backend/
#
# L'etape « builder » ne depend PAS de MODULE : elle compile le reacteur complet
# une seule fois et les sept images se partagent la meme couche de compilation.
# Seule l'etape finale, minuscule, differe d'une image a l'autre.
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.5-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# L'agregateur declare 9 modules : Maven construit le reacteur en lisant CHAQUE
# repertoire declare. En omettre un seul fait echouer le build des la validation
# (« Child module ... does not exist »), meme avec -pl : ils sont donc tous copies.
COPY pom.xml .
COPY commun               commun
COPY socle                socle
COPY coeur                coeur
COPY api-gateway          api-gateway
COPY service-identite     service-identite
COPY service-referentiel  service-referentiel
COPY service-pharmacie    service-pharmacie
COPY service-poste-sante  service-poste-sante
COPY service-centre-sante service-centre-sante

# Le cache BuildKit garde le depot Maven hors de l'image : les dependances ne sont
# telechargees qu'une fois, et un simple changement de source ne les retelecharge pas.
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime
# Defaut « coeur » : render.yaml construit ce Dockerfile SANS build-arg et attend
# l'application historique. Le retirer casserait silencieusement le deploiement Render.
ARG MODULE=coeur
ARG PORT=8080
WORKDIR /app

# Utilisateur non privilegie : un service web ne s'execute pas en root.
RUN addgroup -S csu && adduser -S -G csu csu
COPY --from=builder --chown=csu:csu /build/${MODULE}/target/*.jar app.jar
USER csu

EXPOSE ${PORT}

# Conteneur contraint en memoire : par defaut la JVM ne prend que ~25 % de la RAM
# en tas. MaxRAMPercentage=75 l'exploite davantage ; SerialGC reduit l'empreinte du
# ramasse-miettes sur les petites instances. Surchargeable via JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseSerialGC", "-jar", "app.jar"]
