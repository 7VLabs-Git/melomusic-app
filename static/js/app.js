// ==========================================
// 1. GLOBAL SCOPE & STORAGE ABSTRACTION
// ==========================================
class MeloStorage {
  constructor() {
    this.syncTimer = null;
  }

  getFavorites() { return JSON.parse(localStorage.getItem('melo_favorites') || '{}'); }
  saveFavorites(favs) { 
    localStorage.setItem('melo_favorites', JSON.stringify(favs));
    this.scheduleSync();
  }
  
  getPlaylists() { 
    return JSON.parse(localStorage.getItem('melo_playlists') || '{"pl-favorites":{"id":"pl-favorites","name":"Favorites","tracks":[],"customCover":null},"pl-downloads":{"id":"pl-downloads","name":"Downloaded Songs","tracks":[],"customCover":null}}'); 
  }
  savePlaylists(pls) { 
    localStorage.setItem('melo_playlists', JSON.stringify(pls)); 
    this.scheduleSync();
  }
  
  getHistory() { return JSON.parse(localStorage.getItem('melo_history') || '[]'); }
  saveHistory(hist) { 
    localStorage.setItem('melo_history', JSON.stringify(hist)); 
    this.scheduleSync();
  }
  
  getQuality() { return localStorage.getItem('melo_quality') || '320'; }
  saveQuality(q) { localStorage.setItem('melo_quality', q); }

  scheduleSync() {
    if (!currentUser) return;
    clearTimeout(this.syncTimer);
    setSyncState('queued');
    this.syncTimer = setTimeout(() => this.pushToCloud(), 2500);
  }

  async pushToCloud() {
    if (!currentUser) return false;
    setSyncState('syncing');
    try {
      const payload = {
        favorites: this.getFavorites(),
        playlists: this.getPlaylists(),
        history: this.getHistory()
      };
      const res = await fetch('/api/auth/sync', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify(payload)
      });
      if (!res.ok) throw new Error(`Sync failed (${res.status})`);
      recordSuccessfulSync('saved');
      if (activeView === 'account') window.renderAccountView();
      return true;
    } catch (e) {
      setSyncState('paused');
      if (activeView === 'account') window.renderAccountView();
      return false;
    }
  }

  async pullFromCloud() {
    if (!currentUser) return false;
    setSyncState('syncing');
    try {
      const res = await fetch('/api/auth/sync', { credentials: 'same-origin' });
      if (res.ok) {
        const data = await res.json();
        if (data.favorites && Object.keys(data.favorites).length > 0) {
          const localFavs = this.getFavorites();
          Object.assign(localFavs, data.favorites);
          localStorage.setItem('melo_favorites', JSON.stringify(localFavs));
          favorites = localFavs;
        }
        if (data.playlists && Object.keys(data.playlists).length > 0) {
          const localPls = this.getPlaylists();
          for (const key in data.playlists) {
            if (key !== 'pl-downloads') {
              localPls[key] = data.playlists[key];
            }
          }
          localStorage.setItem('melo_playlists', JSON.stringify(localPls));
          playlists = localPls;
        }
        recordSuccessfulSync('synced');
        showToast("Library Synced with Cloud ☁️");
        if (activeView === 'favorites') renderFavoritesView();
        if (activeView === 'account') window.renderAccountView();
        return true;
      }
      throw new Error(`Sync failed (${res.status})`);
    } catch (e) {
      setSyncState('paused');
      if (activeView === 'account') window.renderAccountView();
      return false;
    }
  }
}

// Phase 2 data layer. UI code talks to this object instead of choosing between
// localStorage, IndexedDB, and the cloud at each call site.
class MeloDataLayer {
  constructor() {
    this.syncTimer = null;
    this.syncing = false;
    this.scope = 'guest';
    this.state = this.readScope(this.scope);
    this.lastPersisted = this.clone(this.state);
    this.pending = this.readPending();
  }

  defaults() {
    return {
      favorites: {},
      playlists: {
        'pl-favorites': { id: 'pl-favorites', name: 'Loved Tracks', tracks: [], created_at: new Date().toISOString() },
        'pl-downloads': { id: 'pl-downloads', name: 'Offline Vault', tracks: [], created_at: new Date().toISOString() }
      },
      history: [],
      searchHistory: [],
      preferences: { quality: '320' },
      revision: 0,
      queue: { tracks: [], currentIndex: -1, context: null }
    };
  }

  clone(value) { return JSON.parse(JSON.stringify(value)); }
  scopeKey() { return `melo_library_${this.scope}`; }
  pendingKey() { return `melo_library_mutations_${this.scope}`; }

  readScope(scope) {
    const saved = localStorage.getItem(`melo_library_${scope}`);
    if (saved) {
      try { return this.normalize(JSON.parse(saved)); } catch (e) {}
    }
    const fresh = this.defaults();
    // Preserve the original local library as the guest library once, rather
    // than implicitly copying it into whichever account logs in first.
    if (scope === 'guest') {
      try {
        fresh.favorites = JSON.parse(localStorage.getItem('melo_favorites') || '{}');
        fresh.playlists = { ...fresh.playlists, ...JSON.parse(localStorage.getItem('melo_playlists') || '{}') };
        fresh.history = JSON.parse(localStorage.getItem('melo_history') || '[]');
        fresh.preferences.quality = localStorage.getItem('melo_quality') || '320';
      } catch (e) {}
    }
    return this.normalize(fresh);
  }

  normalize(state) {
    const defaults = this.defaults();
    const normalized = { ...defaults, ...state };
    normalized.favorites = normalized.favorites || {};
    normalized.playlists = { ...defaults.playlists, ...(normalized.playlists || {}) };
    normalized.history = Array.isArray(normalized.history) ? normalized.history : [];
    normalized.searchHistory = Array.isArray(normalized.searchHistory) ? normalized.searchHistory : [];
    normalized.preferences = { ...defaults.preferences, ...(normalized.preferences || {}) };
    normalized.queue = { ...defaults.queue, ...(normalized.queue || {}) };
    return normalized;
  }

  persist() {
    localStorage.setItem(this.scopeKey(), JSON.stringify(this.state));
    this.lastPersisted = this.clone(this.state);
  }

  readPending() {
    try { return JSON.parse(localStorage.getItem(this.pendingKey()) || '[]'); } catch (e) { return []; }
  }

