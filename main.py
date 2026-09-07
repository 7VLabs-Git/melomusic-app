import os
import re
import asyncio
from typing import Optional, List, Dict, Any
from fastapi import FastAPI, HTTPException, Request, Query, Response
from fastapi.responses import StreamingResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from ytmusicapi import YTMusic
import yt_dlp
import httpx
from cachetools import TTLCache

app = FastAPI(title="MELO Audio Engine", version="3.4.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

ytmusic = YTMusic()

# Caches
stream_cache = TTLCache(maxsize=1000, ttl=14400)      # 4 Hours
search_cache = TTLCache(maxsize=500, ttl=3600)        # 1 Hour
lyrics_cache = TTLCache(maxsize=500, ttl=86400)       # 24 Hours
rec_cache = TTLCache(maxsize=500, ttl=7200)           # 2 Hours
image_cache = TTLCache(maxsize=2000, ttl=86400)       # 24 Hours

http_client: Optional[httpx.AsyncClient] = None

BROWSER_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
    "Accept": "*/*",
    "Accept-Encoding": "identity",
}

def detect_cookie_file() -> Optional[str]:
    """Detects cookie file from environment variables, secret mounts, or workspace."""
    env_cookies = os.getenv("YOUTUBE_COOKIES")
    if env_cookies:
        try:
            with open("cookies.txt", "w", encoding="utf-8") as f:
                f.write(env_cookies.strip())
            return "cookies.txt"
        except Exception:
            pass

    candidates = [
        "cookies.txt",
        "/etc/secrets/cookies.txt",
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "cookies.txt"),
    ]
    for path in candidates:
        if os.path.exists(path) and os.path.getsize(path) > 10:
            return path
    return None

@app.on_event("startup")
async def startup_event():
    global http_client
    http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(20.0, connect=7.0),
        limits=httpx.Limits(max_keepalive_connections=80, max_connections=300),
        follow_redirects=True,
    )
    detect_cookie_file()

@app.on_event("shutdown")
async def shutdown_event():
    global http_client
    if http_client:
        await http_client.aclose()

def extract_stream_with_client(video_id: str, clients: List[str], cookie_path: Optional[str]) -> Optional[Dict[str, Any]]:
    """Extracts raw metadata without strict format enforcement, then picks the best audio track."""
    target_url = f"https://www.youtube.com/watch?v={video_id}"
    
    opts: Dict[str, Any] = {
        'format': None,          # Do not filter formats in yt-dlp to prevent 'Requested format is not available'
        'quiet': True,
        'no_warnings': True,
        'extract_flat': False,
        'noplaylist': True,
        'skip_download': True,
        'source_address': '0.0.0.0',
        'extractor_args': {
            'youtube': {
                'player_client': clients,
                'formats': ['missing_pot']
            }
        }
    }
    
    if cookie_path:
        opts['cookiefile'] = cookie_path

    proxy = os.getenv("YOUTUBE_PROXY") or os.getenv("HTTP_PROXY")
    if proxy:
        opts['proxy'] = proxy

    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(target_url, download=False)
        formats = info.get("formats") or []
        
        # 1. Filter for audio-only streams
        audio_streams = [
            f for f in formats 
            if f.get("url") and f.get("acodec") not in (None, "none") and f.get("vcodec") in (None, "none")
        ]
        
        # 2. Fallback to any format with an audio track
        if not audio_streams:
            audio_streams = [f for f in formats if f.get("url") and f.get("acodec") not in (None, "none")]
            
        # 3. Fallback to any playable stream URL
        if not audio_streams:
            audio_streams = [f for f in formats if f.get("url")]

        if not audio_streams:
            return None

        # Sort by highest bitrate
        audio_streams.sort(key=lambda x: (x.get("abr") or x.get("tbr") or 0), reverse=True)
        chosen = audio_streams[0]
        stream_url = chosen.get("url")

        ext = chosen.get("ext", "mp4")
        acodec = str(chosen.get("acodec", "")).lower()
        content_type = "audio/webm" if ("webm" in ext or "opus" in acodec) else "audio/mp4"

        headers = dict(info.get("http_headers", BROWSER_HEADERS))
        headers.pop("host", None)
        headers.pop("Host", None)

        return {
            "url": stream_url,
            "ext": ext,
            "content_type": content_type,
            "headers": headers
        }

def resolve_stream_sync(video_id: str) -> Dict[str, Any]:
    if video_id in stream_cache:
        return stream_cache[video_id]

    cookie_path = detect_cookie_file()
    
    client_strategies = [
        ['android_music'],
        ['ios', 'android'],
        ['web', 'mweb'],
    ]

    last_error = None
    for clients in client_strategies:
        try:
            result = extract_stream_with_client(video_id, clients, cookie_path)
            if result:
                stream_cache[video_id] = result
                print(f"[MELO:RESOLVER] Extracted audio for {video_id} with client: {clients}")
                return result
        except Exception as e:
            last_error = e
            continue

    raise HTTPException(status_code=500, detail=f"Audio extraction failed: {str(last_error)}")

