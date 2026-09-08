// ==========================================
// 1. GLOBAL SCOPE, STATE & INDEXEDDB OFFLINE
// ==========================================

let playlist = [];
let forYouTracks = [];
let categoryData = {};
let currentIndex = -1;
let currentPlaylistContextId = null; // Strictly locks queue when playing custom playlists
let favorites = JSON.parse(localStorage.getItem('melo_favorites') || '{}');
let playlists = JSON.parse(localStorage.getItem('melo_playlists') || '{"pl-favorites":{"id":"pl-favorites","name":"Favorites","tracks":[],"customCover":null}}');
let playHistory = JSON.parse(localStorage.getItem('melo_history') || '[]');
let parsedLyrics = [];
let isSynced = false;
let activeView = 'home';
let navigationHistory = ['home'];
let isShuffle = false;
let repeatMode = 'none';
let sleepTimerId = null;
let currentPrimaryHex = '#fa2d48';
let activePlayToken = 0;
let selectedQuality = localStorage.getItem('melo_quality') || '320';
let currentVibe = 'Flow';
let pendingTrackForPlaylist = null;
let newPlaylistTempCover = null;
let currentEditingPlId = null;
let editPlTempCover = null;
let isFetchingInfiniteQueue = false;
let isRemoveSongsMode = false;
let downloadedTrackIds = new Set(); // Globally tracks offline songs

// Keep Render.com active by pinging the backend periodically
setInterval(() => {
  fetch('/api/ping').catch(() => {});
}, 10 * 60 * 1000);

// IndexedDB Helper for Storing Song Blobs
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
    const store = tx.objectStore(IDB_STORE);
    store.put({
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
      const store = tx.objectStore(IDB_STORE);
      const req = store.get(String(trackId));
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
    const store = tx.objectStore(IDB_STORE);
    const req = store.getAll();
    req.onsuccess = () => {
      const records = req.result || [];
      if (!playlists['pl-downloads']) {
        playlists['pl-downloads'] = {
          id: 'pl-downloads',
          name: 'Downloaded Songs',
          tracks: [],
          customCover: null
        };
      }
      playlists['pl-downloads'].tracks = records.map(r => r.metadata);
      downloadedTrackIds = new Set(records.map(r => String(r.id)));
      localStorage.setItem('melo_playlists', JSON.stringify(playlists));
      if (activeView === 'favorites') renderFavoritesView();
    };
  } catch (e) {}
}

// Toast Utility
function showToast(msg) {
  const t = document.getElementById('meloToast');
  if (!t) return;
  t.innerText = msg;
  t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 2600);
}

// Format Seconds to MM:SS
function fmtTime(s) {
  if (isNaN(s) || s === null || s === undefined) return "0:00";
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return `${m}:${sec < 10 ? '0' : ''}${sec}`;
}

// Global Navigation
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

// Quality Modal
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
  localStorage.setItem('melo_quality', val);
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

// Fullscreen Player Controls
window.openFullscreenPlayer = function () {
  const overlay = document.getElementById('fullscreenPlayerOverlay');
  if (!overlay) return;
  overlay.style.transform = 'translate3d(0, 0, 0)';
  overlay.classList.add('open');
  syncSheetTrackInfo();
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

window.openContextMenu = function () {
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const track = playlist[currentIndex];
  
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

// ====================================================
// 2. OFFLINE DOWNLOAD WITH INDEXEDDB & SPINNER
// ====================================================
window.actionDownloadSong = async function () {
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const track = playlist[currentIndex];

  const dlRow = document.getElementById('ctxDownloadRow');
  const originalHtml = dlRow ? dlRow.innerHTML : null;

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

    const blob = await resp.blob();

    // Save locally
    await saveTrackToOfflineDB(track, blob);
    await syncDownloadedPlaylist();

    // Trigger file download
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = `${track.title} - ${track.artist}.m4a`;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);

    showToast(`Offline track saved!`);
  } catch (err) {
    showToast("Download failed. Check your network.");
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
// 3. PLAYLIST IMPORT ENGINE
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
        customCover: null
      };
      localStorage.setItem('melo_playlists', JSON.stringify(playlists));
      window.closeImportModal();
      showToast(`Imported "${data.name}" (${data.tracks.length} tracks)!`);
      if (activeView === 'favorites') renderFavoritesView();
      else window.switchView('favorites');
    } else {
      showToast(data.detail || "Unable to import playlist. Please check the URL.");
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
// 4. 2X2 COLLAGE & COVER HELPERS
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
  pendingTrackForPlaylist = trackObj || (currentIndex !== -1 ? playlist[currentIndex] : null);
  if (!pendingTrackForPlaylist) return;

  const modal = document.getElementById('addToPlaylistModal');
  const listEl = document.getElementById('playlistOptionsList');
  if (!modal || !listEl) return;

  listEl.innerHTML = '';
  Object.values(playlists).forEach(pl => {
    if (pl.id === 'pl-downloads') return; // Do not manually add to downloads
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
      showToast(`Saved & Added to Downloaded Songs!`);
    } catch (e) {
      showToast("Failed to download track offline.");
    }
    return;
  }

  playlists[plId].tracks.push(trackToSave);
  localStorage.setItem('melo_playlists', JSON.stringify(playlists));
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
  playlists[id] = {
    id,
    name: title,
    tracks: pendingTrackForPlaylist ? [pendingTrackForPlaylist] : [],
    customCover: newPlaylistTempCover || null
  };
  localStorage.setItem('melo_playlists', JSON.stringify(playlists));

  showToast(`Created & Saved to "${title}"!`);
  if (input) input.value = '';
  newPlaylistTempCover = null;
  const prev = document.getElementById('coverUploadPreview');
  if (prev) prev.style.display = 'none';
  const labelText = document.getElementById('coverUploadText');
  if (labelText) labelText.innerText = "Choose Custom Photo Cover (Optional)";

  window.closeAddToPlaylistModal();
  if (activeView === 'favorites') renderFavoritesView();
};

