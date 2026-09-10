// =============================================================================
// MELO Music Core Application Logic
// =============================================================================

const $id = id => document.getElementById(id);
window.__contextTrackMap = {};

// ==========================================
// PIXEL M3 EXPRESSIVE LOADER GENERATOR
// ==========================================
function createMeloLoader() {
  return `
    <div class="melo-inline-loader">
      <div class="m3e-morph-indicator">
        <div class="m3e-morph-shape"></div>
      </div>
    </div>
  `;
}
window.createMeloLoader = createMeloLoader;

// ==========================================
// 1. DATA STORAGE & SYNC ENGINE
// ==========================================
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
        const priorIds = new Set(priorTracks.map(t => String(t.id)));
        const nextTracks = playlistData.tracks || [];
        const additions = nextTracks.filter(t => !priorIds.has(String(t.id)));
        this.enqueue('playlist_upsert', { playlist: { ...playlistData, tracks: additions } });
        priorTracks.filter(track => !nextTracks.some(next => String(next.id) === String(track.id))).forEach((track) => {
          this.enqueue('playlist_track_remove', { playlist_id: playlistId, track_id: track.id, updated_at: playlistData.updated_at });
        });
        const oldOrder = priorTracks.map(t => String(t.id)).join('|');
        const newOrder = nextTracks.map(t => String(t.id)).join('|');
        if (oldOrder !== newOrder && nextTracks.length) {
          this.enqueue('playlist_track_order', { playlist_id: playlistId, track_ids: nextTracks.map(t => String(t.id)), updated_at: playlistData.updated_at });
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
  saveHistory(history) { this.state.history = history.slice(-100); this.persist(); }
  saveQuality(quality) { this.state.preferences.quality = quality; this.persist(); this.enqueue('preferences', { values: { quality } }); }
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
    this.state.history = this.state.history.filter(e => e.id !== entryId);
    this.persist();
    this.enqueue('history_remove', { entry_id: entryId });
  }
  rememberSearch(query) {
    const clean = query.trim();
    if (!clean) return;
    this.state.searchHistory = [
      { query: clean, searched_at: new Date().toISOString() },
      ...this.state.searchHistory.filter(i => i.query.toLowerCase() !== clean.toLowerCase())
    ].slice(0, 12);
    this.persist();
    this.enqueue('search_history', { query: clean, searched_at: new Date().toISOString() });
  }
  removeSearch(query) {
    this.state.searchHistory = this.state.searchHistory.filter(i => i.query !== query);
    this.persist();
    this.enqueue('search_history_remove', { query });
  }
  clearSearches() {
    this.state.searchHistory = [];
    this.persist();
    this.enqueue('search_history_clear', {});
  }
  saveQueue(queue) { this.state.queue = queue; this.persist(); }
  scheduleSync() {
    if (!currentUser) return;
    clearTimeout(this.syncTimer);
    setSyncState('queued');
    this.syncTimer = setTimeout(() => this.pushToCloud(), 1200);
  }
  applyMutation(operation, payload) {
    const plMap = this.state.playlists;
    if (operation === 'favorite') {
      const track = payload.track || {}, id = String(track.id || payload.track_id || '');
      if (payload.loved !== false && id) this.state.favorites[id] = track;
      else delete this.state.favorites[id];
    } else if (operation === 'playlist_upsert' && payload.playlist?.id) {
      const inc = payload.playlist, old = plMap[inc.id] || {}, trks = [...(old.tracks || []), ...(inc.tracks || [])], seen = new Set();
      plMap[inc.id] = { ...old, ...inc, tracks: trks.filter(t => t.id && !seen.has(String(t.id)) && seen.add(String(t.id))) };
    } else if (operation === 'playlist_delete') {
      delete plMap[payload.playlist_id];
    } else if (operation === 'playlist_track_add' && plMap[payload.playlist_id]) {
      const trks = plMap[payload.playlist_id].tracks || [];
      if (!trks.some(t => String(t.id) === String(payload.track?.id))) trks.push(payload.track);
    } else if (operation === 'playlist_track_remove' && plMap[payload.playlist_id]) {
      plMap[payload.playlist_id].tracks = (plMap[payload.playlist_id].tracks || []).filter(t => String(t.id) !== String(payload.track_id));
    } else if (operation === 'playlist_track_order' && plMap[payload.playlist_id]) {
      const trks = plMap[payload.playlist_id].tracks || [], byId = Object.fromEntries(trks.map(t => [String(t.id), t]));
      const ordered = (payload.track_ids || []).map(id => byId[String(id)]).filter(Boolean);
      plMap[payload.playlist_id].tracks = [...ordered, ...trks.filter(t => !payload.track_ids.includes(String(t.id)))];
    } else if (operation === 'history_add' && payload.entry?.id) {
      if (!this.state.history.some(e => e.id === payload.entry.id)) this.state.history.push(payload.entry);
      this.state.history = this.state.history.slice(-100);
    } else if (operation === 'history_remove') {
      this.state.history = this.state.history.filter(e => e.id !== payload.entry_id);
    } else if (operation === 'search_history') {
      this.state.searchHistory = [{ query: payload.query, searched_at: payload.searched_at }, ...this.state.searchHistory.filter(i => i.query.toLowerCase() !== payload.query.toLowerCase())].slice(0, 12);
    } else if (operation === 'search_history_remove') {
      this.state.searchHistory = this.state.searchHistory.filter(i => i.query !== payload.query);
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
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ base_revision: this.state.revision || 0, mutations: sent })
      });
      if (!res.ok) throw new Error(`Sync failed (${res.status})`);
      const data = await res.json();
      if (data.snapshot) this.applySnapshot(data.snapshot);
      const sentIds = new Set(data.acknowledged_ids || []);
      this.pending = this.pending.filter(m => !sentIds.has(m.id));
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
      if (activeView === 'account') renderAccountView();
    }
  }
  async pullFromCloud() { return this.pushToCloud(); }
}

// ==========================================
// 2. STATE & VARIABLES
// ==========================================
const store = new MeloDataLayer();
let previousNavView = 'home';
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
let isCinematicActive = false;
let repeatMode = 'none';
let sleepTimerTimeout = null;
let sleepTimerEndsAt = 0;
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
let globalSearchState = { query: '', filter: 'all', scrollY: 0 };
let currentSearchAbort = null;
let lastActiveLyricIdx = -1;
let isUserScrollingLyrics = false;
let lyricsScrollResumeTimer = null;
let isDraggingScrubber = false;
let isRegisterMode = false;
let meloConfirmationAction = null;

// ==========================================
// 3. CORE NAVIGATION & HOISTED UTILITIES
// ==========================================
function switchView(view, pushState = true) {
  // At the top of switchView:
if (activeView !== view && !['playlist-detail', 'album-detail', 'artist-detail'].includes(view)) {
  previousNavView = activeView;
}
  if (pushState && activeView !== view) {
    navigationHistory.push(view);
  }
  activeView = view;

  document.querySelectorAll('.capsule-btn, .pill-nav-item').forEach(btn => btn.classList.remove('active'));

  // Encapsulate the DOM update so it can run instantly or with smooth view transitions
  const run = () => {
    if (view === 'home') {
      $id('navHome')?.classList.add('active');
      $id('mNavHome')?.classList.add('active');
      renderHomeView();
    } else if (view === 'search') {
      $id('navSearch')?.classList.add('active');
      $id('mNavSearch')?.classList.add('active');
      renderSearchView();
    } else if (view === 'favorites') {
      $id('navFavs')?.classList.add('active');
      $id('mNavFavs')?.classList.add('active');
      renderFavoritesView();
    } else if (view === 'loved') {
      $id('navFavs')?.classList.add('active');
      $id('mNavFavs')?.classList.add('active');
      renderLovedTracks();
    } else if (view === 'history') {
      $id('navFavs')?.classList.add('active');
      $id('mNavFavs')?.classList.add('active');
      renderHistoryView();
    } else if (view === 'offline') {
      $id('navFavs')?.classList.add('active');
      $id('mNavFavs')?.classList.add('active');
      renderOfflineVault();
    } else if (view === 'account') {
      renderAccountView();
    } else if (view === 'player' || view === 'nowplaying' || view === 'fullscreen') {
      openFullscreenPlayer();
    }

   // Inside switchView(view, pushState = true) in static/js/app.js:
if (!['playlist-detail', 'album-detail', 'artist-detail'].includes(view)) {
  document.documentElement.style.removeProperty('--pl-dynamic-bg');
  document.documentElement.style.removeProperty('--pl-dynamic-mid');
  document.documentElement.style.removeProperty('--pl-dynamic-deep');
  document.documentElement.style.removeProperty('--pl-dynamic-accent');
}

    // Hide fullscreen overlay if switching back to standard navigation tabs
    if (view !== 'player' && view !== 'nowplaying' && view !== 'fullscreen') {
      closeFullscreenPlayer();
    }

    // Smooth scroll reset to top of viewport to prevent jank
    const vp = $id('mainViewport');
    if (vp) vp.scrollTop = 0;
  };

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!reduceMotion && document.startViewTransition) {
    try {
      const transition = document.startViewTransition(run);
      transition.catch((err) => {
        // Suppress benign transition aborts from rapid clicks or tab switches
        if (err.name !== 'InvalidStateError' && err.name !== 'AbortError') {
          console.warn('ViewTransition failed:', err);
        }
      });
    } catch (e) {
      run();
    }
  } else {
    run();
  }
}

function goBack() {
  // Hub sub-views and details must return to Music Hub (favorites)
  const hubViews = ['favorites', 'loved', 'history', 'offline', 'hub', 'library'];
  
  if (['playlist-detail', 'album-detail', 'artist-detail', 'loved', 'history', 'offline'].includes(activeView)) {
    // If we entered from Search or Home, return there; otherwise return to Music Hub
    if (previousNavView === 'search') {
      switchView('search');
    } else if (previousNavView === 'home') {
      switchView('home');
    } else {
      switchView('favorites');
    }
  } else if (window.history.length > 1) {
    window.history.back();
  } else {
    switchView('favorites');
  }
}
window.goBack = goBack;

function scrollToCategory(id) {
  const el = $id(id);
  if (el) el.scrollIntoView({ behavior: 'smooth' });
}

function accountText(value) {
  const node = document.createElement('span');
  node.textContent = value || '';
  return node.innerHTML;
}

function normalizeTrackData(raw) {
  if (!raw) return { id: '', type: 'song', title: 'Unknown Track', artist: 'Unknown Artist', album: '', duration: '0:00', thumbnail: '' };
  return {
    id: String(raw.id || raw.songid || (raw.perma_url ? raw.perma_url.split('/').pop() : '') || ''),
    type: 'song',
    title: raw.title || raw.song || 'Unknown Track',
    artist: raw.more_info?.music || raw.primary_artists || raw.singers || raw.artist || 'Unknown Artist',
    album: raw.more_info?.album || raw.album || '',
    duration: raw.duration || '0:00',
    thumbnail: raw.image || raw.image_url || raw.thumbnail || ''
  };
}

function fmtTime(s) {
  if (isNaN(s) || s == null || s < 0) return "0:00";
  const m = Math.floor(s / 60), sec = Math.floor(s % 60);
  return `${m}:${sec < 10 ? '0' : ''}${sec}`;
}

function showToast(msg) {
  const t = $id('meloToast');
  if (!t) return;
  t.innerText = msg;
  t.classList.add('show');
  clearTimeout(t._timer);
  t._timer = setTimeout(() => t.classList.remove('show'), 2600);
}

function syncTimestampKey() { return currentUser ? `melo_last_synced_at_${currentUser.id}` : null; }
function loadLastSyncedAt() {
  const key = syncTimestampKey();
  const value = key ? localStorage.getItem(key) : null;
  const date = value ? new Date(value) : null;
  lastSyncedAt = date && !Number.isNaN(date.getTime()) ? date : null;
}
function setSyncState(state) {
  syncState = state;
  if (activeView === 'account') renderAccountView();
}
function recordSuccessfulSync(state) {
  lastSyncedAt = new Date();
  const key = syncTimestampKey();
  if (key) localStorage.setItem(key, lastSyncedAt.toISOString());
  syncState = state;
}

setInterval(() => { fetch('/api/ping').catch(() => {}); }, 10 * 60 * 1000);

// ==========================================
// ROBUST MELO CONFIRMATION MODAL SYSTEM
// ==========================================
function showMeloConfirmation({ title, message, actionLabel = 'Confirm', danger = false, action }) {
  meloConfirmationAction = action;
  let modal = $id('meloConfirmModal');
  
  // If the modal markup doesn't exist in index.html, inject it dynamically
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'meloConfirmModal';
    modal.className = 'context-action-modal';
    modal.onclick = (e) => closeMeloConfirmation(e);
    modal.innerHTML = `
      <div class="context-modal-sheet" onclick="event.stopPropagation()" style="text-align:center;">
        <div class="drag-handle" onclick="closeMeloConfirmation()"></div>
        <h2 style="font-size:1.3rem;font-weight:800;margin-bottom:8px;" id="meloConfirmTitle"></h2>
        <p style="color:var(--text-muted);font-size:0.88rem;margin-bottom:20px;" id="meloConfirmMessage"></p>
        <div style="display:flex;gap:10px;">
          <button class="filter-chip" onclick="closeMeloConfirmation()" style="flex:1;padding:12px;">Cancel</button>
          <button class="pill-action-btn" id="meloConfirmButton" onclick="confirmMeloAction()" style="flex:1;justify-content:center;padding:12px;"></button>
        </div>
      </div>
    `;
    document.body.appendChild(modal);
  }

  const titleEl = $id('meloConfirmTitle');
  const msgEl = $id('meloConfirmMessage');
  const btn = $id('meloConfirmButton');

  if (titleEl) titleEl.innerText = title;
  if (msgEl) msgEl.innerText = message;
  if (btn) {
    btn.innerText = actionLabel;
    btn.style.background = danger ? '#fa2d48' : '#ffffff';
    btn.style.color = danger ? '#ffffff' : '#000000';
  }
  
  modal.classList.add('open');
}

function closeMeloConfirmation(e) {
  if (!e || e.target === $id('meloConfirmModal') || e.target?.classList.contains('drag-handle')) {
    $id('meloConfirmModal')?.classList.remove('open');
    meloConfirmationAction = null;
  }
}

function confirmMeloAction() {
  const act = meloConfirmationAction;
  closeMeloConfirmation();
  if (typeof act === 'function') act();
}

// ==========================================
// 4. INDEXEDDB OFFLINE STORAGE
// ==========================================
const IDB_NAME = 'melo_offline_db', IDB_STORE = 'downloaded_tracks';
function openMeloDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(IDB_NAME, 1);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains(IDB_STORE)) db.createObjectStore(IDB_STORE, { keyPath: 'id' });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function saveTrackToOfflineDB(trackObj, blobData) {
  const db = await openMeloDB();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(IDB_STORE, 'readwrite');
    tx.objectStore(IDB_STORE).put({ id: String(trackObj.id), metadata: trackObj, blob: blobData, downloadedAt: Date.now() });
    tx.oncomplete = () => resolve(true);
    tx.onerror = () => reject(tx.error);
  });
}

async function getTrackFromOfflineDB(trackId) {
  try {
    const db = await openMeloDB();
    return new Promise((resolve) => {
      const req = db.transaction(IDB_STORE, 'readonly').objectStore(IDB_STORE).get(String(trackId));
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => resolve(null);
    });
  } catch (e) { return null; }
}

async function syncDownloadedPlaylist() {
  try {
    const db = await openMeloDB();
    const req = db.transaction(IDB_STORE, 'readonly').objectStore(IDB_STORE).getAll();
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
// 5. AUDIO QUALITY & PERSISTENCE
// ==========================================
const QUALITY_MAP = {
  '96': 'Standard (96 kbps)',
  '160': 'High (160 kbps)',
  '320': 'Very High (320 kbps)'
};

function promptQualitySelection() { closeSettingsModal(); $id('qualityModal')?.classList.add('open'); }
function closeQualityModal(e) { if (!e || e.target === $id('qualityModal') || e.target.classList.contains('drag-handle')) $id('qualityModal')?.classList.remove('open'); }
function selectQualityOption(val) { changeQuality(val); closeQualityModal(); }

function changeQuality(val) {
  selectedQuality = String(val);
  store.saveQuality(selectedQuality);
  if ($id('currentQualityLabel')) $id('currentQualityLabel').innerText = `Audio Quality: ${QUALITY_MAP[selectedQuality] || selectedQuality + ' kbps'}`;
  if ($id('desktopQualitySelect')) $id('desktopQualitySelect').value = selectedQuality;
  showToast(`Streaming Quality: ${QUALITY_MAP[selectedQuality] || selectedQuality + ' kbps'}`);
}

function persistPlaybackQueue() {
  store.saveQueue({ tracks: playlist, currentIndex, context: currentPlaylistContextId });
  persistPlaybackSession();
}

function persistPlaybackSession() {
  if (currentIndex === -1 || !playlist.length) return;
  const audio = $id('audio');
  const session = {
    playlist: playlist.slice(0, 100),
    currentIndex,
    currentTime: audio && !isNaN(audio.currentTime) ? audio.currentTime : 0,
    contextId: currentPlaylistContextId,
    repeatMode,
    isShuffle,
    quality: selectedQuality
  };
  localStorage.setItem('melo_playback_session', JSON.stringify(session));
}

function restorePlaybackSession() {
  try {
    const raw = localStorage.getItem('melo_playback_session');
    if (!raw) return;
    const session = JSON.parse(raw);
    if (session && Array.isArray(session.playlist) && session.playlist.length > 0) {
      playlist = session.playlist.map(normalizeTrackData);
      currentIndex = typeof session.currentIndex === 'number' ? Math.min(session.currentIndex, playlist.length - 1) : 0;
      currentPlaylistContextId = session.contextId || null;
      repeatMode = session.repeatMode || 'none';
      isShuffle = !!session.isShuffle;
      if (session.quality) selectedQuality = session.quality;

      const track = playlist[currentIndex];
      if (track) {
        if ($id('dockTitle')) $id('dockTitle').innerText = track.title;
        if ($id('dockArtist')) $id('dockArtist').innerText = track.artist;
        if ($id('dockThumb')) $id('dockThumb').src = track.thumbnail || '';
        $id('dockPlayerBar')?.classList.add('active');

        const audio = $id('audio');
        if (audio) {
          audio.src = `/api/stream/${track.id}?quality=${selectedQuality}`;
          audio.currentTime = session.currentTime || 0;
          audio.load();
        }
        syncSheetTrackInfo();
        syncPlaybackControlsUI();
        fetchLyrics(track, activePlayToken);
      }
    }
  } catch (e) {}
}

// ==========================================
// 6. COLOR THEME GENERATION
// ==========================================
function generateVibrantColors(seedStr) {
  let hash = 0;
  for (let i = 0; i < seedStr.length; i++) hash = seedStr.charCodeAt(i) + ((hash << 5) - hash);
  const r1 = Math.abs((hash * 43) % 180) + 60, g1 = Math.abs((hash * 23) % 150) + 45, b1 = Math.abs((hash * 67) % 200) + 55;
  const r2 = Math.min(240, Math.abs(255 - r1) + 40), g2 = Math.min(240, Math.abs(255 - g1) + 40), b2 = Math.min(240, Math.abs(255 - b1) + 40);
  currentPrimaryHex = `#${((1 << 24) + (r1 << 16) + (g1 << 8) + b1).toString(16).slice(1)}`;
  document.documentElement.style.setProperty('--mesh-color-1', `rgba(${r1}, ${g1}, ${b1}, 0.95)`);
  document.documentElement.style.setProperty('--mesh-color-2', `rgba(${r2}, ${g2}, ${b2}, 0.88)`);
  document.documentElement.style.setProperty('--play-accent-color', `rgb(${r1}, ${g1}, ${b1})`);
}

function applyPlaylistDynamicColors(imgUrl, fallbackSeed) {
  let hash = 0;
  const seed = fallbackSeed || 'melo';
  for (let i = 0; i < seed.length; i++) hash = seed.charCodeAt(i) + ((hash << 5) - hash);
  const defR = Math.abs((hash * 47) % 160) + 70;
  const defG = Math.abs((hash * 29) % 130) + 40;
  const defB = Math.abs((hash * 61) % 170) + 60;

  const setColors = (r, g, b) => {
    // Rich cover glow at the header
    document.documentElement.style.setProperty('--pl-dynamic-bg', `rgba(${r}, ${g}, ${b}, 0.65)`);
    // Calibrated subtle ambient tint for song list (12% alpha)
    document.documentElement.style.setProperty('--pl-dynamic-mid', `rgba(${r}, ${g}, ${b}, 0.12)`);
    // Dark floor with matching tone so scroll depth has zero black cutoffs
    const deepTone = `rgb(${Math.floor(r * 0.06) + 7}, ${Math.floor(g * 0.06) + 8}, ${Math.floor(b * 0.06) + 11})`;
    document.documentElement.style.setProperty('--pl-dynamic-deep', deepTone);
  };

  setColors(defR, defG, defB);
  if (!imgUrl) return;

  let safeUrl = imgUrl;
  if ((safeUrl.startsWith('http://') || safeUrl.startsWith('https://')) && !safeUrl.includes('/api/proxy-image')) {
    safeUrl = `/api/proxy-image?url=${encodeURIComponent(safeUrl)}`;
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
        if (data[i + 3] > 128) {
          r += data[i]; 
          g += data[i + 1]; 
          b += data[i + 2]; 
          count++;
        }
      }
      if (count > 0) {
        setColors(
          Math.min(255, Math.round((r / count) * 1.05)),
          Math.min(255, Math.round((g / count) * 1.05)),
          Math.min(255, Math.round((g / count) * 1.05))
        );
      }
    } catch (e) {}
  };
}

