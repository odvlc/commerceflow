# Build browser assets separately from the nonroot static server; browser-visible OIDC
# configuration is fixed at build time.
FROM node:26-alpine AS build
WORKDIR /app
# Cache dependency installation independently of changing UI source files.
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
# Vite substitutes this into browser assets during build; changing only nginx runtime environment will
# not replace it.
ARG VITE_KEYCLOAK_URL=http://localhost:8180
ENV VITE_KEYCLOAK_URL=${VITE_KEYCLOAK_URL}
RUN npm run lint && npm test && npm run build
FROM nginx:1.30-alpine
COPY frontend/nginx.conf /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s CMD wget -q -O /dev/null http://127.0.0.1:8080/health
