import os
import re
import html
import json
import base64
import asyncio
import urllib.parse
import uuid
from datetime import datetime, timedelta
from typing import Optional, List, Dict, Any

from fastapi import FastAPI, HTTPException, Request, Query, Response, Depends
from fastapi.responses import StreamingResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import httpx
from pyDes import des, ECB, PAD_PKCS5
from cachetools import TTLCache
from ytmusicapi import YTMusic

from sqlalchemy import create_engine, Column, String, DateTime, Text, Integer
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from passlib.context import CryptContext

app = FastAPI(title="MELO Hybrid Engine", version="10.2.1")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ==========================================
# 1. DATABASE & AUTHENTICATION SETUP
# ==========================================
raw_db_url = os.getenv("DATABASE_URL", "sqlite:///./melo_cloud.db")
if raw_db_url.startswith("postgres://"):
    raw_db_url = raw_db_url.replace("postgres://", "postgresql://", 1)

engine = create_engine(
    raw_db_url,
    connect_args={"check_same_thread": False} if "sqlite" in raw_db_url else {},
    pool_pre_ping=True
)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

class User(Base):
    __tablename__ = "users"
    id = Column(String, primary_key=True, index=True, default=lambda: str(uuid.uuid4()))
    email = Column(String, unique=True, index=True)
    password_hash = Column(String)
    display_name = Column(String)
    created_at = Column(DateTime, default=datetime.utcnow)

class UserSession(Base):
    __tablename__ = "sessions"
    id = Column(String, primary_key=True, index=True, default=lambda: str(uuid.uuid4()))
    user_id = Column(String, index=True)
    expires_at = Column(DateTime)

class UserLibrary(Base):
    __tablename__ = "user_libraries"
    user_id = Column(String, primary_key=True, index=True)
    favorites_json = Column(Text, default="{}")
    playlists_json = Column(Text, default="{}")
    history_json = Column(Text, default="[]")
    search_history_json = Column(Text, default="[]")
    preferences_json = Column(Text, default="{}")
    revision = Column(Integer, default=0, nullable=False)
    updated_at = Column(DateTime, default=datetime.utcnow)

class LibraryMutation(Base):
    __tablename__ = "library_mutations"
    id = Column(String, primary_key=True)
    user_id = Column(String, index=True, nullable=False)
    revision = Column(Integer, index=True, nullable=False)
    operation = Column(String, nullable=False)
    payload_json = Column(Text, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)

Base.metadata.create_all(bind=engine)

# create_all intentionally does not alter existing databases. Keep upgrades local,
# additive, and safe for the SQLite database used in development.
if "sqlite" in raw_db_url:
    with engine.begin() as connection:
        existing_columns = {
            row[1] for row in connection.exec_driver_sql("PRAGMA table_info(user_libraries)")
        }
        for column_name, definition in {
            "search_history_json": "TEXT DEFAULT '[]'",
            "preferences_json": "TEXT DEFAULT '{}'",
            "revision": "INTEGER NOT NULL DEFAULT 0",
        }.items():
            if column_name not in existing_columns:
                connection.exec_driver_sql(
                    f"ALTER TABLE user_libraries ADD COLUMN {column_name} {definition}"
                )

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

def get_current_user(request: Request, db):
    session_id = request.cookies.get("melo_session")
    if not session_id:
        return None
    session = db.query(UserSession).filter(UserSession.id == session_id).first()
    if not session or session.expires_at < datetime.utcnow():
        return None
    return db.query(User).filter(User.id == session.user_id).first()

# ==========================================
# 2. AUTHENTICATION & SYNC APIS
# ==========================================
class UserCreate(BaseModel):
    email: str
    password: str
    display_name: str

class UserLogin(BaseModel):
    email: str
    password: str

class ChangePasswordRequest(BaseModel):
    old_password: str
    new_password: str

class SyncPayload(BaseModel):
    favorites: Dict[str, Any]
    playlists: Dict[str, Any]
    history: List[str]

class LibraryMutationPayload(BaseModel):
    id: str
    operation: str
    payload: Dict[str, Any] = {}

class DeltaSyncPayload(BaseModel):
    base_revision: int = 0
    mutations: List[LibraryMutationPayload] = []

def json_value(raw: Optional[str], fallback):
    try:
        value = json.loads(raw or "")
        return value if isinstance(value, type(fallback)) else fallback
    except Exception:
        return fallback

