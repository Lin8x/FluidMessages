FROM python:3.13-slim
WORKDIR /app
# rclone serves cloud backups; slixmpp runs native XMPP; matrix-nio with vodozemac handles Matrix encryption.
RUN apt-get update && apt-get install -y --no-install-recommends rclone ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir "slixmpp==1.17.0" "aiohttp>=3.10" "matrix-nio[e2e]==0.26.0" \
    && groupadd --system fluid && useradd --system --gid fluid fluid && mkdir /data && chown fluid:fluid /data
COPY main.py cloud_backup.py protocol_gateway.py accounts.py irc_client.py matrix_e2ee.py xmpp_client.py index.html app.js style.css ./
USER fluid
ENV FLUID_BIND=0.0.0.0 FLUID_DB=/data/fluid.sqlite
EXPOSE 8080
CMD ["python", "main.py"]
