FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir flask flask-cors yt-dlp

COPY server.py .

EXPOSE 8081

CMD ["python", "server.py"]