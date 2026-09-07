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

app = FastAPI(title="MELO Audio Engine", version="5.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

ytmusic = YTMusic()

# Memory Caches
stream_cache = TTLCache(maxsize=1500, ttl=14400)      # 4 Hours
search_cache = TTLCache(maxsize=500, ttl=3600)        # 1 Hour
lyrics_cache = TTLCache(maxsize=500, ttl=86400)       # 24 Hours
rec_cache = TTLCache(maxsize=500, ttl=7200)           # 2 Hours
image_cache = TTLCache(maxsize=2000, ttl=86400)       # 24 Hours

http_client: Optional[httpx.AsyncClient] = None

BROWSER_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    "Accept": "*/*",
    "Accept-Encoding": "identity",
}

@app.on_event("startup")
async def startup_event():
    global http_client
    http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(20.0, connect=6.0),
        limits=httpx.Limits(max_keepalive_connections=100, max_connections=300),
        follow_redirects=True,
    )

@app.on_event("shutdown")
async def shutdown_event():
    global http_client
    if http_client:
        await http_client.aclose()

# --- BULLETPROOF AUDIO RESOLUTION ENGINE ---

async def resolve_cobalt_stream(video_id: str) -> Optional[Dict[str, Any]]:
    """Resolves high-bitrate direct audio URL via Cobalt API nodes."""
    api_nodes = [
        "https://api.cobalt.tools",
        "https://cobalt-api.kwiatekm.tokyo",
        "https://api.wuk.sh"
    ]
    payload = {
        "url": f"https://www.youtube.com/watch?v={video_id}",
        "downloadMode": "audio",
        "audioFormat": "best"
    }
    headers = {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "User-Agent": BROWSER_HEADERS["User-Agent"]
    }

    for node in api_nodes:
        try:
            resp = await http_client.post(f"{node}/", json=payload, headers=headers, timeout=5.0)
            if resp.status_code == 200:
                data = resp.json()
                stream_url = data.get("url")
                if stream_url:
                    return {
                        "url": stream_url,
                        "content_type": "audio/mp4",
                        "headers": BROWSER_HEADERS
                    }
        except Exception:
            continue
    return None

async def resolve_piped_stream(video_id: str) -> Optional[Dict[str, Any]]:
    """Resolves direct stream via active Piped nodes."""
    nodes = [
        "https://pipedapi.kavin.rocks",
        "https://api.piped.privacydev.net",
        "https://pipedapi.tokhmi.xyz"
    ]
    for host in nodes:
        try:
            resp = await http_client.get(f"{host}/streams/{video_id}", headers=BROWSER_HEADERS, timeout=4.0)
            if resp.status_code == 200:
                data = resp.json()
                streams = data.get("audioStreams", [])
                if streams:
                    streams.sort(key=lambda x: x.get("bitrate", 0), reverse=True)
                    best = streams[0]
                    mime = best.get("mimeType", "audio/mp4").split(";")[0]
                    return {
                        "url": best["url"],
                        "content_type": mime,
                        "headers": BROWSER_HEADERS
                    }
        except Exception:
            continue
    return None

def resolve_ytdlp_raw(video_id: str) -> Optional[Dict[str, Any]]:
    """Fallback yt-dlp that extracts raw format dictionaries without crashing."""
    target_url = f"https://www.youtube.com/watch?v={video_id}"
    opts = {
        'format': None,  # NEVER specify a string format here to avoid 'Requested format is not available'
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
        'extract_flat': False,
        'source_address': '0.0.0.0',
        'extractor_args': {
            'youtube': {
                'player_client': ['android', 'ios'],
                'formats': ['missing_pot']
            }
        }
    }
    
    # Check for secret or local cookie file
    for cp in ["/etc/secrets/cookies.txt", "cookies.txt"]:
        if os.path.isfile(cp) and os.path.getsize(cp) > 10:
            opts['cookiefile'] = cp
            break

    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(target_url, download=False)
            formats = info.get("formats") or []
            
            # Filter any playable stream URL
            candidates = [f for f in formats if f.get("url") and f.get("acodec") not in (None, "none")]
            if not candidates:
                candidates = [f for f in formats if f.get("url")]

            if candidates:
                candidates.sort(key=lambda x: (x.get("abr") or x.get("tbr") or 0), reverse=True)
                chosen = candidates[0]
                ext = chosen.get("ext", "mp4")
                mime = "audio/webm" if "webm" in ext else "audio/mp4"
                return {
                    "url": chosen["url"],
                    "content_type": mime,
                    "headers": BROWSER_HEADERS
                }
    except Exception:
        pass
    return None