async def get_stream_data(video_id: str) -> Dict[str, Any]:
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, resolve_stream_sync, video_id)

def clean_thumbnail_to_hd(url: str) -> str:
    if not url:
        return ""
    if "googleusercontent.com" in url or "ggpht.com" in url:
        if "=" in url:
            return re.sub(r'=.*$', '=w544-h544-l90-rj', url)
        return f"{url}=w544-h544-l90-rj"
    if "ytimg.com" in url:
        return url.replace("default.jpg", "hqdefault.jpg").replace("mqdefault.jpg", "hqdefault.jpg")
    return url

@app.get("/api/proxy-image")
async def proxy_image(url: str):
    if not url:
        raise HTTPException(status_code=400, detail="Missing URL")
    
    if url in image_cache:
        cached_data, content_type = image_cache[url]
        return Response(content=cached_data, media_type=content_type, headers={"Cache-Control": "public, max-age=86400"})

    try:
        resp = await http_client.get(url, headers=BROWSER_HEADERS, timeout=8.0)
        if resp.status_code == 200:
            content_type = resp.headers.get("content-type", "image/jpeg")
            image_cache[url] = (resp.content, content_type)
            return Response(content=resp.content, media_type=content_type, headers={"Cache-Control": "public, max-age=86400"})
        return Response(status_code=302, headers={"Location": url})
    except Exception:
        return Response(status_code=302, headers={"Location": url})

def clean_search_term(term: str) -> str:
    if not term:
        return ""
    cleaned = re.sub(r'\(.*?\)|\[.*?\]', '', term)
    cleaned = re.sub(r'(?i)(official|video|audio|lyric|lyrics|remix|version|visualizer|from|soundtrack)', '', cleaned)
    return cleaned.strip()

@app.get("/favicon.ico")
async def favicon():
    svg_data = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="40" fill="#fa2d48"/></svg>'
    return Response(content=svg_data, media_type="image/svg+xml")

@app.get("/.well-known/appspecific/com.chrome.devtools.json")
async def chrome_devtools_mock():
    return {}

@app.get("/api/search")
async def search_endpoint(query: str = Query(..., min_length=1)):
    q = query.strip()
    if q in search_cache:
        return search_cache[q]

    loop = asyncio.get_running_loop()
    try:
        raw = await loop.run_in_executor(
            None,
            lambda: ytmusic.search(query=q, filter="songs")
        )
        results = []
        for item in raw:
            item_id = item.get("videoId")
            if not item_id:
                continue

            artists = [a.get("name") for a in item.get("artists", []) if a.get("name")]
            thumbs = item.get("thumbnails", [])
            raw_thumb = thumbs[-1].get("url") if thumbs else ""
            best_thumb = clean_thumbnail_to_hd(raw_thumb)

            results.append({
                "id": item_id,
                "title": item.get("title", "Unknown Title"),
                "artist": ", ".join(artists) if artists else item.get("author", "Unknown Artist"),
                "album": item.get("album", {}).get("name") if item.get("album") else "",
                "duration": item.get("duration", "3:30"),
                "thumbnail": f"/api/proxy-image?url={httpx.URL(best_thumb)}" if best_thumb else ""
            })

        payload = {"results": results}
        search_cache[q] = payload
        return payload
    except Exception as e:
        print(f"[MELO:SEARCH_ERROR] {e}")
        raise HTTPException(status_code=500, detail=f"Search failed: {str(e)}")

@app.get("/api/recommendations/{video_id}")
async def get_recommendations(video_id: str):
    if video_id in rec_cache:
        return rec_cache[video_id]

    loop = asyncio.get_running_loop()
    try:
        watch_data = await loop.run_in_executor(
            None,
            lambda: ytmusic.get_watch_playlist(videoId=video_id, limit=25)
        )
        tracks = []
        for item in watch_data.get("tracks", []):
            item_id = item.get("videoId")
            if not item_id or item_id == video_id:
                continue

            artists = [a.get("name") for a in item.get("artists", []) if a.get("name")]
            thumbs = item.get("thumbnails", [])
            raw_thumb = thumbs[-1].get("url") if thumbs else ""
            best_thumb = clean_thumbnail_to_hd(raw_thumb)

            tracks.append({
                "id": item_id,
                "title": item.get("title", "Unknown Track"),
                "artist": ", ".join(artists) if artists else "Unknown Artist",
                "album": item.get("album", {}).get("name") if item.get("album") else "",
                "duration": item.get("length", "3:30"),
                "thumbnail": f"/api/proxy-image?url={httpx.URL(best_thumb)}" if best_thumb else ""
            })

        payload = {"tracks": tracks}
        rec_cache[video_id] = payload
        return payload
    except Exception:
        return {"tracks": []}

