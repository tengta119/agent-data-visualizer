FROM maven:3.9.9-eclipse-temurin-17 AS backend-builder
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY data-visualizer-api ./data-visualizer-api
COPY data-visualizer-app ./data-visualizer-app
COPY data-visualizer-domain ./data-visualizer-domain
COPY data-visualizer-trigger ./data-visualizer-trigger
COPY data-visualizer-infrastructure ./data-visualizer-infrastructure
COPY data-visualizer-types ./data-visualizer-types

RUN mvn -pl data-visualizer-app -am clean package -DskipTests

FROM node:22-alpine AS frontend-builder
WORKDIR /workspace/data-visualizer-front

COPY data-visualizer-front/package.json data-visualizer-front/package-lock.json* ./
RUN npm ci

COPY data-visualizer-front ./
ARG NEXT_PUBLIC_API_BASE=http://localhost:8091
ENV NEXT_PUBLIC_API_BASE=${NEXT_PUBLIC_API_BASE}
RUN npm run build

FROM node:22-alpine AS runtime
RUN apk add --no-cache openjdk17-jre bash ca-certificates

WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

COPY --from=backend-builder /workspace/data-visualizer-app/target/ai-agent-scaffold-lite-app.jar /app/backend/app.jar
COPY --from=backend-builder /workspace/data-visualizer-app/src/main/resources/agent/skills /app/frontend/agent/skills
COPY --from=frontend-builder /workspace/data-visualizer-front/.next /app/frontend/.next
COPY --from=frontend-builder /workspace/data-visualizer-front/public /app/frontend/public
COPY --from=frontend-builder /workspace/data-visualizer-front/package.json /app/frontend/package.json
COPY --from=frontend-builder /workspace/data-visualizer-front/node_modules /app/frontend/node_modules
COPY start.sh /app/start.sh

RUN chmod +x /app/start.sh \
    && chown -R node:node /app

USER node

EXPOSE 3000 8091 9077
CMD ["/app/start.sh"]
