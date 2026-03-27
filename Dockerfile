FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app
COPY pom.xml .
COPY src ./src

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
RUN mvn clean package -DskipTests

# ← YEH ADD KARO
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN mvn exec:java -e \
    -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.args="install chromium"

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y \
    wget curl unzip ca-certificates \
    libnss3 libnspr4 libdbus-1-3 \
    libatk1.0-0 libatk-bridge2.0-0 \
    libcups2 libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 \
    libxfixes3 libxrandr2 libgbm1 libasound2 libpango-1.0-0 libcairo2 \
    libx11-6 libx11-xcb1 libxcb1 libxext6 fonts-liberation libfontconfig1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar
# ← YEH ADD KARO
COPY --from=builder /ms-playwright /ms-playwright
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

RUN mkdir -p /app/output/data /app/output/pdfs /app/output/debug /app/output/downloads

EXPOSE 8080
ENV PORT=8080
ENV HEADLESS=true

ENTRYPOINT ["java", \
    "-Xms256m", "-Xmx768m", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=200", \
    "-jar", "app.jar"]
