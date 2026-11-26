FROM python:3.13-slim
WORKDIR /app
# rclone serves cloud backups; openssl makes the certificate phones pair with (companion_tls.py); slixmpp runs native XMPP; matrix-nio with vodozemac handles Matrix encryption.
RUN apt-get update && apt-get install -y --no-install-recommends rclone ca-certificates openssl \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir "slixmpp==1.17.0" "aiohttp>=3.10" "matrix-nio[e2e]==0.26.0" \
    && groupadd --system fluid && useradd --system --gid fluid fluid && mkdir /data && chown fluid:fluid /data
# Every file the web app serves (main.py's FILES), copied by pattern so a new script or icon can't be left out.
COPY *.py *.html *.js *.css *.webp *.webmanifest ./
COPY shared/ ./shared/
COPY icons/ ./icons/
COPY samples/ ./samples/
USER fluid
ENV FLUID_BIND=0.0.0.0 FLUID_DB=/data/fluid.sqlite
# 8080 is the web app; 8081 is HTTPS for phones, with the certificate made on first start (FLUID_TLS_PORT).
EXPOSE 8080 8081
CMD ["python", "main.py"]
