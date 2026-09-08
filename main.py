import os
import re
import html
import json
import base64
import asyncio
import urllib.parse
from typing import Optional, List, Dict, Any
from fastapi import FastAPI, HTTPException, Request, Query, Response
from fastapi.responses import StreamingResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import httpx
from pyDes import des, ECB, PAD_PKCS5
from cachetools import TTLCache
from ytmusicapi import YTMusic

app = FastAPI(title="MELO Hybrid Engine", version="9.4.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# In-memory caches
stream_cache = TTLCache(maxsize=15000, ttl=86400)
search_cache = TTLCache(maxsize=1500, ttl=3600)
lyrics_cache = TTLCache(maxsize=1500, ttl=86400)
rec_cache = TTLCache(maxsize=1500, ttl=7200)
image_cache = TTLCache(maxsize=5000, ttl=86400)

http_client: Optional[httpx.AsyncClient] = None
ytm: Optional[YTMusic] = None

DES_KEY = b"38346591"
DES_CIPHER = des(DES_KEY, ECB, padmode=PAD_PKCS5)

CDN_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    "Accept": "*/*",
    "Accept-Encoding": "identity",
    "Referer": "https://www.jiosaavn.com/",
}

class ImportRequest(BaseModel):
    url: str

async def keep_alive_task():
    """Pings the external Render URL every 13 minutes to prevent sleep."""
    await asyncio.sleep(30)
    while True:
        render_url = os.getenv("RENDER_EXTERNAL_URL")
        if render_url and http_client:
            try:
                ping_target = f"{render_url.rstrip('/')}/api/ping"
                await http_client.get(ping_target, timeout=10.0)
            except Exception as err:
                print(f"[KEEP_ALIVE_WARN] {err}")
        await asyncio.sleep(13 * 60)

@app.on_event("startup")
async def startup_event():
    global http_client, ytm
    http_client = httpx.AsyncClient(
        timeout=httpx.Timeout(15.0, connect=5.0),
        limits=httpx.Limits(max_keepalive_connections=150, max_connections=400),
        follow_redirects=True,
    )
    try:
        ytm = YTMusic()
    except Exception as e:
        print(f"[YTM_INIT_WARNING] {e}")
    asyncio.create_task(keep_alive_task())

@app.on_event("shutdown")
async def shutdown_event():
    global http_client
    if http_client:
        await http_client.aclose()

@app.get("/api/ping")
async def ping():
    return {"status": "alive"}

def decrypt_saavn_url(enc_str: str) -> str:
    if not enc_str:
        return ""
    try:
        clean_enc = enc_str.strip()
        missing_padding = len(clean_enc) % 4
        if missing_padding:
            clean_enc += "=" * (4 - missing_padding)

        raw_bytes = base64.b64decode(clean_enc)
        decrypted_bytes = DES_CIPHER.decrypt(raw_bytes)
        decrypted_str = decrypted_bytes.decode("utf-8", errors="ignore").strip()

        url = re.sub(r'[\x00-\x1f\x7f-\x9f]', '', decrypted_str)
        if url.startswith("http"):
            return url
        return ""
    except Exception:
        return ""

def clean_thumbnail_url(raw_url: str) -> str:
    if not raw_url:
        return ""
    clean = html.unescape(raw_url.strip())
    clean = clean.replace("50x50", "500x500").replace("150x150", "500x500")
    if "http://" in clean:
        clean = clean.replace("http://", "https://")
    return clean