function updateArtworkPalette(imgUrl, trackTitle = '', trackId = '') {
  if (!imgUrl) return generateVibrantColors(trackTitle || trackId || 'melo');
  const img = new Image();
  img.crossOrigin = "anonymous";
  img.src = imgUrl;
  img.onload = () => {
    try {
      const cvs = document.createElement("canvas"), ctx = cvs.getContext("2d"), size = 32;
      cvs.width = size; cvs.height = size;
      ctx.drawImage(img, 0, 0, size, size);
      const data = ctx.getImageData(0, 0, size, size).data, buckets = {};
      for (let i = 0; i < data.length; i += 4) {
        if (data[i + 3] < 128) continue;
        const qr = Math.round(data[i] / 24) * 24, qg = Math.round(data[i + 1] / 24) * 24, qb = Math.round(data[i + 2] / 24) * 24;
        const key = `${qr},${qg},${qb}`;
        if (!buckets[key]) buckets[key] = { r: qr, g: qg, b: qb, count: 0 };
        buckets[key].count += 1;
      }
      const sorted = Object.values(buckets).sort((a, b) => b.count - a.count);
      let primary = sorted[0];
      if (!primary) return generateVibrantColors(trackTitle || trackId);
      const r1 = primary.r, g1 = primary.g, b1 = primary.b;
      currentPrimaryHex = `#${((1 << 24) + (r1 << 16) + (g1 << 8) + b1).toString(16).slice(1)}`;
      document.documentElement.style.setProperty('--mesh-color-1', `rgba(${r1}, ${g1}, ${b1}, 0.95)`);
      document.documentElement.style.setProperty('--play-accent-color', `rgb(${r1}, ${g1}, ${b1})`);
    } catch (e) {
      generateVibrantColors(trackTitle || trackId);
    }
  };
  img.onerror = () => generateVibrantColors(trackTitle || trackId);
}

// ==========================================
// 7. CORE PLAYBACK ENGINE
// ==========================================
async function playIndex(idx) {
  if (idx < 0 || idx >= playlist.length) return;
  const thisToken = ++activePlayToken;
  currentIndex = idx;
  const track = playlist[currentIndex];
  const audio = $id('audio');

  showMeloLoader('STARTING AUDIO...'); // <--- Show Loader

  listeningCandidate = { track, queueToken: activePlayToken, recorded: false };
  store.saveQueue({ tracks: playlist, currentIndex, context: currentPlaylistContextId });

  if ($id('dockTitle')) $id('dockTitle').innerText = track.title;
  if ($id('dockArtist')) $id('dockArtist').innerText = ''; // Hide artist name in mini player as requested
  if ($id('dockThumb')) $id('dockThumb').src = track.thumbnail || '';

  syncSheetTrackInfo();
  syncPlaybackControlsUI();

  $id('dockPlayerBar')?.classList.add('active');
  if ($id('tabQueue')?.classList.contains('active')) renderSheetQueueList();

  if (audio) {
    try {
      showMeloLoader('STARTING AUDIO...');

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
            } else {
              setPlayState(true);
            }
          })
          .catch((err) => {
            if (thisToken === activePlayToken) {
              setPlayState(false);
              if (err.name !== 'AbortError' && err.name !== 'NotAllowedError') {
                showToast("Stream loading issue. Retrying...");
                setTimeout(() => nextTrack(), 1500);
              }
            }
          })
          .finally(() => {
            hideMeloLoader();
          });
      } else {
        hideMeloLoader();
      }
    } catch (err) {
      hideMeloLoader();
      showToast("Unable to load audio track.");
    }
  }

  updateMediaSession(track);
  fetchLyrics(track, thisToken);
  checkAndExpandInfiniteQueue();
  persistPlaybackSession();
}

function setPlayState(playing) {
  const p = playing ? 'M6 19h4V5H6v14zm8-14v14h4V5h-4z' : 'M8 5v14l11-7z';
  const dockIcon = `<svg viewBox="0 0 24 24" style="fill:#000000;width:16px;height:16px;pointer-events:none;"><path d="${p}" style="pointer-events:none;"/></svg>`;
  const sheetIcon = `<svg id="sheetPlayBtnSvg" viewBox="0 0 24 24" style="fill:#ffffff;width:28px;height:28px;pointer-events:none;"><path d="${p}" style="pointer-events:none;"/></svg>`;

  const dBtn = $id('dockPlayBtn');
  const mBtn = $id('mDockPlayBtn');
  const sBtn = $id('sheetPlayBtn');

  if (dBtn) dBtn.innerHTML = dockIcon;
  if (mBtn) mBtn.innerHTML = dockIcon;
  if (sBtn) sBtn.innerHTML = sheetIcon;

  $id('dockPlayerBar')?.classList.toggle('is-playing', playing);
  $id('sheetCoverBox')?.classList.toggle('is-playing', playing);

  const mwc = $id('miniWaveCanvas');
  if (mwc) {
    mwc.style.opacity = playing ? '1' : '0';
  }
}

let isTogglingAudio = false;

function togglePlay() {
  const audio = $id('audio');
  if (!audio) return;

  if (!audio.src && playlist.length) {
    return playIndex(currentIndex >= 0 ? currentIndex : 0);
  }

  if (audio.paused) {
    audio.play()
      .then(() => setPlayState(true))
      .catch((err) => {
        console.warn('Audio play interrupted:', err);
        setPlayState(false);
      });
  } else {
    audio.pause();
    setPlayState(false);
  }
  persistPlaybackSession();
}

async function nextTrack() {
  const audio = $id('audio');
  if (repeatMode === 'one' && audio) {
    audio.currentTime = 0;
    audio.play().catch(console.warn);
    return;
  }
  if (isShuffle && playlist.length > 1) {
    let nextIdx = Math.floor(Math.random() * playlist.length);
    if (nextIdx === currentIndex) nextIdx = (currentIndex + 1) % playlist.length;
    return playIndex(nextIdx);
  }
  if (currentIndex + 1 < playlist.length) {
    playIndex(currentIndex + 1);
    checkAndExpandInfiniteQueue();
    return;
  }
  if (currentPlaylistContextId === null) {
    await checkAndExpandInfiniteQueue();
    if (currentIndex + 1 < playlist.length) return playIndex(currentIndex + 1);
  }
  if (repeatMode === 'all') {
    playIndex(0);
  } else {
    setPlayState(false);
  }
}

function prevTrack() {
  const audio = $id('audio');
  if (audio && audio.currentTime > 3) {
    audio.currentTime = 0;
    return;
  }
  if (currentIndex > 0) playIndex(currentIndex - 1);
  else if (repeatMode === 'all') playIndex(playlist.length - 1);
}

function toggleShuffle() {
  isShuffle = !isShuffle;
  $id('sheetShuffleBtn')?.classList.toggle('active', isShuffle);
  showToast(isShuffle ? "Shuffle On" : "Shuffle Off");
  persistPlaybackSession();
}

function toggleRepeatMode() {
  const rBtn = $id('sheetRepeatBtn');
  const badge = $id('loopBadge');
  if (repeatMode === 'none') {
    repeatMode = 'all';
    rBtn?.classList.add('active');
    if (badge) { badge.style.display = 'block'; badge.innerText = 'ALL'; }
    showToast("Repeat All");
  } else if (repeatMode === 'all') {
    repeatMode = 'one';
    rBtn?.classList.add('active');
    if (badge) { badge.style.display = 'block'; badge.innerText = '1'; }
    showToast("Repeat One");
  } else {
    repeatMode = 'none';
    rBtn?.classList.remove('active');
    if (badge) badge.style.display = 'none';
    showToast("Repeat Off");
  }
  persistPlaybackSession();
}

function syncPlaybackControlsUI() {
  $id('sheetShuffleBtn')?.classList.toggle('active', isShuffle);
  const rBtn = $id('sheetRepeatBtn');
  const badge = $id('loopBadge');
  if (repeatMode === 'all') {
    rBtn?.classList.add('active');
    if (badge) { badge.style.display = 'block'; badge.innerText = 'ALL'; }
  } else if (repeatMode === 'one') {
    rBtn?.classList.add('active');
    if (badge) { badge.style.display = 'block'; badge.innerText = '1'; }
  } else {
    rBtn?.classList.remove('active');
    if (badge) badge.style.display = 'none';
  }
}

function syncSheetTrackInfo() {
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const track = playlist[currentIndex];
  if ($id('sheetCover')) $id('sheetCover').src = track.thumbnail || '';
  if ($id('sheetTitle')) {
    $id('sheetTitle').innerText = track.title;
    setTimeout(() => {
      const wrap = $id('sheetTitle').parentElement;
      if (wrap && $id('sheetTitle').scrollWidth > wrap.clientWidth) $id('sheetTitle').classList.add('is-overflowing');
      else $id('sheetTitle').classList.remove('is-overflowing');
    }, 50);
  }
  if ($id('sheetArtist')) $id('sheetArtist').innerText = track.artist;
  const isFav = !!favorites[track.id];
  if ($id('playerSheetFavIcon')) {
    $id('playerSheetFavIcon').innerText = isFav ? '♥' : '♡';
    $id('playerSheetFavIcon').style.color = isFav ? 'var(--accent)' : '#fff';
  }
  updateArtworkPalette(track.thumbnail, track.title, track.id);
}

// ==========================================
// 8. SEEKING & SCRUBBERS
// ==========================================
function updateScrubberVisuals(p) {
  const pct = Math.max(0, Math.min(100, p * 100));
  if ($id('dockScrubber')) $id('dockScrubber').value = pct;
  if ($id('scrubberPlayedZone')) $id('scrubberPlayedZone').style.width = pct + '%';
  if ($id('scrubberThumbIndicator')) $id('scrubberThumbIndicator').style.left = pct + '%';
}

function initScrubberHandlers() {
  const audio = $id('audio');
  const stb = $id('scrubberTrackBase');
  const dockScrubber = $id('dockScrubber');

  if (stb && audio) {
    const handleSeek = (clientX) => {
      if (!audio.duration || isNaN(audio.duration)) return;
      const rect = stb.getBoundingClientRect();
      const p = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
      updateScrubberVisuals(p);
      if ($id('sheetTimeCur')) $id('sheetTimeCur').innerText = fmtTime(p * audio.duration);
      return p;
    };

    stb.addEventListener('mousedown', (e) => {
      isDraggingScrubber = true;
      handleSeek(e.clientX);
      const onMove = (mv) => { if (isDraggingScrubber) handleSeek(mv.clientX); };
      const onUp = (up) => {
        if (isDraggingScrubber) {
          isDraggingScrubber = false;
          const finalP = handleSeek(up.clientX);
          if (typeof finalP === 'number') audio.currentTime = finalP * audio.duration;
          persistPlaybackSession();
        }
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
      };
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
    });

    stb.addEventListener('touchstart', (e) => {
      isDraggingScrubber = true;
      if (e.touches[0]) handleSeek(e.touches[0].clientX);
    }, { passive: true });

    stb.addEventListener('touchmove', (e) => {
      if (isDraggingScrubber && e.touches[0]) handleSeek(e.touches[0].clientX);
    }, { passive: true });

    stb.addEventListener('touchend', (e) => {
      if (isDraggingScrubber && e.changedTouches[0]) {
        isDraggingScrubber = false;
        const rect = stb.getBoundingClientRect();
        const p = Math.max(0, Math.min(1, (e.changedTouches[0].clientX - rect.left) / rect.width));
        audio.currentTime = p * audio.duration;
        persistPlaybackSession();
      }
    });
  }

  if (dockScrubber && audio) {
    dockScrubber.addEventListener('input', () => {
      if (!audio.duration || isNaN(audio.duration)) return;
      const cur = (dockScrubber.value / 100) * audio.duration;
      if ($id('timeCurrent')) $id('timeCurrent').innerText = fmtTime(cur);
    });
    dockScrubber.addEventListener('change', () => {
      if (!audio.duration || isNaN(audio.duration)) return;
      audio.currentTime = (dockScrubber.value / 100) * audio.duration;
      persistPlaybackSession();
    });
  }

  window.addEventListener('keydown', (e) => {
    if (['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName)) return;
    if (e.code === 'Space') {
      e.preventDefault();
      togglePlay();
    } else if (e.code === 'ArrowRight') {
      e.preventDefault();
      if (audio && !isNaN(audio.duration)) {
        audio.currentTime = Math.min(audio.duration, audio.currentTime + 5);
        persistPlaybackSession();
      }
    } else if (e.code === 'ArrowLeft') {
      e.preventDefault();
      if (audio) {
        audio.currentTime = Math.max(0, audio.currentTime - 5);
        persistPlaybackSession();
      }
    }
  });
}

// ==========================================
// 9. SYNCHRONIZED LYRICS
// ==========================================
async function fetchLyrics(track, token) {
  const container = $id('sheetViewLyrics');
  if (container) container.innerHTML = `<div class="lyrics-line active" style="opacity:0.6;">Syncing lyrics...</div>`;
  parsedLyrics = [];
  isSynced = false;
  lastActiveLyricIdx = -1;

  try {
    const res = await fetch(`/api/lyrics?video_id=${track.id}&title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}`);
    const data = await res.json();
    if (token !== activePlayToken) return;

    isSynced = !!data.synced;
    parsedLyrics = Array.isArray(data.lines) ? data.lines : [];

    if (container) {
      if (!parsedLyrics.length) {
        container.innerHTML = `<div class="lyrics-line" style="opacity:0.55; cursor:default; font-size:1.3rem;">Lyrics aren’t available for this track.</div>`;
        return;
      }
      container.innerHTML = '';
      parsedLyrics.forEach((l, idx) => {
        const div = document.createElement('div');
        div.className = 'lyrics-line';
        div.innerText = l.text;
        div.dataset.index = idx;
        div.onclick = () => {
          if (isSynced && typeof l.time === 'number') {
            const audio = $id('audio');
            if (audio) {
              audio.currentTime = l.time;
              isUserScrollingLyrics = false;
              updateLyricsSync();
            }
          }
        };
        container.appendChild(div);
      });
    }
  } catch (err) {
    if (token === activePlayToken && container) {
      container.innerHTML = `<div class="lyrics-line" style="opacity:0.55; cursor:default; font-size:1.3rem;">Lyrics aren’t available for this track.</div>`;
    }
  }
}

function updateLyricsSync() {
  if (!isSynced || !parsedLyrics || !parsedLyrics.length) return;
  const audio = $id('audio');
  if (!audio || isNaN(audio.currentTime)) return;

  const curTime = audio.currentTime;
  let activeIndex = -1;
  for (let i = parsedLyrics.length - 1; i >= 0; i--) {
    if (parsedLyrics[i].time <= curTime) {
      activeIndex = i;
      break;
    }
  }

  if (activeIndex !== lastActiveLyricIdx && activeIndex >= 0) {
    lastActiveLyricIdx = activeIndex;

    // A. Update Regular Player Sheet Lyrics
    const container = $id('sheetViewLyrics');
    if (container) {
      const lines = container.querySelectorAll('.lyrics-line');
      lines.forEach((line, idx) => {
        if (idx === activeIndex) {
          line.classList.add('active');
          if (!isUserScrollingLyrics) {
            const targetY = line.offsetTop - (container.clientHeight / 2) + (line.clientHeight / 2);
            container.scrollTo({ top: Math.max(0, targetY), behavior: 'smooth' });
          }
        } else {
          line.classList.remove('active');
        }
      });
    }

    // B. Update Cinematic View Lyrics
    const cineContainer = $id('cinematicLyricsScroll');
    if (cineContainer) {
      const cineLines = cineContainer.querySelectorAll('.cinematic-lyrics-line');
      cineLines.forEach((line, idx) => {
        if (idx === activeIndex) {
          line.classList.add('active');
          if (!isUserScrollingLyrics) {
            const targetY = line.offsetTop - (cineContainer.clientHeight / 2) + (line.clientHeight / 2);
            cineContainer.scrollTo({ top: Math.max(0, targetY), behavior: 'smooth' });
          }
        } else {
          line.classList.remove('active');
        }
      });
    }
  }
}

function initLyricsUserScroll() {
  const markUserScroll = () => {
    isUserScrollingLyrics = true;
    clearTimeout(lyricsScrollResumeTimer);
    lyricsScrollResumeTimer = setTimeout(() => {
      isUserScrollingLyrics = false;
      updateLyricsSync();
    }, 4000);
  };

  const container = $id('sheetViewLyrics');
  if (container) {
    container.addEventListener('wheel', markUserScroll, { passive: true });
    container.addEventListener('touchmove', markUserScroll, { passive: true });
  }

  const cineContainer = $id('cinematicLyricsScroll');
  if (cineContainer) {
    cineContainer.addEventListener('wheel', markUserScroll, { passive: true });
    cineContainer.addEventListener('touchmove', markUserScroll, { passive: true });
  }
}

// ==========================================
// 10. MEDIA SESSION & SLEEP TIMER
// ==========================================
function updateMediaSession(track) {
  if (!('mediaSession' in navigator)) return;
  try {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: track.title || 'Unknown Title',
      artist: track.artist || 'Unknown Artist',
      album: track.album || 'MELO Music',
      artwork: [
        { src: track.thumbnail || '', sizes: '96x96', type: 'image/jpeg' },
        { src: track.thumbnail || '', sizes: '128x128', type: 'image/jpeg' },
        { src: track.thumbnail || '', sizes: '256x256', type: 'image/jpeg' },
        { src: track.thumbnail || '', sizes: '512x512', type: 'image/jpeg' }
      ]
    });
    navigator.mediaSession.setActionHandler('play', () => togglePlay());
    navigator.mediaSession.setActionHandler('pause', () => togglePlay());
    navigator.mediaSession.setActionHandler('previoustrack', () => prevTrack());
    navigator.mediaSession.setActionHandler('nexttrack', () => nextTrack());
    navigator.mediaSession.setActionHandler('seekbackward', (details) => {
      const audio = $id('audio');
      if (audio) audio.currentTime = Math.max(0, audio.currentTime - (details.seekOffset || 10));
    });
    navigator.mediaSession.setActionHandler('seekforward', (details) => {
      const audio = $id('audio');
      if (audio && !isNaN(audio.duration)) audio.currentTime = Math.min(audio.duration, audio.currentTime + (details.seekOffset || 10));
    });
  } catch (e) {}
}

function actionSleepTimerPrompt() {
  closeContextMenu();
  const modal = $id('contextModal');
  if (!modal) return;
  const sheet = modal.querySelector('.context-modal-sheet');
  if (!sheet) return;

  sheet.innerHTML = `
    <div class="drag-handle" onclick="closeContextMenu()"></div>
    <div class="context-modal-title">Sleep Timer</div>
    <div class="context-action-list">
      <div class="context-action-row" onclick="setSleepTimerMinutes(5)"><span>5 Minutes</span></div>
      <div class="context-action-row" onclick="setSleepTimerMinutes(10)"><span>10 Minutes</span></div>
      <div class="context-action-row" onclick="setSleepTimerMinutes(15)"><span>15 Minutes</span></div>
      <div class="context-action-row" onclick="setSleepTimerMinutes(30)"><span>30 Minutes</span></div>
      <div class="context-action-row" onclick="setSleepTimerMinutes(45)"><span>45 Minutes</span></div>
      <div class="context-action-row" onclick="setSleepTimerMinutes(60)"><span>60 Minutes</span></div>
      <div class="context-action-row" onclick="setSleepTimerEndOfTrack()"><span>End of Song</span></div>
      ${sleepTimerEndsAt !== 0 ? `<div class="context-action-row" onclick="cancelSleepTimer()" style="color:#ff4d6d;"><span>Turn Off Timer</span></div>` : ''}
    </div>
  `;
  modal.classList.add('open');
}

function setSleepTimerMinutes(mins) {
  cancelSleepTimer();
  const ms = mins * 60 * 1000;
  sleepTimerEndsAt = Date.now() + ms;
  sleepTimerTimeout = setTimeout(() => {
    const audio = $id('audio');
    if (audio) audio.pause();
    setPlayState(false);
    showToast("Sleep timer reached. Good night!");
    cancelSleepTimer();
  }, ms);
  showToast(`Sleep timer set for ${mins} minutes`);
  closeContextMenu();
}

function setSleepTimerEndOfTrack() {
  cancelSleepTimer();
  sleepTimerEndsAt = -1;
  showToast("Sleep timer: Pausing at the end of this track");
  closeContextMenu();
}

function cancelSleepTimer() {
  if (sleepTimerTimeout) clearTimeout(sleepTimerTimeout);
  sleepTimerTimeout = null;
  sleepTimerEndsAt = 0;
  showToast("Sleep timer turned off");
  closeContextMenu();
}

