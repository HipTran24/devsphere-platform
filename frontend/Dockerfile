# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# Stage 1: install dependencies and build the Next.js app
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY . .
RUN npm run build

# ---------------------------------------------------------------------------
# Stage 2: production-only dependencies
# ---------------------------------------------------------------------------
FROM node:22-alpine AS deps
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --omit=dev

# ---------------------------------------------------------------------------
# Stage 3: runtime image, non-root user
# ---------------------------------------------------------------------------
FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

RUN addgroup -S -g 10001 nextjs && adduser -S -u 10001 -G nextjs nextjs

COPY --from=deps --chown=nextjs:nextjs /app/node_modules ./node_modules
COPY --from=build --chown=nextjs:nextjs /app/.next ./.next
COPY --from=build --chown=nextjs:nextjs /app/public ./public
COPY --from=build --chown=nextjs:nextjs /app/package.json /app/next.config.js ./

USER nextjs
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
    CMD wget -q --spider http://127.0.0.1:3000/login || exit 1

CMD ["npm", "run", "start"]