  persistPending() { localStorage.setItem(this.pendingKey(), JSON.stringify(this.pending)); }
  id() { return crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`; }

  getFavorites() { return this.state.favorites; }
  getPlaylists() { return this.state.playlists; }
  getHistory() { return this.state.history; }
  getSearchHistory() { return this.state.searchHistory; }
  getQuality() { return this.state.preferences.quality || '320'; }

  restoreGlobals() {
    favorites = this.state.favorites;
    playlists = this.state.playlists;
    playHistory = this.state.history;
    selectedQuality = this.getQuality();
  }

  async switchProfile(user) {
    this.scope = user ? `account_${user.id}` : 'guest';
    this.state = this.readScope(this.scope);
    this.lastPersisted = this.clone(this.state);
    this.pending = this.readPending();
    this.restoreGlobals();
    if (typeof currentIndex !== 'undefined' && currentIndex === -1 && this.state.queue.tracks.length) {
      playlist = this.state.queue.tracks;
      currentIndex = this.state.queue.currentIndex;
      currentPlaylistContextId = this.state.queue.context;
    }
    if (user) await this.pullFromCloud();
  }

  enqueue(operation, payload) {
    if (!currentUser) return;
    this.pending.push({ id: this.id(), operation, payload });
    this.persistPending();
    this.scheduleSync();
  }

  saveFavorites(favs) {
    const previous = this.lastPersisted.favorites || {};
    this.state.favorites = favs;
    Object.keys(favs).forEach((trackId) => {
      if (JSON.stringify(previous[trackId]) !== JSON.stringify(favs[trackId])) {
        this.enqueue('favorite', { track: favs[trackId], loved: true });
      }
    });
    Object.keys(previous).forEach((trackId) => {
      if (!favs[trackId]) this.enqueue('favorite', { track_id: trackId, loved: false });
    });
    this.persist();
  }

  savePlaylists(playlistsToSave) {
    const previous = this.lastPersisted.playlists || {};
    this.state.playlists = playlistsToSave;
    Object.entries(playlistsToSave).forEach(([playlistId, playlistData]) => {
      if (playlistId === 'pl-favorites' || playlistId === 'pl-downloads') return;
      if (JSON.stringify(previous[playlistId]) !== JSON.stringify(playlistData)) {
        const priorTracks = previous[playlistId]?.tracks || [];
        const priorIds = new Set(priorTracks.map((track) => String(track.id)));
        const nextTracks = playlistData.tracks || [];
        const additions = nextTracks.filter((track) => !priorIds.has(String(track.id)));
        // Metadata and new tracks are merged; removals and order are independent
        // mutations so another device's additions are never overwritten.
        this.enqueue('playlist_upsert', { playlist: { ...playlistData, tracks: additions } });
        priorTracks.filter((track) => !nextTracks.some((next) => String(next.id) === String(track.id))).forEach((track) => {
          this.enqueue('playlist_track_remove', { playlist_id: playlistId, track_id: track.id, updated_at: playlistData.updated_at });
        });
        const oldOrder = priorTracks.map((track) => String(track.id)).join('|');
        const newOrder = nextTracks.map((track) => String(track.id)).join('|');
        if (oldOrder !== newOrder && nextTracks.length) {
          this.enqueue('playlist_track_order', { playlist_id: playlistId, track_ids: nextTracks.map((track) => String(track.id)), updated_at: playlistData.updated_at });
        }
      }
    });
    Object.keys(previous).forEach((playlistId) => {
      if (!playlistsToSave[playlistId] && !['pl-favorites', 'pl-downloads'].includes(playlistId)) {
        this.enqueue('playlist_delete', { playlist_id: playlistId });
      }
    });
    this.persist();
  }

  saveHistory(history) {
    this.state.history = history.slice(-100);
    this.persist();
  }

  saveQuality(quality) {
    this.state.preferences.quality = quality;
    this.persist();
    this.enqueue('preferences', { values: { quality } });
  }

  setFavorite(track, loved = !this.state.favorites[track.id]) {
    if (loved) this.state.favorites[track.id] = { ...track, added_at: this.state.favorites[track.id]?.added_at || new Date().toISOString() };
    else delete this.state.favorites[track.id];
    this.persist();
    this.enqueue('favorite', loved ? { track: this.state.favorites[track.id], loved: true } : { track_id: track.id, loved: false });
  }

  recordListening(track) {
    const last = this.state.history[this.state.history.length - 1];
    if (last && last.track && String(last.track.id) === String(track.id)) return;
    const entry = { id: this.id(), track, played_at: new Date().toISOString() };
    this.state.history = [...this.state.history, entry].slice(-100);
    this.persist();
    this.enqueue('history_add', { entry });
  }

  removeHistory(entryId) {
    this.state.history = this.state.history.filter((entry) => entry.id !== entryId);
    this.persist();
    this.enqueue('history_remove', { entry_id: entryId });
  }

  rememberSearch(query) {
    const clean = query.trim();
    if (!clean) return;
    this.state.searchHistory = [
      { query: clean, searched_at: new Date().toISOString() },
      ...this.state.searchHistory.filter((item) => item.query.toLowerCase() !== clean.toLowerCase())
    ].slice(0, 12);
    this.persist();
    this.enqueue('search_history', { query: clean, searched_at: new Date().toISOString() });
  }

  removeSearch(query) {
    this.state.searchHistory = this.state.searchHistory.filter((item) => item.query !== query);
    this.persist();
    this.enqueue('search_history_remove', { query });
  }

  clearSearches() {
    this.state.searchHistory = [];
    this.persist();
    this.enqueue('search_history_clear', {});
  }

  saveQueue(queue) {
    this.state.queue = queue;
    this.persist();
  }

  scheduleSync() {
    if (!currentUser) return;
    clearTimeout(this.syncTimer);
    setSyncState('queued');
    this.syncTimer = setTimeout(() => this.pushToCloud(), 1200);
  }

  applyMutation(operation, payload) {
    const playlists = this.state.playlists;
    if (operation === 'favorite') {
      const track = payload.track || {};
      const id = String(track.id || payload.track_id || '');
      if (payload.loved !== false && id) this.state.favorites[id] = track;
      else delete this.state.favorites[id];
    } else if (operation === 'playlist_upsert' && payload.playlist?.id) {
      const incoming = payload.playlist;
      const old = playlists[incoming.id] || {};
      const tracks = [...(old.tracks || []), ...(incoming.tracks || [])];
      const seen = new Set();
      playlists[incoming.id] = { ...old, ...incoming, tracks: tracks.filter((track) => track.id && !seen.has(String(track.id)) && seen.add(String(track.id))) };
    } else if (operation === 'playlist_delete') {
      delete playlists[payload.playlist_id];
    } else if (operation === 'playlist_track_add' && playlists[payload.playlist_id]) {
      const tracks = playlists[payload.playlist_id].tracks || [];
      if (!tracks.some((track) => String(track.id) === String(payload.track?.id))) tracks.push(payload.track);
    } else if (operation === 'playlist_track_remove' && playlists[payload.playlist_id]) {
      playlists[payload.playlist_id].tracks = (playlists[payload.playlist_id].tracks || []).filter((track) => String(track.id) !== String(payload.track_id));
    } else if (operation === 'playlist_track_order' && playlists[payload.playlist_id]) {
      const tracks = playlists[payload.playlist_id].tracks || [];
      const byId = Object.fromEntries(tracks.map((track) => [String(track.id), track]));
      const ordered = (payload.track_ids || []).map((id) => byId[String(id)]).filter(Boolean);
      playlists[payload.playlist_id].tracks = [...ordered, ...tracks.filter((track) => !payload.track_ids.includes(String(track.id)))];
    } else if (operation === 'history_add' && payload.entry?.id) {
      if (!this.state.history.some((entry) => entry.id === payload.entry.id)) this.state.history.push(payload.entry);
      this.state.history = this.state.history.slice(-100);
    } else if (operation === 'history_remove') {
      this.state.history = this.state.history.filter((entry) => entry.id !== payload.entry_id);
    } else if (operation === 'search_history') {
      this.state.searchHistory = [{ query: payload.query, searched_at: payload.searched_at }, ...this.state.searchHistory.filter((item) => item.query.toLowerCase() !== payload.query.toLowerCase())].slice(0, 12);
    } else if (operation === 'search_history_remove') {
      this.state.searchHistory = this.state.searchHistory.filter((item) => item.query !== payload.query);
    } else if (operation === 'search_history_clear') {
      this.state.searchHistory = [];
    } else if (operation === 'preferences') {
      Object.assign(this.state.preferences, payload.values || {});
    }
  }

  applySnapshot(snapshot) {
    const downloaded = this.state.playlists['pl-downloads'];
    this.state = this.normalize({
      ...this.state,
      favorites: snapshot.favorites || {},
      playlists: { ...(snapshot.playlists || {}), ...(downloaded ? { 'pl-downloads': downloaded } : {}) },
      history: snapshot.history || [],
      searchHistory: snapshot.search_history || [],
      preferences: { ...this.state.preferences, ...(snapshot.preferences || {}) },
      revision: snapshot.revision || 0
    });
  }

  async pushToCloud() {
    if (!currentUser || this.syncing) return false;
    if (navigator.onLine === false) { setSyncState('paused'); return false; }
    this.syncing = true;
    setSyncState('syncing');
    try {
      const sent = this.pending.slice(0, 100);
      const res = await fetch('/api/auth/library/sync', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ base_revision: this.state.revision || 0, mutations: sent })
      });
      if (!res.ok) throw new Error(`Sync failed (${res.status})`);
      const data = await res.json();
      if (data.snapshot && (this.state.revision || 0) === 0) this.applySnapshot(data.snapshot);
      const sentIds = new Set(data.acknowledged_ids || []);
      this.pending = this.pending.filter((mutation) => !sentIds.has(mutation.id));
      (data.changes || []).forEach((change) => {
        if (!sentIds.has(change.id)) this.applyMutation(change.operation, change.payload || {});
      });
      this.state.revision = data.revision || this.state.revision;
      this.persist();
      this.persistPending();
      this.restoreGlobals();
      recordSuccessfulSync('saved');
      return true;
    } catch (error) {
      setSyncState('paused');
      return false;
    } finally {
      this.syncing = false;
      if (activeView === 'account') window.renderAccountView();
    }
  }

  async pullFromCloud() { return this.pushToCloud(); }
}

const store = new MeloDataLayer();

let playlist = [];
let forYouTracks = [];
let categoryData = {};
let currentIndex = -1;
let currentPlaylistContextId = null; 
let favorites = store.getFavorites();
let playlists = store.getPlaylists();
let playHistory = store.getHistory();
let selectedQuality = store.getQuality();

let parsedLyrics = [];
let isSynced = false;
let activeView = 'home';
let navigationHistory = ['home'];
let isShuffle = false;
let repeatMode = 'none';
let sleepTimerId = null;
let currentPrimaryHex = '#fa2d48';
let activePlayToken = 0;
let currentVibe = 'Flow';
let pendingTrackForPlaylist = null;
let newPlaylistTempCover = null;
let currentEditingPlId = null;
let editPlTempCover = null;
let isFetchingInfiniteQueue = false;
let isRemoveSongsMode = false;
let downloadedTrackIds = new Set();
let currentUser = null;
let lastSyncedAt = null;
let syncState = 'idle';
let listeningCandidate = null;
let contextTrack = null;

function syncTimestampKey() {
  return currentUser ? `melo_last_synced_at_${currentUser.id}` : null;
}

function loadLastSyncedAt() {
  const key = syncTimestampKey();
  const value = key ? localStorage.getItem(key) : null;
  const date = value ? new Date(value) : null;
  lastSyncedAt = date && !Number.isNaN(date.getTime()) ? date : null;
}

function setSyncState(state) {
  syncState = state;
  if (activeView === 'account') window.renderAccountView();
}

function recordSuccessfulSync(state) {
  lastSyncedAt = new Date();
  const key = syncTimestampKey();
  if (key) localStorage.setItem(key, lastSyncedAt.toISOString());
  syncState = state;
}

// Keep Render.com active
setInterval(() => { fetch('/api/ping').catch(() => {}); }, 10 * 60 * 1000);

// IndexedDB Helper
const IDB_NAME = 'melo_offline_db';
const IDB_STORE = 'downloaded_tracks';

function openMeloDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(IDB_NAME, 1);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains(IDB_STORE)) {
        db.createObjectStore(IDB_STORE, { keyPath: 'id' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function saveTrackToOfflineDB(trackObj, blobData) {
  const db = await openMeloDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(IDB_STORE, 'readwrite');
    const dbStore = tx.objectStore(IDB_STORE);
    dbStore.put({
      id: String(trackObj.id),
      metadata: trackObj,
      blob: blobData,
      downloadedAt: Date.now()
    });
    tx.oncomplete = () => resolve(true);
    tx.onerror = () => reject(tx.error);
  });
}

async function getTrackFromOfflineDB(trackId) {
  try {
    const db = await openMeloDB();
    return new Promise((resolve) => {
      const tx = db.transaction(IDB_STORE, 'readonly');
      const dbStore = tx.objectStore(IDB_STORE);
      const req = dbStore.get(String(trackId));
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => resolve(null);
    });
  } catch (e) {
    return null;
  }
}

async function syncDownloadedPlaylist() {
  try {
    const db = await openMeloDB();
    const tx = db.transaction(IDB_STORE, 'readonly');
    const dbStore = tx.objectStore(IDB_STORE);
    const req = dbStore.getAll();
    req.onsuccess = () => {
      const records = req.result || [];
      if (!playlists['pl-downloads']) {
        playlists['pl-downloads'] = { id: 'pl-downloads', name: 'Downloaded Songs', tracks: [], customCover: null };
      }
      playlists['pl-downloads'].tracks = records.map(r => r.metadata);
      downloadedTrackIds = new Set(records.map(r => String(r.id)));
      store.savePlaylists(playlists);
      if (activeView === 'favorites') renderFavoritesView();
    };
  } catch (e) {}
}

// ==========================================
// AUTHENTICATION & ACCOUNT UI
// ==========================================
let isRegisterMode = false;

window.openAuthModal = function() {
  window.closeSettingsModal();
  document.getElementById('authModal')?.classList.add('open');
};

window.closeAuthModal = function(e) {
  if (!e || e.target.id === 'authModal' || e.target.classList.contains('drag-handle')) {
    document.getElementById('authModal')?.classList.remove('open');
  }
};

window.toggleAuthMode = function() {
  isRegisterMode = !isRegisterMode;
  document.getElementById('authTitle').innerText = isRegisterMode ? 'Create Account' : 'Welcome to MELO';
  document.getElementById('authSubtitle').innerText = isRegisterMode ? 'Join to sync your library anywhere.' : 'Sign in to sync your library across devices.';
  document.getElementById('authName').style.display = isRegisterMode ? 'block' : 'none';
  document.getElementById('authSubmitBtn').innerText = isRegisterMode ? 'Sign Up' : 'Log In';
  document.getElementById('authToggleLink').innerText = isRegisterMode ? 'Already have an account? Log In' : "Don't have an account? Sign up";
};

window.handleAuthSubmit = async function() {
  const email = document.getElementById('authEmail').value.trim();
  const password = document.getElementById('authPassword').value;
  const name = document.getElementById('authName').value.trim();
  const btn = document.getElementById('authSubmitBtn');
  
  if (!email || !password || (isRegisterMode && !name)) {
    showToast("Please fill in all fields.");
    return;
  }
  
  btn.disabled = true;
  btn.innerText = "Please wait...";
  
  const endpoint = isRegisterMode ? '/api/auth/register' : '/api/auth/login';
  const payload = isRegisterMode ? { email, password, display_name: name } : { email, password };
  
  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    
    if (res.ok) {
      // Update immediately, then verify the HttpOnly session cookie was accepted.
      currentUser = data;
      await store.switchProfile(currentUser);
      loadLastSyncedAt();
      updateAccountUI();
      showToast(isRegisterMode ? "Account created! Signed in." : "Logged in successfully!");
      window.closeAuthModal();
      await window.checkAuthStatus();
      
      const hasLocalFavorites = Object.keys(favorites).length > 0;
      const hasLocalPlaylists = Object.keys(playlists).filter(k => k !== 'pl-downloads' && k !== 'pl-favorites').length > 0;
      
      if (hasLocalFavorites || hasLocalPlaylists) {
        document.getElementById('migrationModal').classList.add('open');
      } else {
        await store.pullFromCloud();
      }
    } else {
      showToast(data.detail || "Authentication failed.");
    }
  } catch (err) {
    showToast("Network error. Please try again.");
  } finally {
    btn.disabled = false;
    btn.innerText = isRegisterMode ? 'Sign Up' : 'Log In';
  }
};

window.checkAuthStatus = async function() {
  try {
    const res = await fetch('/api/auth/me', { credentials: 'same-origin' });
    if (res.ok) {
      currentUser = await res.json();
      if (store.scope !== `account_${currentUser.id}`) await store.switchProfile(currentUser);
      loadLastSyncedAt();
      updateAccountUI();
      return true;
    } else {
      currentUser = null;
      updateAccountUI();
      return false;
    }
  } catch (e) {
    currentUser = null;
    updateAccountUI();
    return false;
  }
};

function updateAccountUI() {
  const btn = document.getElementById('navAccountBtn');
  if (!btn) return;
  if (currentUser) {
    btn.innerHTML = `<svg viewBox="0 0 24 24" style="width:14px;height:14px;fill:none;stroke:#fff;stroke-width:2;"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg><span>${currentUser.display_name}</span>`;
    btn.onclick = () => window.openAccountView();
  } else {
    btn.innerHTML = `<svg viewBox="0 0 24 24" style="width:14px;height:14px;fill:none;stroke:#fff;stroke-width:2;"><path d="M15 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4M10 17l5-5-5-5M15 12H3"/></svg><span>Sign In</span>`;
    btn.onclick = () => window.openAuthModal();
  }
}

window.openAccountView = function () {
  if (!currentUser) {
    window.openAuthModal();
    return;
  }
  window.switchView('account');
};

window.renderAccountView = function () {
  const viewContainer = document.getElementById('viewContainer');
  if (!viewContainer || !currentUser) return;

  const syncTimeStr = lastSyncedAt 
    ? `${Math.max(0, Math.round((Date.now() - lastSyncedAt.getTime()) / 60000))} min ago`
    : 'Just now';

  viewContainer.innerHTML = `
    <div class="stage-content" style="max-width: 680px; margin: 0 auto; padding-bottom: 140px;">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" aria-label="Go Back" title="Back">
          <svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <h1 style="font-size: 1.45rem; font-weight: 800; letter-spacing: -0.02em;">Account & Cloud Sync</h1>
      </div>

      <div class="hub-vault-card" style="margin-bottom: 24px; padding: 22px;">
        <div class="hub-vault-badge" style="background: linear-gradient(135deg, var(--accent), #9333ea); width: 62px; height: 62px;">
          <span style="font-size: 1.6rem; font-weight: 800; color: #fff;">${currentUser.display_name.charAt(0).toUpperCase()}</span>
        </div>
        <div style="flex: 1;">
          <div style="font-size: 1.25rem; font-weight: 800; color: #fff;">${currentUser.display_name}</div>
          <div style="font-size: 0.85rem; color: var(--text-muted); margin-top: 2px;">${currentUser.email}</div>
          <span class="pl-meta-tag" style="margin-top: 8px; font-size: 0.65rem;">MELO Cloud Active</span>
        </div>
      </div>

      <div class="section-heading"><h2>Cloud Sync Status</h2></div>
      <div class="create-pl-drawer" style="margin-bottom: 26px; display: flex; align-items: center; justify-content: space-between;">
        <div>
          <div style="font-weight: 700; color: #fff; font-size: 0.95rem;">☁️ Status: Synced</div>
          <div style="font-size: 0.78rem; color: var(--text-muted); margin-top: 3px;">Last backed up: ${syncTimeStr}</div>
        </div>
        <button class="pill-action-btn" onclick="store.pushToCloud(); showToast('Syncing with Cloud...');" style="padding: 8px 18px; font-size: 0.82rem;">
          Sync Now
        </button>
      </div>

      <div class="section-heading"><h2>Security</h2></div>
      <div class="create-pl-drawer" style="margin-bottom: 26px; display: flex; flex-direction: column; gap: 10px;">
        <div style="font-weight: 700; color: #fff; font-size: 0.9rem;">Change Password</div>
        <input type="password" id="oldPassInput" class="themed-pl-input" placeholder="Current Password" />
        <input type="password" id="newPassInput" class="themed-pl-input" placeholder="New Password" />
        <button class="pill-action-btn" onclick="executeChangePassword()" style="width: 100%; justify-content: center; margin-top: 4px;">
          Update Password
        </button>
      </div>

      <div class="section-heading"><h2>Danger Zone</h2></div>
      <div class="create-pl-drawer" style="border-color: rgba(250, 45, 72, 0.25);">
        <button class="pill-action-btn" onclick="executeLogout()" style="width: 100%; justify-content: center; margin-bottom: 10px; background: rgba(255,255,255,0.08); color: #fff;">
          Log Out
        </button>
        <button class="filter-chip" onclick="executeDeleteAccount()" style="width: 100%; text-align: center; color: #ff4d6d; border-color: rgba(250, 45, 72, 0.3);">
          Delete MELO Account
        </button>
      </div>
    </div>
  `;
};

// Account view overrides: status is derived only from real sync requests.
window.openAccountView = async function () {
  if (!currentUser) await window.checkAuthStatus();
  if (!currentUser) return window.openAuthModal();
  window.switchView('account');
};

function accountText(value) {
  const node = document.createElement('span');
  node.textContent = value || '';
  return node.innerHTML;
}

function lastSyncedLabel() {
  if (!lastSyncedAt) return 'Last synced: not yet';
  const minutes = Math.max(0, Math.floor((Date.now() - lastSyncedAt.getTime()) / 60000));
  if (minutes === 0) return 'Last synced: just now';
  return `Last synced: ${minutes} minute${minutes === 1 ? '' : 's'} ago`;
}

function getSyncPresentation() {
  const states = {
    idle: { icon: '&#9729;', label: 'Ready to sync', detail: lastSyncedLabel() },
    queued: { icon: '&#8635;', label: 'Sync queued', detail: 'Changes will be saved shortly.' },
    syncing: { icon: '&#8635;', label: 'Syncing…', detail: 'Saving your library to MELO Cloud.' },
    synced: { icon: '&#9729;', label: 'Synced', detail: lastSyncedLabel() },
    saved: { icon: '&#10003;', label: 'Saved', detail: lastSyncedLabel() },
    paused: { icon: '&#9888;', label: 'Sync paused', detail: 'Unable to reach MELO Cloud. Your local library is safe.' }
  };
  return states[syncState] || states.idle;
}

window.renderAccountView = function () {
  const viewContainer = document.getElementById('viewContainer');
  if (!viewContainer || !currentUser) return;

  const name = accountText(currentUser.display_name);
  const email = accountText(currentUser.email);
  const initial = accountText((currentUser.display_name || currentUser.email || 'M').trim().charAt(0).toUpperCase());
  const favoriteCount = Object.keys(favorites).length;
  const historyCount = playHistory.length;
  const playlistCount = Object.values(playlists).filter(p => p.id !== 'pl-favorites' && p.id !== 'pl-downloads').length;
  const sync = getSyncPresentation();

  viewContainer.innerHTML = `
    <div class="stage-content account-stage">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" aria-label="Go Back" title="Back"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button>
        <h1>MELO Account</h1>
      </div>

      <section class="account-profile-card">
        <div class="account-avatar" aria-hidden="true">${initial}</div>
        <div class="account-identity"><div class="account-name">${name}</div><div class="account-email">${email}</div></div>
      </section>

      <section class="account-section">
        <div class="section-heading"><h2>Your Library</h2></div>
        <div class="account-list">
          <button class="account-row" onclick="switchView('favorites')"><span class="account-row-icon">&#9825;</span><span><strong>Favorites</strong><small>${favoriteCount} saved ${favoriteCount === 1 ? 'track' : 'tracks'}</small></span><span class="account-row-arrow">›</span></button>
          <div class="account-row"><span class="account-row-icon">&#9719;</span><span><strong>Listening History</strong><small>${historyCount} ${historyCount === 1 ? 'play' : 'plays'} on this device</small></span></div>
          <button class="account-row" onclick="switchView('favorites')"><span class="account-row-icon">&#9835;</span><span><strong>Playlists</strong><small>${playlistCount} ${playlistCount === 1 ? 'playlist' : 'playlists'}</small></span><span class="account-row-arrow">›</span></button>
        </div>
      </section>

      <section class="account-section">
        <div class="section-heading"><h2>Sync</h2></div>
        <div class="sync-card sync-${syncState}">
          <div class="sync-status"><span class="sync-icon">${sync.icon}</span><span><strong>${sync.label}</strong><small>${sync.detail}</small></span></div>
          <button class="pill-action-btn account-sync-button" onclick="store.pushToCloud()" ${syncState === 'syncing' ? 'disabled' : ''}>${syncState === 'syncing' ? 'Syncing…' : 'Sync now'}</button>
        </div>
      </section>

      <section class="account-section">
        <div class="section-heading"><h2>Settings</h2></div>
        <div class="account-list">
          <button class="account-row" onclick="promptQualitySelection()"><span class="account-row-icon">&#9834;</span><span><strong>Streaming Quality</strong><small>${selectedQuality} kbps</small></span><span class="account-row-arrow">›</span></button>
          <div class="account-row"><span class="account-row-icon">&#9654;</span><span><strong>Playback</strong><small>Controls are available in the player</small></span></div>
          <button class="account-row" onclick="switchView('favorites')"><span class="account-row-icon">&#8595;</span><span><strong>Downloads</strong><small>Manage offline tracks in Music Hub</small></span><span class="account-row-arrow">›</span></button>
          <div class="account-row"><span class="account-row-icon">&#9681;</span><span><strong>Appearance</strong><small>MELO dark theme</small></span></div>
        </div>
      </section>

      <section class="account-section">
        <div class="section-heading"><h2>Account</h2></div>
        <div class="account-list">
          <button class="account-row" onclick="togglePasswordPanel()"><span class="account-row-icon">&#128274;</span><span><strong>Change Password</strong><small>Update your account password</small></span><span class="account-row-arrow">›</span></button>
          <div id="passwordPanel" class="account-password-panel" hidden><input type="password" id="oldPassInput" class="themed-pl-input" placeholder="Current password" autocomplete="current-password" /><input type="password" id="newPassInput" class="themed-pl-input" placeholder="New password" autocomplete="new-password" /><button class="pill-action-btn" onclick="executeChangePassword()">Update password</button></div>
          <button class="account-row" onclick="executeLogout()"><span class="account-row-icon">&#8594;</span><span><strong>Log Out</strong><small>Sign out of this device</small></span><span class="account-row-arrow">›</span></button>
          <button class="account-row account-row-danger" onclick="executeDeleteAccount()"><span class="account-row-icon">&#215;</span><span><strong>Delete Account</strong><small>Permanently remove cloud data</small></span><span class="account-row-arrow">›</span></button>
        </div>
      </section>
    </div>
  `;
};

window.togglePasswordPanel = function () {
  const panel = document.getElementById('passwordPanel');
  if (panel) panel.hidden = !panel.hidden;
};

window.executeChangePassword = async function () {
  const old_password = document.getElementById('oldPassInput').value;
  const new_password = document.getElementById('newPassInput').value;
  if (!old_password || !new_password) {
    showToast("Please fill both password fields.");
    return;
  }
  try {
    const res = await fetch('/api/auth/change-password', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ old_password, new_password })
    });
    const data = await res.json();
    if (res.ok) {
      showToast("Password updated successfully!");
      document.getElementById('oldPassInput').value = '';
      document.getElementById('newPassInput').value = '';
    } else {
      showToast(data.detail || "Failed to update password.");
    }
  } catch (err) {
    showToast("Network error.");
  }
};

window.executeLogout = async function () {
  await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' });
  currentUser = null;
  await store.switchProfile(null);
  updateAccountUI();
  window.switchView('home');
  showToast("Logged out.");
};

window.executeDeleteAccount = async function (confirmed = false) {
  if (!confirmed) {
    window.showMeloConfirmation({
      title: 'Delete MELO Account?',
      message: 'Your cloud library and account will be permanently removed. Offline downloads remain on this device.',
      actionLabel: 'Delete account', danger: true,
      action: () => window.executeDeleteAccount(true)
    });
    return;
  }
  try {
    const res = await fetch('/api/auth/delete-account', { method: 'POST', credentials: 'same-origin' });
    if (res.ok) {
      currentUser = null;
      await store.switchProfile(null);
      updateAccountUI();
      window.switchView('home');
      showToast("Account deleted.");
    }
  } catch (e) {
    showToast("Failed to delete account.");
  }
};

window.closeMigrationModal = function(e) {
  if (!e || e.target.id === 'migrationModal' || e.target.classList.contains('drag-handle')) {
    document.getElementById('migrationModal')?.classList.remove('open');
  }
};

window.executeLocalToCloudMigration = async function() {
  showToast("Syncing local library to cloud...");
  window.closeMigrationModal();
  const synced = await store.pushToCloud();
  showToast(synced ? "Library synced to your account!" : "Sync paused. Please try again.");
};

// ==========================================
// UTILITIES & NAVIGATION
// ==========================================
function showToast(msg) {
  const t = document.getElementById('meloToast');
  if (!t) return;
  t.innerText = msg;
  t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 2600);
}

function fmtTime(s) {
  if (isNaN(s) || s === null || s === undefined) return "0:00";
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return `${m}:${sec < 10 ? '0' : ''}${sec}`;
}

window.switchView = function (view, pushState = true) {
  if (pushState && activeView !== view) {
    navigationHistory.push(view);
  }
  activeView = view;

  document.querySelectorAll('.capsule-btn, .pill-nav-item').forEach(btn => btn.classList.remove('active'));

  if (view === 'home') {
    document.getElementById('navHome')?.classList.add('active');
    document.getElementById('mNavHome')?.classList.add('active');
    renderHomeView();
  } else if (view === 'search') {
    document.getElementById('navSearch')?.classList.add('active');
    document.getElementById('mNavSearch')?.classList.add('active');
    renderSearchView();
  } else if (view === 'favorites') {
    document.getElementById('navFavs')?.classList.add('active');
    document.getElementById('mNavFavs')?.classList.add('active');
    renderFavoritesView();
  } else if (view === 'account') {
    renderAccountView();
  }
  const vp = document.getElementById('mainViewport');
  if (vp) vp.scrollTop = 0;
};

window.goBack = function () {
  if (navigationHistory.length > 1) {
    navigationHistory.pop();
    const prev = navigationHistory[navigationHistory.length - 1];
    window.switchView(prev, false);
  } else {
    window.switchView('home', false);
  }
};

window.scrollToCategory = function (id) {
  const el = document.getElementById(id);
  if (el) el.scrollIntoView({ behavior: 'smooth' });
};

window.promptQualitySelection = function () {
  window.closeSettingsModal();
  document.getElementById('qualityModal')?.classList.add('open');
};

window.closeQualityModal = function (e) {
  if (!e || e.target === document.getElementById('qualityModal') || e.target.classList.contains('drag-handle')) {
    document.getElementById('qualityModal')?.classList.remove('open');
  }
};

window.selectQualityOption = function (val) {
  window.changeQuality(val);
  window.closeQualityModal();
};

window.changeQuality = function (val) {
  selectedQuality = val;
  store.saveQuality(val);
  const qLabel = document.getElementById('currentQualityLabel');
  if (qLabel) qLabel.innerText = `Audio Quality: ${val} kbps`;
  const deskSelect = document.getElementById('desktopQualitySelect');
  if (deskSelect) deskSelect.value = val;

  const audio = document.getElementById('audio');
  if (audio && currentIndex !== -1 && playlist[currentIndex] && !audio.paused) {
    const curTime = audio.currentTime;
    audio.src = `/api/stream/${playlist[currentIndex].id}?quality=${val}`;
    audio.currentTime = curTime;
    audio.play().catch(console.warn);
  }
};

window.openFullscreenPlayer = function () {
  const overlay = document.getElementById('fullscreenPlayerOverlay');
  if (!overlay) return;
  overlay.style.transform = 'translate3d(0, 0, 0)';
  overlay.classList.add('open');
  syncSheetTrackInfo();
  
  // Update scrubber canvas dimensions for fullscreen
  setTimeout(() => {
    const scrubberWaveCanvas = document.getElementById('scrubberWaveCanvas');
    const scrubberTrackBase = document.getElementById('scrubberTrackBase');
    if (scrubberWaveCanvas && scrubberTrackBase) {
      const dpr = window.devicePixelRatio || 1;
      const w = scrubberTrackBase.offsetWidth;
      const h = scrubberWaveCanvas.offsetHeight || 14;
      scrubberWaveCanvas.width = w * dpr;
      scrubberWaveCanvas.height = h * dpr;
      const ctx = scrubberWaveCanvas.getContext('2d');
      if (ctx) ctx.scale(dpr, dpr);
    }
  }, 100);
};

window.closeFullscreenPlayer = function () {
  const overlay = document.getElementById('fullscreenPlayerOverlay');
  if (!overlay) return;
  overlay.style.transform = '';
  overlay.classList.remove('open');
};

window.openSettingsModal = function () {
  document.getElementById('settingsModal')?.classList.add('open');
};

window.closeSettingsModal = function () {
  document.getElementById('settingsModal')?.classList.remove('open');
};

window.openContextMenu = function (trackOverride = null) {
  const track = trackOverride || (currentIndex !== -1 ? playlist[currentIndex] : null);
  if (!track) return;
  contextTrack = track;
  
  const titleEl = document.getElementById('ctxModalSongTitle');
  if (titleEl) titleEl.innerText = track.title;
  
  const favTextEl = document.getElementById('ctxFavText');
  if (favTextEl) favTextEl.innerText = favorites[track.id] ? "Remove from Favorites" : "Save to Favorites";
  
  const dlRow = document.getElementById('ctxDownloadRow');
  if (dlRow) {
    if (downloadedTrackIds.has(String(track.id))) {
      dlRow.innerHTML = `
        <svg viewBox="0 0 24 24" style="stroke:#10b981; fill:none; stroke-width:2.5;"><path d="M20 6L9 17l-5-5"/></svg>
        <span style="color:#10b981; font-weight:700;">Downloaded ✓</span>
      `;
      dlRow.onclick = () => { showToast("Already downloaded."); window.closeContextMenu(); };
    } else {
      dlRow.innerHTML = `
        <svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        <span style="color:#fff; font-weight:700;">Download for Offline</span>
      `;
      dlRow.onclick = () => window.actionDownloadSong();
    }
  }

  document.getElementById('contextModal')?.classList.add('open');
};

window.closeContextMenu = function () {
  document.getElementById('contextModal')?.classList.remove('open');
};

// OFFLINE DOWNLOAD WITH INDEXEDDB & SPINNER
window.actionDownloadSong = async function () {
  const track = contextTrack || (currentIndex !== -1 ? playlist[currentIndex] : null);
  if (!track) return;
  if (downloadedTrackIds.has(String(track.id))) {
    showToast('Available offline');
    window.closeContextMenu();
    return;
  }

  const dlRow = document.getElementById('ctxDownloadRow');
  if (dlRow) {
    dlRow.innerHTML = `
      <svg class="download-spinner" viewBox="0 0 24 24">
        <circle cx="12" cy="12" r="9" stroke-dasharray="32" stroke-dashoffset="12" stroke-linecap="round"></circle>
      </svg>
      <span>Downloading Track...</span>
    `;
    dlRow.onclick = null;
  }

  showToast(`Downloading "${track.title}"...`);

  try {
    const streamUrl = `/api/download/${track.id}?title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}&quality=${selectedQuality}`;
    const resp = await fetch(streamUrl);

    if (!resp.ok) throw new Error("Stream fetch failed");

    const total = Number(resp.headers.get('content-length')) || 0;
    let received = 0;
    const chunks = [];
    if (resp.body?.getReader) {
      const reader = resp.body.getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value);
        received += value.byteLength;
        if (dlRow && total) dlRow.querySelector('span').textContent = `Downloading ${Math.round((received / total) * 100)}%`;
      }
    }
    const blob = chunks.length ? new Blob(chunks, { type: 'audio/mp4' }) : await resp.blob();

    await saveTrackToOfflineDB(track, blob);
    await syncDownloadedPlaylist();

    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = `${track.title} - ${track.artist}.m4a`;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);

    showToast(`Offline track saved!`);
  } catch (err) {
    showToast("Download failed. Check network.");
  } finally {
    window.closeContextMenu();
  }
};

window.promptImportSelection = function () {
  window.closeSettingsModal();
  document.getElementById('importModal')?.classList.add('open');
};

window.closeImportModal = function (e) {
  if (!e || e.target === document.getElementById('importModal') || e.target.classList.contains('drag-handle')) {
    document.getElementById('importModal')?.classList.remove('open');
  }
};

// ==========================================
// PLAYLIST IMPORT ENGINE
// ==========================================
window.executePlaylistImport = async function () {
  const input = document.getElementById('importPlaylistUrlInput');
  const btn = document.getElementById('executeImportBtn');
  const url = input ? input.value.trim() : '';

  if (!url) {
    showToast("Please enter a playlist link.");
    return;
  }

  if (btn) {
    btn.disabled = true;
    btn.innerText = "Analyzing & importing tracks...";
  }

  try {
    const res = await fetch("/api/import-playlist", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url })
    });
    const data = await res.json();

    if (res.ok && data.success && data.tracks && data.tracks.length > 0) {
      const plId = 'pl-import-' + Date.now();
      playlists[plId] = {
        id: plId,
        name: data.name || "Imported Playlist",
        tracks: data.tracks,
        customCover: null,
        description: '',
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString()
      };
      store.savePlaylists(playlists);
      window.closeImportModal();
      showToast(`Imported "${data.name}" (${data.tracks.length} tracks)!`);
      if (activeView === 'favorites') renderFavoritesView();
      else window.switchView('favorites');
    } else {
      showToast(data.detail || "Unable to import playlist.");
    }
  } catch (err) {
    showToast("Network error importing playlist.");
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.innerText = "Import to MELO Hub";
    }
  }
};

// ==========================================
// 2X2 COLLAGE & COVER HELPERS
// ==========================================
function renderPlaylistCoverHTML(pl) {
  if (pl.customCover) {
    return `<img src="${pl.customCover}" class="pl-cover-img" alt="${pl.name}" />`;
  }
  if (pl.tracks && pl.tracks.length >= 4) {
    return `
      <div class="pl-collage-grid">
        <img src="${pl.tracks[0].thumbnail}" loading="lazy" />
        <img src="${pl.tracks[1].thumbnail}" loading="lazy" />
        <img src="${pl.tracks[2].thumbnail}" loading="lazy" />
        <img src="${pl.tracks[3].thumbnail}" loading="lazy" />
      </div>
    `;
  }
  if (pl.tracks && pl.tracks.length > 0) {
    return `<img src="${pl.tracks[0].thumbnail}" class="pl-cover-img" loading="lazy" />`;
  }
  return `<div class="pl-empty-cover">🎧</div>`;
}

function getPlaylistHeroCoverURL(pl) {
  if (pl.customCover) return pl.customCover;
  if (pl.tracks && pl.tracks.length > 0) return pl.tracks[0].thumbnail;
  return '';
}

window.actionOpenAddToPlaylist = function (trackObj = null) {
  window.closeContextMenu();
  pendingTrackForPlaylist = trackObj || contextTrack || (currentIndex !== -1 ? playlist[currentIndex] : null);
  if (!pendingTrackForPlaylist) return;

  const modal = document.getElementById('addToPlaylistModal');
  const listEl = document.getElementById('playlistOptionsList');
  if (!modal || !listEl) return;

  listEl.innerHTML = '';
  Object.values(playlists).forEach(pl => {
    if (pl.id === 'pl-downloads') return;
    const card = document.createElement('div');
    card.className = 'themed-pl-card';
    card.innerHTML = `
      <div class="themed-pl-card-thumb">${renderPlaylistCoverHTML(pl)}</div>
      <div style="font-size:0.82rem; font-weight:700; max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:#fff;">${pl.name}</div>
      <div style="font-size:0.72rem; color:var(--text-muted);">${pl.tracks.length} songs</div>
    `;
    card.onclick = () => window.addTrackToSpecificPlaylist(pl.id);
    listEl.appendChild(card);
  });

  modal.classList.add('open');
};

window.closeAddToPlaylistModal = function (e) {
  if (!e || e.target === document.getElementById('addToPlaylistModal') || e.target.classList.contains('drag-handle')) {
    document.getElementById('addToPlaylistModal')?.classList.remove('open');
  }
};

window.addTrackToSpecificPlaylist = async function (plId) {
  if (!pendingTrackForPlaylist || !playlists[plId]) return;
  const trackToSave = { ...pendingTrackForPlaylist };

  if (plId === 'pl-downloads') {
    showToast(`Downloading "${trackToSave.title}" for offline storage...`);
    window.closeAddToPlaylistModal();
    try {
      const streamUrl = `/api/download/${trackToSave.id}?title=${encodeURIComponent(trackToSave.title)}&artist=${encodeURIComponent(trackToSave.artist)}`;
      const resp = await fetch(streamUrl);
      if (!resp.ok) throw new Error("Stream fetch failed");
      const blob = await resp.blob();
      await saveTrackToOfflineDB(trackToSave, blob);
      await syncDownloadedPlaylist();
      showToast(`Saved to Downloaded Songs!`);
    } catch (e) {
      showToast("Failed to download track offline.");
    }
    return;
  }

  playlists[plId].tracks.push(trackToSave);
  store.savePlaylists(playlists);
  showToast(`Added to "${playlists[plId].name}"!`);
  window.closeAddToPlaylistModal();
  if (activeView === 'favorites') renderFavoritesView();
};

window.handleCoverFileSelected = function (e) {
  const file = e.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = function (evt) {
    newPlaylistTempCover = evt.target.result;
    const prev = document.getElementById('coverUploadPreview');
    if (prev) {
      prev.style.display = 'block';
      prev.style.backgroundImage = `url(${newPlaylistTempCover})`;
    }
    const labelText = document.getElementById('coverUploadText');
    if (labelText) labelText.innerText = "Cover Selected ✓";
  };
  reader.readAsDataURL(file);
};

window.confirmCreateAndAddToPlaylist = function () {
  const input = document.getElementById('newPlTitleInput');
  const title = input ? input.value.trim() : '';
  if (!title) {
    showToast("Please enter a playlist title.");
    return;
  }

  const id = 'pl-' + Date.now();
  const description = document.getElementById('newPlDescriptionInput')?.value.trim() || '';
  const now = new Date().toISOString();
  playlists[id] = {
    id,
    name: title,
    description,
    tracks: pendingTrackForPlaylist ? [pendingTrackForPlaylist] : [],
    customCover: newPlaylistTempCover || null,
    created_at: now,
    updated_at: now
  };
  store.savePlaylists(playlists);

  showToast(`Created & Saved to "${title}"!`);
  if (input) input.value = '';
  const descriptionInput = document.getElementById('newPlDescriptionInput');
  if (descriptionInput) descriptionInput.value = '';
  newPlaylistTempCover = null;
  const prev = document.getElementById('coverUploadPreview');
  if (prev) prev.style.display = 'none';
  const labelText = document.getElementById('coverUploadText');
  if (labelText) labelText.innerText = "Choose Custom Photo Cover (Optional)";

  window.closeAddToPlaylistModal();
  if (activeView === 'favorites') renderFavoritesView();
};

// ==========================================
// UNIFIED PLAYLIST ACTION MENUS
// ==========================================
window.openPlaylistActionMenu = function (plId) {
  currentEditingPlId = plId;
  const pl = playlists[plId];
  if (!pl) return;

  const modal = document.getElementById('playlistActionMenuModal');
  const title = document.getElementById('plActionMenuTitle');
  const deleteRow = document.getElementById('plMenuDeleteRow');
  const removeToggle = document.getElementById('removeSongsToggleText');

  if (title) title.innerText = pl.name;
  if (deleteRow) {
    deleteRow.style.display = (plId === 'pl-favorites' || plId === 'pl-downloads') ? 'none' : 'flex';
  }
  if (removeToggle) {
    removeToggle.innerText = isRemoveSongsMode ? "Exit Remove Mode" : "Remove Songs";
  }

  modal?.classList.add('open');
};

window.closePlaylistActionMenu = function (e) {
  if (!e || e.target === document.getElementById('playlistActionMenuModal') || e.target.classList.contains('drag-handle')) {
    document.getElementById('playlistActionMenuModal')?.classList.remove('open');
  }
};

window.actionFromMenuEditPlaylist = function () {
  window.closePlaylistActionMenu();
  if (currentEditingPlId) window.openPlaylistEditor(currentEditingPlId);
};

window.actionFromMenuAddSongs = function () {
  window.closePlaylistActionMenu();
  if (!currentEditingPlId) return;

  const modal = document.getElementById('playlistSearchAddModal');
  const input = document.getElementById('plSearchAddInput');
  const results = document.getElementById('plSearchResultsList');

  if (input) {
    input.value = '';
    input.oninput = () => {
      clearTimeout(window.plSearchTimer);
      const q = input.value.trim();
      if (!q) {
        results.innerHTML = '<p style="color:var(--text-dim); font-size:0.85rem; text-align:center; padding:20px;">Type above to find tracks and add directly.</p>';
        return;
      }
      window.plSearchTimer = setTimeout(async () => {
        results.innerHTML = '<p style="color:var(--text-muted); font-size:0.85rem; text-align:center; padding:20px;">Searching...</p>';
        try {
          const res = await fetch(`/api/search?query=${encodeURIComponent(q)}`);
          const data = await res.json();
          const items = data.results || [];

          if (items.length === 0) {
            results.innerHTML = '<p style="color:var(--text-dim); font-size:0.85rem; text-align:center; padding:20px;">No tracks found.</p>';
            return;
          }

          results.innerHTML = '';
          items.forEach(track => {
            const row = document.createElement('div');
            row.className = 'queue-row';
            row.innerHTML = `
              <div class="queue-left-block">
                <img class="queue-thumb" src="${track.thumbnail || ''}" loading="lazy" />
                <div class="queue-info">
                  <div class="queue-title">${track.title}</div>
                  <div class="queue-artist">${track.artist}</div>
                </div>
              </div>
              <button class="queue-action-btn" style="background:var(--accent); color:#fff;">+</button>
            `;
            row.onclick = async () => {
              if (currentEditingPlId === 'pl-downloads') {
                showToast(`Downloading "${track.title}" offline...`);
                try {
                  const streamUrl = `/api/download/${track.id}?title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}`;
                  const resp = await fetch(streamUrl);
                  if (!resp.ok) throw new Error("Download failed");
                  const blob = await resp.blob();
                  await saveTrackToOfflineDB(track, blob);
                  await syncDownloadedPlaylist();
                  showToast(`Downloaded & added!`);
                  openPlaylistDetails('pl-downloads');
                } catch (err) {
                  showToast("Failed to download track.");
                }
              } else if (playlists[currentEditingPlId]) {
                playlists[currentEditingPlId].tracks.push(track);
                store.savePlaylists(playlists);
                showToast(`Added to "${playlists[currentEditingPlId].name}"!`);
                openPlaylistDetails(currentEditingPlId);
              }
            };
            results.appendChild(row);
          });
        } catch (e) {
          results.innerHTML = '<p style="color:var(--accent); font-size:0.85rem; text-align:center; padding:20px;">Search error.</p>';
        }
      }, 250);
    };
  }

  modal?.classList.add('open');
};

window.closePlaylistSearchAddModal = function (e) {
  if (!e || e.target === document.getElementById('playlistSearchAddModal') || e.target.classList.contains('drag-handle')) {
    document.getElementById('playlistSearchAddModal')?.classList.remove('open');
  }
};

window.actionToggleRemoveSongsMode = function () {
  window.closePlaylistActionMenu();
  isRemoveSongsMode = !isRemoveSongsMode;
  if (currentEditingPlId) openPlaylistDetails(currentEditingPlId);
};

window.removeTrackFromPlaylistDirect = function (plId, trackIndex) {
  const pl = playlists[plId];
  if (!pl || !pl.tracks[trackIndex]) return;

  const trackTitle = pl.tracks[trackIndex].title;
  pl.tracks.splice(trackIndex, 1);
  store.savePlaylists(playlists);
  showToast(`Removed "${trackTitle}"`);
  openPlaylistDetails(plId);
};

window.actionFromMenuDeletePlaylist = function () {
  window.closePlaylistActionMenu();
  if (currentEditingPlId) window.deleteCustomPlaylist(currentEditingPlId);
};

window.deleteCustomPlaylist = function (playlistId) {
  const playlistToDelete = playlists[playlistId];
  if (!playlistToDelete || ['pl-favorites', 'pl-downloads'].includes(playlistId)) return;
  window.showMeloConfirmation({
    title: `Delete “${playlistToDelete.name}”?`, message: 'This removes the playlist, not the songs in your library.',
    actionLabel: 'Delete playlist', danger: true,
    action: () => { delete playlists[playlistId]; store.savePlaylists(playlists); window.switchView('favorites'); showToast('Playlist deleted.'); }
  });
};

window.openPlaylistEditor = function (plId) {
  currentEditingPlId = plId;
  editPlTempCover = null;
  const pl = playlists[plId];
  if (!pl) return;

  const modal = document.getElementById('playlistEditModal');
  const nameInput = document.getElementById('editPlNameInput');
  const descriptionInput = document.getElementById('editPlDescriptionInput');
  const preview = document.getElementById('editCoverUploadPreview');
  const labelText = document.getElementById('editCoverUploadText');

  if (nameInput) nameInput.value = pl.name;
  if (descriptionInput) descriptionInput.value = pl.description || '';
  if (labelText) labelText.innerText = "Change Photo Cover (Optional)";
  if (preview) {
    if (pl.customCover) {
      preview.style.display = 'block';
      preview.style.backgroundImage = `url(${pl.customCover})`;
    } else {
      preview.style.display = 'none';
    }
  }

  modal?.classList.add('open');
};

window.closePlaylistEditModal = function (e) {
  if (!e || e.target === document.getElementById('playlistEditModal') || e.target.classList.contains('drag-handle')) {
    document.getElementById('playlistEditModal')?.classList.remove('open');
  }
};

window.handleEditCoverFileSelected = function (e) {
  const file = e.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = function (evt) {
    editPlTempCover = evt.target.result;
    const prev = document.getElementById('editCoverUploadPreview');
    if (prev) {
      prev.style.display = 'block';
      prev.style.backgroundImage = `url(${editPlTempCover})`;
    }
    const labelText = document.getElementById('editCoverUploadText');
    if (labelText) labelText.innerText = "New Cover Selected ✓";
  };
  reader.readAsDataURL(file);
};

window.confirmPlaylistEdit = function () {
  if (!currentEditingPlId || !playlists[currentEditingPlId]) return;
  const pl = playlists[currentEditingPlId];

  const nameInput = document.getElementById('editPlNameInput');
  const newName = nameInput ? nameInput.value.trim() : '';
  if (newName) pl.name = newName;
  const descriptionInput = document.getElementById('editPlDescriptionInput');
  if (descriptionInput) pl.description = descriptionInput.value.trim();

  if (editPlTempCover) {
    pl.customCover = editPlTempCover;
  }
  pl.updated_at = new Date().toISOString();

  store.savePlaylists(playlists);
  showToast("Playlist updated!");
  window.closePlaylistEditModal();
  openPlaylistDetails(currentEditingPlId);
};

window.resetPlaylistCoverToCollage = function (plId) {
  if (!playlists[plId]) return;
  playlists[plId].customCover = null;
  store.savePlaylists(playlists);
  showToast("Reverted to automatic collage!");
  window.closePlaylistEditModal();
  openPlaylistDetails(plId);
};

// ==========================================
// TRANSPORT & QUEUE LOGIC
// ==========================================
window.toggleRepeatMode = function () {
  const rBtn = document.getElementById('sheetRepeatBtn');
  const badge = document.getElementById('loopBadge');

  if (repeatMode === 'none') {
    repeatMode = 'all';
    rBtn?.classList.add('active');
    if (badge) {
      badge.style.display = 'block';
      badge.innerText = 'ALL';
      badge.style.background = 'var(--accent)';
    }
  } else if (repeatMode === 'all') {
    repeatMode = 'one';
    rBtn?.classList.add('active');
    if (badge) {
      badge.style.display = 'block';
      badge.innerText = '1';
      badge.style.background = 'var(--accent-purple)';
    }
  } else {
    repeatMode = 'none';
    rBtn?.classList.remove('active');
    if (badge) badge.style.display = 'none';
  }
};

window.toggleShuffle = function () {
  isShuffle = !isShuffle;
  document.getElementById('sheetShuffleBtn')?.classList.toggle('active', isShuffle);
};

window.togglePlay = function () {
  const audio = document.getElementById('audio');
  if (!audio) return;
  if (!audio.src && playlist.length) {
    window.playIndex(0);
    return;
  }
  if (audio.paused) {
    audio.play().then(() => setPlayState(true)).catch(console.warn);
  } else {
    audio.pause();
    setPlayState(false);
  }
};

async function checkAndExpandInfiniteQueue() {
  if (currentPlaylistContextId !== null) return;
  if (isFetchingInfiniteQueue || playlist.length === 0) return;

  const remaining = playlist.length - 1 - currentIndex;

  if (remaining <= 3) {
    isFetchingInfiniteQueue = true;
    const seed = playlist[playlist.length - 1];
    try {
      const res = await fetch(`/api/recommendations/${seed.id}?title=${encodeURIComponent(seed.title)}&artist=${encodeURIComponent(seed.artist)}`);
      const data = await res.json();
      if (data.tracks && data.tracks.length > 0) {
        const existingIds = new Set(playlist.map(t => t.id));
        const newTracks = data.tracks.filter(t => !existingIds.has(t.id));
        if (newTracks.length > 0) {
          playlist.push(...newTracks);
          persistPlaybackQueue();
          if (document.getElementById('tabQueue')?.classList.contains('active')) {
            renderSheetQueueList();
          }
        }
      }
    } catch (e) {
    } finally {
      isFetchingInfiniteQueue = false;
    }
  }
}

window.nextTrack = async function () {
  const audio = document.getElementById('audio');
  if (repeatMode === 'one' && audio) {
    audio.currentTime = 0;
    audio.play().catch(console.warn);
    return;
  }

  if (isShuffle && playlist.length > 1) {
    const nextIdx = Math.floor(Math.random() * playlist.length);
    window.playIndex(nextIdx);
    return;
  }

  if (currentIndex + 1 < playlist.length) {
    window.playIndex(currentIndex + 1);
    checkAndExpandInfiniteQueue();
    return;
  }

  if (currentPlaylistContextId === null) {
    await checkAndExpandInfiniteQueue();
    if (currentIndex + 1 < playlist.length) {
      window.playIndex(currentIndex + 1);
      return;
    }
  }

  if (repeatMode === 'all') {
    window.playIndex(0);
  }
};

window.prevTrack = function () {
  const audio = document.getElementById('audio');
  if (audio && audio.currentTime > 3) {
    audio.currentTime = 0;
    return;
  }
  if (currentIndex > 0) window.playIndex(currentIndex - 1);
};

window.switchPlayerSheetTab = function (tab) {
  document.querySelectorAll('.sheet-tab-btn').forEach(b => b.classList.remove('active'));
  const playView = document.getElementById('sheetViewPlaying');
  const lyricsView = document.getElementById('sheetViewLyrics');
  const queueView = document.getElementById('sheetViewQueue');

  if (playView) playView.style.display = 'none';
  if (lyricsView) lyricsView.style.display = 'none';
  if (queueView) queueView.style.display = 'none';

  if (tab === 'playing') {
    document.getElementById('tabNowPlaying')?.classList.add('active');
    if (playView) playView.style.display = 'flex';
  } else if (tab === 'lyrics') {
    document.getElementById('tabLyrics')?.classList.add('active');
    if (lyricsView) lyricsView.style.display = 'flex';
  } else if (tab === 'queue') {
    document.getElementById('tabQueue')?.classList.add('active');
    if (queueView) queueView.style.display = 'flex';
    renderSheetQueueList();
  }
};

window.toggleCurrentTrackFavorite = function () {
  const track = contextTrack || (currentIndex !== -1 ? playlist[currentIndex] : null);
  if (!track) return;
  store.setFavorite(track);
  syncSheetTrackInfo();
};

window.actionAddToFavorites = function () {
  window.toggleCurrentTrackFavorite();
  window.closeContextMenu();
};

function persistPlaybackQueue() {
  store.saveQueue({ tracks: playlist, currentIndex, context: currentPlaylistContextId });
}

let meloConfirmationAction = null;
window.showMeloConfirmation = function ({ title, message, actionLabel = 'Continue', danger = false, action }) {
  document.getElementById('meloConfirmTitle').textContent = title;
  document.getElementById('meloConfirmMessage').textContent = message;
  const button = document.getElementById('meloConfirmButton');
  button.textContent = actionLabel;
  button.style.background = danger ? '#fa2d48' : 'var(--accent)';
  meloConfirmationAction = action;
  document.getElementById('meloConfirmModal')?.classList.add('open');
};
window.closeMeloConfirmation = function (event) {
  if (!event || event.target?.id === 'meloConfirmModal' || event.target?.classList.contains('drag-handle')) {
    document.getElementById('meloConfirmModal')?.classList.remove('open');
    meloConfirmationAction = null;
  }
};
window.confirmMeloAction = async function () {
  const action = meloConfirmationAction;
  window.closeMeloConfirmation();
  if (action) await action();
};

window.actionPlayContextTrack = function () {
  const track = contextTrack;
  if (!track) return;
  playlist = [track, ...playlist.filter((item) => String(item.id) !== String(track.id))];
  currentPlaylistContextId = null;
  window.playIndex(0);
  window.closeContextMenu();
};

window.actionPlayNext = function () {
  const track = contextTrack;
  if (!track) return;
  const existing = playlist.findIndex((item, index) => index > currentIndex && String(item.id) === String(track.id));
  if (existing >= 0) playlist.splice(existing, 1);
  playlist.splice(Math.max(0, currentIndex + 1), 0, track);
  persistPlaybackQueue();
  showToast('Playing next');
  window.closeContextMenu();
};

window.actionAddToQueue = function () {
  const track = contextTrack;
  if (!track) return;
  playlist.push(track);
  persistPlaybackQueue();
  showToast('Added to queue');
  window.closeContextMenu();
};

window.actionShareSong = function () {
  window.closeContextMenu();
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const track = playlist[currentIndex];
  const shareData = {
    title: track.title,
    text: `Listen to "${track.title}" by ${track.artist} on MELO!`,
    url: window.location.origin
  };
  if (navigator.share) {
    navigator.share(shareData).catch(() => {});
  } else {
    navigator.clipboard.writeText(`${shareData.text} ${shareData.url}`);
    showToast("Song link copied to clipboard!");
  }
};

window.actionSleepTimerPrompt = function () {
  window.closeContextMenu();
  window.showMeloConfirmation({ title: 'Start sleep timer?', message: 'Playback will pause in 30 minutes.', actionLabel: 'Start timer', action: () => {
    if (sleepTimerId) clearTimeout(sleepTimerId);
    sleepTimerId = setTimeout(() => { const audio = document.getElementById('audio'); if (audio) audio.pause(); setPlayState(false); showToast('Sleep timer reached. Good night!'); }, 30 * 60 * 1000);
    showToast('Sleep timer set for 30 minutes.');
  }});
};

window.actionViewCredits = function () {
  window.closeContextMenu();
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const track = playlist[currentIndex];
  window.showMeloConfirmation({ title: track.title, message: `${track.artist || 'Unknown artist'} · ${track.album || 'Single'} · ${track.duration || '—'}`, actionLabel: 'Close', action: () => {} });
};

// ==========================================
// COLOR SYSTEM WITH PROXY-FALLBACK SAMPLING
// ==========================================
function generateVibrantColors(seedStr) {
  let hash = 0;
  for (let i = 0; i < seedStr.length; i++) {
    hash = seedStr.charCodeAt(i) + ((hash << 5) - hash);
  }
  const r1 = Math.abs((hash * 43) % 180) + 60;
  const g1 = Math.abs((hash * 23) % 150) + 45;
  const b1 = Math.abs((hash * 67) % 200) + 55;
  const r2 = Math.min(240, Math.abs(255 - r1) + 40);
  const g2 = Math.min(240, Math.abs(255 - g1) + 40);
  const b2 = Math.min(240, Math.abs(255 - b1) + 40);

  currentPrimaryHex = `#${((1 << 24) + (r1 << 16) + (g1 << 8) + b1).toString(16).slice(1)}`;
  document.documentElement.style.setProperty('--mesh-color-1', `rgba(${r1}, ${g1}, ${b1}, 0.95)`);
  document.documentElement.style.setProperty('--mesh-color-2', `rgba(${r2}, ${g2}, ${b2}, 0.88)`);
  document.documentElement.style.setProperty('--play-accent-color', `rgb(${r1}, ${g1}, ${b1})`);
}

