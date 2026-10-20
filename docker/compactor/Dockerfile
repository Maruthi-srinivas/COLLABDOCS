FROM node:22-alpine
WORKDIR /app
COPY services/compactor/package.json ./
COPY services/compactor/server.js ./
EXPOSE 8083
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD node -e "fetch('http://127.0.0.1:8083/health/live').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
USER node
CMD ["node", "server.js"]