// ====================================================
// 5. UNIFIED PLAYLIST EDIT & ACTION MODALS
// ====================================================
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
                localStorage.setItem('melo_playlists', JSON.stringify(playlists));
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
  localStorage.setItem('melo_playlists', JSON.stringify(playlists));
  showToast(`Removed "${trackTitle}"`);
  openPlaylistDetails(plId);
};

window.actionFromMenuDeletePlaylist = function () {
  window.closePlaylistActionMenu();
  if (currentEditingPlId) window.deleteCustomPlaylist(currentEditingPlId);
};

// Unified Playlist Edit Modal (Single Edit Flow)
window.openPlaylistEditor = function (plId) {
  currentEditingPlId = plId;
  editPlTempCover = null;
  const pl = playlists[plId];
  if (!pl) return;

  const modal = document.getElementById('playlistEditModal');
  const nameInput = document.getElementById('editPlNameInput');
  const preview = document.getElementById('editCoverUploadPreview');
  const labelText = document.getElementById('editCoverUploadText');

  if (nameInput) nameInput.value = pl.name;
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

  if (editPlTempCover) {
    pl.customCover = editPlTempCover;
  }

  localStorage.setItem('melo_playlists', JSON.stringify(playlists));
  showToast("Playlist updated successfully!");
  window.closePlaylistEditModal();
  openPlaylistDetails(currentEditingPlId);
};

window.resetPlaylistCoverToCollage = function (plId) {
  if (!playlists[plId]) return;
  playlists[plId].customCover = null;
  localStorage.setItem('melo_playlists', JSON.stringify(playlists));
  showToast("Reverted cover to automatic collage!");
  window.closePlaylistEditModal();
  openPlaylistDetails(plId);
};

// Transport Modes
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

// ====================================================
// 6. QUEUE ENGINE (STRICT PURITY: NO RANDOM INJECTIONS IN PLAYLISTS)
// ====================================================
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
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const track = playlist[currentIndex];
  if (favorites[track.id]) delete favorites[track.id];
  else favorites[track.id] = track;
  localStorage.setItem('melo_favorites', JSON.stringify(favorites));
  syncSheetTrackInfo();
};

window.actionAddToFavorites = function () {
  window.toggleCurrentTrackFavorite();
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
  const minutes = prompt("Enter sleep timer in minutes (15, 30, 45, 60 or 0 to turn off):", "30");
  if (minutes !== null) {
    const min = parseInt(minutes);
    if (sleepTimerId) clearTimeout(sleepTimerId);
    if (!isNaN(min) && min > 0) {
      sleepTimerId = setTimeout(() => {
        const audio = document.getElementById('audio');
        if (audio) audio.pause();
        setPlayState(false);
        showToast("Sleep timer reached. Good night!");
      }, min * 60 * 1000);
      showToast(`Sleep timer set for ${min} minutes.`);
    } else {
      sleepTimerId = null;
      showToast("Sleep timer turned off.");
    }
  }
};

window.actionViewCredits = function () {
  window.closeContextMenu();
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const track = playlist[currentIndex];
  alert(`Title: ${track.title}\nArtist: ${track.artist}\nAlbum: ${track.album || 'Single'}\nDuration: ${track.duration}\nTrack ID: ${track.id}`);
};

