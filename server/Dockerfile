FROM python:3.13-alpine
WORKDIR /app
RUN apk add --no-cache rclone && addgroup -S fluid && adduser -S -G fluid fluid && mkdir /data && chown fluid:fluid /data
COPY main.py cloud_backup.py index.html app.js style.css ./
USER fluid
ENV FLUID_BIND=0.0.0.0 FLUID_DB=/data/fluid.sqlite
EXPOSE 8080
CMD ["python", "main.py"]