def format_saavn_track(item: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    more_info = item.get("more_info", {})
    enc_url = more_info.get("encrypted_media_url", item.get("encrypted_media_url", ""))
    
    item_type = item.get("type", "")
    if item_type and item_type not in ("song", "tracks"):
        if not enc_url:
            return None
    if not enc_url:
        return None

    track_id = item.get("id") or more_info.get("song_id") or item.get("songid")
    if not track_id:
        perma = item.get("perma_url", "")
        if perma:
            track_id = perma.split("/")[-1]
    
    if not track_id:
        return None

    title = item.get("title", item.get("song", "Unknown Title"))
    title = html.unescape(re.sub(r"&quot;", '"', re.sub(r"&#039;", "'", title)).strip())

    artist = more_info.get("music", item.get("primary_artists", item.get("singers", "Unknown Artist")))
    artist = html.unescape(artist).strip()

    album = more_info.get("album", item.get("album", ""))
    album = html.unescape(album).strip()

    duration = more_info.get("duration", item.get("duration", "210"))
    try:
        dur_secs = int(duration)
        dur_str = f"{dur_secs // 60}:{dur_secs % 60:02d}"
    except Exception:
        dur_str = "3:30"

    raw_image = more_info.get("image") or item.get("image") or item.get("image_url") or ""
    thumb = clean_thumbnail_url(raw_image)

    decrypted_audio_url = decrypt_saavn_url(enc_url)
    if decrypted_audio_url:
        stream_cache[str(track_id)] = decrypted_audio_url

    encoded_thumb_param = urllib.parse.quote(thumb, safe="") if thumb else ""

    return {
        "id": str(track_id),
        "title": title,
        "artist": artist,
        "album": album,
        "duration": dur_str,
        "thumbnail": f"/api/proxy-image?url={encoded_thumb_param}" if encoded_thumb_param else thumb,
        "enc_url": enc_url
    }

async def search_single_saavn_track(query_str: str) -> Optional[Dict[str, Any]]:
    clean_q = re.sub(r'\(.*?\)|\[.*?\]', '', query_str).strip()
    if not clean_q:
        return None
    api_url = "https://www.jiosaavn.com/api.php"
    params = {
        "__call": "search.getResults",
        "_format": "json",
        "_marker": "0",
        "api_version": "4",
        "ctx": "web6dot0",
        "p": "1",
        "n": "5",
        "q": clean_q
    }
    try:
        resp = await http_client.get(api_url, params=params, headers=CDN_HEADERS)
        if resp.status_code == 200:
            data = resp.json()
            results = data.get("results", [])
            for item in results:
                track = format_saavn_track(item)
                if track:
                    return {k: v for k, v in track.items() if k != "enc_url"}
    except Exception:
        pass
    return None

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
        "ctx": "web6dot0",
        "p": "1",
        "n": "40",
        "q": q
    }

    try:
        resp = await http_client.get(api_url, params=params, headers=CDN_HEADERS)
        if resp.status_code == 200:
            data = resp.json()
            raw_results = data.get("results", [])
            formatted = []
            seen_titles = set()

            for item in raw_results:
                track = format_saavn_track(item)
                if not track:
                    continue

                clean_title = re.sub(r'\(.*?\)|\[.*?\]', '', track["title"]).strip().lower()
                if clean_title in seen_titles:
                    continue
                seen_titles.add(clean_title)

                clean_payload = {k: v for k, v in track.items() if k != "enc_url"}
                formatted.append(clean_payload)

            payload = {"results": formatted}
            search_cache[q] = payload
            return payload
    except Exception as e:
        print(f"[SEARCH_ERROR] {e}")

    return {"results": []}

@app.get("/api/recommendations/{video_id}")
async def get_recommendations(
    video_id: str,
    title: Optional[str] = Query(None),
    artist: Optional[str] = Query(None)
):
    cache_key = f"{video_id}:{title}:{artist}"
    if cache_key in rec_cache:
        return rec_cache[cache_key]

    matched_tracks = []
    seen_track_ids = {str(video_id)}

    if ytm and title:
        try:
            yt_search_query = f"{title} {artist or ''}".strip()
            yt_results = ytm.search(yt_search_query, filter="songs")
            if yt_results and len(yt_results) > 0:
                yt_video_id = yt_results[0].get("videoId")
                if yt_video_id:
                    watch_data = ytm.get_watch_playlist(videoId=yt_video_id, limit=25)
                    yt_tracks = watch_data.get("tracks", [])

                    for item in yt_tracks[1:]:
                        t_title = item.get("title", "")
                        t_artists = item.get("artists", [])
                        t_artist_name = t_artists[0].get("name", "") if t_artists else ""

                        if t_title:
                            saavn_match = await search_single_saavn_track(f"{t_title} {t_artist_name}".strip())
                            if saavn_match and saavn_match["id"] not in seen_track_ids:
                                seen_track_ids.add(saavn_match["id"])
                                matched_tracks.append(saavn_match)
                            if len(matched_tracks) >= 20:
                                break
        except Exception as e:
            print(f"[YTM_RADIO_FALLBACK] {e}")

    if len(matched_tracks) < 10 and (artist or title):
        query_seed = f"{artist or ''} {title or ''}".strip()
        first_artist = artist.split(',')[0].split('&')[0].strip() if artist else query_seed
        fallback_res = await search_endpoint(query=first_artist)
        for t in fallback_res.get("results", []):
            if t["id"] not in seen_track_ids:
                seen_track_ids.add(t["id"])
                matched_tracks.append(t)
            if len(matched_tracks) >= 20:
                break

    payload = {"tracks": matched_tracks}
    rec_cache[cache_key] = payload
    return payload