// ==========================================
// 11. QUEUE HANDLING
// ==========================================
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
          if ($id('tabQueue')?.classList.contains('active')) {
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

function switchPlayerSheetTab(tab) {
  document.querySelectorAll('.sheet-tab-btn').forEach(b => b.classList.remove('active'));
  const playView = $id('sheetViewPlaying');
  const lyricsView = $id('sheetViewLyrics');
  const queueView = $id('sheetViewQueue');

  if (playView) playView.style.display = 'none';
  if (lyricsView) lyricsView.style.display = 'none';
  if (queueView) queueView.style.display = 'none';

  if (tab === 'playing') {
    $id('tabNowPlaying')?.classList.add('active');
    if (playView) playView.style.display = 'flex';
  } else if (tab === 'lyrics') {
    $id('tabLyrics')?.classList.add('active');
    if (lyricsView) lyricsView.style.display = 'flex';
  } else if (tab === 'queue') {
    $id('tabQueue')?.classList.add('active');
    if (queueView) queueView.style.display = 'flex';
    renderSheetQueueList();
  }
}

function renderSheetQueueList() {
  const qView = $id('sheetViewQueue');
  if (!qView) return;
  qView.innerHTML = '';

  const currentTrack = playlist[currentIndex];
  if (currentTrack) {
    window.__contextTrackMap[currentTrack.id] = currentTrack;
    const nowPlayingEl = document.createElement('div');
    nowPlayingEl.className = 'queue-row queue-now-playing';
    nowPlayingEl.innerHTML = `
      <div class="queue-left-block">
        <img class="queue-thumb" src="${currentTrack.thumbnail || ''}" loading="lazy" />
        <div class="queue-info">
          <span class="queue-now-label">NOW PLAYING</span>
          <div class="queue-title">${accountText(currentTrack.title)}</div>
          <div class="queue-artist">${accountText(currentTrack.artist)}</div>
        </div>
      </div>
    `;
    qView.appendChild(nowPlayingEl);
  }

  const upNextHeader = document.createElement('div');
  upNextHeader.className = 'queue-section-header queue-up-next-header';
  upNextHeader.style.display = 'flex';
  upNextHeader.style.justifyContent = 'space-between';
  upNextHeader.style.alignItems = 'center';
  upNextHeader.innerHTML = `
    <span>Up Next</span>
    ${playlist.length > currentIndex + 1 ? `<button class="queue-clear-btn" onclick="clearQueue()">Clear</button>` : ''}
  `;
  qView.appendChild(upNextHeader);

  const remaining = playlist.slice(currentIndex + 1);
  if (!remaining.length) {
    const empty = document.createElement('div');
    empty.className = 'queue-empty-state';
    empty.innerText = 'No more songs in queue. Recommendations will expand as you listen.';
    qView.appendChild(empty);
    checkAndExpandInfiniteQueue();
    return;
  }

  remaining.forEach((t, i) => {
    const globalIdx = currentIndex + 1 + i;
    window.__contextTrackMap[t.id] = t;
    const isFav = !!favorites[t.id];
    const item = document.createElement('div');
    item.className = 'queue-row queue-draggable';
    item.draggable = true;
    item.addEventListener('dragstart', (e) => e.dataTransfer.setData('text/plain', String(globalIdx)));
    item.addEventListener('dragover', (e) => e.preventDefault());
    item.addEventListener('drop', (e) => {
      e.preventDefault();
      const fromIdx = Number(e.dataTransfer.getData('text/plain'));
      moveQueueTrack(fromIdx, globalIdx);
    });

    item.onclick = () => playIndex(globalIdx);
    item.innerHTML = `
      <span class="queue-drag">&#8801;</span>
      <div class="queue-left-block">
        <img class="queue-thumb" src="${t.thumbnail || ''}" loading="lazy" />
        <div class="queue-info">
          <div class="queue-title">${accountText(t.title)}</div>
          <div class="queue-artist">${accountText(t.artist)}</div>
        </div>
      </div>
      <div class="queue-actions-cluster">
        <button class="queue-action-btn" title="Favorite" onclick="event.stopPropagation(); toggleFavTrackDirect('${t.id}', this)">
          ${isFav ? '♥' : '♡'}
        </button>
        <button class="queue-action-btn" title="Add to Playlist" onclick="event.stopPropagation(); actionOpenAddToPlaylist(window.__contextTrackMap['${t.id}'])">
          +
        </button>
        <button class="queue-action-btn" title="Remove" onclick="event.stopPropagation(); removeFromQueue(${globalIdx})">
          ×
        </button>
      </div>
    `;
    qView.appendChild(item);
  });
}

function removeFromQueue(index) {
  if (index <= currentIndex || index >= playlist.length) return;
  playlist.splice(index, 1);
  persistPlaybackQueue();
  renderSheetQueueList();
}

function moveQueueTrack(from, to) {
  if (from <= currentIndex || to <= currentIndex || from === to) return;
  const [track] = playlist.splice(from, 1);
  playlist.splice(to, 0, track);
  persistPlaybackQueue();
  renderSheetQueueList();
}

function clearQueue() {
  playlist = playlist.slice(0, Math.max(0, currentIndex + 1));
  persistPlaybackQueue();
  renderSheetQueueList();
  showToast('Up next cleared');
}

function reorderPlaylistTrack(playlistId, from, to) {
  const tgt = playlists[playlistId];
  if (!tgt || from === to || from < 0 || to < 0) return;
  const [track] = tgt.tracks.splice(from, 1);
  tgt.tracks.splice(to, 0, track);
  tgt.updated_at = new Date().toISOString();
  store.savePlaylists(playlists);
  if (activeView === 'playlist-detail') openPlaylistDetails(playlistId);
}

// ==========================================
// 12. SEARCH & DISCOVERY ENGINE
// ==========================================
async function loadSearchShelf(query, containerId = 'searchGrid', limit = 6) {
  const el = $id(containerId);
  if (el) el.innerHTML = createMeloLoader();

  try {
    const res = await fetch(`/api/search?query=${encodeURIComponent(query)}`);
    const data = await res.json();
    const items = (data.results || []).map(normalizeTrackData);
    categoryData[containerId] = items;
    renderGridContainer(containerId, items.slice(0, limit));
  } catch (err) {
    if (el) el.innerHTML = '';
  }
}

function renderSearchView() {
  // Direct guard: If offline, route to offline search page immediately
  if (!navigator.onLine) {
    return renderSearchOfflineView();
  }

  const vc = $id('viewContainer');
  if (!vc) return;
  vc.innerHTML = `
    <div class="stage-content" id="searchStageContent">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" title="Back"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button>
        <h1>Search</h1>
      </div>
      <div class="search-hero-zone">
        <div class="search-glass-capsule">
          <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>
          <input type="text" id="dedicatedSearchInput" class="search-glass-input" placeholder="Songs, Bollywood artists, lyrics, albums..." autocomplete="off" value="${accountText(globalSearchState.query)}" />
          <button class="search-clear-btn" id="searchClearBtn" onclick="clearSearchInput()" style="display: ${globalSearchState.query ? 'flex' : 'none'};">✕</button>
        </div>
      </div>

      <div class="vibe-chips-row" id="searchFiltersRow" style="display: ${globalSearchState.query ? 'flex' : 'none'};">
        <button class="vibe-chip ${globalSearchState.filter === 'all' ? 'active' : ''}" onclick="applySearchFilter('all', this)">All</button>
        <button class="vibe-chip ${globalSearchState.filter === 'songs' ? 'active' : ''}" onclick="applySearchFilter('songs', this)">Songs</button>
        <button class="vibe-chip ${globalSearchState.filter === 'albums' ? 'active' : ''}" onclick="applySearchFilter('albums', this)">Albums</button>
        <button class="vibe-chip ${globalSearchState.filter === 'artists' ? 'active' : ''}" onclick="applySearchFilter('artists', this)">Artists</button>
      </div>

      <div id="recentSearchesPanel" style="display: ${globalSearchState.query ? 'none' : 'block'};"></div>
      
      <div id="dynamicSearchResultsArea">
        <div class="section-heading" id="searchResultsHeading"><h2 id="searchResultsTitle">Trending Recommendations</h2></div>
        <div class="capsule-grid" id="searchGrid" style="margin-bottom:24px;"></div>
      </div>
      
      <div id="defaultExploreArea" style="display: ${globalSearchState.query ? 'none' : 'block'};">
        <div class="section-heading"><h2>Explore by Mood & Genre</h2></div>
        <div class="search-mood-cards">
          <div class="mood-card" onclick="quickSearch('Bollywood Romantic Melodies')" style="background-color: #e11d48;"><span>Romance</span><span class="mood-icon">🤍</span></div>
          <div class="mood-card" onclick="quickSearch('Diljit Dosanjh Punjabi Hits')" style="background-color: #ea580c;"><span>Punjabi Wave</span><span class="mood-icon">🪩</span></div>
          <div class="mood-card" onclick="quickSearch('Desi Hip Hop India 2026')" style="background-color: #7c3aed;"><span>Desi Rap</span><span class="mood-icon">🎙️</span></div>
          <div class="mood-card" onclick="quickSearch('Indian Indie Acoustic Chill')" style="background-color: #2563eb;"><span>Indie Chill</span><span class="mood-icon">🌙</span></div>
        </div>
      </div>
    </div>`;

  const input = $id('dedicatedSearchInput');
  const clearBtn = $id('searchClearBtn');
  let searchTimer = null;
  renderRecentSearches();

  if (input) {
    input.addEventListener('focus', () => { if (input.value.trim()) $id('searchStageContent')?.classList.add('is-searching'); });
    input.addEventListener('input', () => {
      clearTimeout(searchTimer);
      const q = input.value.trim();
      globalSearchState.query = q;
      if (clearBtn) clearBtn.style.display = q ? 'flex' : 'none';
      if (!q) {
        $id('searchStageContent')?.classList.remove('is-searching');
        clearSearchInput();
        return;
      }
      $id('searchStageContent')?.classList.add('is-searching');
      if ($id('recentSearchesPanel')) $id('recentSearchesPanel').style.display = 'none';
      if ($id('defaultExploreArea')) $id('defaultExploreArea').style.display = 'none';
      if ($id('searchFiltersRow')) $id('searchFiltersRow').style.display = 'flex';
      searchTimer = setTimeout(() => {
        store.rememberSearch(q);
        if ($id('searchResultsTitle')) $id('searchResultsTitle').innerText = `Results for "${q}"`;
        executeLiveSearch(q);
      }, 250);
    });
  }

  if (globalSearchState.query) executeLiveSearch(globalSearchState.query);
  else loadSearchShelf('Bollywood Trending 2026', 'searchGrid', 12);

  if (globalSearchState.scrollY > 0) {
    setTimeout(() => { $id('mainViewport').scrollTop = globalSearchState.scrollY; }, 50);
  }
  $id('mainViewport').addEventListener('scroll', () => {
    if (activeView === 'search') globalSearchState.scrollY = $id('mainViewport').scrollTop;
  });
}

function applySearchFilter(filterStr, btnObj) {
  globalSearchState.filter = filterStr;
  document.querySelectorAll('#searchFiltersRow .vibe-chip').forEach(el => el.classList.remove('active'));
  if (btnObj) btnObj.classList.add('active');
  if (globalSearchState.query) executeLiveSearch(globalSearchState.query);
}

async function executeLiveSearch(query) {
  const area = $id('dynamicSearchResultsArea');
  if (!area) return;
  if (currentSearchAbort) currentSearchAbort.abort();
  currentSearchAbort = new AbortController();

  // Only render the loader if the network request takes longer than 200ms
  const loaderTimer = setTimeout(() => {
    area.innerHTML = createMeloLoader();
  }, 200);

  try {
    const res = await fetch(`/api/search?query=${encodeURIComponent(query)}`, { signal: currentSearchAbort.signal });
    const data = await res.json();
    clearTimeout(loaderTimer);

    const items = (data.results || []).map(normalizeTrackData);
    if (!items.length) {
      area.innerHTML = `<div class="library-empty compact" style="margin-top:20px;"><strong>No results found</strong><span>Try checking your spelling or using different keywords.</span></div>`;
      return;
    }

    const topItem = items[0];
    const isSongSearch = globalSearchState.filter === 'all' || globalSearchState.filter === 'songs';
    const uniqueAlbums = [], uniqueArtists = [], seenAlbums = new Set(), seenArtists = new Set();

    items.forEach(track => {
      if (track.album && !seenAlbums.has(track.album)) {
        seenAlbums.add(track.album);
        uniqueAlbums.push({ type: 'album', title: track.album, artist: track.artist, thumbnail: track.thumbnail });
      }
      if (track.artist) {
        const mainArt = track.artist.split(',')[0].trim();
        if (!seenArtists.has(mainArt)) {
          seenArtists.add(mainArt);
          uniqueArtists.push({ type: 'artist', title: mainArt, thumbnail: track.thumbnail });
        }
      }
    });

    let html = '';
    if (globalSearchState.filter === 'all') {
      window.__contextTrackMap[topItem.id] = topItem;
      html += `
        <div class="section-heading" style="margin-top:10px;"><h2>Top Result</h2></div>
        <div class="hub-vault-card" onclick="playlist=[window.__contextTrackMap['${topItem.id}']]; currentPlaylistContextId=null; playIndex(0);" style="margin-bottom:24px;">
          <img src="${topItem.thumbnail}" style="width:72px;height:72px;border-radius:12px;object-fit:cover;"/>
          <div style="flex:1;">
            <div style="font-size:1.1rem;font-weight:800;color:#fff;">${accountText(topItem.title)}</div>
            <div style="font-size:0.85rem;color:var(--text-muted);margin-top:4px;">Song · ${accountText(topItem.artist)}</div>
          </div>
          <button class="play-bubble" style="position:relative;opacity:1;transform:none;" onclick="event.stopPropagation(); playlist=[window.__contextTrackMap['${topItem.id}']]; currentPlaylistContextId=null; playIndex(0);">
            <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
          </button>
        </div>
      `;
    }

    if (isSongSearch) {
      html += `<div class="section-heading"><h2>Songs</h2></div><div style="margin-bottom:24px;">`;
      items.slice(globalSearchState.filter === 'all' ? 1 : 0, 10).forEach((track, idx) => {
        html += libraryTrackRow(track, idx, { source: 'search_results' });
      });
      html += `</div>`;
      window.__lovedTracks = items;
    }

    if (globalSearchState.filter === 'all' || globalSearchState.filter === 'albums') {
      html += `<div class="section-heading"><h2>Albums & Collections</h2></div><div class="capsule-grid" id="searchAlbumsGrid" style="margin-bottom:24px;"></div>`;
      categoryData['searchAlbumsGrid'] = uniqueAlbums.slice(0, 6);
    }

    if (globalSearchState.filter === 'all' || globalSearchState.filter === 'artists') {
      html += `<div class="section-heading"><h2>Artists</h2></div><div class="capsule-grid" id="searchArtistsGrid" style="margin-bottom:24px;"></div>`;
      categoryData['searchArtistsGrid'] = uniqueArtists.slice(0, 6);
    }

    area.innerHTML = html;
    if ($id('searchAlbumsGrid')) renderSimulatedGrid('searchAlbumsGrid', uniqueAlbums.slice(0, 6), 'album');
    if ($id('searchArtistsGrid')) renderSimulatedGrid('searchArtistsGrid', uniqueArtists.slice(0, 6), 'artist');
  } catch (err) {
    clearTimeout(loaderTimer);
    if (err.name !== 'AbortError') {
      area.innerHTML = `<div class="library-empty compact" style="margin-top:20px;"><strong>Connection Error</strong><span>Unable to reach MELO servers. Please try again.</span><button class="pill-action-btn" onclick="executeLiveSearch('${accountText(query)}')" style="margin-top:12px;">Retry</button></div>`;
    }
  }
}

function renderSimulatedGrid(containerId, items, type) {
  const c = $id(containerId);
  if (!c) return;
  c.innerHTML = '';
  items.forEach((item) => {
    const el = document.createElement('div');
    el.className = 'poster-item';
    el.onclick = () => {
      if (type === 'album') openAlbumView(item.title, item.artist, item.thumbnail);
      else openArtistView(item.title, item.thumbnail);
    };
    el.innerHTML = `
      <div class="poster-wrap" style="${type === 'artist' ? 'border-radius:50%;' : ''}">
        <img class="poster-img" src="${item.thumbnail || ''}" loading="lazy" />
      </div>
      <div class="poster-title" style="${type === 'artist' ? 'text-align:center;' : ''}">${accountText(item.title)}</div>
      <div class="poster-subtitle" style="${type === 'artist' ? 'text-align:center;' : ''}">${type === 'artist' ? 'Artist' : accountText(item.artist)}</div>
    `;
    c.appendChild(el);
  });
}

function openArtistView(artistName, thumb) {
  activeView = 'artist-detail';
  navigationHistory.push('artist-detail');
  const vc = $id('viewContainer');
  vc.innerHTML = `
    <div class="playlist-immersive-view">
      <div class="playlist-immersive-hero" style="background-image: url('${thumb}');">
        <div class="playlist-top-nav-row">
          <button class="circle-back-btn" onclick="goBack()" title="Back"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button>
        </div>
        <div class="playlist-backdrop-content">
          <span class="pl-meta-tag">Artist</span>
          <div class="playlist-immersive-title-row"><h1 class="playlist-immersive-title">${accountText(artistName)}</h1></div>
        </div>
      </div>
      <div class="playlist-action-bar-strip">
        <div class="playlist-immersive-actions">
          <button class="pill-action-btn pl-play-all-btn" onclick="quickSearch('${accountText(artistName)}')">
            <svg viewBox="0 0 24 24" style="width:16px;height:16px;fill:#000;"><path d="M8 5v14l11-7z"/></svg>
            <span>Play Popular</span>
          </button>
        </div>
      </div>
      <div class="playlist-tracks-section">
        <div class="section-heading"><h2>Popular Tracks</h2></div>
        <div id="artistTracksBox">${createMeloLoader()}</div>
      </div>
    </div>`;
  applyPlaylistDynamicColors(thumb, artistName);
  fetch(`/api/search?query=${encodeURIComponent(artistName)}`).then(r => r.json()).then(d => {
    const tks = (d.results || []).map(normalizeTrackData).slice(0, 10);
    window.__lovedTracks = tks;
    $id('artistTracksBox').innerHTML = tks.length ? tks.map((t, i) => libraryTrackRow(t, i, { source: 'search_results' })).join('') : '<p>No tracks found.</p>';
  });
}

function openAlbumView(albumName, artistName, thumb) {
  activeView = 'album-detail';
  navigationHistory.push('album-detail');
  const vc = $id('viewContainer');
  vc.innerHTML = `
    <div class="playlist-immersive-view">
      <div class="playlist-immersive-hero" style="background-image: url('${thumb}');">
        <div class="playlist-top-nav-row">
          <button class="circle-back-btn" onclick="goBack()" title="Back"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button>
        </div>
        <div class="playlist-backdrop-content">
          <span class="pl-meta-tag">Album</span>
          <div class="playlist-immersive-title-row"><h1 class="playlist-immersive-title">${accountText(albumName)}</h1></div>
          <div class="playlist-immersive-stats">${accountText(artistName)}</div>
        </div>
      </div>
      <div class="playlist-action-bar-strip">
        <div class="playlist-immersive-actions">
          <button class="pill-action-btn pl-play-all-btn" onclick="quickSearch('${accountText(albumName + ' ' + artistName)}')">
            <svg viewBox="0 0 24 24" style="width:16px;height:16px;fill:#000;"><path d="M8 5v14l11-7z"/></svg>
            <span>Play Album</span>
          </button>
        </div>
      </div>
      <div class="playlist-tracks-section">
        <div class="section-heading"><h2>Tracklist</h2></div>
        <div id="albumTracksBox">${createMeloLoader()}</div>
      </div>
    </div>`;
  applyPlaylistDynamicColors(thumb, albumName);
  fetch(`/api/search?query=${encodeURIComponent(albumName + ' ' + artistName)}`).then(r => r.json()).then(d => {
    const tks = (d.results || []).map(normalizeTrackData).filter(t => t.album && t.album.includes(albumName)).slice(0, 15);
    window.__lovedTracks = tks.length ? tks : (d.results || []).map(normalizeTrackData).slice(0, 10);
    $id('albumTracksBox').innerHTML = window.__lovedTracks.length ? window.__lovedTracks.map((t, i) => libraryTrackRow(t, i, { source: 'search_results' })).join('') : '<p>No tracks found.</p>';
  });
}

function clearSearchInput() {
  globalSearchState.query = '';
  const input = $id('dedicatedSearchInput');
  const clearBtn = $id('searchClearBtn');
  const stage = $id('searchStageContent');
  if (input) { input.value = ''; input.focus(); }
  if (clearBtn) clearBtn.style.display = 'none';
  stage?.classList.remove('is-searching');

  if ($id('recentSearchesPanel')) $id('recentSearchesPanel').style.display = 'block';
  if ($id('defaultExploreArea')) $id('defaultExploreArea').style.display = 'block';
  if ($id('searchFiltersRow')) $id('searchFiltersRow').style.display = 'none';

  const area = $id('dynamicSearchResultsArea');
  if (area) {
    area.innerHTML = `<div class="section-heading" id="searchResultsHeading"><h2 id="searchResultsTitle">Trending Recommendations</h2></div><div class="capsule-grid" id="searchGrid" style="margin-bottom:24px;"></div>`;
    loadSearchShelf('Bollywood Trending 2026', 'searchGrid', 12);
  }
}

function quickSearch(query) {
  store.rememberSearch(query);
  globalSearchState.query = query;
  const input = $id('dedicatedSearchInput');
  const clearBtn = $id('searchClearBtn');
  const stage = $id('searchStageContent');
  if (input) { input.value = query; if (clearBtn) clearBtn.style.display = 'flex'; }
  stage?.classList.add('is-searching');
  if ($id('recentSearchesPanel')) $id('recentSearchesPanel').style.display = 'none';
  if ($id('defaultExploreArea')) $id('defaultExploreArea').style.display = 'none';
  if ($id('searchFiltersRow')) $id('searchFiltersRow').style.display = 'flex';
  executeLiveSearch(query);
}

function renderRecentSearches() {
  const panel = $id('recentSearchesPanel');
  if (!panel) return;
  const searches = store.getSearchHistory();
  panel.innerHTML = searches.length
    ? `<div class="section-heading recent-search-heading"><h2>Recent Searches</h2><a onclick="store.clearSearches(); renderRecentSearches()">Clear all</a></div><div class="recent-search-list">${searches.map(item => `<button class="recent-search-chip" onclick="quickSearch(${JSON.stringify(item.query).replace(/"/g, '&quot;')})">${accountText(item.query)}<span onclick="event.stopPropagation(); store.removeSearch(${JSON.stringify(item.query).replace(/"/g, '&quot;')}); renderRecentSearches()">×</span></button>`).join('')}</div>`
    : '';
}

// ==========================================
// MINIMAL OFFLINE HOME VIEW (RESTORED SVG)
// ==========================================
function renderHomeOfflineView() {
  const vc = $id('viewContainer');
  if (!vc) return;

  const count = (typeof playlists !== 'undefined' && playlists?.['pl-downloads']?.tracks?.length) || 0;

  vc.innerHTML = `
    <div class="stage-content">
      <!-- Standard Top Brand Bar -->
      <div class="home-brand-header">
        <div class="brand-capsule" style="background: transparent; border: none; padding: 0; display: flex; align-items: center;">
          <img src="/static/images/melo-text.png" alt="MELO" style="height: 34px; width: auto; object-fit: contain;" />
        </div>
        <button class="sheet-icon-btn" onclick="openSettingsModal()" title="Settings">
          <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z"/></svg>
        </button>
      </div>

      <!-- Frameless Centered Offline Content -->
      <div class="melo-offline-minimal">
        <div class="offline-svg-wrapper">
          <svg class="melo-offline-illustration" viewBox="0 0 200 180" fill="none" xmlns="http://www.w3.org/2000/svg">
            <!-- Glow backdrop -->
            <ellipse cx="100" cy="90" rx="65" ry="50" fill="rgba(250, 45, 72, 0.12)" filter="blur(14px)" />
            
            <!-- Broken Signal Waves -->
            <path class="wave-static wave-1" d="M30 45 C 50 30, 70 30, 90 45" stroke="#fa2d48" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="4 6" />
            <path class="wave-static wave-2" d="M110 45 C 130 30, 150 30, 170 45" stroke="#fa2d48" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="4 6" />
            
            <!-- Cassette Body -->
            <rect x="35" y="55" width="130" height="82" rx="10" fill="#16181f" stroke="#2a2d3d" stroke-width="2" />
            <rect x="43" y="63" width="114" height="66" rx="6" fill="#0f1015" />

            <!-- Tape Label -->
            <path d="M50 70 H150 V100 H50 Z" fill="#fa2d48" fill-opacity="0.15" />
            <line x1="50" y1="85" x2="150" y2="85" stroke="#fa2d48" stroke-width="1.5" stroke-opacity="0.4" />

            <!-- Spools / Eyes -->
            <circle cx="75" cy="95" r="14" fill="#1c1e27" stroke="#3b3f54" stroke-width="2" />
            <circle cx="125" cy="95" r="14" fill="#1c1e27" stroke="#3b3f54" stroke-width="2" />
            
            <!-- Dizzy Spool Teeth -->
            <path class="spool-x" d="M70 90 L80 100 M80 90 L70 100" stroke="#fa2d48" stroke-width="2.2" stroke-linecap="round" />
            <path class="spool-x" d="M120 90 L130 100 M130 90 L120 100" stroke="#fa2d48" stroke-width="2.2" stroke-linecap="round" />

            <!-- Tangled Ribbon Tongue -->
            <path class="tape-tongue" d="M85 116 C 90 125, 110 125, 115 116" stroke="#fa2d48" stroke-width="2.5" stroke-linecap="round" fill="none" />
            <path class="tangled-thread" d="M96 122 C 90 140, 115 145, 100 160 C 95 166, 85 158, 80 168" stroke="#ff4d6d" stroke-width="2" stroke-linecap="round" fill="none" />

            <!-- Loose Plug & Floating Sparks -->
            <circle class="sparkle sp-1" cx="38" cy="135" r="2" fill="#fa2d48" />
            <circle class="sparkle sp-2" cx="162" cy="70" r="2.5" fill="#ff4d6d" />
            <path class="plug-loose" d="M165 140 L175 148 M172 138 L180 144" stroke="#5a5e73" stroke-width="2" stroke-linecap="round" />
          </svg>
        </div>

        <div class="melo-offline-info">
          <span class="offline-badge">SIGNAL LOST</span>
          <h2 class="melo-offline-title">Tape’s Tangled.</h2>
          <p class="melo-offline-desc">Internet ghosted us, but your vault didn’t.</p>
        </div>

        <button class="melo-offline-btn" onclick="switchView('offline')">
          <svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          <span>Downloaded Songs</span>
          <span class="melo-offline-count">${count}</span>
        </button>
      </div>
    </div>
  `;

  setupBottomGlowWatcher();
}

window.renderHomeOfflineView = renderHomeOfflineView;

function handleHomeScroll(el) {
  const glow = $id('homeBottomGlow');
  if (!glow) return;
  if (el.scrollTop > 30) {
    glow.classList.add('visible');
  } else {
    glow.classList.remove('visible');
  }
}

window.renderHomeOfflineView = renderHomeOfflineView;
window.handleHomeScroll = handleHomeScroll;

// ==========================================
// 13. DISCOVERY HOME
// ==========================================
function checkConnectionAndReload() {
  if (navigator.onLine) {
    showToast("Reconnected! Tuning back in...");
    renderHomeView();
  } else {
    showToast("Still offline. Check Wi-Fi or mobile data.");
  }
}

window.renderHomeOfflineView = renderHomeOfflineView;
window.checkConnectionAndReload = checkConnectionAndReload;

function initQuickPicksObserver() {
  const spotlight = $id('quickPicksSpotlightBox');
  if (!spotlight || !('IntersectionObserver' in window)) return;

  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        spotlight.classList.remove('paused');
      } else {
        spotlight.classList.add('paused');
      }
    });
  }, { threshold: 0.1 });

  observer.observe(spotlight);
}

