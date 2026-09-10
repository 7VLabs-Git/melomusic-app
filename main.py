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

from fastapi import FastAPI, Request, Response, HTTPException, Depends, status, Cookie, Query
from fastapi.responses import HTMLResponse, RedirectResponse, StreamingResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, EmailStr
import httpx
http_client = httpx.AsyncClient()
from pyDes import des, ECB, PAD_PKCS5
from cachetools import TTLCache
from ytmusicapi import YTMusic

from sqlalchemy import create_engine, Column, String, DateTime, Boolean, Integer, ForeignKey, Text
from sqlalchemy.orm import sessionmaker, declarative_base
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
pwd_context = CryptContext(schemes=["argon2"], deprecated="auto")

class User(Base):
    __tablename__ = "users"
    id = Column(String, primary_key=True, index=True, default=lambda: str(uuid.uuid4()))
    email = Column(String, unique=True, index=True)
    password_hash = Column(String)
    display_name = Column(String)
    is_verified = Column(Boolean, default=False)               # Added
    verification_token = Column(String, nullable=True)          # Added
    reset_token = Column(String, nullable=True)
    reset_expires_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)

# ==========================================
# AUTH / JWT HELPERS
# ==========================================
from datetime import datetime, timedelta
from typing import Optional
import jwt  # Make sure to run: pip install PyJWT

JWT_SECRET = os.getenv("JWT_SECRET", "melo_super_secret_jwt_key_change_in_prod")
JWT_ALGORITHM = "HS256"

def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str:
    to_encode = data.copy()
    expire = datetime.utcnow() + (expires_delta or timedelta(days=7))
    to_encode.update({"exp": expire})
    return jwt.encode(to_encode, JWT_SECRET, algorithm=JWT_ALGORITHM)

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

def get_current_user_obj(
    request: Request,
    session_token: Optional[str] = Cookie(None),
    db=Depends(get_db)
) -> User:
    token = session_token or request.cookies.get("session_token")
    if not token:
        raise HTTPException(status_code=401, detail="Session cookie missing")

    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        user_id = payload.get("sub")
        if not user_id:
            raise HTTPException(status_code=401, detail="Invalid token payload")
    except Exception:
        raise HTTPException(status_code=401, detail="Token invalid or expired")

    user = db.query(User).filter(User.id == str(user_id)).first()
    if not user:
        raise HTTPException(status_code=401, detail="User not found")

    return user

# ==========================================
# 2. AUTHENTICATION & SYNC APIS
# ==========================================
from typing import Optional
from pydantic import BaseModel

class AuthPayload(BaseModel):
    email: str
    password: str
    display_name: Optional[str] = None

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
class ForgotPasswordPayload(BaseModel):
    email: str

