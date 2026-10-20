FROM nginx:1.27-alpine
COPY docker/nginx/nginx.conf /etc/nginx/conf.d/default.conf
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1/nginx-health || exit 1
