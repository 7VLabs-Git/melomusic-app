import os
import re
import base64
import asyncio
from typing import Optional, List, Dict, Any
from fastapi import FastAPI, HTTPException, Request, Query, Response
from fastapi.responses import StreamingResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
import httpx
from pyDes import des, CBC, PAD_PKCS5
from cachetools import TTLCache

app = FastAPI(title="MELO Audio Engine (Saavn Edition)", version="6.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Caches
stream_cache = TTLCache(maxsize=2000, ttl=14400)      # 4 Hours
search_cache = TTLCache(maxsize=1000, ttl=3600)       # 1 Hour
lyrics_cache = TTLCache(maxsize=1000, ttl=86400)      # 24 Hours
rec_cache = TTLCache(maxsize=1000, ttl=7200)          # 2 Hours
image_cache = TTLCache(maxsize=3000, ttl=86400)       # 24 Hours

http_client: Optional[httpx.AsyncClient] = None

DES_KEY = b"38346591"
DES_CIPHER = des(DES_KEY, CBC, b"00000000", pad=None, padmode=PAD_PKCS5)

BROWSER_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    "Accept": "*/*",
    "Accept-Encoding": "identity",
}

@app.on_event("startup")
async def startup_event():
    global http_client
    http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(15.0, connect=5.0),
        limits=httpx.Limits(max_keepalive_connections=100, max_connections=300),
        follow_redirects=True,
    )

@app.on_event("shutdown")
async def shutdown_event():
    global http_client
    if http_client:
        await http_client.aclose()

def decrypt_saavn_url(encrypted_url: str) -> str:
    """Decrypts JioSaavn encrypted media URLs into direct MP4/AAC stream links."""
    try:
        raw_b64 = base64.b64decode(encrypted_url.strip())
        decrypted = DES_CIPHER.decrypt(raw_b64)
        url = decrypted.decode("utf-8").strip()
        # Upgrade to 320kbps stream if available, otherwise default to 160kbps
        return url.replace("_96.mp4", "_320.mp4").replace("_160.mp4", "_320.mp4")
    except Exception:
        return ""