function applyPlaylistDynamicColors(imgUrl, fallbackSeed) {
  let hash = 0;
  const seed = fallbackSeed || 'melo';
  for (let i = 0; i < seed.length; i++) hash = seed.charCodeAt(i) + ((hash << 5) - hash);
  const r0 = Math.abs((hash * 47) % 160) + 70;
  const g0 = Math.abs((hash * 29) % 130) + 40;
  const b0 = Math.abs((hash * 61) % 170) + 60;
  
  document.documentElement.style.setProperty('--pl-dynamic-bg', `rgba(${r0}, ${g0}, ${b0}, 0.72)`);
  document.documentElement.style.setProperty('--pl-dynamic-bg-dark', `rgba(${r0}, ${g0}, ${b0}, 0.15)`);

  if (!imgUrl) return;

  let safeUrl = imgUrl;
  if (safeUrl.startsWith('http://') || safeUrl.startsWith('https://')) {
    if (!safeUrl.includes('/api/proxy-image')) {
      safeUrl = `/api/proxy-image?url=${encodeURIComponent(safeUrl)}`;
    }
  }

  const img = new Image();
  img.crossOrigin = "anonymous";
  img.src = safeUrl;

  img.onload = () => {
    try {
      const cvs = document.createElement("canvas");
      const ctx = cvs.getContext("2d");
      cvs.width = 32;
      cvs.height = 32;
      ctx.drawImage(img, 0, 0, 32, 32);
      const data = ctx.getImageData(0, 0, 32, 32).data;
      let r = 0, g = 0, b = 0, count = 0;
      for (let i = 0; i < data.length; i += 4) {
        const a = data[i + 3];
        if (a > 128) {
          const pr = data[i], pg = data[i + 1], pb = data[i + 2];
          const max = Math.max(pr, pg, pb), min = Math.min(pr, pg, pb);
          const sat = max === min ? 0 : (max - min) / (255 - Math.abs(max + min - 255));
          if (sat > 0.25) {
            r += pr * (1 + sat);
            g += pg * (1 + sat);
            b += pb * (1 + sat);
            count += (1 + sat);
          } else {
            r += pr; g += pg; b += pb; count++;
          }
        }
      }
      if (count > 0) {
        r = Math.min(255, Math.round(r / count * 1.15));
        g = Math.min(255, Math.round(g / count * 1.15));
        b = Math.min(255, Math.round(b / count * 1.15));
        document.documentElement.style.setProperty('--pl-dynamic-bg', `rgba(${r}, ${g}, ${b}, 0.72)`);
        document.documentElement.style.setProperty('--pl-dynamic-bg-dark', `rgba(${r}, ${g}, ${b}, 0.15)`);
      }
    } catch (e) {}
  };
}

