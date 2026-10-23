FROM minio/mc:latest AS mc
FROM postgres:16-alpine
COPY --from=mc /usr/bin/mc /usr/local/bin/mc
RUN apk add --no-cache openssl bash
COPY docker/backup/backup.sh /backup.sh
RUN chmod +x /backup.sh
ENTRYPOINT ["/backup.sh"]
