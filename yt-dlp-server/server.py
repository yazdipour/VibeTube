#!/usr/bin/env python3
import subprocess
import json
import sys
import os
import tempfile
import urllib.parse
import urllib.request
from flask import Flask, request, jsonify, Response
from flask_cors import CORS
import signal
import time

app = Flask(__name__)
CORS(app)

# Simple cache: video_id -> {'path': str, 'size': int, 'timestamp': float}
cache = {}
CACHE_TTL = 3600  # 1 hour


def cleanup(signum, frame):
    sys.exit(0)


signal.signal(signal.SIGTERM, cleanup)
signal.signal(signal.SIGINT, cleanup)


def get_available_formats(video_id):
    """Get available formats for a video"""
    cmd = [
        "yt-dlp",
        "--no-download",
        "--list-formats",
        "--no-warnings",
        f"https://www.youtube.com/watch?v={video_id}",
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)

    if result.returncode != 0:
        return []

    formats = []
    lines = result.stdout.split("\n")
    # Skip header lines until we find the separator
    format_start = False
    for line in lines:
        if (
            "--------------------------------------------------------------------------------------------------"
            in line
        ):
            format_start = True
            continue
        if not format_start:
            continue
        if not line.strip() or line.startswith("[info]"):
            continue

        parts = line.split()
        if len(parts) >= 4:  # ID, EXT, RESOLUTION, FPS at minimum
            try:
                format_id = parts[0]
                ext = parts[1]
                resolution = parts[2]
                fps = parts[3] if len(parts) > 3 else "0"

                # Skip if FPS is not a number (like 'audio' or 'video')
                if not fps.replace(".", "").isdigit():
                    fps = "0"

                # Skip audio-only formats and storyboard formats
                if "audio" in line.lower() and "video" not in line.lower():
                    continue
                if "storyboard" in line.lower():
                    continue

                # Only include formats with actual resolution (not just audio)
                if resolution != "audio only" and "x" in resolution:
                    formats.append(
                        {
                            "format_id": format_id,
                            "ext": ext,
                            "resolution": resolution,
                            "fps": fps,
                        }
                    )
            except (ValueError, IndexError):
                pass
    return formats