window.updateArtworkPalette = function (imgUrl, trackTitle = '', trackId = '') {
  if (!imgUrl) {
    generateVibrantColors(trackTitle || trackId || 'melo');
    return;
  }
  const img = new Image();
  img.crossOrigin = "anonymous";
  img.src = imgUrl;

  img.onload = () => {
    try {
      const cvs = document.createElement("canvas");
      const ctx = cvs.getContext("2d");
      const size = 32;
      cvs.width = size;
      cvs.height = size;
      ctx.drawImage(img, 0, 0, size, size);

      const data = ctx.getImageData(0, 0, size, size).data;
      const buckets = {};

      for (let i = 0; i < data.length; i += 4) {
        const r = data[i];
        const g = data[i + 1];
        const b = data[i + 2];
        const a = data[i + 3];

        if (a < 128) continue;
        const max = Math.max(r, g, b);
        const min = Math.min(r, g, b);
        const l = (max + min) / 2;
        const s = max === min ? 0 : (max - min) / (255 - Math.abs(2 * l - 255));

        if (l < 25 || l > 235 || s < 0.2) continue;

        const qr = Math.round(r / 24) * 24;
        const qg = Math.round(g / 24) * 24;
        const qb = Math.round(b / 24) * 24;
        const key = `${qr},${qg},${qb}`;

        if (!buckets[key]) buckets[key] = { r: qr, g: qg, b: qb, count: 0, sat: s };
        buckets[key].count += 1;
      }

      const sorted = Object.values(buckets).sort((a, b) => (b.count * b.sat) - (a.count * a.sat));
      let primary = sorted[0];
      let secondary = sorted[1];

      if (!primary) {
        generateVibrantColors(trackTitle || trackId);
        return;
      }

      if (!secondary) {
        secondary = {
          r: Math.min(240, Math.max(40, 255 - primary.r)),
          g: Math.min(240, Math.max(40, primary.b)),
          b: Math.min(240, Math.max(40, primary.g))
        };
      }

      const r1 = primary.r, g1 = primary.g, b1 = primary.b;
      const r2 = secondary.r, g2 = secondary.g, b2 = secondary.b;

      currentPrimaryHex = `#${((1 << 24) + (r1 << 16) + (g1 << 8) + b1).toString(16).slice(1)}`;
      document.documentElement.style.setProperty('--mesh-color-1', `rgba(${r1}, ${g1}, ${b1}, 0.95)`);
      document.documentElement.style.setProperty('--mesh-color-2', `rgba(${r2}, ${g2}, ${b2}, 0.88)`);
      document.documentElement.style.setProperty('--play-accent-color', `rgb(${r1}, ${g1}, ${b1})`);

      const luminance = (0.299 * r1 + 0.587 * g1 + 0.114 * b1) / 255;
      const playSvg = document.getElementById('sheetPlayBtnSvg');
      if (playSvg) playSvg.style.fill = luminance > 0.6 ? '#000000' : '#ffffff';
    } catch (e) {
      generateVibrantColors(trackTitle || trackId);
    }
  };

  img.onerror = () => generateVibrantColors(trackTitle || trackId);
};