function initQuickPicksDots(containerId = 'quickPicksGrid', dotsContainerId = 'quickPicksDots') {
  const grid = $id(containerId);
  const dotsContainer = $id(dotsContainerId);
  if (!grid || !dotsContainer) return;

  const items = grid.children;
  if (!items.length) {
    dotsContainer.innerHTML = '';
    return;
  }

  dotsContainer.innerHTML = Array.from(items).map((_, i) => `<span class="qp-dot ${i === 0 ? 'active' : ''}" onclick="scrollQuickPicksTo(${i})"></span>`).join('');
}

function scrollQuickPicksTo(index) {
  const grid = $id('quickPicksGrid');
  if (!grid) return;
  const items = grid.children;
  if (items[index]) {
    grid.scrollTo({ left: items[index].offsetLeft - grid.offsetLeft, behavior: 'smooth' });
  }
}

function updateQuickPicksDots() {
  const grid = $id('quickPicksGrid');
  const dotsContainer = $id('quickPicksDots');
  if (!grid || !dotsContainer) return;

  const items = grid.children;
  if (!items.length) return;

  const scrollLeft = grid.scrollLeft;
  let closestIndex = 0;
  let minDiff = Infinity;

  Array.from(items).forEach((item, i) => {
    const diff = Math.abs(item.offsetLeft - grid.offsetLeft - scrollLeft);
    if (diff < minDiff) {
      minDiff = diff;
      closestIndex = i;
    }
  });

  const dots = dotsContainer.children;
  Array.from(dots).forEach((dot, i) => {
    dot.classList.toggle('active', i === closestIndex);
  });
}
window.scrollQuickPicksTo = scrollQuickPicksTo;
window.updateQuickPicksDots = updateQuickPicksDots;
window.initQuickPicksObserver = initQuickPicksObserver;

function loadForYouCatalog(query = 'Flow') {
  return loadPersonalizedForYou(typeof query === 'string' ? query : 'Flow');
}
window.loadForYouCatalog = loadForYouCatalog;

// ==========================================
// BOTTOM SCROLL RED GLOW ENGINE
// ==========================================
function setupBottomGlowWatcher() {
  let glow = document.getElementById('homeBottomGlow');
  if (!glow) {
    glow = document.createElement('div');
    glow.id = 'homeBottomGlow';
    document.body.appendChild(glow);
  }

  const updateGlow = () => {
    const vc = document.getElementById('viewContainer');
    const scrollPositions = [
      window.scrollY || 0,
      document.documentElement.scrollTop || 0,
      document.body.scrollTop || 0,
      vc ? vc.scrollTop : 0
    ];
    const maxScroll = Math.max(...scrollPositions);

    if (maxScroll > 20) {
      glow.classList.add('visible');
    } else {
      glow.classList.remove('visible');
    }
  };

  // The 'true' capture flag catches scrolling inside viewContainer or any nested div
  window.removeEventListener('scroll', updateGlow, true);
  window.addEventListener('scroll', updateGlow, true);
  updateGlow();
}
window.setupBottomGlowWatcher = setupBottomGlowWatcher;

// Global switchView router in case other buttons call it
// Current view tracker
let currentView = 'home';

function routeHomeView() {
  currentView = 'home';
  if (!navigator.onLine) {
    if (typeof renderHomeOfflineView === 'function') {
      renderHomeOfflineView();
    }
  } else {
    if (typeof renderHomeView === 'function') {
      renderHomeView();
    }
  }
}
window.routeHomeView = routeHomeView;

// ==========================================
// 3. CORE NAVIGATION & ROBUST VIEW STACK
// ==========================================

// Dedicated navigation stack to track where the user actually came from
let viewStack = ['home'];

function updateNavActiveStates(viewName) {
  const idMap = {
    'home': ['navHome', 'mNavHome'],
    'search': ['navSearch', 'mNavSearch'],
    'favorites': ['navFavs', 'mNavFavs'],
    'loved': ['navFavs', 'mNavFavs'],
    'history': ['navFavs', 'mNavFavs'],
    'offline': ['navFavs', 'mNavFavs']
  };

  document.querySelectorAll('.capsule-btn, .pill-nav-item').forEach(btn => btn.classList.remove('active'));
  const activeIds = idMap[viewName] || [];
  activeIds.forEach(id => $id(id)?.classList.add('active'));
}

function switchView(view, pushState = true) {
  updateOfflinePlayerVisibility();

  // If selecting a root tab from navigation bar, reset the stack
  if (['home', 'search', 'favorites'].includes(view) && pushState) {
    viewStack = [view];
  } else if (pushState && activeView !== view) {
    viewStack.push(activeView);
  }

  activeView = view;
  currentView = view;
  updateNavActiveStates(view);

  const run = () => {
    if (view === 'home') {
      if (!navigator.onLine) {
        renderHomeOfflineView();
      } else {
        renderHomeView();
      }
    } else if (view === 'search') {
      if (!navigator.onLine) {
        renderSearchOfflineView();
      } else {
        renderSearchView();
      }
    } else if (view === 'favorites' || view === 'library') {
      if (!navigator.onLine) {
        renderHubOfflineView();
      } else {
        renderFavoritesView();
      }
    } else if (view === 'loved') {
      renderLovedTracks();
    } else if (view === 'history') {
      renderHistoryView();
    } else if (view === 'offline') {
      renderOfflineVault();
    } else if (view === 'account') {
      renderAccountView();
    } else if (view === 'player' || view === 'nowplaying' || view === 'fullscreen') {
      openFullscreenPlayer();
    }

    if (!['playlist-detail', 'album-detail', 'artist-detail'].includes(view)) {
      document.documentElement.style.removeProperty('--pl-dynamic-bg');
      document.documentElement.style.removeProperty('--pl-dynamic-mid');
      document.documentElement.style.removeProperty('--pl-dynamic-deep');
      document.documentElement.style.removeProperty('--pl-dynamic-accent');
    }

    if (view !== 'player' && view !== 'nowplaying' && view !== 'fullscreen') {
      closeFullscreenPlayer();
    }

    const vp = $id('mainViewport');
    if (vp) vp.scrollTop = 0;
  };

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!reduceMotion && document.startViewTransition) {
    try {
      const transition = document.startViewTransition(run);
      transition.catch((err) => {
        if (err.name !== 'InvalidStateError' && err.name !== 'AbortError') {
          console.warn('ViewTransition failed:', err);
        }
      });
    } catch (e) {
      run();
    }
  } else {
    run();
  }
}
window.switchView = switchView;

function goBack() {
  // If we are in sub-views of the hub, return directly to Music Hub
  const hubSubViews = ['loved', 'history', 'offline'];
  if (hubSubViews.includes(activeView)) {
    switchView('favorites', false);
    return;
  }

  // Pop from our internal view stack
  if (viewStack.length > 0) {
    let prev = viewStack.pop();
    
    // Avoid routing to self
    while (prev === activeView && viewStack.length > 0) {
      prev = viewStack.pop();
    }

    if (prev && prev !== activeView) {
      switchView(prev, false);
      return;
    }
  }

  // Fallback: Default to Music Hub if playlist, otherwise Home
  if (['playlist-detail', 'album-detail', 'artist-detail'].includes(activeView)) {
    switchView('favorites', false);
  } else {
    switchView('home', false);
  }
}
window.goBack = goBack;

// Listen for network state drops/restores across all primary tabs
window.addEventListener('offline', () => {
  if (currentView === 'home') {
    if (typeof renderHomeOfflineView === 'function') renderHomeOfflineView();
  } else if (currentView === 'search') {
    if (typeof renderSearchOfflineView === 'function') renderSearchOfflineView();
  } else if (currentView === 'favorites' || currentView === 'library') {
    if (typeof renderHubOfflineView === 'function') renderHubOfflineView();
  }
});

window.addEventListener('online', () => {
  if (currentView === 'home') {
    if (typeof renderHomeView === 'function') renderHomeView();
  } else if (currentView === 'search') {
    if (typeof renderSearchView === 'function') renderSearchView();
  } else if (currentView === 'favorites' || currentView === 'library') {
    if (typeof renderFavoritesView === 'function') {
      renderFavoritesView();
    } else if (typeof renderLibraryView === 'function') {
      renderLibraryView();
    }
  }
});

function renderHubOfflineView() {
  const vc = $id('viewContainer');
  if (!vc) return;

  const count = (typeof playlists !== 'undefined' && playlists?.['pl-downloads']?.tracks?.length) || 0;

  vc.innerHTML = `
    <div class="stage-content">
      <!-- Clean Melo Hub Header -->
      <div class="hub-clean-header">
        <div class="hub-header-top">
          <h1 class="hub-clean-title">Music Hub</h1>
          <span class="hub-status-chip">Offline</span>
        </div>
      </div>

      <!-- Minimal Ambient Hub Section -->
      <div class="hub-hero-container">
        <!-- Frameless Cute White Puppy Artwork -->
        <div class="hub-puppy-wrap">
          <svg viewBox="0 0 160 140" class="hub-puppy-svg" fill="none" xmlns="http://www.w3.org/2000/svg">
            <!-- Ambient Underglow -->
            <ellipse cx="80" cy="80" rx="55" ry="38" fill="rgba(250, 45, 72, 0.12)" filter="blur(16px)" />

            <!-- Headphone Band -->
            <path d="M42 55 C 42 22, 118 22, 118 55" stroke="#ffffff" stroke-width="3" stroke-linecap="round"/>
            <rect x="34" y="52" width="9" height="18" rx="4.5" fill="#ffffff"/>
            <rect x="117" y="52" width="9" height="18" rx="4.5" fill="#ffffff"/>

            <!-- Floppy Puppy Ears -->
            <path d="M50 48 C 30 46, 20 68, 25 90 C 28 100, 39 104, 46 95 C 52 87, 50 68, 50 48 Z" fill="#14161f" stroke="#ffffff" stroke-width="2.6" stroke-linejoin="round"/>
            <path d="M110 48 C 130 46, 140 68, 135 90 C 132 100, 121 104, 114 95 C 108 87, 110 68, 110 48 Z" fill="#14161f" stroke="#ffffff" stroke-width="2.6" stroke-linejoin="round"/>

            <!-- Puppy Head Contour -->
            <path d="M52 54 C 52 40, 108 40, 108 54 C 114 65, 116 82, 110 98 C 102 114, 58 114, 50 98 C 44 82, 46 65, 52 54 Z" fill="#0f1117" stroke="#ffffff" stroke-width="2.6" stroke-linecap="round"/>

            <!-- Cute Curved Happy Eyes -->
            <path d="M60 70 C 64 64, 70 64, 73 70" stroke="#ffffff" stroke-width="3" stroke-linecap="round"/>
            <path d="M87 70 C 90 64, 96 64, 100 70" stroke="#ffffff" stroke-width="3" stroke-linecap="round"/>

            <!-- Heart-Shaped Puppy Nose -->
            <path d="M75 80 C 75 77, 85 77, 85 80 C 85 85, 80 88, 80 88 C 80 88, 75 85, 75 80 Z" fill="#ffffff"/>

            <!-- Puppy Smile & Tongue -->
            <path d="M74 88 C 74 93, 80 95, 80 95 C 80 95, 86 93, 86 88" stroke="#ffffff" stroke-width="2.4" stroke-linecap="round"/>
            <path d="M76 94 C 76 102, 84 102, 84 94 Z" fill="#fa2d48"/>

            <!-- Rosy Blush Marks -->
            <ellipse cx="53" cy="80" rx="4" ry="2.2" fill="rgba(250, 45, 72, 0.45)"/>
            <ellipse cx="107" cy="80" rx="4" ry="2.2" fill="rgba(250, 45, 72, 0.45)"/>

            <!-- Little Musical Sparkle -->
            <path d="M126 36 Q 130 36 130 32 Q 130 36 134 36 Q 130 36 130 40 Q 130 36 126 36 Z" fill="#ffffff"/>
          </svg>
        </div>

        <h2 class="hub-hero-headline">Enjoy Your Downloads</h2>
        <p class="hub-hero-caption">Stored locally on your device for instant playback.</p>

        <!-- Immersive Vault Launcher Card -->
        <div class="hub-vault-card" onclick="switchView('offline')">
          <div class="vault-card-body">
            <div class="vault-card-icon-wrap">
              <svg viewBox="0 0 24 24" class="vault-card-svg">
                <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
                <polyline points="7 10 12 15 17 10" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
                <line x1="12" y1="15" x2="12" y2="3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <div class="vault-card-meta">
              <span class="vault-card-title">Downloaded Vault</span>
              <span class="vault-card-sub">${count} songs ready to play</span>
            </div>
            <div class="vault-card-arrow">
              <svg viewBox="0 0 24 24" style="width:18px;height:18px;fill:none;stroke:currentColor;stroke-width:2.2;stroke-linecap:round;stroke-linejoin:round;">
                <path d="M5 12h14M12 5l7 7-7 7"/>
              </svg>
            </div>
          </div>
        </div>
      </div>
    </div>
  `;
}
window.renderHubOfflineView = renderHubOfflineView;

function playOfflineHubTrack(index) {
  const dlTracks = (typeof playlists !== 'undefined' && playlists?.['pl-downloads']?.tracks) || [];
  if (!dlTracks[index]) return;
  currentPlaylistContextId = 'pl-downloads';
  playlist = [...dlTracks];
  playIndex(index);
}

function playOfflineHubAll(shuffle = false) {
  const dlTracks = (typeof playlists !== 'undefined' && playlists?.['pl-downloads']?.tracks) || [];
  if (dlTracks.length === 0) return;
  currentPlaylistContextId = 'pl-downloads';
  playlist = [...dlTracks];
  if (shuffle) {
    for (let i = playlist.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [playlist[i], playlist[j]] = [playlist[j], playlist[i]];
    }
  }
  playIndex(0);
}

window.renderHubOfflineView = renderHubOfflineView;
window.playOfflineHubTrack = playOfflineHubTrack;
window.playOfflineHubAll = playOfflineHubAll;

// Syncs player visibility with network connectivity
function updateOfflinePlayerVisibility() {
  if (!navigator.onLine) {
    document.body.classList.add('is-offline');
  } else {
    document.body.classList.remove('is-offline');
  }
}

// Initial check on load
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', updateOfflinePlayerVisibility);
} else {
  updateOfflinePlayerVisibility();
}

// Real-time network transitions
window.addEventListener('offline', updateOfflinePlayerVisibility);
window.addEventListener('online', updateOfflinePlayerVisibility);

// ==========================================
// FULLSCREEN PLAYER FLUID DISMISSAL ENGINE
// ==========================================
function initFullscreenSwipeDown() {
  const sheet = $id('fullscreenPlayerOverlay') || $id('fullscreenPlayer') || $id('playerOverlay');
  if (!sheet) return;

  let startY = 0;
  let startX = 0;
  let currentY = 0;
  let isDragging = false;
  let isGestureLocked = false;
  let startTime = 0;

  sheet.addEventListener('touchstart', (e) => {
    if (!sheet.classList.contains('open')) return;
    if (e.target.closest('#scrubberTrackBase, input[type="range"]')) return;

    const scrollableParent = e.target.closest('.lyrics-scroll-container, #sheetViewLyrics, #sheetViewQueue');
    if (scrollableParent && scrollableParent.scrollTop > 0) return;

    startY = e.touches[0].clientY;
    startX = e.touches[0].clientX;
    currentY = startY;
    startTime = Date.now();
    isDragging = false;
    isGestureLocked = false;
  }, { passive: true });

  sheet.addEventListener('touchmove', (e) => {
    if (!startY) return;

    const touchY = e.touches[0].clientY;
    const touchX = e.touches[0].clientX;
    const deltaY = touchY - startY;
    const deltaX = Math.abs(touchX - startX);

    if (!isGestureLocked) {
      if (Math.abs(deltaY) > 8 && Math.abs(deltaY) > deltaX) {
        if (deltaY > 0) {
          isGestureLocked = true;
          isDragging = true;
          sheet.classList.add('is-dragging');
        } else {
          startY = 0;
          return;
        }
      } else if (deltaX > 10) {
        startY = 0;
        return;
      }
    }

    if (!isDragging) return;

    currentY = touchY;
    const travel = Math.max(0, deltaY);
    const progress = Math.min(travel / (window.innerHeight * 0.7), 1);

    // 1:1 physical tracking
    sheet.style.transform = `translateY(${travel}px) scale(${1 - progress * 0.08})`;
    sheet.style.borderRadius = `${progress * 28}px`;
    sheet.style.opacity = `${1 - progress * 0.25}`;
  }, { passive: false });

  const finishGesture = () => {
    if (!isDragging) {
      startY = 0;
      return;
    }

    sheet.classList.remove('is-dragging');
    const travel = currentY - startY;
    const duration = Date.now() - startTime;
    const velocity = travel / (duration || 1);

    const shouldClose = travel > 120 || (velocity > 0.45 && travel > 30);

    if (shouldClose) {
      // Smoothly animate the rest of the way down from where the finger was released
      sheet.style.transition = 'transform 0.28s cubic-bezier(0.32, 0.72, 0, 1), opacity 0.24s ease, border-radius 0.28s ease';
      sheet.style.transform = 'translateY(100%) scale(0.92)';
      sheet.style.opacity = '0';
      sheet.style.borderRadius = '32px';

      setTimeout(() => {
        closeFullscreenPlayer();
      }, 280);
    } else {
      // Snap back up to fullscreen
      sheet.style.transition = 'transform 0.32s cubic-bezier(0.175, 0.885, 0.32, 1.15), opacity 0.2s ease, border-radius 0.32s ease';
      sheet.style.transform = 'translateY(0px) scale(1)';
      sheet.style.opacity = '1';
      sheet.style.borderRadius = '0px';

      setTimeout(() => {
        sheet.style.transform = '';
        sheet.style.opacity = '';
        sheet.style.borderRadius = '';
        sheet.style.transition = '';
      }, 320);
    }

    startY = 0;
    isDragging = false;
    isGestureLocked = false;
  };

  sheet.addEventListener('touchend', finishGesture, { passive: true });
  sheet.addEventListener('touchcancel', finishGesture, { passive: true });
}