@app.post("/api/import-playlist")
async def import_playlist(req: ImportRequest):
    url = req.url.strip()
    if not url:
        raise HTTPException(status_code=400, detail="URL cannot be empty")

    imported_tracks = []
    playlist_name = "Imported Playlist"

    try:
        # Spotify Import via Embed Page extraction
        if "spotify.com" in url:
            match = re.search(r'spotify\.com/(?:intl-[a-z]+/)?(playlist|album|track)/([a-zA-Z0-9]+)', url)
            if match:
                media_type, media_id = match.group(1), match.group(2)
                embed_url = f"https://open.spotify.com/embed/{media_type}/{media_id}"
                embed_resp = await http_client.get(embed_url, headers=CDN_HEADERS, timeout=10.0)

                if embed_resp.status_code == 200:
                    next_data_match = re.search(r'<script id="__NEXT_DATA__" type="application/json">([^<]+)</script>', embed_resp.text)
                    if next_data_match:
                        try:
                            raw_json = json.loads(next_data_match.group(1))
                            entity = raw_json.get("props", {}).get("pageProps", {}).get("state", {}).get("data", {}).get("entity", {})
                            playlist_name = entity.get("title") or entity.get("name") or playlist_name
                            track_list = entity.get("trackList", [])

                            for t in track_list[:40]:
                                t_title = t.get("title", "")
                                t_subtitle = t.get("subtitle", "")
                                if t_title:
                                    matched = await search_single_saavn_track(f"{t_title} {t_subtitle}".strip())
                                    if matched and matched["id"] not in [x["id"] for x in imported_tracks]:
                                        imported_tracks.append(matched)
                        except Exception as json_err:
                            print(f"[SPOTIFY_JSON_ERR] {json_err}")

        # YouTube & YouTube Music Links
        elif "youtube.com" in url or "youtu.be" in url:
            parsed = urllib.parse.urlparse(url)
            query_params = urllib.parse.parse_qs(parsed.query)
            playlist_id = query_params.get("list", [None])[0]

            if not playlist_id and "playlist/" in url:
                playlist_id = url.split("playlist/")[1].split("?")[0]

            if playlist_id:
                if ytm:
                    try:
                        pl_data = ytm.get_playlist(playlist_id, limit=60)
                        playlist_name = pl_data.get("title", "YouTube Playlist")
                        raw_tracks = pl_data.get("tracks", [])

                        for item in raw_tracks[:35]:
                            t_name = item.get("title", "")
                            t_artists = item.get("artists", [])
                            t_artist = t_artists[0].get("name", "") if t_artists else ""
                            if t_name:
                                matched = await search_single_saavn_track(f"{t_name} {t_artist}".strip())
                                if matched and matched["id"] not in [x["id"] for x in imported_tracks]:
                                    imported_tracks.append(matched)
                    except Exception as ytm_err:
                        print(f"[YTM_IMPORT_ERR] {ytm_err}")

        # Fallback keyword extraction
        if not imported_tracks:
            clean_seed = re.sub(r'https?://[^\s]+', '', url).strip()
            if clean_seed:
                res = await search_endpoint(query=clean_seed)
                imported_tracks = res.get("results", [])[:20]
                playlist_name = clean_seed.capitalize()

    except Exception as e:
        print(f"[IMPORT_ERROR] {e}")

    if not imported_tracks:
        raise HTTPException(status_code=404, detail="Could not retrieve playable songs. Ensure the playlist is public.")

    return {
        "success": True,
        "name": playlist_name,
        "tracks": imported_tracks
    }

async def resolve_saavn_url(track_id: str) -> str:
    if track_id in stream_cache:
        return stream_cache[track_id]

    api_url = "https://www.jiosaavn.com/api.php"
    params = {
        "__call": "song.getDetails",
        "_format": "json",
        "_marker": "0",
        "api_version": "4",
        "ctx": "web6dot0",
        "pids": track_id
    }

    try:
        resp = await http_client.get(api_url, params=params, headers=CDN_HEADERS)
        if resp.status_code == 200:
            data = resp.json()
            item = None
            if isinstance(data, dict):
                if track_id in data:
                    item = data[track_id]
                elif "songs" in data and isinstance(data["songs"], list):
                    for s in data["songs"]:
                        if str(s.get("id")) == str(track_id) or str(s.get("songid")) == str(track_id):
                            item = s
                            break
                else:
                    for k, v in data.items():
                        if isinstance(v, dict) and (str(k) == str(track_id) or str(v.get("id")) == str(track_id)):
                            item = v
                            break

            if item:
                enc_url = item.get("more_info", {}).get("encrypted_media_url", item.get("encrypted_media_url", ""))
                if enc_url:
                    dec = decrypt_saavn_url(enc_url)
                    if dec:
                        stream_cache[track_id] = dec
                        return dec
    except Exception as e:
        print(f"[RESOLVE_ERROR] {e}")

    raise HTTPException(status_code=404, detail="Audio stream could not be resolved.")