window.playIndex = async function (idx) {
  if (idx < 0 || idx >= playlist.length) return;

  const thisToken = ++activePlayToken;
  currentIndex = idx;
  const track = playlist[currentIndex];
  const audio = document.getElementById('audio');
  listeningCandidate = { track, queueToken: activePlayToken, recorded: false };
  store.saveQueue({ tracks: playlist, currentIndex, context: currentPlaylistContextId });

  const dockTitle = document.getElementById('dockTitle');
  const dockArtist = document.getElementById('dockArtist');
  const dockThumb = document.getElementById('dockThumb');

  if (dockTitle) dockTitle.innerText = track.title;
  if (dockArtist) dockArtist.innerText = track.artist;
  if (dockThumb) dockThumb.src = track.thumbnail || '';

  syncSheetTrackInfo();

  const miniplayer = document.getElementById('dockPlayerBar');
  if (miniplayer) miniplayer.classList.add('active');

  if (document.getElementById('tabQueue')?.classList.contains('active')) {
    renderSheetQueueList();
  }

  if (audio) {
    const offlineRecord = await getTrackFromOfflineDB(track.id);
    if (offlineRecord && offlineRecord.blob) {
      audio.src = URL.createObjectURL(offlineRecord.blob);
    } else {
      audio.src = `/api/stream/${track.id}?quality=${selectedQuality}`;
    }
    audio.load();

    const playPromise = audio.play();
    if (playPromise !== undefined) {
      playPromise
        .then(() => {
          if (thisToken !== activePlayToken) {
            audio.pause();
            return;
          }
          setPlayState(true);
        })
        .catch((err) => {
          if (thisToken === activePlayToken) {
            setPlayState(false);
          }
        });
    }
  }

  fetchLyrics(track, thisToken);
  checkAndExpandInfiniteQueue();

  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: track.title,
      artist: track.artist,
      album: 'MELO Music',
      artwork: [{ src: track.thumbnail || '', sizes: '512x512', type: 'image/jpeg' }]
    });
    navigator.mediaSession.setActionHandler('play', () => window.togglePlay());
    navigator.mediaSession.setActionHandler('pause', () => window.togglePlay());
    navigator.mediaSession.setActionHandler('previoustrack', () => window.prevTrack());
    navigator.mediaSession.setActionHandler('nexttrack', () => window.nextTrack());
  }
};

function setPlayState(playing) {
  const icon = playing
    ? `<svg viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>`
    : `<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>`;

  const dockPlayBtn = document.getElementById('dockPlayBtn');
  const mDockPlayBtn = document.getElementById('mDockPlayBtn');
  const sheetPlayBtn = document.getElementById('sheetPlayBtn');
  const dockPlayerBar = document.getElementById('dockPlayerBar');
  const coverBox = document.getElementById('sheetCoverBox');

  if (dockPlayBtn) dockPlayBtn.innerHTML = icon;
  if (mDockPlayBtn) mDockPlayBtn.innerHTML = icon;
  if (sheetPlayBtn) sheetPlayBtn.innerHTML = icon;

  if (dockPlayerBar) dockPlayerBar.classList.toggle('is-playing', playing);
  if (coverBox) coverBox.classList.toggle('is-playing', playing);
}

function syncSheetTrackInfo() {
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const track = playlist[currentIndex];

  const sheetCover = document.getElementById('sheetCover');
  if (sheetCover) sheetCover.src = track.thumbnail || '';

  const titleEl = document.getElementById('sheetTitle');
  if (titleEl) {
    titleEl.innerText = track.title;
    setTimeout(() => {
      const wrapper = titleEl.parentElement;
      if (wrapper && titleEl.scrollWidth > wrapper.clientWidth) {
        titleEl.classList.add('is-overflowing');
      } else {
        titleEl.classList.remove('is-overflowing');
      }
    }, 50);
  }

  const artistEl = document.getElementById('sheetArtist');
  if (artistEl) artistEl.innerText = track.artist;

  const isFav = !!favorites[track.id];
  const favIcon = document.getElementById('playerSheetFavIcon');
  if (favIcon) {
    favIcon.innerText = isFav ? '♥' : '♡';
    favIcon.style.color = isFav ? 'var(--accent)' : '#fff';
  }

  updateArtworkPalette(track.thumbnail, track.title, track.id);
}

// Lyrics Engine
async function fetchLyrics(track, token) {
  const container = document.getElementById('sheetViewLyrics');
  if (container) container.innerHTML = `<div class="lyrics-line active">Syncing lyrics...</div>`;
  parsedLyrics = [];
  isSynced = false;

  try {
    const res = await fetch(`/api/lyrics?video_id=${track.id}&title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}`);
    const data = await res.json();

    if (token !== activePlayToken) return;

    isSynced = data.synced || false;
    parsedLyrics = data.lines || [];

    if (container) {
      container.innerHTML = '';
      parsedLyrics.forEach((l) => {
        const div = document.createElement('div');
        div.className = `lyrics-line`;
        div.innerText = l.text;
        if (isSynced) {
          div.onclick = () => {
            const audio = document.getElementById('audio');
            if (audio) audio.currentTime = l.time;
          };
        }
        container.appendChild(div);
      });
      
      // Start lyrics sync loop
      if (isSynced && parsedLyrics.length > 0) {
        updateLyricsSync();
      }
    }
  } catch (err) {
    if (token === activePlayToken && container) {
      container.innerHTML = `<div class="lyrics-line">Lyrics unavailable.</div>`;
    }
  }
}