def library_snapshot(lib: UserLibrary) -> Dict[str, Any]:
    return {
        "favorites": json_value(lib.favorites_json, {}),
        "playlists": json_value(lib.playlists_json, {}),
        "history": json_value(lib.history_json, []),
        "search_history": json_value(lib.search_history_json, []),
        "preferences": json_value(lib.preferences_json, {}),
        "revision": lib.revision or 0,
    }

def save_library_snapshot(lib: UserLibrary, snapshot: Dict[str, Any]):
    lib.favorites_json = json.dumps(snapshot["favorites"])
    lib.playlists_json = json.dumps(snapshot["playlists"])
    lib.history_json = json.dumps(snapshot["history"])
    lib.search_history_json = json.dumps(snapshot["search_history"])
    lib.preferences_json = json.dumps(snapshot["preferences"])
    lib.updated_at = datetime.utcnow()

def unique_tracks(tracks: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    seen = set()
    result = []
    for track in tracks:
        track_id = str(track.get("id", ""))
        if track_id and track_id not in seen:
            seen.add(track_id)
            result.append(track)
    return result

def apply_library_mutation(snapshot: Dict[str, Any], operation: str, payload: Dict[str, Any]):
    """Apply a small, idempotent client change while preserving remote additions."""
    favorites = snapshot["favorites"]
    playlists = snapshot["playlists"]
    history = snapshot["history"]

    if operation == "favorite":
        track = payload.get("track") or {}
        track_id = str(track.get("id", payload.get("track_id", "")))
        if payload.get("loved", True) and track_id:
            favorites[track_id] = track
        elif track_id:
            favorites.pop(track_id, None)

    elif operation == "playlist_upsert":
        incoming = payload.get("playlist") or {}
        playlist_id = str(incoming.get("id", ""))
        if playlist_id:
            existing = playlists.get(playlist_id, {})
            merged = {**existing, **{k: v for k, v in incoming.items() if k != "tracks"}}
            old_tracks = existing.get("tracks", [])
            new_tracks = incoming.get("tracks", [])
            # An upsert does not discard tracks created by another device.
            merged["tracks"] = unique_tracks(old_tracks + new_tracks)
            playlists[playlist_id] = merged

    elif operation == "playlist_delete":
        playlists.pop(str(payload.get("playlist_id", "")), None)

    elif operation == "playlist_track_add":
        playlist = playlists.get(str(payload.get("playlist_id", "")))
        track = payload.get("track") or {}
        if playlist and track.get("id"):
            playlist["tracks"] = unique_tracks(playlist.get("tracks", []) + [track])
            playlist["updated_at"] = payload.get("updated_at") or datetime.utcnow().isoformat()

    elif operation == "playlist_track_remove":
        playlist = playlists.get(str(payload.get("playlist_id", "")))
        track_id = str(payload.get("track_id", ""))
        if playlist and track_id:
            playlist["tracks"] = [t for t in playlist.get("tracks", []) if str(t.get("id")) != track_id]
            playlist["updated_at"] = payload.get("updated_at") or datetime.utcnow().isoformat()

    elif operation == "playlist_track_order":
        playlist = playlists.get(str(payload.get("playlist_id", "")))
        order = [str(track_id) for track_id in payload.get("track_ids", [])]
        if playlist and order:
            tracks = playlist.get("tracks", [])
            by_id = {str(track.get("id")): track for track in tracks}
            ordered = [by_id[track_id] for track_id in order if track_id in by_id]
            # Tracks added remotely after this device went offline are retained at the end.
            playlist["tracks"] = unique_tracks(ordered + tracks)
            playlist["updated_at"] = payload.get("updated_at") or datetime.utcnow().isoformat()

    elif operation == "playlist_order":
        order = [str(playlist_id) for playlist_id in payload.get("playlist_ids", [])]
        ordered = {playlist_id: playlists[playlist_id] for playlist_id in order if playlist_id in playlists}
        # Preserve system playlists and newly-created remote playlists not known to this client.
        for playlist_id, playlist in playlists.items():
            if playlist_id not in ordered:
                ordered[playlist_id] = playlist
        snapshot["playlists"] = ordered

    elif operation == "history_add":
        entry = payload.get("entry") or {}
        if entry.get("id") and not any(item.get("id") == entry["id"] for item in history if isinstance(item, dict)):
            history.append(entry)
            snapshot["history"] = history[-100:]

    elif operation == "history_remove":
        entry_id = str(payload.get("entry_id", ""))
        snapshot["history"] = [entry for entry in history if not isinstance(entry, dict) or entry.get("id") != entry_id]

    elif operation == "search_history":
        query = str(payload.get("query", "")).strip()
        current = snapshot["search_history"]
        if query:
            current = [item for item in current if str(item.get("query", "")).lower() != query.lower()]
            snapshot["search_history"] = ([{"query": query, "searched_at": payload.get("searched_at") or datetime.utcnow().isoformat()}] + current)[:12]

    elif operation == "search_history_remove":
        query = str(payload.get("query", "")).lower()
        snapshot["search_history"] = [item for item in snapshot["search_history"] if str(item.get("query", "")).lower() != query]

    elif operation == "search_history_clear":
        snapshot["search_history"] = []

    elif operation == "preferences":
        snapshot["preferences"].update(payload.get("values") or {})

@app.post("/api/auth/register")
def register_user(user: UserCreate, response: Response, db=Depends(get_db)):
    if db.query(User).filter(User.email == user.email).first():
        raise HTTPException(status_code=400, detail="Email already registered")
    
    hashed_password = pwd_context.hash(user.password)
    new_user = User(email=user.email, password_hash=hashed_password, display_name=user.display_name)
    db.add(new_user)
    db.commit()
    db.refresh(new_user)
    
    db.add(UserLibrary(user_id=new_user.id))
    
    session = UserSession(user_id=new_user.id, expires_at=datetime.utcnow() + timedelta(days=30))
    db.add(session)
    db.commit()
    
    is_secure = os.getenv("ENVIRONMENT") == "production" or os.getenv("RENDER") is not None
    response.set_cookie(
        key="melo_session",
        value=session.id,
        httponly=True,
        samesite="lax",
        secure=is_secure,
        max_age=30 * 86400
    )
    return {"id": new_user.id, "email": new_user.email, "display_name": new_user.display_name}

@app.post("/api/auth/login")
def login_user(user: UserLogin, response: Response, db=Depends(get_db)):
    db_user = db.query(User).filter(User.email == user.email).first()
    if not db_user or not pwd_context.verify(user.password, db_user.password_hash):
        raise HTTPException(status_code=400, detail="Invalid email or password")
        
    session = UserSession(user_id=db_user.id, expires_at=datetime.utcnow() + timedelta(days=30))
    db.add(session)
    db.commit()
    
    is_secure = os.getenv("ENVIRONMENT") == "production" or os.getenv("RENDER") is not None
    response.set_cookie(
        key="melo_session",
        value=session.id,
        httponly=True,
        samesite="lax",
        secure=is_secure,
        max_age=30 * 86400
    )
    return {"id": db_user.id, "email": db_user.email, "display_name": db_user.display_name}

@app.get("/api/auth/me")
def get_me(request: Request, db=Depends(get_db)):
    user = get_current_user(request, db)
    if not user:
        raise HTTPException(status_code=401, detail="Not authenticated")
    return {"id": user.id, "email": user.email, "display_name": user.display_name}

@app.post("/api/auth/change-password")
def change_password(req: ChangePasswordRequest, request: Request, db=Depends(get_db)):
    user = get_current_user(request, db)
    if not user:
        raise HTTPException(status_code=401, detail="Not authenticated")
    
    if not pwd_context.verify(req.old_password, user.password_hash):
        raise HTTPException(status_code=400, detail="Current password incorrect")
    
    user.password_hash = pwd_context.hash(req.new_password)
    db.commit()
    return {"success": True, "message": "Password updated successfully"}

@app.post("/api/auth/delete-account")
def delete_account(request: Request, response: Response, db=Depends(get_db)):
    user = get_current_user(request, db)
    if not user:
        raise HTTPException(status_code=401, detail="Not authenticated")
    
    db.query(UserSession).filter(UserSession.user_id == user.id).delete()
    db.query(LibraryMutation).filter(LibraryMutation.user_id == user.id).delete()
    db.query(UserLibrary).filter(UserLibrary.user_id == user.id).delete()
    db.query(User).filter(User.id == user.id).delete()
    db.commit()
    
    response.delete_cookie("melo_session")
    return {"success": True, "message": "Account deleted successfully"}

@app.post("/api/auth/logout")
def logout(request: Request, response: Response, db=Depends(get_db)):
    session_id = request.cookies.get("melo_session")
    if session_id:
        db.query(UserSession).filter(UserSession.id == session_id).delete()
        db.commit()
    response.delete_cookie("melo_session")
    return {"success": True}

@app.get("/api/auth/sync")
def get_sync(request: Request, db=Depends(get_db)):
    user = get_current_user(request, db)
    if not user:
        raise HTTPException(status_code=401, detail="Not authenticated")
    
    lib = db.query(UserLibrary).filter(UserLibrary.user_id == user.id).first()
    if not lib:
        return {"favorites": {}, "playlists": {}, "history": []}
        
    try:
        favs = json.loads(lib.favorites_json)
    except Exception:
        favs = {}
    try:
        pls = json.loads(lib.playlists_json)
    except Exception:
        pls = {}
    try:
        hist = json.loads(lib.history_json)
    except Exception:
        hist = []

    return {
        "favorites": favs,
        "playlists": pls,
        "history": hist
    }

@app.post("/api/auth/sync")
def post_sync(payload: SyncPayload, request: Request, db=Depends(get_db)):
    user = get_current_user(request, db)
    if not user:
        raise HTTPException(status_code=401, detail="Not authenticated")
        
    lib = db.query(UserLibrary).filter(UserLibrary.user_id == user.id).first()
    if not lib:
        lib = UserLibrary(user_id=user.id)
        db.add(lib)
        
    lib.favorites_json = json.dumps(payload.favorites)
    lib.playlists_json = json.dumps(payload.playlists)
    lib.history_json = json.dumps(payload.history)
    lib.updated_at = datetime.utcnow()
    db.commit()
    return {"success": True}

@app.post("/api/auth/library/sync")
def sync_library_deltas(payload: DeltaSyncPayload, request: Request, db=Depends(get_db)):
    """Synchronize only queued library mutations for the authenticated account.

    Mutation ids make retries safe. The server stores a revisioned mutation log so
    another device can fetch just the changes it has not seen yet.
    """
    user = get_current_user(request, db)
    if not user:
        raise HTTPException(status_code=401, detail="Not authenticated")

    library = db.query(UserLibrary).filter(UserLibrary.user_id == user.id).first()
    if not library:
        library = UserLibrary(user_id=user.id)
        db.add(library)
        db.flush()

    snapshot = library_snapshot(library)
    acknowledged_ids = []
    processed_ids = set()
    for mutation in payload.mutations[:100]:
        if not mutation.id or len(mutation.id) > 128:
            continue
        if mutation.id in processed_ids:
            acknowledged_ids.append(mutation.id)
            continue
        existing = db.query(LibraryMutation).filter(
            LibraryMutation.id == mutation.id,
            LibraryMutation.user_id == user.id,
        ).first()
        if existing:
            acknowledged_ids.append(mutation.id)
            continue

        apply_library_mutation(snapshot, mutation.operation, mutation.payload)
        library.revision = (library.revision or 0) + 1
        db.add(LibraryMutation(
            id=mutation.id,
            user_id=user.id,
            revision=library.revision,
            operation=mutation.operation,
            payload_json=json.dumps(mutation.payload),
        ))
        processed_ids.add(mutation.id)
        acknowledged_ids.append(mutation.id)

    save_library_snapshot(library, snapshot)
    db.flush()

    changes = db.query(LibraryMutation).filter(
        LibraryMutation.user_id == user.id,
        LibraryMutation.revision > max(0, payload.base_revision),
    ).order_by(LibraryMutation.revision.asc()).limit(200).all()

    # A new browser has no mutation log yet. One initial snapshot seeds its cache;
    # later requests are strictly delta based.
    include_snapshot = payload.base_revision == 0
    db.commit()
    return {
        "revision": library.revision or 0,
        "acknowledged_ids": acknowledged_ids,
        "changes": [
            {
                "id": change.id,
                "revision": change.revision,
                "operation": change.operation,
                "payload": json_value(change.payload_json, {}),
            }
            for change in changes
        ],
        "snapshot": library_snapshot(library) if include_snapshot else None,
    }

# ==========================================
# 3. STREAMING, RECOMMENDATIONS & LYRICS
# ==========================================
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
    except Exception:
        pass

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
        except Exception:
            pass

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
                        except Exception:
                            pass

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
                    except Exception:
                        pass

        if not imported_tracks:
            clean_seed = re.sub(r'https?://[^\s]+', '', url).strip()
            if clean_seed:
                res = await search_endpoint(query=clean_seed)
                imported_tracks = res.get("results", [])[:20]
                playlist_name = clean_seed.capitalize()

    except Exception:
        pass

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
    except Exception:
        pass

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