class ResetPasswordPayload(BaseModel):
    token: str
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
    try:
        if db.query(User).filter(User.email == user.email).first():
            raise HTTPException(status_code=400, detail="Email already registered")
        
        hashed_password = pwd_context.hash(user.password)
        new_user = User(email=user.email, password_hash=hashed_password, display_name=user.display_name)
        db.add(new_user)
        db.flush()
        
        db.add(UserLibrary(
            user_id=new_user.id,
            favorites_json="{}",
            playlists_json="{}",
            history_json="[]",
            search_history_json="[]",
            preferences_json="{}",
            revision=0
        ))
        db.flush()
        
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
    except HTTPException:
        raise
    except Exception as e:
        print(f"Registration error: {str(e)}")
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/auth/login")
def login(payload: AuthPayload, response: Response, db=Depends(get_db)):
    clean_email = payload.email.lower().strip()
    user = db.query(User).filter(User.email == clean_email).first()

    if not user or not pwd_context.verify(payload.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid email or password")

    if not user.is_verified:
        raise HTTPException(
            status_code=403,
            detail="Please verify your email address before logging in. Check your inbox."
        )

    # 1. Create JWT session token
    session_token = create_access_token({"sub": str(user.id), "email": user.email})

    # 2. Write cookie for localhost
    response.set_cookie(
        key="session_token",
        value=session_token,
        httponly=True,
        max_age=60 * 60 * 24 * 7,  # 7 days
        path="/",
        samesite="lax",
        secure=False  # Must be False on http://localhost
    )

    return {
        "success": True,
        "user": {
            "id": user.id,
            "email": user.email,
            "display_name": user.display_name
        }
    }

@app.get("/api/auth/verify-email")
def verify_email(token: str, db=Depends(get_db)):
    user = db.query(User).filter(User.verification_token == token).first()

    if not user:
        return HTMLResponse(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8"><title>Invalid Link - MELO</title>
              <style>
                body { background: #0a0b0f; color: #fff; font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                .card { background: #141622; padding: 32px; border-radius: 14px; text-align: center; border: 1px solid rgba(255,255,255,0.08); }
              </style>
            </head>
            <body>
              <div class="card">
                <h2 style="color: #ff334b;">Verification Link Invalid</h2>
                <p>This link is invalid or has already been used.</p>
                <a href="/" style="color: #fa2d48; text-decoration: none; font-weight: bold;">Go to MELO</a>
              </div>
            </body>
            </html>
            """,
            status_code=400
        )

    # Mark user as verified and clear verification token
    user.is_verified = True
    user.verification_token = None
    db.commit()

    # Generate session and auto-login
    session_token = create_access_token({"sub": user.id, "email": user.email})
    
    redirect_response = RedirectResponse(url="/?verified=1", status_code=302)
    redirect_response.set_cookie(
        key="session_token",
        value=session_token,
        httponly=True,
        max_age=60 * 60 * 24 * 7,
        samesite="lax",
        secure=False  # Set to True on HTTPS/Production
    )
    return redirect_response

@app.get("/api/auth/me")
def get_current_user(
    request: Request,
    session_token: Optional[str] = Cookie(None),
    db=Depends(get_db)
):
    # Fallback check if Cookie param missed it
    token = session_token or request.cookies.get("session_token")
    if not token:
        raise HTTPException(status_code=401, detail="Not authenticated: session cookie missing")

    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        user_id = payload.get("sub")
        if not user_id:
            raise HTTPException(status_code=401, detail="Invalid token payload")
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="Session expired")
    except Exception as e:
        raise HTTPException(status_code=401, detail=f"Token decode error: {str(e)}")

    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=401, detail="User not found")

    return {
        "id": user.id,
        "email": user.email,
        "display_name": user.display_name or user.email.split("@")[0]
    }

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

# PASSWORD RECOVERY ENDPOINT
# ==========================================
import smtplib
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart

import smtplib
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart

@app.post("/api/auth/forgot-password")
async def forgot_password(payload: ForgotPasswordPayload, request: Request, db=Depends(get_db)):
    clean_email = payload.email.lower().strip()
    if not clean_email:
        raise HTTPException(status_code=400, detail="Email is required")

    user = db.query(User).filter(User.email == clean_email).first()
    if not user:
        return {"success": True, "message": f"If an account exists for {clean_email}, reset instructions have been sent."}

    # 1. Generate recovery token & 30-minute expiration
    reset_token = str(uuid.uuid4())
    user.reset_token = reset_token
    user.reset_expires_at = datetime.utcnow() + timedelta(minutes=30)
    db.commit()

    # 2. Dynamic reset link (uses Render domain when deployed, localhost when local)
    base_url = os.getenv("RENDER_EXTERNAL_URL", os.getenv("APP_BASE_URL", str(request.base_url).rstrip("/")))
    reset_link = f"{base_url}/api/auth/reset-password-page?token={reset_token}"

    # 3. Publicly hosted logo URL
    logo_url = "https://melomusic.onrender.com/static/images/melo-text.png"

    # 4. Dispatch email via Brevo HTTP API
    try:
        api_key = os.getenv("BREVO_API_KEY")
        if not api_key:
            print("[EMAIL_ERROR] BREVO_API_KEY is missing.")
            return {"success": True, "message": f"If an account exists for {clean_email}, reset instructions have been sent."}

        sender_email = os.getenv("BREVO_SENDER_EMAIL", "melomusic.team@gmail.com")

        html_content = f"""
        <div style="background-color:#0a0b0f;padding:40px 16px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
            <div style="background-color:#141622;max-width:480px;margin:0 auto;border-radius:16px;padding:36px 28px;border:1px solid rgba(255,255,255,0.08);text-align:center;box-shadow:0 12px 32px rgba(0,0,0,0.5);">
                
                <!-- MELO LOGO -->
                <div style="margin-bottom:24px;text-align:center;">
                    <img src="{logo_url}" alt="MELO" width="130" style="width:130px;max-width:130px;height:auto;display:inline-block;border:0;outline:none;" />
                </div>

                <h2 style="color:#ffffff;font-size:22px;margin:0 0 12px 0;font-weight:700;letter-spacing:-0.3px;">Reset Your Password</h2>
                <p style="color:rgba(255,255,255,0.7);font-size:15px;line-height:1.5;margin:0 0 28px 0;">
                    We received a request to reset the password for your MELO account. Tap the button below to set up a new password:
                </p>
                
                <div style="margin:0 0 28px 0;">
                    <a href="{reset_link}" style="background-color:#fa2d48;color:#ffffff;padding:13px 32px;border-radius:30px;text-decoration:none;font-weight:700;font-size:15px;display:inline-block;box-shadow:0 4px 14px rgba(250,45,72,0.35);">Reset Password</a>
                </div>

                <hr style="border:none;border-top:1px solid rgba(255,255,255,0.08);margin:24px 0 16px 0;" />
                
                <p style="color:rgba(255,255,255,0.4);font-size:12px;line-height:1.4;margin:0;">
                    This link will expire in 30 minutes.<br>
                    If you didn't request a password reset, you can safely ignore this email.
                </p>
            </div>
        </div>
        """

        brevo_payload = {
            "sender": {"name": "MELO Support", "email": sender_email},
            "to": [{"email": clean_email}],
            "subject": "Reset Your MELO Password",
            "htmlContent": html_content
        }

        headers = {
            "accept": "application/json",
            "api-key": api_key,
            "content-type": "application/json"
        }

        response = await http_client.post("https://api.brevo.com/v3/smtp/email", json=brevo_payload, headers=headers)
        if response.status_code in (200, 201):
            print(f"[BREVO HTTP] Reset email dispatched to {clean_email}")
        else:
            print(f"[BREVO_ERROR] Status {response.status_code}: {response.text}")

    except Exception as e:
        print(f"[EMAIL_ERROR] Failed to send email: {str(e)}")

    return {"success": True, "message": f"If an account exists for {clean_email}, reset instructions have been sent."}

# ==========================================
# RESET PASSWORD VIEW & SUBMISSION
# ==========================================
@app.get("/api/auth/reset-password-page", response_class=HTMLResponse)
def reset_password_page(token: str, db=Depends(get_db)):
    # Verify token validity and expiration
    user = db.query(User).filter(User.reset_token == token).first()
    if not user or not user.reset_expires_at or user.reset_expires_at < datetime.utcnow():
        return HTMLResponse(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Link Expired - MELO</title>
              <style>
                body { background: #0a0b0f; color: #fff; font-family: -apple-system, BlinkMacSystemFont, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                .card { background: #141622; padding: 32px; border-radius: 16px; border: 1px solid rgba(255,255,255,0.08); text-align: center; max-width: 380px; width: 90%; }
                h2 { color: #ff334b; margin-top: 0; }
                a { color: #fa2d48; text-decoration: none; font-weight: bold; }
              </style>
            </head>
            <body>
              <div class="card">
                <h2>Invalid or Expired Link</h2>
                <p style="color: rgba(255,255,255,0.6); font-size: 0.9rem;">This password recovery link is no longer valid. Please request a new one.</p>
                <a href="/">Back to MELO</a>
              </div>
            </body>
            </html>
            """,
            status_code=400
        )

    # Render Active Password Reset Form
    return HTMLResponse(f"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
      <title>Reset Password - MELO</title>
      <style>
        body {{ background: #0a0b0f; color: #fff; font-family: -apple-system, BlinkMacSystemFont, sans-serif; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; padding: 20px; box-sizing: border-box; }}
        .card {{ background: #141622; padding: 32px; border-radius: 16px; border: 1px solid rgba(255,255,255,0.08); max-width: 400px; width: 100%; box-shadow: 0 16px 36px rgba(0,0,0,0.6); }}
        h2 {{ margin-top: 0; font-size: 1.4rem; }}
        p {{ color: rgba(255,255,255,0.6); font-size: 0.88rem; margin-bottom: 20px; }}
        .input-group {{ position: relative; margin-bottom: 14px; }}
        input {{ width: 100%; padding: 14px 16px; background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.15); border-radius: 10px; color: #fff; font-size: 0.95rem; box-sizing: border-box; outline: none; transition: border-color 0.25s ease, box-shadow 0.25s ease; }}
        input:focus {{ border-color: #fa2d48; }}
        input.is-valid {{ border-color: #10b981 !important; box-shadow: 0 0 0 1px rgba(16,185,129,0.3) !important; }}
        input.is-invalid {{ border-color: #ff334b !important; box-shadow: 0 0 0 1px rgba(255,51,75,0.3) !important; }}
        button.submit-btn {{ width: 100%; padding: 14px; background: #fa2d48; border: none; border-radius: 25px; color: #fff; font-size: 1rem; font-weight: 700; cursor: pointer; transition: opacity 0.2s; margin-top: 10px; }}
        button.submit-btn:disabled {{ opacity: 0.45; cursor: not-allowed; }}
        .msg {{ font-size: 0.88rem; margin-top: 16px; text-align: center; }}
      </style>
    </head>
    <body>
      <div class="card">
        <h2>Set New Password</h2>
        <p>Enter your new password for <strong>{user.email}</strong>.</p>
        
        <div class="input-group">
          <input type="password" id="p1" placeholder="New Password (min 6 chars)" oninput="checkPasswordMatch()" />
        </div>
        <div class="input-group">
          <input type="password" id="p2" placeholder="Confirm Password" oninput="checkPasswordMatch()" />
        </div>

        <button class="submit-btn" id="saveBtn" onclick="handlePasswordReset()" disabled>Update Password</button>
        <div id="statusMsg" class="msg"></div>
      </div>

      <script>
        const resetToken = "{token}";
        const p1 = document.getElementById('p1');
        const p2 = document.getElementById('p2');
        const btn = document.getElementById('saveBtn');
        const msg = document.getElementById('statusMsg');

        function checkPasswordMatch() {{
          const v1 = p1.value;
          const v2 = p2.value;
          if (!v1 || !v2) {{
            p1.className = '';
            p2.className = '';
            btn.disabled = true;
            return;
          }}
          if (v1 === v2 && v1.length >= 6) {{
            p1.className = 'is-valid';
            p2.className = 'is-valid';
            btn.disabled = false;
          }} else {{
            p1.className = 'is-invalid';
            p2.className = 'is-invalid';
            btn.disabled = true;
          }}
        }}

        async function handlePasswordReset() {{
          btn.disabled = true;
          btn.innerText = "Updating...";
          msg.innerText = "";

          try {{
            const res = await fetch('/api/auth/reset-password', {{
              method: 'POST',
              headers: {{ 'Content-Type': 'application/json' }},
              body: JSON.stringify({{ token: resetToken, new_password: p1.value }})
            }});
            const data = await res.json();
            if (res.ok) {{
              msg.style.color = '#10b981';
              msg.innerHTML = 'Password updated successfully! <a href="/" style="color:#fa2d48;margin-left:4px;">Log in now</a>';
              p1.style.display = 'none';
              p2.style.display = 'none';
              btn.style.display = 'none';
            }} else {{
              msg.style.color = '#ff334b';
              msg.innerText = data.detail || 'Reset failed. Please try again.';
              btn.disabled = false;
              btn.innerText = "Update Password";
            }}
          }} catch (err) {{
            msg.style.color = '#ff334b';
            msg.innerText = 'Network error. Please try again.';
            btn.disabled = false;
            btn.innerText = "Update Password";
          }}
        }}
      </script>
    </body>
    </html>
    """)

@app.post("/api/auth/reset-password")
def reset_password(payload: ResetPasswordPayload, db=Depends(get_db)):
    if len(payload.new_password) < 6:
        raise HTTPException(status_code=400, detail="Password must be at least 6 characters")

    user = db.query(User).filter(User.reset_token == payload.token).first()
    if not user or not user.reset_expires_at or user.reset_expires_at < datetime.utcnow():
        raise HTTPException(status_code=400, detail="Reset link is invalid or has expired")

    # Update password and wipe the used recovery token
    user.password_hash = pwd_context.hash(payload.new_password)
    user.reset_token = None
    user.reset_expires_at = None
    db.commit()

    return {"success": True, "message": "Password updated successfully"}

@app.post("/api/auth/delete-account")
def delete_account(
    response: Response,
    current_user: User = Depends(get_current_user_obj),
    db=Depends(get_db)
):
    try:
        user_id = current_user.id

        # 1. Delete associated library mutations
        db.query(LibraryMutation).filter(LibraryMutation.user_id == user_id).delete()

        # 2. Delete user library record
        db.query(UserLibrary).filter(UserLibrary.user_id == user_id).delete()

        # 3. Delete active sessions if UserSession table exists
        if "UserSession" in globals():
            db.query(UserSession).filter(UserSession.user_id == user_id).delete()

        # 4. Delete the user record
        db.delete(current_user)
        db.commit()

        # 5. Clear the authentication cookie
        response.delete_cookie(
            key="session_token",
            path="/"
        )

        return {"success": True, "message": "Account successfully deleted"}

    except Exception as e:
        db.rollback()
        raise HTTPException(
            status_code=500,
            detail=f"Failed to delete account: {str(e)}"
        )



@app.post("/api/auth/signup")
async def signup(payload: AuthPayload, request: Request, db=Depends(get_db)):
    clean_email = payload.email.lower().strip()
    if not clean_email or not payload.password:
        raise HTTPException(status_code=400, detail="Email and password required")

    existing_user = db.query(User).filter(User.email == clean_email).first()
    if existing_user:
        raise HTTPException(status_code=400, detail="Email already registered")

    # Generate verification token
    verification_token = str(uuid.uuid4())

    new_user = User(
        email=clean_email,
        password_hash=pwd_context.hash(payload.password),
        display_name=payload.display_name or clean_email.split("@")[0],
        is_verified=False,
        verification_token=verification_token
    )
    db.add(new_user)
    db.commit()
    db.refresh(new_user)

    # Base URL handling (Render or Localhost)
    base_url = os.getenv("RENDER_EXTERNAL_URL", os.getenv("APP_BASE_URL", str(request.base_url).rstrip("/")))
    verify_link = f"{base_url}/api/auth/verify-email?token={verification_token}"

    logo_url = "https://melomusic.onrender.com/static/images/melo-text.png"

    # Send verification email via Brevo HTTP API
    try:
        api_key = os.getenv("BREVO_API_KEY")
        sender_email = os.getenv("BREVO_SENDER_EMAIL", "melomusic.team@gmail.com")

        if api_key:
            html_content = f"""
            <div style="background-color:#0a0b0f;padding:40px 16px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <div style="background-color:#141622;max-width:480px;margin:0 auto;border-radius:16px;padding:36px 28px;border:1px solid rgba(255,255,255,0.08);text-align:center;box-shadow:0 12px 32px rgba(0,0,0,0.5);">
                    
                    <!-- ENLARGED MELO LOGO -->
                    <div style="margin-bottom:24px;text-align:center;">
                        <img src="{logo_url}" alt="MELO" width="165" style="width:165px;max-width:165px;height:auto;display:inline-block;border:0;outline:none;" />
                    </div>

                    <h2 style="color:#ffffff;font-size:22px;margin:0 0 12px 0;font-weight:700;">Verify Your Email</h2>
                    <p style="color:rgba(255,255,255,0.7);font-size:15px;line-height:1.5;margin:0 0 28px 0;">
                        Welcome to MELO! Tap the button below to verify your email address and start listening immediately:
                    </p>
                    
                    <div style="margin:0 0 28px 0;">
                        <a href="{verify_link}" style="background-color:#fa2d48;color:#ffffff;padding:13px 34px;border-radius:30px;text-decoration:none;font-weight:700;font-size:15px;display:inline-block;box-shadow:0 4px 14px rgba(250,45,72,0.35);">Verify & Log In</a>
                    </div>

                    <hr style="border:none;border-top:1px solid rgba(255,255,255,0.08);margin:24px 0 16px 0;" />
                    
                    <p style="color:rgba(255,255,255,0.4);font-size:12px;line-height:1.4;margin:0;">
                        If you didn't create an account with MELO, you can safely ignore this email.
                    </p>
                </div>
            </div>
            """

            brevo_payload = {
                "sender": {"name": "MELO Support", "email": sender_email},
                "to": [{"email": clean_email}],
                "subject": "Verify your MELO account",
                "htmlContent": html_content
            }

            headers = {
                "accept": "application/json",
                "api-key": api_key,
                "content-type": "application/json"
            }

            await http_client.post("https://api.brevo.com/v3/smtp/email", json=brevo_payload, headers=headers)
            print(f"[BREVO] Verification email sent to {clean_email}")
    except Exception as e:
        print(f"[VERIFY_EMAIL_ERROR] {e}")

    return {
        "success": True,
        "requires_verification": True,
        "message": "Account created! Please check your email to verify and log in."
    }

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
    # 1. Direct Cookie Extraction & JWT Verification
    token = request.cookies.get("session_token")
    if not token:
        raise HTTPException(status_code=401, detail="Not authenticated: session cookie missing")

    try:
        token_data = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        user_id = token_data.get("sub")
        if not user_id:
            raise HTTPException(status_code=401, detail="Invalid token payload")
    except Exception:
        raise HTTPException(status_code=401, detail="Session expired or token invalid")

    user = db.query(User).filter(User.id == str(user_id)).first()
    if not user:
        raise HTTPException(status_code=401, detail="User not found")

    # 2. Retrieve or Initialize User Library
    library = db.query(UserLibrary).filter(UserLibrary.user_id == user.id).first()
    if not library:
        library = UserLibrary(
            user_id=user.id,
            favorites_json="{}",
            playlists_json="{}",
            history_json="[]",
            search_history_json="[]",
            preferences_json="{}",
            revision=0
        )
        db.add(library)
        db.flush()

    # 3. Apply Queued Mutations
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

    # 4. Fetch Revision Deltas
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