// Sync lyrics with playback
function updateLyricsSync() {
  if (!isSynced || !parsedLyrics || parsedLyrics.length === 0) {
    requestAnimationFrame(updateLyricsSync);
    return;
  }

  const audio = document.getElementById('audio');
  if (!audio || isNaN(audio.duration)) {
    requestAnimationFrame(updateLyricsSync);
    return;
  }

  const currentTime = audio.currentTime;
  const container = document.getElementById('sheetViewLyrics');
  if (!container) {
    requestAnimationFrame(updateLyricsSync);
    return;
  }

  // Find the active lyrics line based on current time
  let activeIndex = -1;
  for (let i = parsedLyrics.length - 1; i >= 0; i--) {
    if (parsedLyrics[i].time <= currentTime) {
      activeIndex = i;
      break;
    }
  }

  // Update all lyrics lines
  const lines = container.querySelectorAll('.lyrics-line');
  lines.forEach((line, idx) => {
    line.classList.remove('active');
    if (idx === activeIndex) {
      line.classList.add('active');
      // Scroll active line into view
      line.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  });

  requestAnimationFrame(updateLyricsSync);
}

// ====================================================
// CLEAN FULL-WIDTH QUEUE VIEW
// ====================================================
function renderSheetQueueList() {
  const qView = document.getElementById('sheetViewQueue');
  if (!qView) return;
  qView.innerHTML = '';

  const remaining = playlist.slice(currentIndex + 1);
  if (remaining.length === 0) {
    qView.innerHTML = '<p style="color:var(--text-dim);font-size:0.88rem;padding:24px 8px;">End of playback queue.</p>';
    checkAndExpandInfiniteQueue();
    return;
  }

  qView.innerHTML = `<div class="queue-section-header"><span>Coming Up Next</span></div>`;

  remaining.forEach((t, i) => {
    const globalIdx = currentIndex + 1 + i;
    const item = document.createElement('div');
    item.className = 'queue-row';
    const isFav = !!favorites[t.id];

    item.innerHTML = `
      <div class="queue-left-block">
        <img class="queue-thumb" src="${t.thumbnail || ''}" loading="lazy" />
        <div class="queue-info">
          <div class="queue-title">${t.title}</div>
          <div class="queue-artist">${t.artist}</div>
        </div>
      </div>
      <div class="queue-actions-cluster">
        <button class="queue-action-btn" title="Save to Favorites" onclick="event.stopPropagation(); toggleFavTrackDirect('${t.id}', this)">
          ${isFav ? '♥' : '♡'}
        </button>
        <button class="queue-action-btn" title="Add to Playlist" onclick="event.stopPropagation(); window.actionOpenAddToPlaylist(playlist[${globalIdx}])">
          +
        </button>
      </div>
    `;
    item.onclick = () => window.playIndex(globalIdx);
    qView.appendChild(item);
  });
}

window.toggleFavTrackDirect = function (trackId, btn) {
  const track = playlist.find(t => t.id === trackId);
  if (!track) return;
  if (favorites[trackId]) {
    delete favorites[trackId];
    btn.innerText = '♡';
    btn.style.color = 'var(--text-muted)';
  } else {
    favorites[trackId] = track;
    btn.innerText = '♥';
    btn.style.color = 'var(--accent)';
  }
  store.saveFavorites(favorites);
};

// ==========================================
// HOME, SEARCH & CLEAN MUSIC HUB
// ==========================================
function renderHomeView() {
  const viewContainer = document.getElementById('viewContainer');
  if (!viewContainer) return;
  viewContainer.innerHTML = `
    <div class="stage-content">
      <div class="home-brand-header">
        <div class="brand-capsule" style="background: transparent; border: none; padding: 0; display: flex; align-items: center;">
          <img src="/static/images/melo-text.png" alt="MELO" style="height: 34px; width: auto; object-fit: contain;" />
        </div>
        <button class="sheet-icon-btn" onclick="openSettingsModal()" title="Settings">
          <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z"/></svg>
        </button>
      </div>

      <div class="for-you-spotlight">
        <div class="for-you-header">
          <div class="for-you-title">Made For You</div>
          <button class="pill-action-btn" onclick="playForYouAll()" style="padding: 8px 18px; font-size: 0.85rem;">
            <svg viewBox="0 0 24 24" style="width: 14px; height: 14px; fill:#000;"><path d="M8 5v14l11-7z"/></svg>
            <span>Play Mix</span>
          </button>
        </div>

        <div class="vibe-chips-row">
          <button class="vibe-chip active" onclick="switchVibePreset('Flow', this)">🌊 Flow</button>
          <button class="vibe-chip" onclick="switchVibePreset('Acoustic Chill', this)">☕ Acoustic</button>
          <button class="vibe-chip" onclick="switchVibePreset('Workout High Energy', this)">⚡ Energy</button>
          <button class="vibe-chip" onclick="switchVibePreset('Late Night Soul', this)">🌙 Velvet</button>
        </div>

        <div class="capsule-grid" id="forYouGrid" style="margin-bottom:0;"></div>
      </div>

      <div class="section-heading" id="playlistsShelf">
        <h2>Your Playlists</h2>
        <a onclick="actionOpenAddToPlaylist(null)">+ Create</a>
      </div>
      <div class="capsule-grid" id="homePlaylistsGrid"></div>

      <div class="section-heading" id="trendingShelf">
        <h2>Trending Across India</h2>
        <a onclick="loadShelfCategory('Top Hindi Songs 2026', 'trendingGrid')">Refresh</a>
      </div>
      <div class="capsule-grid" id="trendingGrid"></div>

      <div class="section-heading" id="bollywoodShelf"><h2>Bollywood Chartbusters</h2></div>
      <div class="capsule-grid" id="bollywoodGrid"></div>

      <div class="section-heading" id="punjabiShelf"><h2>Punjabi Banger Wave</h2></div>
      <div class="capsule-grid" id="punjabiGrid"></div>

      <div class="section-heading" id="indieShelf"><h2>Desi Indie & Acoustic Chill</h2></div>
      <div class="capsule-grid" id="indieGrid"></div>
    </div>
  `;

  loadForYouCatalog('Acoustic Bollywood Indie Hits');
  renderHomePagePlaylists();
  window.loadShelfCategory('Top Hindi Songs 2026', 'trendingGrid');
  window.loadShelfCategory('Bollywood Romantic Hits', 'bollywoodGrid');
  window.loadShelfCategory('Punjabi Hits 2026', 'punjabiGrid');
  window.loadShelfCategory('Indian Indie Songs', 'indieGrid');
}

function renderHomePagePlaylists() {
  const plGrid = document.getElementById('homePlaylistsGrid');
  if (!plGrid) return;

  const userPlaylists = Object.values(playlists).filter(p => p.id !== 'pl-favorites' && p.id !== 'pl-downloads');
  
  plGrid.innerHTML = '';
  if (userPlaylists.length === 0) {
    plGrid.innerHTML = `<p style="color:var(--text-dim);font-size:0.85rem;grid-column:1/-1;">No playlists yet. Tap "+ Create" to make your first one!</p>`;
  } else {
    userPlaylists.forEach(pl => {
      const item = document.createElement('div');
      item.className = 'poster-item';
      item.onclick = () => openPlaylistDetails(pl.id);
      item.innerHTML = `
        <div class="poster-wrap">${renderPlaylistCoverHTML(pl)}</div>
        <div class="poster-title">${pl.name}</div>
        <div class="poster-subtitle">${pl.tracks.length} tracks</div>
      `;
      plGrid.appendChild(item);
    });
  }
}

async function loadForYouCatalog(query = 'Acoustic Bollywood Indie Hits') {
  try {
    const res = await fetch(`/api/search?query=${encodeURIComponent(query)}`);
    const data = await res.json();
    const tracks = data.results || [];
    forYouTracks = tracks.slice(0, 6);
    categoryData['forYouGrid'] = forYouTracks;
    renderGridContainer('forYouGrid', forYouTracks);
  } catch (err) {}
}

window.switchVibePreset = function (vibe, btn) {
  document.querySelectorAll('.vibe-chip').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  currentVibe = vibe;
  loadForYouCatalog(vibe === 'Flow' ? 'Acoustic Bollywood Indie Hits' : vibe);
};

window.playForYouAll = function () {
  if (forYouTracks.length > 0) {
    currentPlaylistContextId = null;
    playlist = [...forYouTracks];
    window.playIndex(0);
  }
};

window.loadShelfCategory = async function (query, containerId) {
  try {
    const res = await fetch(`/api/search?query=${encodeURIComponent(query)}`);
    const data = await res.json();
    const items = data.results || [];
    categoryData[containerId] = items;
    renderGridContainer(containerId, items.slice(0, 6));

    if (containerId === 'trendingGrid' && playlist.length === 0) {
      currentPlaylistContextId = null;
      playlist = [...items];
    }
  } catch (err) {}
};

function renderGridContainer(containerId, items) {
  const c = document.getElementById(containerId);
  if (!c) return;
  c.innerHTML = '';

  items.forEach((track, i) => {
    const item = document.createElement('div');
    item.className = 'poster-item';
    item.onclick = () => {
      currentPlaylistContextId = null;
      if (containerId === 'searchGrid') {
        playlist = [track];
        window.playIndex(0);
      } else {
        playlist = categoryData[containerId] || items;
        window.playIndex(i);
      }
    };
    item.innerHTML = `
      <div class="poster-wrap">
        <img class="poster-img" src="${track.thumbnail || ''}" loading="lazy" />
        <div class="play-bubble"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></div>
      </div>
      <div class="poster-title">${track.title}</div>
      <div class="poster-subtitle">${track.artist}</div>
    `;
    c.appendChild(item);
  });
}

// ----------------------------------------------------
// MOBILE SEARCH VIEW
// ----------------------------------------------------
function renderSearchView() {
  const viewContainer = document.getElementById('viewContainer');
  if (!viewContainer) return;
  viewContainer.innerHTML = `
    <div class="stage-content" id="searchStageContent">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" aria-label="Go Back" title="Back">
          <svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <h1 style="font-size: 1.45rem; font-weight: 800; letter-spacing: -0.02em;">Search</h1>
      </div>

      <div class="search-hero-zone">
        <div class="search-glass-capsule">
          <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>
          <input type="text" id="dedicatedSearchInput" class="search-glass-input" placeholder="Songs, Bollywood artists, lyrics, albums..." autofocus autocomplete="off" />
          <button class="search-clear-btn" id="searchClearBtn" onclick="clearSearchInput()">✕</button>
        </div>
      </div>

      <div id="recentSearchesPanel"></div>

      <div class="section-heading" id="searchResultsHeading">
        <h2 id="searchResultsTitle">Trending Recommendations</h2>
      </div>
      <div class="capsule-grid" id="searchGrid" style="margin-bottom:24px;"></div>

      <div class="section-heading"><h2>Explore by Mood & Genre</h2></div>
      <div class="search-mood-cards">
        <div class="mood-card" onclick="quickSearch('Bollywood Romantic Melodies')" style="background-color: #e11d48;">
          <span>Romance</span><span class="mood-icon">💖</span>
        </div>
        <div class="mood-card" onclick="quickSearch('Diljit Dosanjh Punjabi Hits')" style="background-color: #ea580c;">
          <span>Punjabi Wave</span><span class="mood-icon">🔥</span>
        </div>
        <div class="mood-card" onclick="quickSearch('Desi Hip Hop India 2026')" style="background-color: #7c3aed;">
          <span>Desi Rap</span><span class="mood-icon">⚡</span>
        </div>
        <div class="mood-card" onclick="quickSearch('Indian Indie Acoustic Chill')" style="background-color: #2563eb;">
          <span>Indie Chill</span><span class="mood-icon">🌙</span>
        </div>
      </div>

      <div class="section-heading"><h2>Bollywood Chartbusters</h2></div>
      <div class="capsule-grid" id="searchBollywoodGrid"></div>

      <div class="section-heading"><h2>Punjabi Banger Wave</h2></div>
      <div class="capsule-grid" id="searchPunjabiGrid"></div>
    </div>
  `;

  const input = document.getElementById('dedicatedSearchInput');
  const clearBtn = document.getElementById('searchClearBtn');
  const stage = document.getElementById('searchStageContent');
  let searchTimer = null;
  window.renderRecentSearches();

  if (input) {
    input.addEventListener('focus', () => {
      if (input.value.trim()) stage?.classList.add('is-searching');
    });

    input.addEventListener('input', () => {
      clearTimeout(searchTimer);
      const q = input.value.trim();
      if (clearBtn) clearBtn.style.display = q ? 'flex' : 'none';

      if (!q) {
        stage?.classList.remove('is-searching');
        window.clearSearchInput();
        return;
      }

      stage?.classList.add('is-searching');
      searchTimer = setTimeout(() => {
        store.rememberSearch(q);
        window.renderRecentSearches();
        const title = document.getElementById('searchResultsTitle');
        if (title) title.innerText = `Results for "${q}"`;
        loadSearchShelf(q, 'searchGrid', 24);
      }, 220);
    });
  }

  loadSearchShelf('Bollywood Trending 2026', 'searchGrid', 12);
  loadSearchShelf('Bollywood Romantic Hits', 'searchBollywoodGrid', 6);
  loadSearchShelf('Punjabi Hits 2026', 'searchPunjabiGrid', 6);
}

window.clearSearchInput = function () {
  const input = document.getElementById('dedicatedSearchInput');
  const clearBtn = document.getElementById('searchClearBtn');
  const stage = document.getElementById('searchStageContent');
  if (input) { input.value = ''; input.focus(); }
  if (clearBtn) clearBtn.style.display = 'none';
  stage?.classList.remove('is-searching');
  const title = document.getElementById('searchResultsTitle');
  if (title) title.innerText = "Trending Recommendations";
  loadSearchShelf('Bollywood Trending 2026', 'searchGrid', 12);
};

window.quickSearch = function (query) {
  store.rememberSearch(query);
  const input = document.getElementById('dedicatedSearchInput');
  const clearBtn = document.getElementById('searchClearBtn');
  const stage = document.getElementById('searchStageContent');
  if (input) {
    input.value = query;
    if (clearBtn) clearBtn.style.display = 'flex';
  }
  stage?.classList.add('is-searching');
  const title = document.getElementById('searchResultsTitle');
  if (title) title.innerText = `Results for "${query}"`;
  loadSearchShelf(query, 'searchGrid', 24);
  const grid = document.getElementById('searchGrid');
  if (grid) grid.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
};

window.renderRecentSearches = function () {
  const panel = document.getElementById('recentSearchesPanel');
  if (!panel) return;
  const searches = store.getSearchHistory();
  panel.innerHTML = searches.length ? `<div class="section-heading recent-search-heading"><h2>Recent Searches</h2><a onclick="store.clearSearches(); renderRecentSearches()">Clear all</a></div><div class="recent-search-list">${searches.map((item) => `<button class="recent-search-chip" onclick="quickSearch(${JSON.stringify(item.query)})">${accountText(item.query)}<span onclick="event.stopPropagation(); store.removeSearch(${JSON.stringify(item.query)}); renderRecentSearches()">×</span></button>`).join('')}</div>` : '';
};

async function loadSearchShelf(query, containerId = 'searchGrid', limit = 6) {
  try {
    const res = await fetch(`/api/search?query=${encodeURIComponent(query)}`);
    const data = await res.json();
    const items = data.results || [];
    categoryData[containerId] = items;
    renderGridContainer(containerId, items.slice(0, limit));
  } catch (err) {}
}

// ----------------------------------------------------
// SOPHISTICATED EDITORIAL MUSIC HUB
// ----------------------------------------------------
function renderFavoritesView() {
  const viewContainer = document.getElementById('viewContainer');
  if (!viewContainer) return;

  const downloadedCount = playlists['pl-downloads'] ? playlists['pl-downloads'].tracks.length : 0;
  const userPlaylists = Object.values(playlists).filter(p => p.id !== 'pl-favorites' && p.id !== 'pl-downloads');
  
  if (playlists['pl-favorites']) {
    playlists['pl-favorites'].tracks = Object.values(favorites);
  }

  viewContainer.innerHTML = `
    <div class="stage-content">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" aria-label="Go Back" title="Back">
          <svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <h1 style="font-size: 1.45rem; font-weight: 800; letter-spacing: -0.02em;">Music Hub</h1>
      </div>

      <div class="hub-vault-grid">
        <div class="hub-vault-card" onclick="openPlaylistDetails('pl-favorites')">
          <div class="hub-vault-badge" style="background: linear-gradient(135deg, #fa2d48, #e11d48);">
            <svg viewBox="0 0 24 24"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>
          </div>
          <div>
            <div class="hub-vault-title">Loved Tracks</div>
            <div class="hub-vault-count">${Object.values(favorites).length} saved songs</div>
          </div>
        </div>

        <div class="hub-vault-card" onclick="openPlaylistDetails('pl-downloads')">
          <div class="hub-vault-badge" style="background: linear-gradient(135deg, #2563eb, #7c3aed);">
            <svg viewBox="0 0 24 24"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>
          </div>
          <div>
            <div class="hub-vault-title">Downloaded Songs</div>
            <div class="hub-vault-count">${downloadedCount} offline tracks</div>
          </div>
        </div>
      </div>

      <div class="section-heading">
        <h2>Your Playlists (${userPlaylists.length})</h2>
        <a onclick="actionOpenAddToPlaylist(null)">+ Create</a>
      </div>
      <div class="capsule-grid" id="customPlaylistsGrid"></div>

      <div class="section-heading"><h2>Recent Favorites</h2></div>
      <div id="favsTracklist"></div>
    </div>
  `;

  const plGrid = document.getElementById('customPlaylistsGrid');
  if (plGrid) {
    plGrid.innerHTML = '';
    if (userPlaylists.length === 0) {
      plGrid.innerHTML = `<p style="color:var(--text-dim);font-size:0.85rem;grid-column:1/-1;">No custom playlists created yet. Tap "+ Create" above.</p>`;
    } else {
      userPlaylists.forEach(pl => {
        const item = document.createElement('div');
        item.className = 'poster-item';
        item.onclick = () => openPlaylistDetails(pl.id);
        item.innerHTML = `
          <div class="poster-wrap">${renderPlaylistCoverHTML(pl)}</div>
          <div class="poster-title">${pl.name}</div>
          <div class="poster-subtitle">${pl.tracks.length} tracks</div>
        `;
        plGrid.appendChild(item);
      });
    }
  }

  const fList = document.getElementById('favsTracklist');
  if (fList) {
    fList.innerHTML = '';
    const recentFavs = Object.values(favorites).reverse().slice(0, 10);
    if (recentFavs.length === 0) {
      fList.innerHTML = `<p style="color:var(--text-dim);font-size:0.88rem;padding:16px;">No loved tracks yet. Tap the heart on any song to save it here.</p>`;
    } else {
      recentFavs.forEach((track, i) => {
        const row = document.createElement('div');
        row.className = `track-row`;
        row.onclick = () => {
          currentPlaylistContextId = 'pl-favorites';
          playlist = Object.values(favorites);
          window.playIndex(i);
        };
        row.innerHTML = `
          <div class="tr-num">${i + 1}</div>
          <img class="tr-thumb" src="${track.thumbnail || ''}" loading="lazy" />
          <div class="tr-info">
            <div class="tr-title">${track.title}</div>
            <div class="tr-artist">${track.artist}</div>
          </div>
          <div class="tr-time">${track.duration}</div>
          <button class="tr-fav active" onclick="removeFavoriteItem(event, '${track.id}')">♥</button>
        `;
        fList.appendChild(row);
      });
    }
  }
}

// ----------------------------------------------------
// MASSIVE IMMERSIVE PLAYLIST WITH COLOR FADE TO SONG LIST
// ----------------------------------------------------
function openPlaylistDetails(plId) {
  activeView = 'playlist-detail';
  if (plId === 'pl-favorites') {
    playlists['pl-favorites'].tracks = Object.values(favorites);
  }

  const pl = playlists[plId];
  const viewContainer = document.getElementById('viewContainer');
  if (!viewContainer || !pl) return;

  const leadImage = getPlaylistHeroCoverURL(pl);
  applyPlaylistDynamicColors(leadImage, pl.name);

  const bgStyle = leadImage ? `style="background-image: url('${leadImage}');"` : '';

  viewContainer.innerHTML = `
    <div class="playlist-immersive-view">
      <div class="playlist-immersive-hero" ${bgStyle}>
        <div style="position:absolute; top:28px; left:28px; right:28px; display:flex; justify-content:space-between; align-items:center; z-index:10;">
          <button class="circle-back-btn" onclick="goBack()" aria-label="Go Back" title="Back">
            <svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
          </button>
          
          <button class="circle-back-btn" onclick="openPlaylistActionMenu('${plId}')" title="Playlist Menu">
            <svg viewBox="0 0 24 24" style="stroke:none; fill:#fff;">
              <circle cx="12" cy="5" r="2.2"/>
              <circle cx="12" cy="12" r="2.2"/>
              <circle cx="12" cy="19" r="2.2"/>
            </svg>
          </button>
        </div>

        <div class="playlist-backdrop-content">
          <span class="pl-meta-tag">${plId === 'pl-downloads' ? 'Offline Vault' : (plId === 'pl-favorites' ? 'Curated Collection' : 'Playlist')}</span>
          <div class="playlist-immersive-title-row">
            <h1 class="playlist-immersive-title">${pl.name}</h1>
          </div>
          <div class="playlist-immersive-stats">${pl.tracks.length} tracks${pl.description ? ` · ${accountText(pl.description)}` : ''}</div>

          <div class="playlist-immersive-actions">
            ${pl.tracks.length > 0 ? `
              <button class="pill-action-btn" onclick="playPlaylistContext('${plId}')">
                <svg viewBox="0 0 24 24" style="width:16px;height:16px;fill:#000;"><path d="M8 5v14l11-7z"/></svg>
                <span>Play All</span>
              </button>
              <button class="filter-chip" onclick="playPlaylistContext('${plId}', true)">Shuffle</button>
            ` : ''}
            ${isRemoveSongsMode ? `
              <button class="filter-chip" style="background:#fa2d48; color:#fff; border-color:#fa2d48;" onclick="actionToggleRemoveSongsMode()">
                Done Removing
              </button>
            ` : ''}
          </div>
        </div>
      </div>

      <div class="playlist-tracks-section">
        <div id="playlistTracksBox"></div>
      </div>
    </div>
  `;

  const box = document.getElementById('playlistTracksBox');
  if (!box) return;
  if (pl.tracks.length === 0) {
    box.innerHTML = `<p style="color:var(--text-dim);font-size:0.9rem;padding:24px 0;">This playlist is currently empty. Tap the menu (⋮) above to search and add songs.</p>`;
  } else {
    pl.tracks.forEach((track, i) => {
      const row = document.createElement('div');
      row.className = 'track-row playlist-track-row';
      row.draggable = plId !== 'pl-favorites' && plId !== 'pl-downloads';
      row.addEventListener('dragstart', (event) => event.dataTransfer.setData('text/plain', String(i)));
      row.addEventListener('dragover', (event) => event.preventDefault());
      row.addEventListener('drop', (event) => { event.preventDefault(); reorderPlaylistTrack(plId, Number(event.dataTransfer.getData('text/plain')), i); });
      row.onclick = () => {
        if (isRemoveSongsMode) return;
        currentPlaylistContextId = plId;
        playlist = [...pl.tracks];
        window.playIndex(i);
      };
      
      const downloadedTick = downloadedTrackIds.has(String(track.id)) 
        ? `<svg viewBox="0 0 24 24" style="width:14px; height:14px; stroke:#10b981; fill:none; stroke-width:2.5; margin-left:6px;"><path d="M20 6L9 17l-5-5"/></svg>`
        : '';

      row.innerHTML = `
        <div class="tr-num"><span class="playlist-drag-handle">&#8801;</span>${i + 1}</div>
        <img class="tr-thumb" src="${track.thumbnail || ''}" loading="lazy" />
        <div class="tr-info">
          <div class="tr-title" style="display:flex;align-items:center;">
            ${track.title} ${downloadedTick}
          </div>
          <div class="tr-artist">${track.artist}</div>
        </div>
        <div class="tr-album">${track.album || 'Single'}</div>
        <div class="tr-time">${track.duration}</div>
        ${isRemoveSongsMode ? `
          <button class="tr-remove-btn" title="Remove Song" onclick="event.stopPropagation(); removeTrackFromPlaylistDirect('${plId}', ${i})">
            ✕
          </button>
        ` : `
          <button class="tr-fav ${favorites[track.id] ? 'active' : ''}" onclick="event.stopPropagation(); toggleFavTrackDirect('${track.id}', this)">♥</button>
          <button class="library-more-btn" onclick="event.stopPropagation(); openContextMenu(${JSON.stringify(track).replace(/"/g, '&quot;')})">⋮</button>
        `}
      `;
      box.appendChild(row);
    });
  }
}