function renderHomeView() {
  // If offline, redirect directly to the offline view
  if (!navigator.onLine) return renderHomeOfflineView();

  const vc = $id('viewContainer');
  if (!vc) return;
  // ... rest of your code ...

  const recentHistory = store.getHistory();
  const validHistory = recentHistory.filter(e => e && e.track && e.track.artist);
  
  let seedArtist = "Top Bollywood Hits";
  if (validHistory.length > 0) {
    seedArtist = validHistory[validHistory.length - 1].track.artist;
  }
  const artistEsc = accountText(seedArtist).replace(/'/g, "\\'");

  const quickPicksHtml = `
    <div class="quick-picks-spotlight" id="quickPicksSpotlightBox">
      <div class="quick-picks-header-wrap">
        <div class="quick-picks-titles">
          <span class="qp-subtext">${validHistory.length > 0 ? 'Because you listened to' : 'Recommended For You'}</span>
          <h2 class="qp-artist-name" title="${accountText(seedArtist)}">${accountText(seedArtist)}</h2>
        </div>
        <button type="button" onclick="refreshQuickPicks('${artistEsc}')" title="Refresh" class="shelf-refresh-btn" aria-label="Refresh Quick Picks">
          <svg viewBox="0 0 24 24" style="width:15px;height:15px;fill:none;stroke:currentColor;stroke-width:2.2;stroke-linecap:round;stroke-linejoin:round;"><path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/></svg>
        </button>
      </div>
      <div class="capsule-grid" id="quickPicksGrid" onscroll="updateQuickPicksDots()" style="margin-bottom:0;"></div>
      <div class="qp-dots-container" id="quickPicksDots"></div>
    </div>
  `;

  vc.innerHTML = `
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
          <button class="pill-action-btn" onclick="playForYouAll()" style="padding: 5px 12px; font-size: 0.76rem;">
            <svg viewBox="0 0 24 24" style="width: 12px; height: 12px; fill:#000;"><path d="M8 5v14l11-7z"/></svg>
            <span>Play Mix</span>
          </button>
        </div>
        <div class="capsule-grid" id="forYouGrid" style="margin-bottom:0;"></div>
      </div>
      ${quickPicksHtml}
      <div class="section-heading" id="trendingShelf">
        <h2>Trending Across India</h2>
        <a onclick="loadShelfCategory('Top Hindi Songs 2026', 'trendingGrid')" title="Refresh" class="shelf-refresh-btn" style="display:inline-flex;align-items:center;cursor:pointer;color:var(--text-muted);padding:4px;flex-shrink:0;">
          <svg viewBox="0 0 24 24" style="width:15px;height:15px;fill:none;stroke:currentColor;stroke-width:2.2;stroke-linecap:round;stroke-linejoin:round;"><path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/></svg>
        </a>
      </div>
      <div class="capsule-grid" id="trendingGrid"></div>
      <div class="section-heading" id="bollywoodShelf"><h2>Bollywood Chartbusters</h2></div>
      <div class="capsule-grid" id="bollywoodGrid"></div>
      <div class="section-heading" id="punjabiShelf"><h2>Punjabi Banger Wave</h2></div>
      <div class="capsule-grid" id="punjabiGrid"></div>
      <div class="section-heading" id="indieShelf"><h2>Desi Indie & Acoustic Chill</h2></div>
      <div class="capsule-grid" id="indieGrid"></div>
    </div>
    <div class="home-bottom-glow" id="homeBottomGlow"></div>
  `;

  // Attach dynamic scroll watcher to viewContainer and window
  const glowEl = $id('homeBottomGlow');
  const checkGlow = () => {
    const top = vc.scrollTop || window.scrollY || document.documentElement.scrollTop || 0;
    if (glowEl) {
      if (top > 25) {
        glowEl.classList.add('visible');
      } else {
        glowEl.classList.remove('visible');
      }
    }
  };

  vc.onscroll = checkGlow;
  window.onscroll = checkGlow;

  loadPersonalizedForYou('Flow');
  loadShelfCategory('Top Hindi Songs 2026', 'trendingGrid');
  loadShelfCategory('Bollywood Romantic Hits', 'bollywoodGrid');
  loadShelfCategory('Punjabi Hits 2026', 'punjabiGrid');
  loadShelfCategory('Indian Indie Songs', 'indieGrid');
  loadShelfCategory(seedArtist, 'quickPicksGrid').then(() => {
    if (typeof initQuickPicksDots === 'function') initQuickPicksDots();
    if (typeof initQuickPicksObserver === 'function') initQuickPicksObserver();
  });
  setupBottomGlowWatcher();
}

function renderSearchOfflineView() {
  const vc = $id('viewContainer');
  if (!vc) return;

  const count = (typeof playlists !== 'undefined' && playlists?.['pl-downloads']?.tracks?.length) || 0;

  vc.innerHTML = `
    <div class="stage-content">
      <!-- Search Header Bar -->
      <div class="search-header-wrap" style="padding: 10px 0 20px 0;">
        <h1 style="font-size: 1.6rem; font-weight: 800; color: #fff; margin: 0 0 6px 0;">Search</h1>
        <p style="color: var(--text-muted); font-size: 0.85rem; margin: 0;">Explore songs, artists, and playlists</p>
      </div>

      <!-- Static Minimal Offline Card -->
      <div class="melo-offline-minimal">
        <div class="offline-static-icon-box">
          <svg viewBox="0 0 24 24" class="offline-search-icon">
            <path d="M1 1l22 22M16.72 11.06A7 7 0 005.27 5.27M10.5 17.5a7 7 0 006.22-3.8M21 21l-4.35-4.35" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
          </svg>
        </div>

        <div class="melo-offline-info">
          <h2 class="melo-offline-title">Search is Offline.</h2>
          <p class="melo-offline-desc">Cloud lookup needs an active internet connection. You can still play anything saved in your offline vault.</p>
        </div>

        <button class="melo-offline-btn" onclick="switchView('offline')">
          <svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          <span>Downloaded Songs</span>
          <span class="melo-offline-count">${count}</span>
        </button>
      </div>
    </div>
  `;
}
window.renderSearchOfflineView = renderSearchOfflineView;

function renderHomePagePlaylists() {
  const plGrid = $id('homePlaylistsGrid');
  const plWrapper = $id('homePlaylistsWrapper');
  if (!plGrid || !plWrapper) return;
  const userPlaylists = Object.values(playlists).filter(p => p.id !== 'pl-favorites' && p.id !== 'pl-downloads');
  plGrid.innerHTML = '';
  if (!userPlaylists.length) {
    plWrapper.style.display = 'none';
  } else {
    plWrapper.style.display = 'block';
    userPlaylists.forEach(pl => {
      const item = document.createElement('div');
      item.className = 'poster-item';
      item.onclick = () => openPlaylistDetails(pl.id);
      item.innerHTML = `
        <div class="poster-wrap">${renderPlaylistCoverHTML(pl)}</div>
        <div class="poster-title">${accountText(pl.name)}</div>
        <div class="poster-subtitle">${pl.tracks.length} tracks</div>
      `;
      plGrid.appendChild(item);
    });
  }
}

function loadPersonalizedForYou() {
  const history = store.getHistory();
  const favoritesObj = store.getFavorites();
  const searchHistory = store.getSearchHistory();
  
  let tasteKeywords = [];
  Object.values(favoritesObj).forEach(t => { if (t.artist) tasteKeywords.push(t.artist.split(',')[0].trim()); });
  history.forEach(h => { if (h.track?.artist) tasteKeywords.push(h.track.artist.split(',')[0].trim()); });
  searchHistory.forEach(s => { if (s.query) tasteKeywords.push(s.query); });
  tasteKeywords = [...new Set(tasteKeywords)];

  let query = 'Acoustic Bollywood Indie Hits';
  if (tasteKeywords.length > 0) {
    const seed = tasteKeywords[Math.floor(Math.random() * tasteKeywords.length)];
    query = `${seed} Hits`;
  }

  const c = $id('forYouGrid');
  if (c) c.innerHTML = createMeloLoader();

  fetch(`/api/search?query=${encodeURIComponent(query)}`)
    .then(res => res.json())
    .then(data => {
      forYouTracks = (data.results || []).map(normalizeTrackData);
      categoryData['forYouGrid'] = forYouTracks.slice(0, 10);
      renderForYouCompactGrid('forYouGrid', forYouTracks.slice(0, 10));
    })
    .catch(() => { if (c) c.innerHTML = ''; });
}

function loadForYouCatalog() {
  return loadPersonalizedForYou();
}

function switchVibePreset() {
  return loadPersonalizedForYou();
}

function playForYouAll() {
  if (forYouTracks.length > 0) {
    currentPlaylistContextId = null;
    playlist = [...forYouTracks];
    playIndex(0);
  }
}

function renderForYouCompactGrid(containerId, items) {
  const c = $id(containerId);
  if (!c) return;
  c.innerHTML = '';
  items.forEach((track, i) => {
    window.__contextTrackMap[track.id] = track;
    const item = document.createElement('div');
    item.className = 'for-you-mini-card';
    item.onclick = () => {
      currentPlaylistContextId = null;
      playlist = categoryData[containerId] || items;
      playIndex(i);
    };
    item.innerHTML = `
      <img class="fym-thumb" src="${track.thumbnail || ''}" loading="lazy" />
      <div class="fym-info">
        <div class="fym-title">${accountText(track.title)}</div>
        <div class="fym-artist">${accountText(track.artist)}</div>
      </div>
      <div class="fym-play-icon">▶</div>
    `;
    c.appendChild(item);
  });
}

window.loadForYouCatalog = loadForYouCatalog;
window.loadPersonalizedForYou = loadPersonalizedForYou;
window.switchVibePreset = switchVibePreset;
window.playForYouAll = playForYouAll;
window.renderForYouCompactGrid = renderForYouCompactGrid;

function playForYouAll() {
  if (forYouTracks.length > 0) {
    currentPlaylistContextId = null;
    playlist = [...forYouTracks];
    playIndex(0);
  }
}

async function loadShelfCategory(query, containerId) {
  const c = $id(containerId);
  if (c) c.innerHTML = createMeloLoader();

  try {
    const res = await fetch(`/api/search?query=${encodeURIComponent(query)}`);
    const data = await res.json();
    let items = (data.results || []).map(normalizeTrackData);
    // Shuffle items so refresh gives a fresh selection every time
    items.sort(() => Math.random() - 0.5);
    categoryData[containerId] = items;
    renderGridContainer(containerId, items.slice(0, 6));
  } catch (err) {
    if (c) c.innerHTML = '';
  }
}

async function refreshQuickPicks(artist) {
  const grid = $id('quickPicksGrid');
  const btn = document.querySelector('.quick-picks-spotlight .shelf-refresh-btn');
  if (!grid) return;

  // Add smooth spin to button without clearing container contents
  if (btn) btn.classList.add('is-spinning');
  grid.style.opacity = '0.45';
  grid.style.transition = 'opacity 0.2s ease';

  try {
    const res = await fetch(`/api/search?query=${encodeURIComponent(artist)}`);
    const data = await res.json();
    let items = (data.results || []).map(normalizeTrackData);
    items.sort(() => Math.random() - 0.5);
    categoryData['quickPicksGrid'] = items;
    renderGridContainer('quickPicksGrid', items.slice(0, 6));
    grid.scrollTo({ left: 0, behavior: 'smooth' });
    if (typeof initQuickPicksDots === 'function') initQuickPicksDots();
  } catch (e) {
  } finally {
    grid.style.opacity = '1';
    if (btn) {
      setTimeout(() => btn.classList.remove('is-spinning'), 400);
    }
  }
}
window.refreshQuickPicks = refreshQuickPicks;

function renderGridContainer(containerId, items) {
  const c = $id(containerId);
  if (!c) return;
  c.innerHTML = '';
  items.forEach((track, i) => {
    window.__contextTrackMap[track.id] = track;
    const item = document.createElement('div');
    item.className = 'poster-item';
    item.onclick = () => {
      currentPlaylistContextId = null;
      if (containerId === 'searchGrid') {
        playlist = [track];
        playIndex(0);
      } else {
        playlist = categoryData[containerId] || items;
        playIndex(i);
      }
    };
    item.innerHTML = `
      <div class="poster-wrap">
        <img class="poster-img" src="${track.thumbnail || ''}" loading="lazy" />
        <div class="play-bubble"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></div>
      </div>
      <div class="poster-title">${accountText(track.title)}</div>
      <div class="poster-subtitle">${accountText(track.artist)}</div>
    `;
    c.appendChild(item);
  });

  // Automatically initialize slide dots and visibility pause observer for Quick Picks
  if (containerId === 'quickPicksGrid') {
    if (typeof initQuickPicksDots === 'function') initQuickPicksDots();
    if (typeof initQuickPicksObserver === 'function') initQuickPicksObserver();
  }
}

// ==========================================
// 14. MUSIC HUB & PLAYLISTS
// ==========================================
function renderFavoritesView() {
  activeView = 'favorites';
  const vc = $id('viewContainer');
  if (!vc) return;
  const lovedCount = Object.keys(favorites).length;
  const historyCount = store.getHistory().length;
  const downloadedCount = playlists['pl-downloads']?.tracks?.length || 0;
  const userPlaylists = Object.values(playlists).filter(pl => !['pl-favorites', 'pl-downloads'].includes(pl.id));

  let playlistsHtml = '';
  if (userPlaylists.length > 0) {
    playlistsHtml = `
      <div class="section-heading"><h2>Your Playlists</h2><a onclick="actionOpenAddToPlaylist(null)">+ Create</a></div>
      <div class="capsule-grid" id="customPlaylistsGrid" style="margin-bottom: 32px;">
        ${userPlaylists.map(pl => `
          <div class="poster-item" onclick="openPlaylistDetails('${pl.id}')">
            <div class="poster-wrap">${renderPlaylistCoverHTML(pl)}</div>
            <div class="poster-title">${accountText(pl.name)}</div>
            <div class="poster-subtitle">${pl.tracks?.length || 0} tracks</div>
          </div>
        `).join('')}
      </div>`;
  } else {
    playlistsHtml = `
      <div class="section-heading"><h2>Your Playlists</h2><a onclick="actionOpenAddToPlaylist(null)">+ Create</a></div>
      <p style="color:var(--text-dim);font-size:0.85rem;margin-bottom:32px;">No custom playlists created yet. Tap "+ Create" above.</p>`;
  }

  vc.innerHTML = `
    <div class="stage-content library-stage">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" title="Back"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button>
        <h1>Music Hub</h1>
      </div>
      ${playlistsHtml}
      <div class="section-heading"><h2>Your Library</h2></div>
      <div class="hub-vault-grid library-hub-grid">
        <button class="hub-vault-card" onclick="renderLovedTracks()">
          <div class="hub-vault-badge library-badge-loved">♡</div>
          <div><div class="hub-vault-title">Loved Tracks</div><div class="hub-vault-count">${lovedCount} songs</div></div>
        </button>
        <button class="hub-vault-card" onclick="renderHistoryView()">
          <div class="hub-vault-badge library-badge-history">◷</div>
          <div><div class="hub-vault-title">Recently Played</div><div class="hub-vault-count">${historyCount} listens</div></div>
        </button>
        <button class="hub-vault-card" onclick="renderOfflineVault()">
          <div class="hub-vault-badge library-badge-download">↓</div>
          <div><div class="hub-vault-title">Downloaded</div><div class="hub-vault-count">${downloadedCount} offline</div></div>
        </button>
      </div>
      <div class="section-heading"><h2>Recently Loved</h2><a onclick="renderLovedTracks()">View all</a></div>
      <div id="libraryRecentLoved"></div>
    </div>`;

  const recent = Object.values(favorites).slice(-5).reverse();
  $id('libraryRecentLoved').innerHTML = recent.length
    ? recent.map((track, index) => libraryTrackRow(track, index, { source: 'loved' }))
    : `<div class="library-empty compact" style="margin-bottom: 24px;"><strong>Your favorites belong here.</strong><span>Tap the heart on any song you love.</span></div>`;
}

function openPlaylistDetails(plId) {
  showMeloLoader('OPENING PLAYLIST...');

  activeView = 'playlist-detail';
  if (plId === 'pl-favorites') playlists['pl-favorites'].tracks = Object.values(favorites);
  const pl = playlists[plId], vc = $id('viewContainer');
  if (!vc || !pl) {
    hideMeloLoader();
    return;
  }
  const leadImage = getPlaylistHeroCoverURL(pl);
  applyPlaylistDynamicColors(leadImage, pl.name);
  const bgStyle = leadImage ? `style="background-image: url('${leadImage}');"` : '';

  vc.innerHTML = `
    <div class="playlist-immersive-view">
      <div class="playlist-immersive-hero" ${bgStyle}>
        <div class="playlist-top-nav-row">
          <button class="circle-back-btn" onclick="goBack()" title="Back"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button>
          <button class="circle-back-btn" onclick="openPlaylistActionMenu('${plId}')" title="Playlist Menu"><svg viewBox="0 0 24 24" style="stroke:none; fill:#fff;"><circle cx="12" cy="5" r="2.2"/><circle cx="12" cy="12" r="2.2"/><circle cx="12" cy="19" r="2.2"/></svg></button>
        </div>
        <div class="playlist-backdrop-content">
          <span class="pl-meta-tag">${plId === 'pl-downloads' ? 'Offline Vault' : (plId === 'pl-favorites' ? 'Curated Collection' : 'Playlist')}</span>
          <div class="playlist-immersive-title-row"><h1 class="playlist-immersive-title">${accountText(pl.name)}</h1></div>
          <div class="playlist-immersive-stats">${pl.tracks.length} tracks${pl.description ? ` · ${accountText(pl.description)}` : ''}</div>
        </div>
      </div>

      <!-- Action buttons extracted cleanly outside hero & isolated from tinting -->
      ${pl.tracks.length > 0 || isRemoveSongsMode ? `
        <div class="playlist-action-bar-strip">
          <div class="playlist-immersive-actions">
            ${pl.tracks.length > 0 ? `
              <button class="pill-action-btn pl-play-all-btn" onclick="playPlaylistContext('${plId}')">
                <svg viewBox="0 0 24 24" style="width:16px;height:16px;fill:#000;"><path d="M8 5v14l11-7z"/></svg>
                <span>Play All</span>
              </button>
              <button class="filter-chip pl-shuffle-btn" onclick="playPlaylistContext('${plId}', true)">
                <svg viewBox="0 0 24 24" style="width:14px;height:14px;fill:none;stroke:#fff;stroke-width:2.2;margin-right:4px;"><polygon points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polygon points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>
                <span>Shuffle</span>
              </button>
            ` : ''}
            ${isRemoveSongsMode ? `
              <button class="filter-chip pl-done-remove-btn" onclick="actionToggleRemoveSongsMode()">
                Done Removing
              </button>
            ` : ''}
          </div>
        </div>
      ` : ''}

      <div class="playlist-tracks-section"><div id="playlistTracksBox"></div></div>
    </div>`;

  const box = $id('playlistTracksBox');
  if (!box) return;
  box.innerHTML = '';

  if (!pl.tracks.length) {
    box.innerHTML = `<p style="color:var(--text-dim);font-size:0.9rem;padding:32px 12px;text-align:center;">This playlist is currently empty. Tap the menu (⋮) above to add songs.</p>`;
  } else {
    pl.tracks.forEach((track, i) => {
      const row = document.createElement('div');
      row.className = 'track-row playlist-track-row';
      row.draggable = plId !== 'pl-favorites' && plId !== 'pl-downloads';
      row.addEventListener('dragstart', (e) => e.dataTransfer.setData('text/plain', String(i)));
      row.addEventListener('dragover', (e) => e.preventDefault());
      row.addEventListener('drop', (e) => { 
        e.preventDefault(); 
        reorderPlaylistTrack(plId, Number(e.dataTransfer.getData('text/plain')), i); 
      });
      row.onclick = () => {
        if (isRemoveSongsMode) return;
        currentPlaylistContextId = plId;
        playlist = [...pl.tracks];
        playIndex(i);
      };
      const downloadedTick = downloadedTrackIds.has(String(track.id))
        ? `<svg viewBox="0 0 24 24" style="width:14px; height:14px; stroke:#10b981; fill:none; stroke-width:2.5; margin-left:6px; flex-shrink:0;"><path d="M20 6L9 17l-5-5"/></svg>`
        : '';
      row.innerHTML = `
        <div class="tr-num"><span class="playlist-drag-handle">&#8801;</span>${i + 1}</div>
        <img class="tr-thumb" src="${track.thumbnail || ''}" loading="lazy" />
        <div class="tr-info">
          <div class="tr-title" style="display:flex;align-items:center;">${accountText(track.title)} ${downloadedTick}</div>
          <div class="tr-artist">${accountText(track.artist)}</div>
        </div>
        <div class="tr-album">${accountText(track.album || 'Single')}</div>
        <div class="tr-time">${track.duration || '--:--'}</div>
        ${isRemoveSongsMode
          ? `<button class="tr-remove-btn" title="Remove Song" onclick="event.stopPropagation(); removeTrackFromPlaylistDirect('${plId}', ${i})">✕</button>`
          : `<button class="tr-fav ${favorites[track.id] ? 'active' : ''}" onclick="event.stopPropagation(); toggleFavTrackDirect('${track.id}', this)">♥</button>`
        }`;
      box.appendChild(row);
    });
  }
  setTimeout(() => hideMeloLoader(), 120);
}

function libraryTrackRow(track, index, options = {}) {
  window.__contextTrackMap[track.id] = track;
  const loved = Boolean(favorites[track.id]);
  return `
    <div class="track-row library-track-row" onclick="playLibraryTrack(${index}, '${options.source || 'loved'}')">
      <div class="tr-num">${index + 1}</div>
      <img class="tr-thumb" src="${track.thumbnail || ''}" loading="lazy" />
      <div class="tr-info"><div class="tr-title">${accountText(track.title || 'Unknown Track')}</div><div class="tr-artist">${accountText(track.artist || 'Unknown Artist')}</div></div>
      <div class="tr-album">${accountText(track.album || 'Single')}</div>
      <div class="tr-time">${options.meta || track.duration || ''}</div>
      <button class="tr-fav ${loved ? 'active' : ''}" onclick="event.stopPropagation(); toggleLibraryFavorite('${track.id}', '${options.source || 'loved'}')">${loved ? '♥' : '♡'}</button>
    </div>
  `;
}

function playLibraryTrack(index, source) {
  const tracks = source === 'history'
    ? (window.__historyTracks || store.getHistory().filter(e => e.track).map(e => e.track))
    : (window.__lovedTracks || Object.values(favorites));
  playlist = tracks;
  currentPlaylistContextId = source === 'loved' ? 'pl-favorites' : null;
  playIndex(index);
}

function toggleLibraryFavorite(trackId, source) {
  const track = source === 'history' ? store.getHistory().find(e => e.track?.id === trackId)?.track : (favorites[trackId] || window.__contextTrackMap[trackId]);
  if (track) store.setFavorite(track, !favorites[trackId]);
  if (activeView === 'loved') renderLovedTracks();
  if (activeView === 'history') renderHistoryView();
}

function toggleFavTrackDirect(trackId, btn) {
  const track = window.__contextTrackMap[trackId] || playlist.find(t => String(t.id) === String(trackId)) || favorites[trackId];
  if (!track) return;
  const willLove = !favorites[trackId];
  store.setFavorite(track, willLove);
  if (btn) {
    btn.innerText = willLove ? '♥' : '♡';
    btn.classList.toggle('active', willLove);
    btn.style.color = willLove ? 'var(--accent)' : 'var(--text-muted)';
  }
  syncSheetTrackInfo();
}

function removeFavoriteItem(e, trackId) {
  e.stopPropagation();
  const track = favorites[trackId];
  if (track) store.setFavorite(track, false);
  if (activeView === 'loved' || activeView === 'favorites') renderLovedTracks();
}

// ==========================================
// 15. PLAYLIST ACTIONS & COVER HELPERS
// ==========================================
function renderPlaylistCoverHTML(pl) {
  if (!pl) return `<div class="pl-empty-cover">🎧</div>`;
  if (pl.customCover) return `<img src="${pl.customCover}" class="pl-cover-img" alt="${accountText(pl.name)}" />`;
  if (pl.tracks && pl.tracks.length >= 4) {
    return `<div class="pl-collage-grid"><img src="${pl.tracks[0].thumbnail}" loading="lazy" /><img src="${pl.tracks[1].thumbnail}" loading="lazy" /><img src="${pl.tracks[2].thumbnail}" loading="lazy" /><img src="${pl.tracks[3].thumbnail}" loading="lazy" /></div>`;
  }
  if (pl.tracks && pl.tracks.length > 0) return `<img src="${pl.tracks[0].thumbnail}" class="pl-cover-img" loading="lazy" />`;
  return `<div class="pl-empty-cover">🎧</div>`;
}

function getPlaylistHeroCoverURL(pl) {
  if (!pl) return '';
  if (pl.customCover) return pl.customCover;
  if (pl.tracks && pl.tracks.length > 0) return pl.tracks[0].thumbnail;
  return '';
}

function handleEditCoverFileSelected(e) {
  const file = e.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = function (evt) {
    editPlTempCover = evt.target.result;
    const prev = $id('editCoverUploadPreview');
    if (prev) {
      prev.style.display = 'block';
      prev.style.backgroundImage = `url(${editPlTempCover})`;
    }
    if ($id('editCoverUploadText')) $id('editCoverUploadText').innerText = "New Cover Selected ✓";
  };
  reader.readAsDataURL(file);
}

function handleCoverFileSelected(e) {
  const file = e.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = function (evt) {
    newPlaylistTempCover = evt.target.result;
    const prev = $id('coverUploadPreview');
    if (prev) {
      prev.style.display = 'block';
      prev.style.backgroundImage = `url(${newPlaylistTempCover})`;
    }
    if ($id('coverUploadText')) $id('coverUploadText').innerText = "Cover Selected ✓";
  };
  reader.readAsDataURL(file);
}

function confirmCreateAndAddToPlaylist() {
  const title = $id('newPlTitleInput') ? $id('newPlTitleInput').value.trim() : '';
  if (!title) return showToast("Please enter a playlist title.");
  const id = 'pl-' + Date.now(), now = new Date().toISOString();
  playlists[id] = { id, name: title, description: $id('newPlDescriptionInput')?.value.trim() || '', tracks: pendingTrackForPlaylist ? [pendingTrackForPlaylist] : [], customCover: newPlaylistTempCover || null, created_at: now, updated_at: now };
  store.savePlaylists(playlists);
  showToast(`Created & Saved to "${title}"!`);
  if ($id('newPlTitleInput')) $id('newPlTitleInput').value = '';
  if ($id('newPlDescriptionInput')) $id('newPlDescriptionInput').value = '';
  newPlaylistTempCover = null;
  if ($id('coverUploadPreview')) $id('coverUploadPreview').style.display = 'none';
  if ($id('coverUploadText')) $id('coverUploadText').innerText = "Choose Custom Photo Cover (Optional)";
  closeAddToPlaylistModal();
  if (activeView === 'favorites') renderFavoritesView();
}

function openPlaylistActionMenu(plId) {
  currentEditingPlId = plId;
  const pl = playlists[plId];
  if (!pl) return;
  if ($id('plActionMenuTitle')) $id('plActionMenuTitle').innerText = pl.name;
  if ($id('plMenuDeleteRow')) $id('plMenuDeleteRow').style.display = (plId === 'pl-favorites' || plId === 'pl-downloads') ? 'none' : 'flex';
  if ($id('removeSongsToggleText')) $id('removeSongsToggleText').innerText = isRemoveSongsMode ? "Exit Remove Mode" : "Remove Songs";
  $id('playlistActionMenuModal')?.classList.add('open');
}

function closePlaylistActionMenu(e) {
  if (!e || e.target === $id('playlistActionMenuModal') || e.target.classList.contains('drag-handle')) {
    $id('playlistActionMenuModal')?.classList.remove('open');
  }
}

function actionFromMenuEditPlaylist() {
  closePlaylistActionMenu();
  if (currentEditingPlId) openPlaylistEditor(currentEditingPlId);
}

function actionFromMenuAddSongs() {
  closePlaylistActionMenu();
  if (!currentEditingPlId) return;
  const input = $id('plSearchAddInput'), results = $id('plSearchResultsList');
  if (input) {
    input.value = '';
    input.oninput = () => {
      clearTimeout(window.plSearchTimer);
      const q = input.value.trim();
      if (!q) { results.innerHTML = '<p style="color:var(--text-dim); font-size:0.85rem; text-align:center; padding:20px;">Type above to find tracks and add directly.</p>'; return; }
      window.plSearchTimer = setTimeout(async () => {
        results.innerHTML = createMeloLoader('Searching tracks...');
        if (currentSearchAbort) currentSearchAbort.abort();
        currentSearchAbort = new AbortController();
        try {
          const res = await fetch(`/api/search?query=${encodeURIComponent(q)}`, { signal: currentSearchAbort.signal });
          const data = await res.json();
          const items = data.results || [];
          if (!items.length) {
            results.innerHTML = '<p style="color:var(--text-dim); font-size:0.85rem; text-align:center; padding:20px;">No tracks found.</p>';
            return;
          }
          results.innerHTML = '';
          items.forEach(track => {
            const normalizedTrack = normalizeTrackData(track);
            window.__contextTrackMap[normalizedTrack.id] = normalizedTrack;
            const row = document.createElement('div'); row.className = 'queue-row';
            row.innerHTML = `<div class="queue-left-block"><img class="queue-thumb" src="${normalizedTrack.thumbnail}" loading="lazy" /><div class="queue-info"><div class="queue-title">${accountText(normalizedTrack.title)}</div><div class="queue-artist">${accountText(normalizedTrack.artist)}</div></div></div><button class="queue-action-btn" style="background:var(--accent); color:#fff;">+</button>`;
            row.onclick = async () => {
              if (currentEditingPlId === 'pl-downloads') {
                showToast(`Downloading "${normalizedTrack.title}" offline...`);
                try {
                  const resp = await fetch(`/api/download/${normalizedTrack.id}?title=${encodeURIComponent(normalizedTrack.title)}&artist=${encodeURIComponent(normalizedTrack.artist)}`);
                  if (!resp.ok) throw new Error("Download failed");
                  await saveTrackToOfflineDB(normalizedTrack, await resp.blob());
                  await syncDownloadedPlaylist();
                  showToast(`Downloaded & added!`);
                  openPlaylistDetails('pl-downloads');
                } catch (err) { showToast("Failed to download track."); }
              } else if (playlists[currentEditingPlId]) {
                playlists[currentEditingPlId].tracks.push(normalizedTrack);
                store.savePlaylists(playlists);
                showToast(`Added to "${playlists[currentEditingPlId].name}"!`);
                openPlaylistDetails(currentEditingPlId);
              }
            };
            results.appendChild(row);
          });
        } catch (e) {
          if (e.name !== 'AbortError') results.innerHTML = '<p style="color:var(--accent); font-size:0.85rem; text-align:center; padding:20px;">Search error.</p>';
        }
      }, 250);
    };
  }
  $id('playlistSearchAddModal')?.classList.add('open');
}

function closePlaylistSearchAddModal(e) {
  if (!e || e.target === $id('playlistSearchAddModal') || e.target.classList.contains('drag-handle')) {
    $id('playlistSearchAddModal')?.classList.remove('open');
  }
}

function actionToggleRemoveSongsMode() {
  closePlaylistActionMenu();
  isRemoveSongsMode = !isRemoveSongsMode;
  if (currentEditingPlId) openPlaylistDetails(currentEditingPlId);
}

function removeTrackFromPlaylistDirect(plId, trackIndex) {
  const pl = playlists[plId];
  if (!pl || !pl.tracks[trackIndex]) return;
  const trackTitle = pl.tracks[trackIndex].title;
  pl.tracks.splice(trackIndex, 1);
  store.savePlaylists(playlists);
  showToast(`Removed "${trackTitle}"`);
  openPlaylistDetails(plId);
}

function actionFromMenuDeletePlaylist() {
  closePlaylistActionMenu();
  if (currentEditingPlId) deleteCustomPlaylist(currentEditingPlId);
}

function deleteCustomPlaylist(playlistId) {
  const pl = playlists[playlistId];
  if (!pl || ['pl-favorites', 'pl-downloads'].includes(playlistId)) return;
  showMeloConfirmation({
    title: `Delete “${pl.name}”?`,
    message: 'This removes the playlist, not the songs in your library.',
    actionLabel: 'Delete playlist',
    danger: true,
    action: () => {
      delete playlists[playlistId];
      store.savePlaylists(playlists);
      switchView('favorites');
      showToast('Playlist deleted.');
    }
  });
}

function openPlaylistEditor(plId) {
  currentEditingPlId = plId;
  editPlTempCover = null;
  const pl = playlists[plId];
  if (!pl) return;
  if ($id('editPlNameInput')) $id('editPlNameInput').value = pl.name;
  if ($id('editPlDescriptionInput')) $id('editPlDescriptionInput').value = pl.description || '';
  if ($id('editCoverUploadText')) $id('editCoverUploadText').innerText = "Change Photo Cover (Optional)";
  if ($id('editCoverUploadPreview')) {
    if (pl.customCover) {
      $id('editCoverUploadPreview').style.display = 'block';
      $id('editCoverUploadPreview').style.backgroundImage = `url(${pl.customCover})`;
    } else {
      $id('editCoverUploadPreview').style.display = 'none';
    }
  }
  $id('playlistEditModal')?.classList.add('open');
}

function closePlaylistEditModal(e) {
  if (!e || e.target === $id('playlistEditModal') || e.target.classList.contains('drag-handle')) {
    $id('playlistEditModal')?.classList.remove('open');
  }
}

function confirmPlaylistEdit() {
  if (!currentEditingPlId || !playlists[currentEditingPlId]) return;
  const pl = playlists[currentEditingPlId];
  if ($id('editPlNameInput') && $id('editPlNameInput').value.trim()) pl.name = $id('editPlNameInput').value.trim();
  if ($id('editPlDescriptionInput')) pl.description = $id('editPlDescriptionInput').value.trim();
  if (editPlTempCover) pl.customCover = editPlTempCover;
  pl.updated_at = new Date().toISOString();
  store.savePlaylists(playlists);
  showToast("Playlist updated!");
  closePlaylistEditModal();
  openPlaylistDetails(currentEditingPlId);
}

function resetPlaylistCoverToCollage(plId) {
  if (!playlists[plId]) return;
  playlists[plId].customCover = null;
  store.savePlaylists(playlists);
  showToast("Reverted to automatic collage!");
  closePlaylistEditModal();
  openPlaylistDetails(plId);
}

function actionOpenAddToPlaylist(trackObj = null) {
  closeContextMenu();
  pendingTrackForPlaylist = trackObj || contextTrack || (currentIndex !== -1 ? playlist[currentIndex] : null);
  if (!pendingTrackForPlaylist) return;
  const listEl = $id('playlistOptionsList');
  if (!$id('addToPlaylistModal') || !listEl) return;
  listEl.innerHTML = '';
  Object.values(playlists).forEach(pl => {
    if (pl.id === 'pl-downloads') return;
    const card = document.createElement('div');
    card.className = 'themed-pl-card';
    card.innerHTML = `<div class="themed-pl-card-thumb">${renderPlaylistCoverHTML(pl)}</div><div style="font-size:0.82rem; font-weight:700; max-width:100%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:#fff;">${pl.name}</div><div style="font-size:0.72rem; color:var(--text-muted);">${pl.tracks.length} songs</div>`;
    card.onclick = () => addTrackToSpecificPlaylist(pl.id);
    listEl.appendChild(card);
  });
  $id('addToPlaylistModal').classList.add('open');
}

function closeAddToPlaylistModal(e) {
  if (!e || e.target === $id('addToPlaylistModal') || e.target.classList.contains('drag-handle')) {
    $id('addToPlaylistModal')?.classList.remove('open');
  }
}

async function addTrackToSpecificPlaylist(plId) {
  if (!pendingTrackForPlaylist || !playlists[plId]) return;
  const trackToSave = { ...pendingTrackForPlaylist };
  if (plId === 'pl-downloads') {
    showToast(`Downloading "${trackToSave.title}" for offline storage...`);
    closeAddToPlaylistModal();
    try {
      const resp = await fetch(`/api/download/${trackToSave.id}?title=${encodeURIComponent(trackToSave.title)}&artist=${encodeURIComponent(trackToSave.artist)}`);
      if (!resp.ok) throw new Error("Stream fetch failed");
      await saveTrackToOfflineDB(trackToSave, await resp.blob());
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
  closeAddToPlaylistModal();
  if (activeView === 'favorites') renderFavoritesView();
}

function openMenuForTrack(trackId) {
  const track = window.__contextTrackMap[trackId];
  if (track) openContextMenu(track);
}

function openContextMenu(trackOverride = null) {
  const track = trackOverride || (currentIndex !== -1 ? playlist[currentIndex] : null);
  if (!track) return;
  contextTrack = track;
  if ($id('ctxModalSongTitle')) $id('ctxModalSongTitle').innerText = track.title;
  if ($id('ctxFavText')) $id('ctxFavText').innerText = favorites[track.id] ? "Remove from Favorites" : "Save to Favorites";
  const dlRow = $id('ctxDownloadRow');
  if (dlRow) {
    if (downloadedTrackIds.has(String(track.id))) {
      dlRow.innerHTML = `<svg viewBox="0 0 24 24" style="stroke:#10b981; fill:none; stroke-width:2.5;"><path d="M20 6L9 17l-5-5"/></svg><span style="color:#10b981; font-weight:700;">Downloaded ✓</span>`;
      dlRow.onclick = () => { showToast("Already downloaded."); closeContextMenu(); };
    } else {
      dlRow.innerHTML = `<svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg><span style="color:#fff; font-weight:700;">Download for Offline</span>`;
      dlRow.onclick = () => actionDownloadSong();
    }
  }
  $id('contextModal')?.classList.add('open');
}

function closeContextMenu() { $id('contextModal')?.classList.remove('open'); }

async function actionDownloadSong() {
  const track = contextTrack || (currentIndex !== -1 ? playlist[currentIndex] : null);
  if (!track) return;
  if (downloadedTrackIds.has(String(track.id))) {
    showToast('Available offline');
    closeContextMenu();
    return;
  }

  const CIRCUMFERENCE = 56.55; // 2 * Math.PI * 9
  const dlRow = $id('ctxDownloadRow');

  if (dlRow) {
    dlRow.innerHTML = `
      <svg class="download-spinner is-spinning" viewBox="0 0 24 24">
        <circle class="spinner-bg" cx="12" cy="12" r="9"></circle>
        <circle class="spinner-bar" cx="12" cy="12" r="9" 
          stroke-dasharray="${CIRCUMFERENCE}" 
          stroke-dashoffset="${CIRCUMFERENCE}"></circle>
      </svg>
      <span class="dl-status-text" style="font-weight:600; color:#fff; margin-left:10px;">Starting download...</span>
    `;
    dlRow.onclick = null;
  }

  showToast(`Downloading "${track.title}"...`);

  try {
    const streamUrl = `/api/download/${track.id}?title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}&quality=${selectedQuality}`;
    const resp = await fetch(streamUrl);
    if (!resp.ok) throw new Error("Stream fetch failed");

    const total = Number(resp.headers.get('content-length')) || 0;
    const spinnerSvg = dlRow?.querySelector('.download-spinner');
    const spinnerBar = dlRow?.querySelector('.spinner-bar');
    const statusText = dlRow?.querySelector('.dl-status-text');

    // If total length is known, switch from spinning to precise radial progress
    if (total > 0 && spinnerSvg) {
      spinnerSvg.classList.remove('is-spinning');
    }

    let received = 0;
    const chunks = [];

    if (resp.body?.getReader) {
      const reader = resp.body.getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        chunks.push(value);
        received += value.byteLength;

        if (total > 0) {
          const pct = Math.min(1, received / total);
          const offset = CIRCUMFERENCE * (1 - pct);
          if (spinnerBar) spinnerBar.style.strokeDashoffset = offset;
          if (statusText) statusText.textContent = `Downloading ${Math.round(pct * 100)}%`;
        } else {
          // Fallback if Content-Length header is omitted by server
          const mb = (received / (1024 * 1024)).toFixed(1);
          if (statusText) statusText.textContent = `Downloading (${mb} MB)...`;
        }
      }
    }

    const blob = chunks.length ? new Blob(chunks, { type: 'audio/mp4' }) : await resp.blob();
    await saveTrackToOfflineDB(track, blob);
    await syncDownloadedPlaylist();
    showToast(`"${track.title}" saved offline!`);
  } catch (err) {
    showToast("Download failed. Check network.");
  } finally {
    closeContextMenu();
  }
}

function promptImportSelection() { closeSettingsModal(); $id('importModal')?.classList.add('open'); }
function closeImportModal(e) { if (!e || e.target === $id('importModal') || e.target.classList.contains('drag-handle')) $id('importModal')?.classList.remove('open'); }
async function executePlaylistImport() {
  const input = $id('importPlaylistUrlInput'), btn = $id('executeImportBtn'), url = input ? input.value.trim() : '';
  if (!url) return showToast("Please enter a playlist link.");
  if (btn) { btn.disabled = true; btn.innerText = "Analyzing & importing tracks..."; }
  try {
    const res = await fetch("/api/import-playlist", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ url }) });
    const data = await res.json();
    if (res.ok && data.success && data.tracks && data.tracks.length > 0) {
      const plId = 'pl-import-' + Date.now();
      playlists[plId] = { id: plId, name: data.name || "Imported Playlist", tracks: data.tracks, customCover: null, description: '', created_at: new Date().toISOString(), updated_at: new Date().toISOString() };
      store.savePlaylists(playlists);
      closeImportModal();
      showToast(`Imported "${data.name}" (${data.tracks.length} tracks)!`);
      if (activeView === 'favorites') renderFavoritesView(); else switchView('favorites');
    } else {
      showToast(data.detail || "Unable to import playlist.");
    }
  } catch (err) {
    showToast("Network error importing playlist.");
  } finally {
    if (btn) { btn.disabled = false; btn.innerText = "Import to MELO Hub"; }
  }
}

