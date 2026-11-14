FROM node:24-bookworm-slim AS build
WORKDIR /workspace
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN corepack pnpm install --frozen-lockfile
COPY . .
ARG VITE_API_URL
ARG VITE_USE_MOCK=false
ENV VITE_API_URL=$VITE_API_URL VITE_USE_MOCK=$VITE_USE_MOCK
RUN node scripts/verify-deploy-env.mjs && npm run build && npm run check:p4

FROM caddy:2-alpine
COPY --from=build /workspace/dist /srv
COPY deploy/Caddyfile /etc/caddy/Caddyfile
ENV PORT=8080
EXPOSE 8080