// ==========================================
// 7. PLAYBACK & VIVID COLOR HARMONY
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
  
  document.documentElement.style.setProperty('--pl-dynamic-bg', `rgba(${r0}, ${g0}, ${b0}, 0.7)`);
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

  playHistory.push(track.id);
  if (playHistory.length > 50) playHistory.shift();
  localStorage.setItem('melo_history', JSON.stringify(playHistory));

  const dockTitle = document.getElementById('dockTitle');
  const dockArtist = document.getElementById('dockArtist');
  const dockThumb = document.getElementById('dockThumb');

  if (dockTitle) dockTitle.innerText = track.title;
  if (dockArtist) dockArtist.innerText = track.artist;
  if (dockThumb) dockThumb.src = track.thumbnail || '';

  syncSheetTrackInfo();

  // Reveal miniplayer with active spring animation
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
    }
  } catch (err) {
    if (token === activePlayToken && container) {
      container.innerHTML = `<div class="lyrics-line">Lyrics unavailable.</div>`;
    }
  }
}

// ====================================================
// 8. CLEAN FULL-WIDTH QUEUE VIEW
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
  localStorage.setItem('melo_favorites', JSON.stringify(favorites));
};

// ==========================================
// 9. HOME, SEARCH & CLEAN MUSIC HUB
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
  window.loadShelfCategory('Top Hindi Songs 2026', 'trendingGrid');
  window.loadShelfCategory('Bollywood Romantic Hits', 'bollywoodGrid');
  window.loadShelfCategory('Punjabi Hits 2026', 'punjabiGrid');
  window.loadShelfCategory('Indian Indie Songs', 'indieGrid');
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
      currentPlaylistContextId = null; // Radio / Discovery allows recommendations
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

window.removeFavoriteItem = function (e, trackId) {
  e.stopPropagation();
  delete favorites[trackId];
  localStorage.setItem('melo_favorites', JSON.stringify(favorites));
  renderFavoritesView();
};

