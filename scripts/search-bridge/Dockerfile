# Cloudimage search bridge — deployable image for Fly.io / Render / any
# Docker host. Inside this container the search provider is always
# `googlecse`, fed by the GOOGLE_CSE_KEY / GOOGLE_CSE_CX secrets — see
# DEPLOY.md.
FROM node:20-alpine

WORKDIR /app
ENV NODE_ENV=production

# The bridge is dependency-free Node — npm install is a fast no-op that
# keeps this layer honest if dependencies are ever added.
COPY package.json ./
RUN npm install --omit=dev

COPY server.js ./

# Run as the unprivileged node user.
USER node

ENV PORT=3000
EXPOSE 3000
CMD ["node", "server.js"]