async def get_stream_data(video_id: str) -> Dict[str, Any]:
    if video_id in stream_cache:
        return stream_cache[video_id]

    # Tier 1: Fast Cobalt Node
    res = await resolve_cobalt_stream(video_id)
    if res:
        stream_cache[video_id] = res
        print(f"[MELO:RESOLVER] {video_id} resolved via Cobalt API.")
        return res

    # Tier 2: Piped API Node
    res = await resolve_piped_stream(video_id)
    if res:
        stream_cache[video_id] = res
        print(f"[MELO:RESOLVER] {video_id} resolved via Piped Network.")
        return res

    # Tier 3: Direct local manifest extraction
    loop = asyncio.get_running_loop()
    res = await loop.run_in_executor(None, resolve_ytdlp_raw, video_id)
    if res:
        stream_cache[video_id] = res
        print(f"[MELO:RESOLVER] {video_id} resolved via Local yt-dlp.")
        return res

    raise HTTPException(status_code=500, detail="Audio resolution failed on all upstream tiers.")

# --- UTILITIES & ENDPOINTS ---

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

            resp = await http_client.get("https://lrclib.net/api/get", params=params, headers=BROWSER_HEADERS, timeout=4.0)
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
                timeout=4.0
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

    return {
        "synced": False,
        "lines": [
            {"time": 0, "text": "High-fidelity audio on MELO."},
            {"time": 0, "text": "Enjoy your music!"}
        ]
    }

@app.get("/api/stream/{video_id}")
async def stream_audio(video_id: str, request: Request):
    stream_info = await get_stream_data(video_id)

    headers = dict(stream_info.get("headers", BROWSER_HEADERS))
    client_range = request.headers.get("range")
    if client_range:
        headers["Range"] = client_range

    try:
        req = http_client.build_request("GET", stream_info["url"], headers=headers)
        upstream = await http_client.send(req, stream=True)

        if upstream.status_code in (403, 404, 410):
            stream_cache.pop(video_id, None)
            stream_info = await get_stream_data(video_id)
            headers = dict(stream_info.get("headers", BROWSER_HEADERS))
            if client_range:
                headers["Range"] = client_range
            req = http_client.build_request("GET", stream_info["url"], headers=headers)
            upstream = await http_client.send(req, stream=True)

    except Exception as e:
        stream_cache.pop(video_id, None)
        raise HTTPException(status_code=502, detail=f"Upstream stream connection error: {str(e)}")

    async def body_iterator():
        try:
            async for chunk in upstream.aiter_bytes(chunk_size=64 * 1024):
                yield chunk
        finally:
            await upstream.aclose()

    resp_headers = {
        "Accept-Ranges": "bytes",
        "Content-Type": stream_info.get("content_type", "audio/mp4")
    }
    for key in ["Content-Range", "Content-Length"]:
        if key in upstream.headers:
            resp_headers[key] = upstream.headers[key]

    return StreamingResponse(
        body_iterator(),
        status_code=upstream.status_code,
        headers=resp_headers,
        media_type=stream_info.get("content_type", "audio/mp4")
    )

os.makedirs("static", exist_ok=True)
app.mount("/static", StaticFiles(directory="static"), name="static")

@app.get("/")
def serve_index():
    return FileResponse("static/index.html")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)