function openFullscreenPlayer() {
  const overlay = $id('fullscreenPlayerOverlay') || $id('fullscreenPlayer') || $id('playerOverlay');
  if (!overlay) return;

  if (overlay._animTimer) {
    clearTimeout(overlay._animTimer);
    overlay._animTimer = null;
  }

  // Clear any residual inline drag styles so CSS classes take over cleanly
  overlay.style.transform = '';
  overlay.style.opacity = '';
  overlay.style.borderRadius = '';
  overlay.style.transition = '';

  // Trigger CSS-driven upward slide
  overlay.classList.add('open', 'active');

  const current = (currentIndex !== -1 && playlist[currentIndex]) ? playlist[currentIndex] : window.currentTrack;
  if (current) {
    window.currentTrack = current;
    if (!parsedLyrics || parsedLyrics.length === 0) {
      fetchLyrics(current, activePlayToken);
    } else {
      updateLyricsSync();
    }
  }
}
window.openFullscreenPlayer = openFullscreenPlayer;

function closeFullscreenPlayer() {
  const overlay = $id('fullscreenPlayerOverlay') || $id('fullscreenPlayer') || $id('playerOverlay');
  if (!overlay || !overlay.classList.contains('open')) return;

  if (overlay._animTimer) {
    clearTimeout(overlay._animTimer);
    overlay._animTimer = null;
  }

  // 1. Remove the open class so CSS naturally animates back to translateY(100%) and visibility: hidden
  overlay.classList.remove('open', 'active');
  
  // 2. Clear any dragging inline styles to allow pure CSS transition to play out
  overlay.style.transform = '';
  overlay.style.opacity = '';
  overlay.style.borderRadius = '';
  overlay.style.transition = '';
}
window.closeFullscreenPlayer = closeFullscreenPlayer;

