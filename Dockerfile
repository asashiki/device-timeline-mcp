# syntax=docker/dockerfile:1.7
FROM node:24-bookworm-slim AS base
WORKDIR /app

FROM base AS deps
COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund

FROM deps AS build
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM base AS runtime
ENV NODE_ENV=production
COPY package.json package-lock.json* ./
RUN npm install --omit=dev --no-audit --no-fund
COPY --from=build /app/dist ./dist
COPY public ./public
# Default label map ships in the image. The compose file bind-mounts ./config
# over this so you can edit names without rebuilding (changes hot-reload).
COPY config ./config
ENV LABELS_PATH=/app/config/app-labels.json
ENV DB_PATH=/data/device-timeline.sqlite
EXPOSE 4200
CMD ["node", "dist/server.js"]