def format_saavn_track(item: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Formats Saavn response item into MELO standard schema."""
    track_id = item.get("id")
    if not track_id:
        return None

    title = item.get("title", item.get("song", "Unknown Title"))
    title = re.sub(r"&quot;", '"', re.sub(r"&#039;", "'", title)).strip()

    artist = item.get("more_info", {}).get("music", item.get("primary_artists", item.get("singers", "Unknown Artist")))
    album = item.get("more_info", {}).get("album", item.get("album", ""))
    duration = item.get("more_info", {}).get("duration", item.get("duration", "210"))
    
    # Clean duration to mm:ss format
    try:
        dur_secs = int(duration)
        mins = dur_secs // 60
        secs = dur_secs % 60
        dur_str = f"{mins}:{secs:02d}"
    except Exception:
        dur_str = "3:30"

    raw_image = item.get("image", "")
    thumb = raw_image.replace("150x150", "500x500").replace("50x50", "500x500")

    return {
        "id": track_id,
        "title": title,
        "artist": artist,
        "album": album,
        "duration": dur_str,
        "thumbnail": f"/api/proxy-image?url={httpx.URL(thumb)}" if thumb else ""
    }

# --- SEARCH & RECOMMENDATIONS ---

@app.get("/api/search")
async def search_endpoint(query: str = Query(..., min_length=1)):
    q = query.strip()
    if q in search_cache:
        return search_cache[q]

    api_url = "https://www.jiosaavn.com/api.php"
    params = {
        "__call": "search.getResults",
        "_format": "json",
        "_marker": "0",
        "api_version": "4",
        "p": "1",
        "n": "30",
        "q": q
    }

    try:
        resp = await http_client.get(api_url, params=params, headers=BROWSER_HEADERS)
        if resp.status_code == 200:
            data = resp.json()
            raw_results = data.get("results", [])
            formatted = []
            for item in raw_results:
                track = format_saavn_track(item)
                if track:
                    # Pre-cache stream URL if present
                    enc_url = item.get("more_info", {}).get("encrypted_media_url")
                    if enc_url:
                        decrypted = decrypt_saavn_url(enc_url)
                        if decrypted:
                            stream_cache[track["id"]] = {
                                "url": decrypted,
                                "content_type": "audio/mp4",
                                "headers": BROWSER_HEADERS
                            }
                    formatted.append(track)

            payload = {"results": formatted}
            search_cache[q] = payload
            return payload
    except Exception as e:
        print(f"[MELO:SEARCH_ERROR] {e}")

    return {"results": []}

@app.get("/api/recommendations/{video_id}")
async def get_recommendations(video_id: str):
    if video_id in rec_cache:
        return rec_cache[video_id]

    api_url = "https://www.jiosaavn.com/api.php"
    params = {
        "__call": "reco.getrecos",
        "_format": "json",
        "_marker": "0",
        "api_version": "4",
        "pid": video_id
    }

    try:
        resp = await http_client.get(api_url, params=params, headers=BROWSER_HEADERS)
        if resp.status_code == 200:
            data = resp.json()
            tracks = []
            for item in data:
                track = format_saavn_track(item)
                if track and track["id"] != video_id:
                    enc_url = item.get("more_info", {}).get("encrypted_media_url")
                    if enc_url:
                        decrypted = decrypt_saavn_url(enc_url)
                        if decrypted:
                            stream_cache[track["id"]] = {
                                "url": decrypted,
                                "content_type": "audio/mp4",
                                "headers": BROWSER_HEADERS
                            }
                    tracks.append(track)

            payload = {"tracks": tracks}
            rec_cache[video_id] = payload
            return payload
    except Exception:
        pass

    return {"tracks": []}

# --- DIRECT AUDIO STREAM ENGINE ---

async def resolve_saavn_stream(track_id: str) -> Dict[str, Any]:
    """Fetches track metadata directly by ID and decodes the stream URL."""
    if track_id in stream_cache:
        return stream_cache[track_id]

    api_url = "https://www.jiosaavn.com/api.php"
    params = {
        "__call": "song.getDetails",
        "_format": "json",
        "_marker": "0",
        "api_version": "4",
        "pids": track_id
    }

    resp = await http_client.get(api_url, params=params, headers=BROWSER_HEADERS)
    if resp.status_code == 200:
        data = resp.json()
        item = data.get(track_id, {})
        enc_url = item.get("more_info", {}).get("encrypted_media_url")
        if enc_url:
            direct_url = decrypt_saavn_url(enc_url)
            if direct_url:
                payload = {
                    "url": direct_url,
                    "content_type": "audio/mp4",
                    "headers": BROWSER_HEADERS
                }
                stream_cache[track_id] = payload
                return payload

    raise HTTPException(status_code=500, detail="Unable to retrieve audio stream URL.")

@app.get("/api/stream/{video_id}")
async def stream_audio(video_id: str, request: Request):
    stream_info = await resolve_saavn_stream(video_id)

    headers = dict(stream_info.get("headers", BROWSER_HEADERS))
    client_range = request.headers.get("range")
    if client_range:
        headers["Range"] = client_range

    try:
        req = http_client.build_request("GET", stream_info["url"], headers=headers)
        upstream = await http_client.send(req, stream=True)

        if upstream.status_code == 403:
            # Refresh if expired
            stream_cache.pop(video_id, None)
            stream_info = await resolve_saavn_stream(video_id)
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
        "Content-Type": "audio/mp4"
    }
    for key in ["Content-Range", "Content-Length"]:
        if key in upstream.headers:
            resp_headers[key] = upstream.headers[key]

    return StreamingResponse(
        body_iterator(),
        status_code=upstream.status_code,
        headers=resp_headers,
        media_type="audio/mp4"
    )

# --- IMAGE PROXY & LYRICS ---

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

    clean_t = re.sub(r'\(.*?\)|\[.*?\]', '', title or '').strip()
    primary_artist = (artist or '').split(',')[0].strip()

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

@app.get("/favicon.ico")
async def favicon():
    svg_data = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="40" fill="#fa2d48"/></svg>'
    return Response(content=svg_data, media_type="image/svg+xml")

os.makedirs("static", exist_ok=True)
app.mount("/static", StaticFiles(directory="static"), name="static")

@app.get("/")
def serve_index():
    return FileResponse("static/index.html")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)