function openSettingsModal() { $id('settingsModal')?.classList.add('open'); }
function closeSettingsModal() { $id('settingsModal')?.classList.remove('open'); }

function toggleCurrentTrackFavorite() {
  const track = contextTrack || (currentIndex !== -1 ? playlist[currentIndex] : null);
  if (!track) return;
  store.setFavorite(track);
  syncSheetTrackInfo();
}

function actionAddToFavorites() { toggleCurrentTrackFavorite(); closeContextMenu(); }
function actionPlayContextTrack() {
  const track = contextTrack;
  if (!track) return;
  playlist = [track, ...playlist.filter(item => String(item.id) !== String(track.id))];
  currentPlaylistContextId = null;
  playIndex(0);
  closeContextMenu();
}

function actionPlayNext() {
  const track = contextTrack;
  if (!track) return;
  const ext = playlist.findIndex((item, i) => i > currentIndex && String(item.id) === String(track.id));
  if (ext >= 0) playlist.splice(ext, 1);
  playlist.splice(Math.max(0, currentIndex + 1), 0, track);
  persistPlaybackQueue();
  showToast('Playing next');
  closeContextMenu();
}

function actionAddToQueue() {
  const track = contextTrack;
  if (!track) return;
  playlist.push(track);
  persistPlaybackQueue();
  showToast('Added to queue');
  closeContextMenu();
}

function actionShareSong() {
  closeContextMenu();
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const shareData = {
    title: playlist[currentIndex].title,
    text: `Listen to "${playlist[currentIndex].title}" by ${playlist[currentIndex].artist} on MELO!`,
    url: window.location.origin
  };
  if (navigator.share) navigator.share(shareData).catch(() => {});
  else {
    navigator.clipboard.writeText(`${shareData.text} ${shareData.url}`);
    showToast("Song link copied to clipboard!");
  }
}

function actionViewCredits() {
  closeContextMenu();
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const t = playlist[currentIndex];
  showMeloConfirmation({
    title: t.title,
    message: `${t.artist || 'Unknown'} · ${t.album || 'Single'} · ${t.duration || '—'}`,
    actionLabel: 'Close',
    action: () => {}
  });
}

function openAuthModal() {
  closeSettingsModal();
  $id('authModal')?.classList.add('open');
}

function closeAuthModal(e) {
  if (!e || e.target === $id('authModal') || e.target?.classList.contains('drag-handle')) {
    $id('authModal')?.classList.remove('open');
  }
}

function toggleAuthMode() {
  isRegisterMode = !isRegisterMode;
  if ($id('authTitle')) $id('authTitle').innerText = isRegisterMode ? 'Create Account' : 'Welcome to MELO';
  if ($id('authSubtitle')) $id('authSubtitle').innerText = isRegisterMode ? 'Join to sync your library anywhere.' : 'Sign in to sync your library across devices.';
  if ($id('authName')) $id('authName').style.display = isRegisterMode ? 'block' : 'none';
  if ($id('authSubmitBtn')) $id('authSubmitBtn').innerText = isRegisterMode ? 'Sign Up' : 'Log In';
  if ($id('authToggleLink')) $id('authToggleLink').innerText = isRegisterMode ? 'Already have an account? Log In' : "Don't have an account? Sign up";
}

async function handleAuthSubmit() {
  const email = $id('authEmail')?.value.trim();
  const password = $id('authPassword')?.value;
  const name = $id('authName')?.value.trim();
  const btn = $id('authSubmitBtn');

  if (!email || !password || (isRegisterMode && !name)) {
    showToast("Please fill in all fields.");
    return;
  }

  if (btn) { btn.disabled = true; btn.innerText = "Please wait..."; }
  const endpoint = isRegisterMode ? '/api/auth/register' : '/api/auth/login';
  const payload = isRegisterMode ? { email, password, display_name: name } : { email, password };

  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    if (res.ok) {
      showToast(isRegisterMode ? "Account created!" : "Logged in successfully!");
      closeAuthModal();
      await checkAuthStatus();
      const hasLocalFavs = Object.keys(store.readScope('guest').favorites || {}).length > 0;
      const hasLocalPls = Object.keys(store.readScope('guest').playlists || {}).filter(k => !['pl-favorites', 'pl-downloads'].includes(k)).length > 0;
      if (hasLocalFavs || hasLocalPls) {
        $id('migrationModal')?.classList.add('open');
      } else {
        await store.pullFromCloud();
      }
    } else {
      showToast(data.detail || "Authentication failed.");
    }
  } catch (err) {
    showToast("Network error. Please try again.");
  } finally {
    if (btn) { btn.disabled = false; btn.innerText = isRegisterMode ? 'Sign Up' : 'Log In'; }
  }
}

async function checkAuthStatus() {
  try {
    const res = await fetch('/api/auth/me', { credentials: 'same-origin' });
    if (res.ok) {
      currentUser = await res.json();
      loadLastSyncedAt();
      await store.switchProfile(currentUser);
    } else {
      currentUser = null;
      await store.switchProfile(null);
    }
  } catch (e) {
    currentUser = null;
    await store.switchProfile(null);
  }
  updateAccountUI();
}

function updateAccountUI() {
  const btn = $id('navAccountBtn');
  if (!btn) return;
  if (currentUser) {
    btn.innerHTML = `<svg viewBox="0 0 24 24" style="width:14px;height:14px;fill:none;stroke:#fff;stroke-width:2;"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg><span>${accountText(currentUser.display_name)}</span>`;
    btn.onclick = () => openAccountView();
  } else {
    btn.innerHTML = `<svg viewBox="0 0 24 24" style="width:14px;height:14px;fill:none;stroke:#fff;stroke-width:2;"><path d="M15 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4M10 17l5-5-5-5M15 12H3"/></svg><span>Sign In</span>`;
    btn.onclick = () => openAuthModal();
  }
}

function openAccountView() {
  if (!currentUser) return openAuthModal();
  switchView('account');
}

function renderAccountView() {
  const vc = $id('viewContainer');
  if (!vc || !currentUser) return;

  const syncTimeStr = lastSyncedAt 
    ? `${Math.max(0, Math.round((Date.now() - lastSyncedAt.getTime()) / 60000))} min ago`
    : 'Not synced yet';

  const syncClass = syncState === 'syncing' ? 'sync-syncing'
    : syncState === 'queued' ? 'sync-queued'
    : syncState === 'paused' ? 'sync-paused'
    : 'sync-synced';

  const syncLabel = syncState === 'syncing' ? 'Syncing library…'
    : syncState === 'queued' ? 'Changes queued'
    : syncState === 'paused' ? 'Sync paused (offline)'
    : 'Library synced';

  vc.innerHTML = `
    <div class="stage-content account-stage">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" title="Back"><svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button>
        <h1>MELO Account</h1>
      </div>

      <div class="account-profile-card">
        <div class="account-avatar">${accountText(currentUser.display_name?.charAt(0).toUpperCase() || 'M')}</div>
        <div class="account-identity">
          <div class="account-name">${accountText(currentUser.display_name)}</div>
          <div class="account-email">${accountText(currentUser.email)}</div>
        </div>
      </div>

      <div class="account-section">
        <div class="section-heading"><h2>Sync Engine</h2></div>
        <div class="sync-card ${syncClass}">
          <div class="sync-status">
            <div class="sync-icon">☁</div>
            <div>
              <strong>${syncLabel}</strong>
              <small>Last synced: ${syncTimeStr}</small>
            </div>
          </div>
          <button class="pill-action-btn account-sync-button" onclick="store.pushToCloud(); showToast('Syncing with MELO Cloud…');">Sync Now</button>
        </div>
      </div>

      <div class="account-section">
        <div class="section-heading"><h2>Security & Credentials</h2></div>
        <div class="account-list">
          <button class="account-row" onclick="togglePasswordPanel()">
            <span class="account-row-icon">🔒</span>
            <span>
              <strong>Change Password</strong>
              <small>Update your account login password</small>
            </span>
            <span class="account-row-arrow" id="accountPasswordArrow">›</span>
          </button>
          <div class="account-password-panel" id="accountPasswordPanel" style="display:none;">
            <input type="password" id="oldPassInput" class="themed-pl-input" placeholder="Current Password" />
            <input type="password" id="newPassInput" class="themed-pl-input" placeholder="New Password (min 6 characters)" />
            <button class="pill-action-btn" onclick="executeChangePassword()">Save New Password</button>
          </div>
        </div>
      </div>

      <div class="account-section">
        <div class="section-heading"><h2>Session & Data</h2></div>
        <div class="account-list">
          <button class="account-row" onclick="executeLogout()">
            <span class="account-row-icon">↪</span>
            <span>
              <strong>Log Out</strong>
              <small>Sign out on this device</small>
            </span>
            <span class="account-row-arrow">›</span>
          </button>
          <button class="account-row account-row-danger" onclick="executeDeleteAccount()">
            <span class="account-row-icon">🗑</span>
            <span>
              <strong>Delete Account</strong>
              <small>Permanently delete account and cloud library</small>
            </span>
            <span class="account-row-arrow">›</span>
          </button>
        </div>
      </div>
    </div>
  `;
}

function togglePasswordPanel() {
  const p = $id('accountPasswordPanel');
  const a = $id('accountPasswordArrow');
  if (!p) return;
  const isHidden = p.style.display === 'none';
  p.style.display = isHidden ? 'block' : 'none';
  if (a) a.style.transform = isHidden ? 'rotate(90deg)' : 'none';
}

async function executeChangePassword() {
  const old_password = $id('oldPassInput')?.value;
  const new_password = $id('newPassInput')?.value;
  if (!old_password || !new_password) return showToast("Please fill in both password fields.");
  if (new_password.length < 6) return showToast("New password must be at least 6 characters.");
  try {
    const res = await fetch('/api/auth/change-password', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ old_password, new_password })
    });
    const data = await res.json();
    if (res.ok) {
      showToast("Password updated successfully!");
      if ($id('oldPassInput')) $id('oldPassInput').value = '';
      if ($id('newPassInput')) $id('newPassInput').value = '';
      togglePasswordPanel();
    } else {
      showToast(data.detail || "Failed to change password.");
    }
  } catch (err) {
    showToast("Network error.");
  }
}

async function executeLogout() {
  await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' });
  currentUser = null;
  await store.switchProfile(null);
  updateAccountUI();
  switchView('home');
  showToast("Logged out.");
}

function executeDeleteAccount() {
  showMeloConfirmation({
    title: 'Delete Account?',
    message: 'This permanently wipes your account and cloud library. Your downloaded offline songs will stay safely on this device.',
    actionLabel: 'Delete Permanently',
    danger: true,
    action: async () => {
      try {
        const res = await fetch('/api/auth/delete-account', { method: 'POST', credentials: 'same-origin' });
        if (res.ok) {
          currentUser = null;
          await store.switchProfile(null);
          updateAccountUI();
          switchView('home');
          showToast("Account deleted successfully.");
        } else {
          showToast("Failed to delete account.");
        }
      } catch (e) {
        showToast("Network error while deleting account.");
      }
    }
  });
}

function closeMigrationModal(e) {
  if (!e || e.target === $id('migrationModal') || e.target?.classList.contains('drag-handle')) {
    $id('migrationModal')?.classList.remove('open');
  }
}

async function executeLocalToCloudMigration() {
  showToast("Syncing your local library to MELO Cloud…");
  closeMigrationModal();
  const guestData = store.readScope('guest');
  if (guestData.favorites) {
    Object.assign(store.state.favorites, guestData.favorites);
  }
  if (guestData.playlists) {
    for (const k in guestData.playlists) {
      if (!['pl-downloads', 'pl-favorites'].includes(k)) {
        store.state.playlists[k] = guestData.playlists[k];
      }
    }
  }
  store.persist();
  store.restoreGlobals();
  await store.pushToCloud();
  showToast("Library successfully merged to Cloud! ☁️");
}

function removeHistoryItem(entryId) { store.removeHistory(entryId); renderHistoryView(); }

async function getOfflineRecords() {
  try {
    const db = await openMeloDB();
    return await new Promise((res) => {
      const req = db.transaction(IDB_STORE, 'readonly').objectStore(IDB_STORE).getAll();
      req.onsuccess = () => res(req.result || []);
      req.onerror = () => res([]);
    });
  } catch (e) { return []; }
}

function formatStorage(bytes) {
  if (!bytes) return '0 MB';
  const units = ['B', 'KB', 'MB', 'GB'];
  let val = bytes, idx = 0;
  while (val >= 1024 && idx < units.length - 1) { val /= 1024; idx++; }
  return `${val.toFixed(idx > 1 ? 1 : 0)} ${units[idx]}`;
}

async function renderOfflineVault() {
  activeView = 'offline';
  const vc = $id('viewContainer');
  vc.innerHTML = `
    <div class="stage-content library-stage">
      <div class="top-action-bar"><button class="circle-back-btn" onclick="goBack()" title="Back">
  <svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg></button><h1>Offline Vault</h1></div>
      <div id="offlineVaultContent" class="library-loading">Loading your downloads…</div>
    </div>`;
  const records = await getOfflineRecords();
  const usage = records.reduce((s, r) => s + (r.blob?.size || 0), 0);
  let available = 'Unavailable';
  try {
    const est = await navigator.storage?.estimate?.();
    if (est?.quota) available = formatStorage(Math.max(0, est.quota - (est.usage || 0)));
  } catch (e) {}

  const content = $id('offlineVaultContent');
  if (!content || activeView !== 'offline') return;
  const rows = records.map((r, i) => {
    window.__contextTrackMap[r.metadata.id] = r.metadata;
    return `
      <div class="track-row library-track-row" onclick="playlist=[window.__contextTrackMap['${r.metadata.id}']];currentPlaylistContextId='pl-downloads';playIndex(0)">
        <div class="tr-num">${i + 1}</div>
        <img class="tr-thumb" src="${r.metadata.thumbnail || ''}" loading="lazy"/>
        <div class="tr-info"><div class="tr-title">${accountText(r.metadata.title)}</div><div class="tr-artist">${accountText(r.metadata.artist)}</div></div>
        <div class="tr-album">${accountText(r.metadata.album || 'Offline')}</div>
        <div class="tr-time">Available</div>
        <button class="tr-remove-btn" onclick="event.stopPropagation(); removeOfflineDownload('${r.id}')" title="Remove download" style="width:28px;height:28px;font-size:0.8rem;">✕</button>
      </div>`;
  }).join('');

  content.innerHTML = `
    <section class="offline-storage-card">
      <span>MELO Offline Storage</span>
      <strong>Used: ${formatStorage(usage)}</strong>
      <small>Available: ${available} · Downloads: ${records.length} tracks</small>
      ${records.length ? '<button class="filter-chip offline-clear-btn" onclick="clearOfflineDownloads()">Clear Downloads</button>' : ''}
    </section>
    ${records.length ? `<div class="section-heading"><h2>Available Offline</h2></div>${rows}` : `<div class="library-empty"><strong>Nothing saved offline.</strong><span>Download music for listening without internet.</span><button class="pill-action-btn" onclick="switchView('search')">Explore Music</button></div>`}
  `;
}

async function removeOfflineDownload(trackId) {
  const db = await openMeloDB();
  await new Promise((res, rej) => {
    const req = db.transaction(IDB_STORE, 'readwrite').objectStore(IDB_STORE).delete(String(trackId));
    req.onsuccess = res; req.onerror = rej;
  });
  await syncDownloadedPlaylist();
  renderOfflineVault();
}

async function clearOfflineDownloads() {
  const db = await openMeloDB();
  await new Promise((res, rej) => {
    const req = db.transaction(IDB_STORE, 'readwrite').objectStore(IDB_STORE).clear();
    req.onsuccess = res; req.onerror = rej;
  });
  await syncDownloadedPlaylist();
  renderOfflineVault();
  showToast('Offline downloads cleared');
}

function playPlaylistContext(playlistId, shuffle = false) {
  const target = playlistId === 'pl-favorites' ? { tracks: Object.values(favorites) } : playlists[playlistId];
  if (!target?.tracks?.length) return;
  playlist = [...target.tracks];
  if (shuffle) playlist.sort(() => Math.random() - 0.5);
  currentPlaylistContextId = playlistId;
  playIndex(0);
}

function renderLovedTracks() {
  activeView = 'loved';
  const vc = $id('viewContainer');
  if (!vc) return;

  const tracks = Object.values(favorites);

  vc.innerHTML = `
    <div class="stage-content library-stage">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" title="Back">
          <svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <h1>Loved Tracks</h1>
      </div>
      ${tracks.length ? `
        <div class="library-toolbar">
          <div class="melo-select-wrapper">
            <select id="lovedSort" class="melo-themed-select">
              <option value="recent">Recently Added</option>
              <option value="title">Alphabetical</option>
              <option value="artist">Artist</option>
            </select>
          </div>
          <div style="display:flex; gap:10px; margin-left:auto;">
            <button class="pill-action-btn" onclick="playLovedTracks(false)">
              <svg viewBox="0 0 24 24" style="width:15px;height:15px;fill:#000;"><path d="M8 5v14l11-7z"/></svg>
              <span>Play All</span>
            </button>
            <button class="filter-chip" onclick="playLovedTracks(true)">Shuffle</button>
          </div>
        </div>
        <div id="lovedTrackList"></div>
      ` : `
        <div class="library-empty">
          <strong>No loved tracks yet</strong>
          <span>Songs you fall for will live here.</span>
          <button class="pill-action-btn" onclick="switchView('search')" style="margin-top:12px;">Explore Music</button>
        </div>
      `}
    </div>`;

  if (!tracks.length) return;

  const draw = () => {
    const sortVal = $id('lovedSort')?.value || 'recent';
    const sorted = [...tracks];

    sorted.sort((a, b) => {
      if (sortVal === 'artist') return String(a.artist || '').localeCompare(String(b.artist || ''));
      if (sortVal === 'title') return String(a.title || '').localeCompare(String(b.title || ''));
      return String(b.added_at || '').localeCompare(String(a.added_at || ''));
    });

    window.__lovedTracks = sorted;
    const listEl = $id('lovedTrackList');
    if (listEl) {
      listEl.innerHTML = sorted.map((track, index) => libraryTrackRow(track, index, { source: 'loved' })).join('');
    }
  };

  const sortSelect = $id('lovedSort');
  if (sortSelect) {
    sortSelect.addEventListener('change', draw);
  }
  
  draw();
}
window.renderLovedTracks = renderLovedTracks;

function playLovedTracks(shuffle) {
  const tracks = window.__lovedTracks || Object.values(favorites);
  if (!tracks.length) return;
  playlist = [...tracks];
  if (shuffle) playlist.sort(() => Math.random() - 0.5);
  currentPlaylistContextId = 'pl-favorites';
  playIndex(0);
}

function historyGroup(dateValue) {
  const d = new Date(dateValue), t = new Date(); t.setHours(0, 0, 0, 0);
  const y = new Date(t); y.setDate(t.getDate() - 1);
  if (d >= t) return 'Today';
  if (d >= y) return 'Yesterday';
  const w = new Date(t); w.setDate(t.getDate() - 7);
  return d >= w ? 'Earlier this week' : 'Earlier';
}

function renderHistoryView() {
  activeView = 'history';
  const entries = store.getHistory().filter(e => e.track).slice().reverse();
  const vc = $id('viewContainer');
  vc.innerHTML = `
    <div class="stage-content library-stage">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" title="Back">
          <svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <h1>Recently Played</h1>
      </div>
      <div id="historyTrackList"></div>
    </div>`;
  
  if (!entries.length) {
    $id('historyTrackList').innerHTML = `<div class="library-empty"><strong>Nothing played yet.</strong><span>Your listening journey starts here.</span><button class="pill-action-btn" onclick="switchView('search')">Explore Music</button></div>`;
    return;
  }
  const groups = {};
  entries.forEach(e => { const g = historyGroup(e.played_at); (groups[g] ||= []).push(e); });
  window.__historyTracks = entries.map(e => e.track);
  let globalIdx = 0;
  $id('historyTrackList').innerHTML = Object.entries(groups).map(([label, grp]) => `
    <div class="section-heading history-heading"><h2>${label}</h2></div>
    ${grp.map(e => libraryTrackRow(e.track, globalIdx++, { source: 'history', meta: new Date(e.played_at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) })).join('')}
  `).join('');
}