def download_video(video_id, output_path, format_selector="bestvideo+bestaudio/best"):
    """Download video using yt-dlp with format selector"""
    cmd = [
        "yt-dlp",
        "-f",
        format_selector,
        "-o",
        output_path,
        "--no-warnings",
        "--no-part",  # Don't use .part files
        f"https://www.youtube.com/watch?v={video_id}",
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    return result.returncode == 0, result.stderr


def get_video_info(video_id):
    """Get video metadata"""
    cmd = [
        "yt-dlp",
        "--no-download",
        "--dump-json",
        "--no-warnings",
        f"https://www.youtube.com/watch?v={video_id}",
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)

    if result.returncode != 0:
        return None

    try:
        return json.loads(result.stdout)
    except:
        return None


def get_subtitle_tracks(video_id, info=None):
    """Get available manual and automatic subtitle tracks for a video."""
    info = info or get_video_info(video_id)
    if not info:
        return []

    tracks = []

    def append_tracks(source, automatic=False):
        for lang, entries in (source or {}).items():
            for entry in entries or []:
                if entry.get("ext") != "vtt" or not entry.get("url"):
                    continue
                tracks.append(
                    {
                        "language": lang,
                        "label": entry.get("name") or lang,
                        "automatic": automatic,
                    }
                )
                break

    append_tracks(info.get("subtitles"), automatic=False)
    append_tracks(info.get("automatic_captions"), automatic=True)

    def score(track):
        lang = track["language"].lower()
        english_score = 0 if lang == "en" else 1 if lang.startswith("en") else 2
        automatic_score = 1 if track["automatic"] else 0
        return (english_score, automatic_score, lang)

    seen = set()
    unique_tracks = []
    for track in sorted(tracks, key=score):
        key = (track["language"], track["automatic"])
        if key in seen:
            continue
        seen.add(key)
        unique_tracks.append(
            {
                **track,
                "default": len(unique_tracks) == 0,
            }
        )
    return unique_tracks


def get_subtitle_url(video_id, language, automatic=False):
    info = get_video_info(video_id)
    if not info:
        return None

    source = info.get("automatic_captions") if automatic else info.get("subtitles")
    entries = (source or {}).get(language) or []
    for entry in entries:
        if entry.get("ext") == "vtt" and entry.get("url"):
            return entry["url"]
    return None


def parse_range(range_header, file_size):
    """Parse HTTP Range header"""
    if not range_header or not range_header.startswith("bytes="):
        return None, None

    try:
        ranges = range_header[6:].split("-")
        start = int(ranges[0]) if ranges[0] else 0
        end = int(ranges[1]) if len(ranges) > 1 and ranges[1] else file_size - 1

        if start < 0:
            start = 0
        if end >= file_size:
            end = file_size - 1
        if start > end:
            return None, None

        return start, end
    except (ValueError, IndexError):
        return None, None


@app.route("/video/<video_id>", methods=["GET"])
def get_video(video_id):
    try:
        # Get format parameter from query string (default to max quality)
        format_selector = request.args.get("format", "bestvideo+bestaudio/best")

        # Clean expired cache entries
        now = time.time()
        expired = [
            vid for vid, data in cache.items() if now - data["timestamp"] > CACHE_TTL
        ]
        for vid in expired:
            try:
                if os.path.exists(cache[vid]["path"]):
                    os.unlink(cache[vid]["path"])
            except:
                pass
            del cache[vid]

        # Create cache key that includes format selector
        cache_key = f"{video_id}_{format_selector}"

        # Check cache
        if cache_key in cache and os.path.exists(cache[cache_key]["path"]):
            video_path = cache[cache_key]["path"]
            file_size = cache[cache_key]["size"]
        else:
            # Download video
            temp_file = tempfile.NamedTemporaryFile(suffix=".mp4", delete=False)
            temp_path = temp_file.name
            temp_file.close()

            print(
                f"Downloading {video_id} with format {format_selector}...", flush=True
            )
            success, error = download_video(video_id, temp_path, format_selector)

            if not success:
                try:
                    os.unlink(temp_path)
                except:
                    pass
                return jsonify({"error": error or "Download failed"}), 500

            if not os.path.exists(temp_path) or os.path.getsize(temp_path) == 0:
                try:
                    os.unlink(temp_path)
                except:
                    pass
                return jsonify({"error": "Download produced empty file"}), 500

            file_size = os.path.getsize(temp_path)
            cache[cache_key] = {"path": temp_path, "size": file_size, "timestamp": now}
            video_path = temp_path
            print(f"Downloaded {video_id}: {file_size} bytes", flush=True)

        # Handle range requests
        range_header = request.headers.get("Range")
        start = 0
        end = file_size - 1

        if range_header:
            start, end = parse_range(range_header, file_size)
            if start is None or end is None:
                return jsonify({"error": "Invalid range"}), 416

        # Serve video content
        def generate():
            with open(video_path, "rb") as f:
                f.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    chunk_size = min(65536, remaining)
                    chunk = f.read(chunk_size)
                    if not chunk:
                        break
                    yield chunk
                    remaining -= len(chunk)

        content_length = end - start + 1

        headers = {
            "Content-Type": "video/mp4",
            "Content-Disposition": f'inline; filename="{video_id}.mp4"',
            "Accept-Ranges": "bytes",
            "Content-Length": str(content_length),
        }

        if range_header is not None:
            headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
            status = 206
        else:
            status = 200

        return Response(
            generate(),
            status=status,
            headers=headers,
        )

    except subprocess.TimeoutExpired:
        return jsonify({"error": "Timeout fetching video"}), 504
    except Exception as e:
        import traceback

        traceback.print_exc()
        return jsonify({"error": str(e)}), 500


@app.route("/video/<video_id>/info", methods=["GET"])
def get_video_info_endpoint(video_id):
    try:
        info = get_video_info(video_id)
        if not info:
            return jsonify({"error": "Failed to fetch video info"}), 500

        return jsonify(
            {
                "videoId": video_id,
                "title": info.get("title", "Unknown"),
                "author": info.get("uploader", "Unknown"),
                "lengthSeconds": info.get("duration", 0),
                "thumbnailUrl": info.get("thumbnail", ""),
                "subtitles": get_subtitle_tracks(video_id, info),
            }
        )

    except subprocess.TimeoutExpired:
        return jsonify({"error": "Timeout fetching video info"}), 504
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/video/<video_id>/formats", methods=["GET"])
def get_video_formats(video_id):
    try:
        formats = get_available_formats(video_id)
        return jsonify({"formats": formats})
    except subprocess.TimeoutExpired:
        return jsonify({"error": "Timeout fetching video formats"}), 504
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/video/<video_id>/subtitles", methods=["GET"])
def get_video_subtitles(video_id):
    try:
        return jsonify({"subtitles": get_subtitle_tracks(video_id)})
    except subprocess.TimeoutExpired:
        return jsonify({"error": "Timeout fetching video subtitles"}), 504
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/video/<video_id>/subtitle", methods=["GET"])
def get_video_subtitle(video_id):
    try:
        language = request.args.get("lang", "en")
        automatic = request.args.get("automatic", "false").lower() == "true"
        subtitle_url = get_subtitle_url(video_id, language, automatic)
        if not subtitle_url:
            return jsonify({"error": "Subtitle track was not found"}), 404

        with urllib.request.urlopen(subtitle_url, timeout=30) as response:
            body = response.read()

        return Response(
            body,
            status=200,
            headers={
                "Content-Type": "text/vtt; charset=utf-8",
                "Content-Disposition": f'inline; filename="{video_id}-{urllib.parse.quote(language)}.vtt"',
            },
        )
    except subprocess.TimeoutExpired:
        return jsonify({"error": "Timeout fetching video subtitle"}), 504
    except Exception as e:
        return jsonify({"error": str(e)}), 500


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8081)