// ----------------------------------------------------
// 10. MASSIVE IMMERSIVE PLAYLIST WITH COLOR FADE TO SONG LIST
// ----------------------------------------------------
function openPlaylistDetails(plId) {
  if (plId === 'pl-favorites') {
    playlists['pl-favorites'].tracks = Object.values(favorites);
  }

  const pl = playlists[plId];
  const viewContainer = document.getElementById('viewContainer');
  if (!viewContainer || !pl) return;

  const leadImage = getPlaylistHeroCoverURL(pl);
  applyPlaylistDynamicColors(leadImage, pl.name);

  const bgStyle = leadImage ? `style="background-image: url('${leadImage}');"` : '';
  const isProtected = plId === 'pl-favorites' || plId === 'pl-downloads';

  viewContainer.innerHTML = `
    <div class="playlist-immersive-view">
      <!-- Massive Immersive Hero Cover Header -->
      <div class="playlist-immersive-hero" ${bgStyle}>
        <!-- Top Navigation Row -->
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
          <div class="playlist-immersive-stats">${pl.tracks.length} Songs • High Fidelity Lossless Audio</div>

          <div class="playlist-immersive-actions">
            ${pl.tracks.length > 0 ? `
              <button class="pill-action-btn" onclick="playPlaylistContext('${plId}')">
                <svg viewBox="0 0 24 24" style="width:16px;height:16px;fill:#000;"><path d="M8 5v14l11-7z"/></svg>
                <span>Play All</span>
              </button>
            ` : ''}
            ${isRemoveSongsMode ? `
              <button class="filter-chip" style="background:#fa2d48; color:#fff; border-color:#fa2d48;" onclick="actionToggleRemoveSongsMode()">
                Done Removing
              </button>
            ` : ''}
          </div>
        </div>
      </div>

      <!-- Color Fades Directly Down Into Tracklist -->
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
      row.className = 'track-row';
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
        <div class="tr-num">${i + 1}</div>
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
        `}
      `;
      box.appendChild(row);
    });
  }
}

window.playPlaylistContext = function (plId) {
  const pl = playlists[plId];
  if (pl && pl.tracks.length > 0) {
    currentPlaylistContextId = plId; // Strict playlist isolation
    playlist = [...pl.tracks];
    window.playIndex(0);
  }
};

window.deleteCustomPlaylist = function (plId) {
  if (confirm("Are you sure you want to delete this playlist?")) {
    delete playlists[plId];
    localStorage.setItem('melo_playlists', JSON.stringify(playlists));
    renderFavoritesView();
  }
};

// ==========================================
// 11. DOM READY & CANVASES
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
      const cy1 = h * (0.35 + 0.25 * Math.cos(fluidTime * 0.8));
      const g1 = fCtx.createRadialGradient(cx1, cy1, 0, cx1, cy1, w * 0.95);
      g1.addColorStop(0, color1);
      g1.addColorStop(1, 'transparent');
      fCtx.fillStyle = g1;
      fCtx.fillRect(0, 0, w, h);

      const cx2 = w * (0.65 + 0.25 * Math.cos(fluidTime * 1.1));
      const cy2 = h * (0.65 + 0.25 * Math.sin(fluidTime * 0.7));
      const g2 = fCtx.createRadialGradient(cx2, cy2, 0, cx2, cy2, w * 0.9);
      g2.addColorStop(0, color2);
      g2.addColorStop(1, 'transparent');
      fCtx.fillStyle = g2;
      fCtx.fillRect(0, 0, w, h);
    });

    requestAnimationFrame(renderLiveFluidMesh);
  }
  setTimeout(renderLiveFluidMesh, 100);

  // Audio Updates
  if (audio) {
    audio.ontimeupdate = () => {
      if (audio.duration) {
        const pct = (audio.currentTime / audio.duration) * 100;
        if (dockScrubber) dockScrubber.value = pct;

        if (scrubberPlayedZone) scrubberPlayedZone.style.setProperty('--scrubber-pct', `${pct}%`);
        if (scrubberThumbIndicator) scrubberThumbIndicator.style.setProperty('--scrubber-pct', `${pct}%`);

        const cur = fmtTime(audio.currentTime);
        const dur = fmtTime(audio.duration);

        if (timeCurrent) timeCurrent.innerText = cur;
        if (timeDuration) timeDuration.innerText = dur;
        if (sheetTimeCur) sheetTimeCur.innerText = cur;
        if (sheetTimeDur) sheetTimeDur.innerText = dur;
      }

      if (isSynced && parsedLyrics.length > 0) {
        const curTime = audio.currentTime;
        let activeIdx = -1;
        for (let i = 0; i < parsedLyrics.length; i++) {
          if (curTime >= parsedLyrics[i].time - 0.2) activeIdx = i;
          else break;
        }

        if (activeIdx !== -1) {
          const lines = document.querySelectorAll('#sheetViewLyrics .lyrics-line');
          lines.forEach((el, idx) => {
            if (idx === activeIdx) {
              if (!el.classList.contains('active')) {
                el.classList.add('active');
                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
              }
            } else {
              el.classList.remove('active');
            }
          });
        }
      }
    };

    audio.onended = () => window.nextTrack();

    if (dockScrubber) {
      dockScrubber.oninput = (e) => {
        if (audio.duration) audio.currentTime = (e.target.value / 100) * audio.duration;
      };
    }

    const dockVol = document.getElementById('dockVol');
    if (dockVol) {
      dockVol.oninput = (e) => { audio.volume = e.target.value; };
    }
  }

  // Smooth Swipe-Down Dismissal Fix
  const overlay = document.getElementById('fullscreenPlayerOverlay');
  let touchStartY = 0;
  let currentTouchY = 0;
  let isDraggingOverlay = false;

  if (overlay) {
    overlay.addEventListener('touchstart', (e) => {
      const scrollableLyrics = document.getElementById('sheetViewLyrics');
      const scrollableQueue = document.getElementById('sheetViewQueue');
      const atTop = (window.getComputedStyle(scrollableLyrics).display === 'none' && window.getComputedStyle(scrollableQueue).display === 'none') ||
                    (scrollableLyrics.scrollTop <= 0 && scrollableQueue.scrollTop <= 0);

      if (atTop || e.target.closest('.drag-handle') || e.target.closest('.player-sheet-header')) {
        touchStartY = e.touches[0].clientY;
        currentTouchY = touchStartY;
        isDraggingOverlay = true;
      }
    }, { passive: true });

    overlay.addEventListener('touchmove', (e) => {
      if (!isDraggingOverlay) return;
      currentTouchY = e.touches[0].clientY;
      const deltaY = currentTouchY - touchStartY;

      if (deltaY > 0) {
        e.preventDefault(); // STOPS browser native scrolling conflict, making drag perfectly smooth
        overlay.classList.add('dragging');
        overlay.style.transform = `translate3d(0, ${deltaY}px, 0)`;
      }
    }, { passive: false });

    overlay.addEventListener('touchend', () => {
      if (!isDraggingOverlay) return;
      isDraggingOverlay = false;
      overlay.classList.remove('dragging');
      const deltaY = currentTouchY - touchStartY;

      if (deltaY > 90) {
        window.closeFullscreenPlayer();
      } else {
        overlay.style.transform = 'translate3d(0, 0, 0)';
      }
      touchStartY = 0;
      currentTouchY = 0;
    });
  }

  window.addEventListener('keydown', (e) => {
    if (['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) return;
    if (e.code === 'Space') {
      e.preventDefault();
      window.togglePlay();
    } else if (e.key === 'Escape') {
      window.closeFullscreenPlayer();
      window.closeContextMenu();
      window.closeSettingsModal();
      window.closeAddToPlaylistModal();
      window.closePlaylistEditModal();
      window.closePlaylistActionMenu();
      window.closePlaylistSearchAddModal();
    }
  });

  renderHomeView();
});