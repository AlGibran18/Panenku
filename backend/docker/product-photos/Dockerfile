FROM golang:1.24-alpine AS builder
ARG MINIO_COMMIT=9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a
WORKDIR /src
# Optional CA mount supports corporate/session HTTPS proxies without persisting their CA.
RUN --mount=type=secret,id=proxy_ca --mount=type=tmpfs,target=/tmp \
    if [ -s /run/secrets/proxy_ca ]; then export SSL_CERT_FILE=/run/secrets/proxy_ca GIT_SSL_CAINFO=/run/secrets/proxy_ca; fi; \
    apk add --no-cache git ca-certificates && \
    git init . && \
    git remote add origin https://github.com/minio/minio.git && \
    git fetch --depth 1 origin "$MINIO_COMMIT" && \
    git checkout --detach FETCH_HEAD && \
    CGO_ENABLED=0 GOMAXPROCS=2 go build -p=2 -trimpath -ldflags='-s -w' -o /minio .
FROM alpine:3.22 AS runtime
RUN mkdir -p /data && chown 65532:65532 /data
COPY --from=builder /minio /usr/local/bin/minio
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=builder /src/LICENSE /usr/share/licenses/minio/LICENSE
USER 65532:65532
EXPOSE 9000 9001
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data", "--console-address", ":9001"]