// ==========================================
// PHASE 2 LIBRARY VIEWS
// ==========================================
function libraryTrackRow(track, index, options = {}) {
  const loved = Boolean(favorites[track.id]);
  const menuAction = options.removeHistory
    ? `removeHistoryItem('${options.removeHistory}')`
    : `openContextMenu(${JSON.stringify(track).replace(/"/g, '&quot;')})`;
  return `<div class="track-row library-track-row" onclick="playLibraryTrack(${index}, '${options.source || 'loved'}')">
    <div class="tr-num">${index + 1}</div><img class="tr-thumb" src="${track.thumbnail || ''}" loading="lazy" />
    <div class="tr-info"><div class="tr-title">${accountText(track.title || 'Unknown track')}</div><div class="tr-artist">${accountText(track.artist || 'Unknown artist')}</div></div>
    <div class="tr-time">${options.meta || track.duration || ''}</div>
    <button class="tr-fav ${loved ? 'active' : ''}" onclick="event.stopPropagation(); toggleLibraryFavorite('${track.id}', '${options.source || 'loved'}')">${loved ? '♥' : '♡'}</button>
    <button class="library-more-btn" onclick="event.stopPropagation(); ${menuAction}" aria-label="Track options">⋮</button>
  </div>`;
}

window.playLibraryTrack = function (index, source) {
  const tracks = source === 'history'
    ? (window.__historyTracks || store.getHistory().filter((entry) => entry.track).map((entry) => entry.track))
    : (window.__lovedTracks || Object.values(favorites));
  playlist = tracks;
  currentPlaylistContextId = source === 'loved' ? 'pl-favorites' : null;
  window.playIndex(index);
};

window.toggleLibraryFavorite = function (trackId, source) {
  const track = source === 'history'
    ? store.getHistory().find((entry) => entry.track?.id === trackId)?.track
    : favorites[trackId];
  if (track) store.setFavorite(track, !favorites[trackId]);
  if (activeView === 'loved') window.renderLovedTracks();
  if (activeView === 'history') window.renderHistoryView();
};

window.removeFavoriteItem = function (event, trackId) {
  event.stopPropagation();
  const track = favorites[trackId];
  if (track) store.setFavorite(track, false);
  if (activeView === 'loved' || activeView === 'favorites') window.renderLovedTracks();
};

window.playPlaylistContext = function (playlistId, shuffle = false) {
  const target = playlistId === 'pl-favorites' ? { tracks: Object.values(favorites) } : playlists[playlistId];
  if (!target?.tracks?.length) return;
  playlist = [...target.tracks];
  if (shuffle) playlist.sort(() => Math.random() - 0.5);
  currentPlaylistContextId = playlistId;
  window.playIndex(0);
};

window.renderFavoritesView = function () {
  const viewContainer = document.getElementById('viewContainer');
  if (!viewContainer) return;
  const lovedCount = Object.keys(favorites).length;
  const historyCount = store.getHistory().length;
  const downloadedCount = playlists['pl-downloads']?.tracks?.length || 0;
  const personalPlaylists = Object.values(playlists).filter((pl) => !['pl-favorites', 'pl-downloads'].includes(pl.id));
  viewContainer.innerHTML = `<div class="stage-content library-stage"><div class="top-action-bar"><button class="circle-back-btn" onclick="goBack()"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button><h1>Music Hub</h1></div>
    <div class="section-heading"><h2>Your Library</h2></div>
    <div class="hub-vault-grid library-hub-grid">
      <button class="hub-vault-card" onclick="renderLovedTracks()"><div class="hub-vault-badge library-badge-loved">♡</div><div><div class="hub-vault-title">Loved Tracks</div><div class="hub-vault-count">${lovedCount} songs</div></div></button>
      <button class="hub-vault-card" onclick="renderHistoryView()"><div class="hub-vault-badge library-badge-history">◷</div><div><div class="hub-vault-title">Recently Played</div><div class="hub-vault-count">${historyCount} listens</div></div></button>
      <button class="hub-vault-card" onclick="renderOfflineVault()"><div class="hub-vault-badge library-badge-download">↓</div><div><div class="hub-vault-title">Downloaded</div><div class="hub-vault-count">${downloadedCount} available offline</div></div></button>
      <button class="hub-vault-card" onclick="renderPlaylistsView()"><div class="hub-vault-badge library-badge-playlist">♫</div><div><div class="hub-vault-title">Playlists</div><div class="hub-vault-count">${personalPlaylists.length} collections</div></div></button>
    </div>
    <div class="section-heading"><h2>Recently Loved</h2><a onclick="renderLovedTracks()">View all</a></div>
    <div id="libraryRecentLoved"></div></div>`;
  const recent = Object.values(favorites).slice(-5).reverse();
  document.getElementById('libraryRecentLoved').innerHTML = recent.length
    ? recent.map((track, index) => libraryTrackRow(track, index, { source: 'loved' })).join('')
    : `<div class="library-empty"><strong>Your favorites belong here.</strong><span>Tap the heart on any song you love.</span><button class="pill-action-btn" onclick="switchView('search')">Explore Music</button></div>`;
};

window.renderLovedTracks = function () {
  activeView = 'loved';
  const viewContainer = document.getElementById('viewContainer');
  const tracks = Object.values(favorites);
  viewContainer.innerHTML = `<div class="stage-content library-stage"><div class="top-action-bar"><button class="circle-back-btn" onclick="switchView('favorites', false)"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button><h1>Loved Tracks</h1></div>
  ${tracks.length ? `<div class="library-toolbar"><input id="lovedSearchInput" class="themed-pl-input" placeholder="Search loved tracks"/><select id="lovedSort" class="capsule-select"><option value="recent">Recently added</option><option value="title">Alphabetically</option><option value="artist">Artist</option></select><button class="pill-action-btn" onclick="playLovedTracks(false)">Play all</button><button class="filter-chip" onclick="playLovedTracks(true)">Shuffle</button></div><div id="lovedTrackList"></div>` : `<div class="library-empty"><strong>No loved tracks yet</strong><span>Songs you fall for will live here.</span><button class="pill-action-btn" onclick="switchView('search')">Explore Music</button></div>`}</div>`;
  if (!tracks.length) return;
  const draw = () => {
    const query = document.getElementById('lovedSearchInput').value.trim().toLowerCase();
    const sort = document.getElementById('lovedSort').value;
    const filtered = tracks.filter((track) => `${track.title} ${track.artist}`.toLowerCase().includes(query));
    filtered.sort((a, b) => sort === 'artist' ? String(a.artist).localeCompare(String(b.artist)) : sort === 'title' ? String(a.title).localeCompare(String(b.title)) : String(b.added_at || '').localeCompare(String(a.added_at || '')));
    window.__lovedTracks = filtered;
    document.getElementById('lovedTrackList').innerHTML = filtered.length ? filtered.map((track, index) => libraryTrackRow(track, index, { source: 'loved' })).join('') : '<div class="library-empty compact">No loved tracks match that search.</div>';
  };
  document.getElementById('lovedSearchInput').addEventListener('input', draw);
  document.getElementById('lovedSort').addEventListener('change', draw);
  draw();
};

window.playLovedTracks = function (shuffle) {
  const tracks = window.__lovedTracks || Object.values(favorites);
  if (!tracks.length) return;
  playlist = [...tracks];
  if (shuffle) playlist.sort(() => Math.random() - 0.5);
  currentPlaylistContextId = 'pl-favorites';
  window.playIndex(0);
};

function historyGroup(dateValue) {
  const date = new Date(dateValue);
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1);
  if (date >= today) return 'Today';
  if (date >= yesterday) return 'Yesterday';
  const week = new Date(today); week.setDate(today.getDate() - 7);
  return date >= week ? 'Earlier this week' : 'Earlier';
}

window.renderHistoryView = function () {
  activeView = 'history';
  const entries = store.getHistory().filter((entry) => entry.track).slice().reverse();
  const viewContainer = document.getElementById('viewContainer');
  viewContainer.innerHTML = `<div class="stage-content library-stage"><div class="top-action-bar"><button class="circle-back-btn" onclick="switchView('favorites', false)"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button><h1>Recently Played</h1></div><div id="historyTrackList"></div></div>`;
  if (!entries.length) { document.getElementById('historyTrackList').innerHTML = `<div class="library-empty"><strong>Nothing played yet.</strong><span>Your listening journey starts here.</span><button class="pill-action-btn" onclick="switchView('search')">Explore Music</button></div>`; return; }
  const groups = {};
  entries.forEach((entry) => { const group = historyGroup(entry.played_at); (groups[group] ||= []).push(entry); });
  window.__historyTracks = entries.map((entry) => entry.track);
  let globalIndex = 0;
  document.getElementById('historyTrackList').innerHTML = Object.entries(groups).map(([label, group]) => `<div class="section-heading history-heading"><h2>${label}</h2></div>${group.map((entry) => libraryTrackRow(entry.track, globalIndex++, { source: 'history', removeHistory: entry.id, meta: new Date(entry.played_at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) })).join('')}`).join('');
};

window.removeHistoryItem = function (entryId) { store.removeHistory(entryId); window.renderHistoryView(); };

window.renderPlaylistsView = function () {
  activeView = 'playlists';
  const items = Object.values(playlists).filter((pl) => !['pl-favorites', 'pl-downloads'].includes(pl.id));
  const viewContainer = document.getElementById('viewContainer');
  viewContainer.innerHTML = `<div class="stage-content library-stage"><div class="top-action-bar"><button class="circle-back-btn" onclick="switchView('favorites', false)"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button><h1>Playlists</h1><button class="pill-action-btn" onclick="actionOpenAddToPlaylist(null)">+ Create</button></div><div id="playlistLibraryGrid" class="capsule-grid"></div></div>`;
  const grid = document.getElementById('playlistLibraryGrid');
  grid.innerHTML = items.length ? items.map((pl) => `<button class="poster-item playlist-library-card" onclick="openPlaylistDetails('${pl.id}')"><div class="poster-wrap">${renderPlaylistCoverHTML(pl)}</div><div class="poster-title">${accountText(pl.name)}</div><div class="poster-subtitle">${pl.tracks?.length || 0} tracks${pl.description ? ` · ${accountText(pl.description)}` : ''}</div></button>`).join('') : `<div class="library-empty"><strong>Create your first playlist.</strong><span>Collect the songs that belong together.</span><button class="pill-action-btn" onclick="actionOpenAddToPlaylist(null)">Create playlist</button></div>`;
};

async function getOfflineRecords() {
  try {
    const db = await openMeloDB();
    return await new Promise((resolve) => {
      const request = db.transaction(IDB_STORE, 'readonly').objectStore(IDB_STORE).getAll();
      request.onsuccess = () => resolve(request.result || []);
      request.onerror = () => resolve([]);
    });
  } catch (e) { return []; }
}

function formatStorage(bytes) {
  if (!bytes) return '0 MB';
  const units = ['B', 'KB', 'MB', 'GB']; let value = bytes; let index = 0;
  while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
  return `${value.toFixed(index > 1 ? 1 : 0)} ${units[index]}`;
}

window.renderOfflineVault = async function () {
  activeView = 'offline';
  const viewContainer = document.getElementById('viewContainer');
  viewContainer.innerHTML = `<div class="stage-content library-stage"><div class="top-action-bar"><button class="circle-back-btn" onclick="switchView('favorites', false)"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button><h1>Offline Vault</h1></div><div id="offlineVaultContent" class="library-loading">Loading your downloads…</div></div>`;
  const records = await getOfflineRecords();
  const usage = records.reduce((sum, record) => sum + (record.blob?.size || 0), 0);
  let available = 'Unavailable';
  try { const estimate = await navigator.storage?.estimate?.(); if (estimate?.quota) available = formatStorage(Math.max(0, estimate.quota - (estimate.usage || 0))); } catch (e) {}
  const content = document.getElementById('offlineVaultContent');
  if (!content || activeView !== 'offline') return;
  const rows = records.map((record, index) => `<div class="track-row library-track-row" onclick="playlist=[${JSON.stringify(record.metadata).replace(/"/g, '&quot;')}];currentPlaylistContextId='pl-downloads';playIndex(0)"><div class="tr-num">${index + 1}</div><img class="tr-thumb" src="${record.metadata.thumbnail || ''}" loading="lazy"/><div class="tr-info"><div class="tr-title">${accountText(record.metadata.title)}</div><div class="tr-artist">${accountText(record.metadata.artist)}</div></div><div class="tr-time">Available offline</div><button class="library-more-btn" onclick="event.stopPropagation(); removeOfflineDownload('${record.id}')" aria-label="Remove download">×</button></div>`).join('');
  content.innerHTML = `<section class="offline-storage-card"><span>MELO Offline Storage</span><strong>Used: ${formatStorage(usage)}</strong><small>Available: ${available} · Downloads: ${records.length} tracks</small>${records.length ? '<button class="filter-chip offline-clear-btn" onclick="clearOfflineDownloads()">Clear Downloads</button>' : ''}</section>${records.length ? `<div class="section-heading"><h2>Available Offline</h2></div>${rows}` : `<div class="library-empty"><strong>Nothing saved offline.</strong><span>Download music for listening without internet.</span><button class="pill-action-btn" onclick="switchView('search')">Explore Music</button></div>`}`;
};