@app.get("/api/stream/{video_id}")
async def stream_audio(
    video_id: str,
    request: Request,
    quality: str = Query("320", pattern="^(96|160|320|low|medium|high)$")
):
    raw_url = await resolve_saavn_url(video_id)
    bitrate_suffix = "_320.mp4"
    if quality in ("96", "low"):
        bitrate_suffix = "_96.mp4"
    elif quality in ("160", "medium"):
        bitrate_suffix = "_160.mp4"

    target_url = re.sub(r'_(96|160|320)\.mp4', bitrate_suffix, raw_url)
    headers = dict(CDN_HEADERS)
    client_range = request.headers.get("range")
    if client_range:
        headers["Range"] = client_range

    try:
        req = http_client.build_request("GET", target_url, headers=headers)
        upstream = await http_client.send(req, stream=True)

        if upstream.status_code in (403, 404):
            target_url = raw_url
            req = http_client.build_request("GET", target_url, headers=headers)
            upstream = await http_client.send(req, stream=True)

        if upstream.status_code not in (200, 206):
            return Response(status_code=302, headers={"Location": target_url, "Access-Control-Allow-Origin": "*"})

    except Exception:
        return Response(status_code=302, headers={"Location": raw_url, "Access-Control-Allow-Origin": "*"})

    async def body_iterator():
        try:
            async for chunk in upstream.aiter_bytes(chunk_size=64 * 1024):
                yield chunk
        finally:
            await upstream.aclose()

    resp_headers = {
        "Accept-Ranges": "bytes",
        "Content-Type": "audio/mp4",
        "Access-Control-Allow-Origin": "*"
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

@app.get("/api/download/{video_id}")
async def download_audio(
    video_id: str,
    title: Optional[str] = Query("Track"),
    artist: Optional[str] = Query("MELO"),
    quality: str = Query("320")
):
    raw_url = await resolve_saavn_url(video_id)
    bitrate_suffix = "_320.mp4"
    if quality in ("96", "low"):
        bitrate_suffix = "_96.mp4"
    elif quality in ("160", "medium"):
        bitrate_suffix = "_160.mp4"

    target_url = re.sub(r'_(96|160|320)\.mp4', bitrate_suffix, raw_url)
    clean_filename = re.sub(r'[\\/*?:"<>|]', "", f"{title} - {artist}")
    encoded_filename = urllib.parse.quote(f"{clean_filename}.m4a")

    try:
        req = http_client.build_request("GET", target_url, headers=CDN_HEADERS)
        upstream = await http_client.send(req, stream=True)

        if upstream.status_code in (403, 404):
            target_url = raw_url
            req = http_client.build_request("GET", target_url, headers=CDN_HEADERS)
            upstream = await http_client.send(req, stream=True)

        async def file_iterator():
            try:
                async for chunk in upstream.aiter_bytes(chunk_size=128 * 1024):
                    yield chunk
            finally:
                await upstream.aclose()

        resp_headers = {
            "Content-Type": "audio/mp4",
            "Content-Disposition": f"attachment; filename*=UTF-8''{encoded_filename}",
            "Access-Control-Allow-Origin": "*"
        }
        if "Content-Length" in upstream.headers:
            resp_headers["Content-Length"] = upstream.headers["Content-Length"]

        return StreamingResponse(
            file_iterator(),
            status_code=200,
            headers=resp_headers,
            media_type="audio/mp4"
        )
    except Exception:
        return Response(status_code=302, headers={"Location": target_url, "Access-Control-Allow-Origin": "*"})

@app.get("/api/proxy-image")
async def proxy_image(url: str):
    if not url:
        raise HTTPException(status_code=400, detail="Missing URL")

    decoded_url = urllib.parse.unquote(url)
    if decoded_url in image_cache:
        cached_data, content_type = image_cache[decoded_url]
        return Response(
            content=cached_data,
            media_type=content_type,
            headers={
                "Cache-Control": "public, max-age=86400",
                "Access-Control-Allow-Origin": "*"
            }
        )

    try:
        resp = await http_client.get(decoded_url, headers=CDN_HEADERS, timeout=8.0)
        if resp.status_code == 200:
            content_type = resp.headers.get("content-type", "image/jpeg")
            image_cache[decoded_url] = (resp.content, content_type)
            return Response(
                content=resp.content,
                media_type=content_type,
                headers={
                    "Cache-Control": "public, max-age=86400",
                    "Access-Control-Allow-Origin": "*"
                }
            )
        return Response(status_code=302, headers={"Location": decoded_url, "Access-Control-Allow-Origin": "*"})
    except Exception:
        return Response(status_code=302, headers={"Location": decoded_url, "Access-Control-Allow-Origin": "*"})

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

            resp = await http_client.get("https://lrclib.net/api/get", params=params, headers=CDN_HEADERS, timeout=4.0)
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
                headers=CDN_HEADERS,
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

class NoCacheStaticFiles(StaticFiles):
    def is_not_modified(self, response: Response, request) -> bool:
        return False

os.makedirs("static", exist_ok=True)
app.mount("/static", NoCacheStaticFiles(directory="static"), name="static")

@app.get("/")
def serve_index():
    return FileResponse("static/index.html")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)