// ==========================================
// 16. EXPORTS & INITIALIZATION
// ==========================================
window.accountText = accountText;
window.normalizeTrackData = normalizeTrackData;
window.fmtTime = fmtTime;
window.showToast = showToast;
window.switchView = switchView;
window.goBack = goBack;
window.scrollToCategory = scrollToCategory;
window.promptQualitySelection = promptQualitySelection;
window.closeQualityModal = closeQualityModal;
window.selectQualityOption = selectQualityOption;
window.changeQuality = changeQuality;
window.openFullscreenPlayer = openFullscreenPlayer;
window.closeFullscreenPlayer = closeFullscreenPlayer;
window.openSettingsModal = openSettingsModal;
window.closeSettingsModal = closeSettingsModal;
window.openContextMenu = openContextMenu;
window.closeContextMenu = closeContextMenu;
window.openMenuForTrack = openMenuForTrack;
window.actionDownloadSong = actionDownloadSong;
window.promptImportSelection = promptImportSelection;
window.closeImportModal = closeImportModal;
window.executePlaylistImport = executePlaylistImport;
window.renderPlaylistCoverHTML = renderPlaylistCoverHTML;
window.getPlaylistHeroCoverURL = getPlaylistHeroCoverURL;
window.actionOpenAddToPlaylist = actionOpenAddToPlaylist;
window.closeAddToPlaylistModal = closeAddToPlaylistModal;
window.addTrackToSpecificPlaylist = addTrackToSpecificPlaylist;
window.handleCoverFileSelected = handleCoverFileSelected;
window.confirmCreateAndAddToPlaylist = confirmCreateAndAddToPlaylist;
window.openPlaylistActionMenu = openPlaylistActionMenu;
window.closePlaylistActionMenu = closePlaylistActionMenu;
window.actionFromMenuEditPlaylist = actionFromMenuEditPlaylist;
window.actionFromMenuAddSongs = actionFromMenuAddSongs;
window.closePlaylistSearchAddModal = closePlaylistSearchAddModal;
window.actionToggleRemoveSongsMode = actionToggleRemoveSongsMode;
window.removeTrackFromPlaylistDirect = removeTrackFromPlaylistDirect;
window.actionFromMenuDeletePlaylist = actionFromMenuDeletePlaylist;
window.deleteCustomPlaylist = deleteCustomPlaylist;
window.openPlaylistEditor = openPlaylistEditor;
window.closePlaylistEditModal = closePlaylistEditModal;
window.handleEditCoverFileSelected = handleEditCoverFileSelected;
window.confirmPlaylistEdit = confirmPlaylistEdit;
window.resetPlaylistCoverToCollage = resetPlaylistCoverToCollage;
window.toggleRepeatMode = toggleRepeatMode;
window.toggleShuffle = toggleShuffle;
window.togglePlay = togglePlay;
window.checkAndExpandInfiniteQueue = checkAndExpandInfiniteQueue;
window.nextTrack = nextTrack;
window.prevTrack = prevTrack;
window.switchPlayerSheetTab = switchPlayerSheetTab;
window.toggleCurrentTrackFavorite = toggleCurrentTrackFavorite;
window.actionAddToFavorites = actionAddToFavorites;
window.persistPlaybackQueue = persistPlaybackQueue;
window.showMeloConfirmation = showMeloConfirmation;
window.closeMeloConfirmation = closeMeloConfirmation;
window.confirmMeloAction = confirmMeloAction;
window.actionPlayContextTrack = actionPlayContextTrack;
window.actionPlayNext = actionPlayNext;
window.actionAddToQueue = actionAddToQueue;
window.actionShareSong = actionShareSong;
window.actionSleepTimerPrompt = actionSleepTimerPrompt;
window.setSleepTimerMinutes = setSleepTimerMinutes;
window.setSleepTimerEndOfTrack = setSleepTimerEndOfTrack;
window.cancelSleepTimer = cancelSleepTimer;
window.actionViewCredits = actionViewCredits;
window.generateVibrantColors = generateVibrantColors;
window.applyPlaylistDynamicColors = applyPlaylistDynamicColors;
window.updateArtworkPalette = updateArtworkPalette;
window.playIndex = playIndex;
window.setPlayState = setPlayState;
window.syncSheetTrackInfo = syncSheetTrackInfo;
window.fetchLyrics = fetchLyrics;
window.updateLyricsSync = updateLyricsSync;
window.renderSheetQueueList = renderSheetQueueList;
window.toggleFavTrackDirect = toggleFavTrackDirect;
window.renderHomeView = renderHomeView;
window.renderHomePagePlaylists = renderHomePagePlaylists;
window.loadForYouCatalog = loadForYouCatalog;
window.switchVibePreset = switchVibePreset;
window.playForYouAll = playForYouAll;
window.loadShelfCategory = loadShelfCategory;
window.renderGridContainer = renderGridContainer;
window.loadSearchShelf = loadSearchShelf;
window.renderSearchView = renderSearchView;
window.applySearchFilter = applySearchFilter;
window.executeLiveSearch = executeLiveSearch;
window.renderSimulatedGrid = renderSimulatedGrid;
window.openArtistView = openArtistView;
window.openAlbumView = openAlbumView;
window.clearSearchInput = clearSearchInput;
window.quickSearch = quickSearch;
window.renderRecentSearches = renderRecentSearches;
window.libraryTrackRow = libraryTrackRow;
window.playLibraryTrack = playLibraryTrack;
window.toggleLibraryFavorite = toggleLibraryFavorite;
window.removeFavoriteItem = removeFavoriteItem;
window.playPlaylistContext = playPlaylistContext;
window.renderFavoritesView = renderFavoritesView;
window.openPlaylistDetails = openPlaylistDetails;
window.renderLovedTracks = renderLovedTracks;
window.playLovedTracks = playLovedTracks;
window.historyGroup = historyGroup;
window.renderHistoryView = renderHistoryView;
window.removeHistoryItem = removeHistoryItem;
window.getOfflineRecords = getOfflineRecords;
window.formatStorage = formatStorage;
window.renderOfflineVault = renderOfflineVault;
window.removeOfflineDownload = removeOfflineDownload;
window.clearOfflineDownloads = clearOfflineDownloads;
window.removeFromQueue = removeFromQueue;
window.moveQueueTrack = moveQueueTrack;
window.clearQueue = clearQueue;
window.reorderPlaylistTrack = reorderPlaylistTrack;
window.openAuthModal = openAuthModal;
window.closeAuthModal = closeAuthModal;
window.toggleAuthMode = toggleAuthMode;
window.handleAuthSubmit = handleAuthSubmit;
window.checkAuthStatus = checkAuthStatus;
window.updateAccountUI = updateAccountUI;
window.openAccountView = openAccountView;
window.renderAccountView = renderAccountView;
window.togglePasswordPanel = togglePasswordPanel;
window.executeChangePassword = executeChangePassword;
window.executeLogout = executeLogout;
window.executeDeleteAccount = executeDeleteAccount;
window.closeMigrationModal = closeMigrationModal;
window.executeLocalToCloudMigration = executeLocalToCloudMigration;

function updateCinematicInfo() {
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const track = playlist[currentIndex];
  if ($id('cinematicTitle')) $id('cinematicTitle').innerText = track.title;
  if ($id('cinematicArtist')) $id('cinematicArtist').innerText = track.artist;
  if ($id('cinematicArtImg')) $id('cinematicArtImg').src = track.thumbnail || '';
}

function renderCinematicLyrics() {
  const container = $id('cinematicLyricsScroll');
  if (!container) return;
  if (!parsedLyrics || !parsedLyrics.length) {
    container.innerHTML = `<div class="cinematic-lyrics-line" style="opacity:0.4; cursor:default; font-size:1.8rem;">Lyrics aren’t available.</div>`;
    return;
  }
  container.innerHTML = '';
  parsedLyrics.forEach((l) => {
    const div = document.createElement('div');
    div.className = 'cinematic-lyrics-line';
    div.innerText = l.text;
    if (isSynced && typeof l.time === 'number') {
      div.onclick = () => {
        const audio = $id('audio');
        if (audio) {
          audio.currentTime = l.time;
          updateLyricsSync();
        }
      };
    }
    container.appendChild(div);
  });
}

async function actionOpenCinematicMode() {
  closeContextMenu();
  closeFullscreenPlayer();
  const overlay = $id('cinematicOverlay');
  if (!overlay) return;

  isCinematicActive = true;
  overlay.classList.add('open');
  updateCinematicInfo();
  renderCinematicLyrics();

  // Request fullscreen and attempt orientation lock
  try {
    if (document.documentElement.requestFullscreen) {
      await document.documentElement.requestFullscreen().catch(() => {});
    }
    if (screen.orientation && screen.orientation.lock) {
      await screen.orientation.lock('landscape').catch(() => {});
    }
  } catch (err) {}
}

async function closeCinematicMode() {
  const overlay = $id('cinematicOverlay');
  if (!overlay) return;
  isCinematicActive = false;
  overlay.classList.remove('open');

  try {
    if (screen.orientation && screen.orientation.unlock) {
      screen.orientation.unlock();
    }
    if (document.exitFullscreen && document.fullscreenElement) {
      await document.exitFullscreen().catch(() => {});
    }
  } catch (err) {}
}

// Current App Build Version (bump this string whenever you deploy updates)
const CURRENT_APP_VERSION = '2.5.5';

async function autoUpdateCache() {
  const savedVersion = localStorage.getItem('melo_app_version');

  // If the app version bumped or hasn't been set yet
  if (savedVersion !== CURRENT_APP_VERSION) {
    console.log(`[MELO] Upgrading from ${savedVersion || 'legacy'} to ${CURRENT_APP_VERSION}...`);

    // 1. Clear Cache Storage ONLY (Leaves IndexedDB & localStorage intact)
    if ('caches' in window) {
      try {
        const cacheKeys = await caches.keys();
        await Promise.all(cacheKeys.map(key => caches.delete(key)));
        console.log('[MELO] Stale service worker cache storage cleared.');
      } catch (err) {
        console.warn('[MELO] Error purging caches:', err);
      }
    }

    // 2. Force Service Worker to update from server
    if ('serviceWorker' in navigator) {
      try {
        const registration = await navigator.serviceWorker.getRegistration();
        if (registration) {
          await registration.update();
        }
      } catch (err) {
        console.warn('[MELO] Service worker update check skipped:', err);
      }
    }

    // 3. Save new version and reload
    localStorage.setItem('melo_app_version', CURRENT_APP_VERSION);
    window.location.reload();
    return;
  }

  // Guarded listener for background Service Worker activation to prevent reload loops
  if ('serviceWorker' in navigator) {
    let refreshing = false;
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (refreshing) return;
      refreshing = true;
      window.location.reload();
    });
  }
}

// Run update check immediately
autoUpdateCache();

// ==========================================
// MELO THEMED LOADER CONTROLLER
// ==========================================
function showMeloLoader(text = 'TUNING MELO...') {
  const loader = document.getElementById('meloLoader');
  const label = document.getElementById('meloLoaderText');
  if (label) label.textContent = text;
  if (loader) loader.classList.add('active');
}

function hideMeloLoader() {
  const loader = document.getElementById('meloLoader');
  if (loader) loader.classList.remove('active');
}

// ==========================================
// 17. RUNTIME INITIALIZATION & CANVAS RENDERERS
// ==========================================
window.actionOpenCinematicMode = actionOpenCinematicMode;
window.closeCinematicMode = closeCinematicMode;

document.addEventListener('DOMContentLoaded', () => {
  const audio = $id('audio');
  const mwc = $id('miniWaveCanvas');
  const swc = $id('scrubberWaveCanvas');

  syncDownloadedPlaylist();
  checkAuthStatus();
  initScrubberHandlers();
  initLyricsUserScroll();
  initFullscreenSwipeDown();
  restorePlaybackSession();

  // ==========================================
  // PLAYLIST CARD EVENT DELEGATION
  // ==========================================
  document.body.addEventListener('click', (e) => {
    const card = e.target.closest('.playlist-card') || e.target.closest('[data-playlist-id]');
    if (card) {
      e.preventDefault();
      const playlistId = card.getAttribute('data-playlist-id') || card.dataset.id;
      if (playlistId) {
        if (typeof openPlaylistDetails === 'function') {
          openPlaylistDetails(playlistId);
        } else if (typeof switchView === 'function') {
          switchView('playlist-detail', true, { id: playlistId });
        } else if (typeof loadPlaylist === 'function') {
          loadPlaylist(playlistId);
        }
      }
    }
  });
  // ==========================================
  // MINI PLAYER CONTROLS & SWIPE-SAFE DELEGATION
  // ==========================================
  const dockPlayer = document.querySelector('footer.dock-player') || $id('dockPlayerBar') || $id('dockPlayer');

  if (dockPlayer) {
    let startY = 0;
    let startX = 0;
    let isTouchMove = false;

    // Track touch coordinates to differentiate between scroll/swipe and a real tap
    dockPlayer.addEventListener('touchstart', (e) => {
      const t = e.touches[0];
      startY = t.clientY;
      startX = t.clientX;
      isTouchMove = false;
    }, { passive: true });

    dockPlayer.addEventListener('touchmove', (e) => {
      const t = e.touches[0];
      // If user dragged finger more than 8px vertically or horizontally, flag as scrolling
      if (Math.abs(t.clientY - startY) > 8 || Math.abs(t.clientX - startX) > 8) {
        isTouchMove = true;
      }
    }, { passive: true });

    dockPlayer.addEventListener('click', (e) => {
      // 1. If user was fast-swiping or scrolling, abort completely
      if (isTouchMove) {
        isTouchMove = false;
        return;
      }

      // 2. Play / Pause Button handling
      const playBtn = e.target.closest('#dockPlayBtn, #mDockPlayBtn');
      if (playBtn) {
        e.preventDefault();
        e.stopPropagation();
        togglePlay();
        return;
      }

      // 3. Ignore control clicks, scrubber inputs, or wave visualizer
      const isControl = e.target.closest('button, .control-btn, input, .dock-controls, #miniWaveCanvas');
      if (isControl) {
        return;
      }

      // 4. Legitimate single tap opens fullscreen
      openFullscreenPlayer();
    });
  }

  // ==========================================
  // ANDROID HARDWARE BACK BUTTON SUPPORT
  // ==========================================
  window.addEventListener('popstate', (e) => {
  const fsOverlay = $id('fullscreenPlayerOverlay');
  if (fsOverlay && fsOverlay.classList.contains('open')) {
    closeFullscreenPlayer();
    return;
  }
  
  if (activeView === 'favorites' || activeView === 'search' || activeView === 'history' || activeView === 'offline' || activeView === 'account') {
    switchView('home', false);
  } else if (activeView === 'playlist-detail' || activeView === 'album-detail' || activeView === 'artist-detail') {
    goBack();
  } else {
    goBack();
  }
});

  window.addEventListener('online', () => { 
  if (currentUser) store.pushToCloud();
  if (activeView === 'home') renderHomeView();
});
window.addEventListener('offline', () => { 
  if (currentUser) setSyncState('paused');
  if (activeView === 'home') renderHomeOfflineView();
});

  if (audio) {
    // Show loader when the network is buffering, seeking, or track stalls
    audio.addEventListener('waiting', () => {
      showMeloLoader('BUFFERING STREAM...');
    });

    // Dismiss loader when audio resumes playing or becomes ready
    audio.addEventListener('playing', () => {
      hideMeloLoader();
    });

    audio.addEventListener('canplay', () => {
      hideMeloLoader();
    });

    audio.addEventListener('timeupdate', () => {
      if (audio.paused || isNaN(audio.duration)) return;
      if (!isDraggingScrubber) {
        const p = audio.currentTime / audio.duration;
        updateScrubberVisuals(p);
        if ($id('timeCurrent')) $id('timeCurrent').innerText = fmtTime(audio.currentTime);
        if ($id('timeDuration')) $id('timeDuration').innerText = fmtTime(audio.duration);
        if ($id('sheetTimeCur')) $id('sheetTimeCur').innerText = fmtTime(audio.currentTime);
        if ($id('sheetTimeDur')) $id('sheetTimeDur').innerText = fmtTime(audio.duration);
      }
      updateLyricsSync();

      if (listeningCandidate && !listeningCandidate.recorded) {
        const threshold = audio.duration > 0 ? Math.min(30, audio.duration * 0.5) : 30;
        if (audio.currentTime >= threshold) {
          listeningCandidate.recorded = true;
          store.recordListening(listeningCandidate.track);
        }
      }
    });

    audio.addEventListener('ended', () => {
      if (sleepTimerEndsAt === -1) {
        audio.pause();
        setPlayState(false);
        showToast("Sleep timer: Paused at track end");
        cancelSleepTimer();
        return;
      }
      nextTrack();
    });

    audio.addEventListener('error', () => {
      hideMeloLoader();
      showToast("Stream loading error. Skipping forward...");
      setTimeout(() => nextTrack(), 1500);
    });
  }

  // Mini-player tidal wave visualizer
  const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const miniCtx = mwc ? mwc.getContext('2d') : null;
  let wavePhase = 0, colorShift = 0, currentAmplitude = 1.0;

  function resizeMiniWave() {
    if (!mwc || !miniCtx) return;
    const dpr = window.devicePixelRatio || 1;
    mwc.width = mwc.offsetWidth * dpr;
    mwc.height = mwc.offsetHeight * dpr;
    miniCtx.setTransform(1, 0, 0, 1, 0, 0);
    miniCtx.scale(dpr, dpr);
  }
  window.addEventListener('resize', resizeMiniWave);

  function renderTidalLightWave() {
    if (!mwc || !miniCtx || !audio) return requestAnimationFrame(renderTidalLightWave);
    const w = mwc.offsetWidth;
    const h = mwc.offsetHeight;
    miniCtx.clearRect(0, 0, w, h);

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
      miniCtx.save();
      miniCtx.beginPath();
      miniCtx.moveTo(0, h);
      for (let x = 0; x <= w; x += 3) {
        const y = midY + Math.sin(x * t.freq + (wavePhase * t.speed) + t.phase) * currentAmplitude;
        miniCtx.lineTo(x, y);
      }
      miniCtx.lineTo(w, h);
      miniCtx.closePath();

      const lightGrad = miniCtx.createLinearGradient(0, midY - currentAmplitude, 0, h);
      lightGrad.addColorStop(0, `hsla(${(colorShift + t.shift) % 360}, 90%, 65%, 0.2)`);
      lightGrad.addColorStop(1, `transparent`);
      miniCtx.fillStyle = lightGrad;
      miniCtx.fill();
      miniCtx.restore();

      miniCtx.save();
      miniCtx.beginPath();
      miniCtx.lineWidth = 1.6;
      const strokeGrad = miniCtx.createLinearGradient(0, 0, w, 0);
      strokeGrad.addColorStop(0, `hsla(${(colorShift + t.shift) % 360}, 90%, 70%, ${t.alpha})`);
      strokeGrad.addColorStop(0.5, `hsla(${(colorShift + t.shift + 60) % 360}, 90%, 65%, ${t.alpha})`);
      strokeGrad.addColorStop(1, `hsla(${(colorShift + t.shift + 120) % 360}, 90%, 70%, ${t.alpha})`);
      miniCtx.strokeStyle = strokeGrad;

      for (let x = 0; x <= w; x += 2) {
        const y = midY + Math.sin(x * t.freq + (wavePhase * t.speed) + t.phase) * currentAmplitude;
        if (x === 0) miniCtx.moveTo(x, y);
        else miniCtx.lineTo(x, y);
      }
      miniCtx.stroke();
      miniCtx.restore();
    });

    requestAnimationFrame(renderTidalLightWave);
  }
  setTimeout(() => { resizeMiniWave(); renderTidalLightWave(); }, 60);

  // Scrubber live wave
  const scrubberWaveCtx = swc ? swc.getContext('2d') : null;
  let scrubberWavePhase = 0;

  function renderScrubberLiveWave() {
    if (!swc || !scrubberWaveCtx) return;
    const trackBase = $id('scrubberTrackBase');
    const w = trackBase ? trackBase.offsetWidth : 300;
    const h = swc.offsetHeight || 14;
    const dpr = window.devicePixelRatio || 1;

    if (swc.width !== w * dpr || swc.height !== h * dpr) {
      swc.width = w * dpr;
      swc.height = h * dpr;
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

  // Fluid blob mesh background
  const fluidCanvases = [$id('fluidMeshCanvas'), $id('cinematicMeshCanvas')];
  let fluidTime = 0;
  let lastFluidFrame = 0;

  function resizeFluidCanvases() {
    fluidCanvases.forEach(canv => {
      if (!canv) return;
      canv.width = Math.floor(window.innerWidth / 4);
      canv.height = Math.floor(window.innerHeight / 4);
    });
  }
  window.addEventListener('resize', resizeFluidCanvases);
  setTimeout(resizeFluidCanvases, 40);

  function renderLiveFluidMesh(timestamp) {
    const isSheetOpen = $id('fullscreenPlayerOverlay')?.classList.contains('open');
    if ((!isSheetOpen && !isCinematicActive) || prefersReducedMotion || document.hidden) {
      return requestAnimationFrame(renderLiveFluidMesh);
    }

    if (timestamp - lastFluidFrame < 33) {
      return requestAnimationFrame(renderLiveFluidMesh);
    }
    lastFluidFrame = timestamp;

    fluidTime += 0.008;
    const computedStyle = getComputedStyle(document.documentElement);
    const color1 = computedStyle.getPropertyValue('--mesh-color-1').trim() || 'rgba(250, 45, 72, 0.95)';
    const color2 = computedStyle.getPropertyValue('--mesh-color-2').trim() || 'rgba(192, 38, 211, 0.88)';

    fluidCanvases.forEach(canv => {
      if (!canv) return;
      const fCtx = canv.getContext('2d');
      const w = canv.width, h = canv.height;
      fCtx.clearRect(0, 0, w, h);

      const cx1 = w * (0.35 + 0.25 * Math.sin(fluidTime));
      const cy1 = h * (0.35 + 0.25 * Math.cos(fluidTime * 0.8));
      const g1 = fCtx.createRadialGradient(cx1, cy1, 0, cx1, cy1, w * 0.95);
      g1.addColorStop(0, color1);
      g1.addColorStop(1, 'transparent');
      fCtx.fillStyle = g1;
      fCtx.fillRect(0, 0, w, h);

      const cx2 = w * (0.65 + 0.25 * Math.cos(fluidTime * 1.1));
      const cy2 = h * (0.55 + 0.25 * Math.sin(fluidTime * 0.7)); // Changed to 0.55 so gradient remains bounded
      const g2 = fCtx.createRadialGradient(cx2, cy2, 0, cx2, cy2, w * 0.9);
      g2.addColorStop(0, color2);
      g2.addColorStop(1, 'transparent');
      fCtx.fillStyle = g2;
      fCtx.fillRect(0, 0, w, h);
    });

    requestAnimationFrame(renderLiveFluidMesh);
  }
  requestAnimationFrame(renderLiveFluidMesh);

  // Initialize Home View cleanly
  switchView('home', false);
});