window.removeOfflineDownload = async function (trackId) {
  const db = await openMeloDB();
  await new Promise((resolve, reject) => { const request = db.transaction(IDB_STORE, 'readwrite').objectStore(IDB_STORE).delete(String(trackId)); request.onsuccess = resolve; request.onerror = reject; });
  await syncDownloadedPlaylist();
  window.renderOfflineVault();
};

window.clearOfflineDownloads = async function () {
  const db = await openMeloDB();
  await new Promise((resolve, reject) => { const request = db.transaction(IDB_STORE, 'readwrite').objectStore(IDB_STORE).clear(); request.onsuccess = resolve; request.onerror = reject; });
  await syncDownloadedPlaylist();
  window.renderOfflineVault();
  showToast('Offline downloads cleared');
};

// Phase 2 queue: the playback array is durable and can be edited without
// resetting the current song or the navigation view.
window.removeFromQueue = function (index) {
  if (index <= currentIndex || index >= playlist.length) return;
  playlist.splice(index, 1);
  persistPlaybackQueue();
  renderSheetQueueList();
};

window.moveQueueTrack = function (from, to) {
  if (from <= currentIndex || to <= currentIndex || from === to) return;
  const [track] = playlist.splice(from, 1);
  playlist.splice(to, 0, track);
  persistPlaybackQueue();
  renderSheetQueueList();
};

window.clearQueue = function () {
  playlist = playlist.slice(0, Math.max(0, currentIndex + 1));
  persistPlaybackQueue();
  renderSheetQueueList();
  showToast('Up next cleared');
};

window.reorderPlaylistTrack = function (playlistId, from, to) {
  const target = playlists[playlistId];
  if (!target || from === to || from < 0 || to < 0) return;
  const [track] = target.tracks.splice(from, 1);
  target.tracks.splice(to, 0, track);
  target.updated_at = new Date().toISOString();
  store.savePlaylists(playlists);
  if (activeView === 'playlist-detail') openPlaylistDetails(playlistId);
};

renderSheetQueueList = function () {
  const qView = document.getElementById('sheetViewQueue');
  if (!qView) return;
  qView.innerHTML = '';
  const now = currentIndex >= 0 ? playlist[currentIndex] : null;
  if (now) {
    qView.insertAdjacentHTML('beforeend', `<div class="queue-section-header"><span>Now Playing</span></div>`);
    const current = document.createElement('div');
    current.className = 'queue-row queue-now-playing';
    current.innerHTML = `<div class="queue-left-block"><img class="queue-thumb" src="${now.thumbnail || ''}" loading="lazy"/><div class="queue-info"><div class="queue-title">${now.title}</div><div class="queue-artist">${now.artist}</div></div></div><span class="queue-now-label">NOW</span>`;
    qView.appendChild(current);
  }
  const remaining = playlist.slice(Math.max(0, currentIndex + 1));
  qView.insertAdjacentHTML('beforeend', `<div class="queue-section-header queue-up-next-header"><span>Up Next</span>${remaining.length ? '<button class="queue-clear-btn" onclick="clearQueue()">Clear</button>' : ''}</div>`);
  if (!remaining.length) {
    qView.insertAdjacentHTML('beforeend', '<p class="queue-empty-state">Your queue is clear. Add a song to keep listening.</p>');
    return;
  }
  remaining.forEach((track, offset) => {
    const index = currentIndex + 1 + offset;
    const row = document.createElement('div');
    row.className = 'queue-row queue-draggable';
    row.draggable = true;
    row.dataset.index = index;
    row.innerHTML = `<span class="queue-drag" aria-label="Drag to reorder">&#8801;</span><div class="queue-left-block"><img class="queue-thumb" src="${track.thumbnail || ''}" loading="lazy"/><div class="queue-info"><div class="queue-title">${track.title}</div><div class="queue-artist">${track.artist}</div></div></div><div class="queue-actions-cluster"><button class="queue-action-btn" title="Play next" onclick="event.stopPropagation(); moveQueueTrack(${index}, ${currentIndex + 1})">&#9197;</button><button class="queue-action-btn" title="Remove" onclick="event.stopPropagation(); removeFromQueue(${index})">&#215;</button></div>`;
    row.onclick = () => window.playIndex(index);
    row.addEventListener('dragstart', (event) => event.dataTransfer.setData('text/plain', String(index)));
    row.addEventListener('dragover', (event) => event.preventDefault());
    row.addEventListener('drop', (event) => { event.preventDefault(); window.moveQueueTrack(Number(event.dataTransfer.getData('text/plain')), index); });
    qView.appendChild(row);
  });
};

// ==========================================
// DOM READY & CANVASES
// ==========================================
document.addEventListener('DOMContentLoaded', () => {
  const audio = document.getElementById('audio');
  const dockScrubber = document.getElementById('dockScrubber');
  const scrubberPlayedZone = document.getElementById('scrubberPlayedZone');
  const scrubberThumbIndicator = document.getElementById('scrubberThumbIndicator');
  const scrubberTrackBase = document.getElementById('scrubberTrackBase');
  const timeCurrent = document.getElementById('timeCurrent');
  const timeDuration = document.getElementById('timeDuration');
  const sheetTimeCur = document.getElementById('sheetTimeCur');
  const sheetTimeDur = document.getElementById('sheetTimeDur');
  const miniWaveCanvas = document.getElementById('miniWaveCanvas');
  const scrubberWaveCanvas = document.getElementById('scrubberWaveCanvas');

  syncDownloadedPlaylist();
  window.checkAuthStatus();

  if (currentIndex === -1 && store.state.queue.tracks.length) {
    playlist = store.state.queue.tracks;
    currentIndex = store.state.queue.currentIndex;
    currentPlaylistContextId = store.state.queue.context;
  }
  window.addEventListener('online', () => { if (currentUser) store.pushToCloud(); });
  window.addEventListener('offline', () => { if (currentUser) setSyncState('paused'); });

  if (audio) {
    audio.addEventListener('timeupdate', () => {
      if (!listeningCandidate || listeningCandidate.recorded || audio.paused) return;
      // A track is counted only after 30 seconds (or half of a short track).
      const threshold = Number.isFinite(audio.duration) && audio.duration > 0 ? Math.min(30, audio.duration * 0.5) : 30;
      if (audio.currentTime >= threshold) {
        listeningCandidate.recorded = true;
        store.recordListening(listeningCandidate.track);
      }
    });
  }

  // Multi-Thread Luminous Tidal Wave
  const miniWaveCtx = miniWaveCanvas ? miniWaveCanvas.getContext('2d') : null;
  let wavePhase = 0;
  let colorShift = 0;
  let currentAmplitude = 1.0;

  function resizeMiniWaveCanvas() {
    if (!miniWaveCanvas || !miniWaveCtx) return;
    const dpr = window.devicePixelRatio || 1;
    const w = miniWaveCanvas.offsetWidth;
    const h = miniWaveCanvas.offsetHeight;
    miniWaveCanvas.width = w * dpr;
    miniWaveCanvas.height = h * dpr;
    miniWaveCtx.setTransform(1, 0, 0, 1, 0, 0);
    miniWaveCtx.scale(dpr, dpr);
  }
  window.addEventListener('resize', resizeMiniWaveCanvas);

  function renderTidalLightWave() {
    if (!miniWaveCanvas || !miniWaveCtx || !audio) return;
    const w = miniWaveCanvas.offsetWidth;
    const h = miniWaveCanvas.offsetHeight;
    miniWaveCtx.clearRect(0, 0, w, h);

    const isPlaying = !audio.paused && audio.currentTime > 0 && !audio.ended;
    const midY = h / 2;
    const targetAmp = isPlaying ? 5.6 : 1.4;
    currentAmplitude += (targetAmp - currentAmplitude) * 0.08;

    colorShift = (colorShift + 0.25) % 360;
    wavePhase += isPlaying ? 0.018 : 0.008;

    const threads = [
      { freq: 0.014, speed: 0.8, phase: 0, alpha: 0.65, shift: 0 },
      { freq: 0.021, speed: 1.0, phase: 1.6, alpha: 0.45, shift: 60 },
      { freq: 0.010, speed: 0.6, phase: 3.2, alpha: 0.35, shift: 140 }
    ];

    threads.forEach(t => {
      miniWaveCtx.save();
      miniWaveCtx.beginPath();
      miniWaveCtx.moveTo(0, h);
      for (let x = 0; x <= w; x += 3) {
        const y = midY + Math.sin(x * t.freq + (wavePhase * t.speed) + t.phase) * currentAmplitude;
        miniWaveCtx.lineTo(x, y);
      }
      miniWaveCtx.lineTo(w, h);
      miniWaveCtx.closePath();

      const lightGrad = miniWaveCtx.createLinearGradient(0, midY - currentAmplitude, 0, h);
      lightGrad.addColorStop(0, `hsla(${(colorShift + t.shift) % 360}, 90%, 65%, 0.2)`);
      lightGrad.addColorStop(1, `transparent`);
      miniWaveCtx.fillStyle = lightGrad;
      miniWaveCtx.fill();
      miniWaveCtx.restore();

      miniWaveCtx.save();
      miniWaveCtx.beginPath();
      miniWaveCtx.lineWidth = 1.6;
      const strokeGrad = miniWaveCtx.createLinearGradient(0, 0, w, 0);
      strokeGrad.addColorStop(0, `hsla(${(colorShift + t.shift) % 360}, 90%, 70%, ${t.alpha})`);
      strokeGrad.addColorStop(0.5, `hsla(${(colorShift + t.shift + 60) % 360}, 90%, 65%, ${t.alpha})`);
      strokeGrad.addColorStop(1, `hsla(${(colorShift + t.shift + 120) % 360}, 90%, 70%, ${t.alpha})`);
      miniWaveCtx.strokeStyle = strokeGrad;

      for (let x = 0; x <= w; x += 2) {
        const y = midY + Math.sin(x * t.freq + (wavePhase * t.speed) + t.phase) * currentAmplitude;
        if (x === 0) miniWaveCtx.moveTo(x, y);
        else miniWaveCtx.lineTo(x, y);
      }
      miniWaveCtx.stroke();
      miniWaveCtx.restore();
    });

    requestAnimationFrame(renderTidalLightWave);
  }
  setTimeout(() => { resizeMiniWaveCanvas(); renderTidalLightWave(); }, 60);

  // Scrubber Wave
  const scrubberWaveCtx = scrubberWaveCanvas ? scrubberWaveCanvas.getContext('2d') : null;
  let scrubberWavePhase = 0;

  function renderScrubberLiveWave() {
    if (!scrubberWaveCanvas || !scrubberWaveCtx) return;
    const trackBase = document.getElementById('scrubberTrackBase');
    const w = trackBase ? trackBase.offsetWidth : 300;
    const h = scrubberWaveCanvas.offsetHeight || 14;
    const dpr = window.devicePixelRatio || 1;

    if (scrubberWaveCanvas.width !== w * dpr || scrubberWaveCanvas.height !== h * dpr) {
      scrubberWaveCanvas.width = w * dpr;
      scrubberWaveCanvas.height = h * dpr;
      scrubberWaveCtx.scale(dpr, dpr);
    }

    scrubberWaveCtx.clearRect(0, 0, w, h);
    const isPlaying = audio && !audio.paused && audio.currentTime > 0 && !audio.ended;

    if (w > 0 && isPlaying) {
      scrubberWavePhase += 0.05;
      const midY = h / 2;
      scrubberWaveCtx.beginPath();
      scrubberWaveCtx.lineWidth = 1.8;
      scrubberWaveCtx.strokeStyle = "rgba(255, 255, 255, 0.45)";

      for (let x = 0; x <= w; x += 3) {
        const y = midY + Math.sin(x * 0.08 + scrubberWavePhase) * 2;
        if (x === 0) scrubberWaveCtx.moveTo(x, y);
        else scrubberWaveCtx.lineTo(x, y);
      }
      scrubberWaveCtx.stroke();
    }
    requestAnimationFrame(renderScrubberLiveWave);
  }
  setTimeout(renderScrubberLiveWave, 80);

  // Update scrubber UI based on audio progress
  function updateScrubberUI() {
    if (audio && audio.duration && !isNaN(audio.duration)) {
      const progress = audio.currentTime / audio.duration;
      const percent = Math.max(0, Math.min(100, progress * 100));

      // Update dock scrubber input
      if (dockScrubber) dockScrubber.value = percent;

      // Update played zone width
      if (scrubberPlayedZone) scrubberPlayedZone.style.width = percent + '%';

      // Update thumb indicator position
      if (scrubberThumbIndicator && scrubberTrackBase) {
        const thumbX = (scrubberTrackBase.offsetWidth * progress) - 6; // 6 is half the thumb width
        scrubberThumbIndicator.style.left = Math.max(0, thumbX) + 'px';
      }

      // Update time labels
      if (timeCurrent) timeCurrent.innerText = fmtTime(audio.currentTime);
      if (timeDuration) timeDuration.innerText = fmtTime(audio.duration);
      if (sheetTimeCur) sheetTimeCur.innerText = fmtTime(audio.currentTime);
      if (sheetTimeDur) sheetTimeDur.innerText = fmtTime(audio.duration);
    }
    requestAnimationFrame(updateScrubberUI);
  }
  setTimeout(updateScrubberUI, 100);

  if (scrubberTrackBase && audio) {
    scrubberTrackBase.addEventListener('click', (e) => {
      if (!audio.duration) return;
      const rect = scrubberTrackBase.getBoundingClientRect();
      const pct = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      audio.currentTime = pct * audio.duration;
    });
  }

  // Fluid Mesh Renderer
  const fluidCanvases = [document.getElementById('fluidMeshCanvas')];
  let fluidTime = 0;

  function resizeFluidCanvases() {
    fluidCanvases.forEach(canv => {
      if (!canv) return;
      canv.width = Math.floor(window.innerWidth / 4);
      canv.height = Math.floor(window.innerHeight / 4);
    });
  }
  window.addEventListener('resize', resizeFluidCanvases);
  setTimeout(resizeFluidCanvases, 40);

  function renderLiveFluidMesh() {
    const isSheetOpen = document.getElementById('fullscreenPlayerOverlay')?.classList.contains('open');
    if (!isSheetOpen) {
      requestAnimationFrame(renderLiveFluidMesh);
      return;
    }

    fluidTime += 0.008;
    const computedStyle = getComputedStyle(document.documentElement);
    const color1 = computedStyle.getPropertyValue('--mesh-color-1').trim() || 'rgba(250, 45, 72, 0.95)';
    const color2 = computedStyle.getPropertyValue('--mesh-color-2').trim() || 'rgba(192, 38, 211, 0.88)';

    fluidCanvases.forEach(canv => {
      if (!canv) return;
      const fCtx = canv.getContext('2d');
      const w = canv.width;
      const h = canv.height;
      fCtx.clearRect(0, 0, w, h);

      const cx1 = w * (0.35 + 0.25 * Math.sin(fluidTime));
      const cy1 = h * (0.35 + 0.25 * Math.cos(fluidTime));

      // Draw fluid mesh
      fCtx.fillStyle = color1;
      fCtx.globalAlpha = 0.6;
      fCtx.beginPath();
      fCtx.arc(cx1, cy1, 120, 0, Math.PI * 2);
      fCtx.fill();

      fCtx.fillStyle = color2;
      fCtx.globalAlpha = 0.5;
      const cx2 = w * (0.65 + 0.2 * Math.cos(fluidTime * 0.7));
      const cy2 = h * (0.65 + 0.2 * Math.sin(fluidTime * 0.7));
      fCtx.beginPath();
      fCtx.arc(cx2, cy2, 100, 0, Math.PI * 2);
      fCtx.fill();
      fCtx.globalAlpha = 1;
    });
    requestAnimationFrame(renderLiveFluidMesh);
  }
  renderLiveFluidMesh();

  // ==========================================
  // 9. INITIALIZE DEFAULT VIEW
  // ==========================================
  window.switchView('home');
});
