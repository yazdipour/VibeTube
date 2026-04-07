# VibeTube

VibeTube is a self-hosted web app for browsing and watching YouTube content with a SmartTube-inspired experience.

It includes:

- Home, Subscriptions, Channels, Library, Watch Later, and Search pages
- YouTube device-code sign-in
- Channel upload browsing
- Watch Later add/remove support
- yt-dlp-backed video playback metadata
- Subtitle track support
- SponsorBlock segment lookup
- PWA support
- Docker Compose setup for local self-hosting

This project is not affiliated with YouTube, Google, or SmartTube.

## Requirements

- Docker and Docker Compose
- Google OAuth credentials for a TV / Limited Input app
- A YouTube InnerTube API key provided through `YOUTUBE_INNERTUBE_API_KEY`

## Quick Start

1. Copy the example environment file:

   ```sh
   cp .env.example .env
   ```

2. Fill in `.env`:

   ```sh
   YOUTUBE_OAUTH_CLIENT_ID=...
   YOUTUBE_OAUTH_CLIENT_SECRET=...
   YOUTUBE_INNERTUBE_API_KEY=...
   YOUTUBE_YTDLP_EXTERNAL_URL=http://localhost:8081
   ```

3. Start the app:

   ```sh
   docker compose up -d --build
   ```

4. Open:

   ```text
   http://localhost:3000
   ```

## Services

- `frontend`: nginx static frontend and PWA shell, exposed at `http://localhost:3000`
- `backend`: Kotlin / Spring Boot API, exposed at `http://localhost:8080`
- `yt-dlp`: Flask helper service for video metadata and subtitles, exposed at `http://localhost:8081`

## Development Checks

Frontend syntax:

```sh
node --check frontend/app.js
node --check frontend/sw.js
```

Backend tests, without requiring a local Gradle install:

```sh
docker compose up -d --build
# or
docker run --rm -v "$PWD/backend:/app" -w /app gradle:8.10.2-jdk17 gradle test --no-daemon
```

Python syntax:

```sh
python3 -m py_compile yt-dlp-server/server.py
```

## Security Notes

- Never commit `.env` or OAuth secrets.
- YouTube session data is stored under the backend data directory, mounted as the `backend-data` Docker volume by default.
- Public deployments should be protected behind authentication or a private network.

## License

MIT. See [LICENSE](LICENSE).