def parse_lrc(lrc_text: str) -> List[Dict[str, Any]]:
    lines = []
    pattern = re.compile(r'\[(\d{2}):(\d{2}(?:\.\d+)?)\](.*)')
    for row in lrc_text.splitlines():
        row = row.strip()
        match = pattern.match(row)
        if match:
            minutes = int(match.group(1))
            seconds = float(match.group(2))
            text = match.group(3).strip()
            total_seconds = round(minutes * 60 + seconds, 2)
            if text:
                lines.append({"time": total_seconds, "text": text})
    lines.sort(key=lambda x: x["time"])
    return lines

@app.get("/api/lyrics")
async def get_lyrics(
    video_id: str,
    title: Optional[str] = Query(None),
    artist: Optional[str] = Query(None)
):
    cache_key = f"{video_id}:{title}:{artist}"
    if cache_key in lyrics_cache:
        return lyrics_cache[cache_key]

    clean_t = clean_search_term(title)
    primary_artist = clean_search_term(artist.split(',')[0]) if artist else ""

    if clean_t:
        try:
            params = {"track_name": clean_t}
            if primary_artist and primary_artist.lower() != "unknown artist":
                params["artist_name"] = primary_artist

            resp = await http_client.get("https://lrclib.net/api/get", params=params, headers=BROWSER_HEADERS, timeout=5.0)
            if resp.status_code == 200:
                data = resp.json()
                if data.get("syncedLyrics"):
                    parsed = parse_lrc(data["syncedLyrics"])
                    if parsed:
                        payload = {"synced": True, "lines": parsed}
                        lyrics_cache[cache_key] = payload
                        return payload

            search_resp = await http_client.get(
                "https://lrclib.net/api/search",
                params={"q": f"{clean_t} {primary_artist}".strip()},
                headers=BROWSER_HEADERS,
                timeout=5.0
            )
            if search_resp.status_code == 200:
                results = search_resp.json()
                for item in results:
                    if item.get("syncedLyrics"):
                        parsed = parse_lrc(item["syncedLyrics"])
                        if parsed:
                            payload = {"synced": True, "lines": parsed}
                            lyrics_cache[cache_key] = payload
                            return payload
                    elif item.get("plainLyrics"):
                        clean = [{"time": 0, "text": l.strip()} for l in item["plainLyrics"].splitlines() if l.strip()]
                        payload = {"synced": False, "lines": clean}
                        lyrics_cache[cache_key] = payload
                        return payload
        except Exception:
            pass

    loop = asyncio.get_running_loop()
    try:
        def fetch_ytm_lyrics():
            watch_data = ytmusic.get_watch_playlist(videoId=video_id)
            lyrics_id = watch_data.get("lyrics")
            if not lyrics_id:
                return None
            return ytmusic.get_lyrics(browseId=lyrics_id)

        ytm_data = await loop.run_in_executor(None, fetch_ytm_lyrics)
        if ytm_data and ytm_data.get("lyrics"):
            clean = [{"time": 0, "text": l.strip()} for l in ytm_data["lyrics"].splitlines() if l.strip()]
            payload = {"synced": False, "lines": clean}
            lyrics_cache[cache_key] = payload
            return payload
    except Exception:
        pass

    return {
        "synced": False,
        "lines": [
            {"time": 0, "text": "Playing high-fidelity audio on MELO."},
            {"time": 0, "text": "Enjoy your music!"}
        ]
    }

@app.get("/api/stream/{video_id}")
async def stream_audio(video_id: str, request: Request):
    try:
        stream_info = await get_stream_data(video_id)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Audio resolution failed: {str(e)}")

    headers = dict(stream_info.get("headers", BROWSER_HEADERS))
    client_range = request.headers.get("range")
    if client_range:
        headers["Range"] = client_range

    try:
        req = http_client.build_request("GET", stream_info["url"], headers=headers)
        upstream = await http_client.send(req, stream=True)

        if upstream.status_code == 403:
            stream_cache.pop(video_id, None)
            stream_info = await get_stream_data(video_id)
            headers = dict(stream_info.get("headers", BROWSER_HEADERS))
            if client_range:
                headers["Range"] = client_range
            req = http_client.build_request("GET", stream_info["url"], headers=headers)
            upstream = await http_client.send(req, stream=True)

    except Exception as e:
        stream_cache.pop(video_id, None)
        raise HTTPException(status_code=502, detail=f"Upstream connection error: {str(e)}")

    async def body_iterator():
        try:
            async for chunk in upstream.aiter_bytes(chunk_size=64 * 1024):
                yield chunk
        finally:
            await upstream.aclose()

    resp_headers = {
        "Accept-Ranges": "bytes",
        "Content-Type": stream_info["content_type"]
    }
    for key in ["Content-Range", "Content-Length"]:
        if key in upstream.headers:
            resp_headers[key] = upstream.headers[key]

    return StreamingResponse(
        body_iterator(),
        status_code=upstream.status_code,
        headers=resp_headers,
        media_type=stream_info["content_type"]
    )

os.makedirs("static", exist_ok=True)
app.mount("/static", StaticFiles(directory="static"), name="static")

@app.get("/")
def serve_index():
    return FileResponse("static/index.html")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)