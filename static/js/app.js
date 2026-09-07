// ==========================================
// 1. GLOBAL SCOPE SAFE GUARDS & PWA CONTROLLER
// (Defined immediately on window so onclick NEVER fails)
// ==========================================

let deferredPwaPrompt = null;
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredPwaPrompt = e;
  setTimeout(() => {
    const pwaBanner = document.getElementById('pwaBanner');
    if (pwaBanner) pwaBanner.classList.add('visible');
  }, 2500);
});

window.triggerPwaInstall = function () {
  if (!deferredPwaPrompt) return;
  deferredPwaPrompt.prompt();
  deferredPwaPrompt.userChoice.then(() => {
    window.dismissPwaBanner();
    deferredPwaPrompt = null;
  });
};

window.dismissPwaBanner = function () {
  const pwaBanner = document.getElementById('pwaBanner');
  if (pwaBanner) pwaBanner.classList.remove('visible');
};

// Global App State
let playlist = [];
let heroTracks = [];
let categoryData = {};
let currentIndex = -1;
let favorites = JSON.parse(localStorage.getItem('melo_favorites') || '{}');
let playlists = JSON.parse(localStorage.getItem('melo_playlists') || '{"pl-favorites":{"id":"pl-favorites","name":"Favorites","tracks":[]}}');
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

// Global Player & Navigation Stubs
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

window.promptQualitySelection = function () {
  const q = prompt("Select Audio Bitrate (320, 160, 96):", selectedQuality);
  if (['320', '160', '96'].includes(q)) window.changeQuality(q);
};

window.openFullscreenPlayer = function () {
  document.getElementById('fullscreenPlayerOverlay')?.classList.add('open');
  syncSheetTrackInfo();
};

window.closeFullscreenPlayer = function () {
  document.getElementById('fullscreenPlayerOverlay')?.classList.remove('open');
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
  document.getElementById('contextModal')?.classList.add('open');
};

window.closeContextMenu = function () {
  document.getElementById('contextModal')?.classList.remove('open');
};

window.actionOpenCinematicMode = function () {
  window.closeContextMenu();
  window.closeFullscreenPlayer();
  const cin = document.getElementById('cinematicOverlay');
  cin?.classList.add('open');

  if (currentIndex !== -1 && playlist[currentIndex]) {
    const t = playlist[currentIndex];
    const cinImg = document.getElementById('cinematicArtImg');
    if (cinImg) cinImg.src = t.thumbnail || '';
    const cinTitle = document.getElementById('cinematicTitle');
    if (cinTitle) cinTitle.innerText = t.title;
    const cinArtist = document.getElementById('cinematicArtist');
    if (cinArtist) cinArtist.innerText = t.artist;
  }
  populateCinematicLyrics();
};

window.closeCinematicMode = function () {
  document.getElementById('cinematicOverlay')?.classList.remove('open');
};

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

window.nextTrack = function () {
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
  } else if (repeatMode === 'all' && playlist.length > 0) {
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

window.actionAddToPlaylistPrompt = function () {
  window.closeContextMenu();
  if (currentIndex === -1 || !playlist[currentIndex]) return;
  const track = playlist[currentIndex];
  const plNames = Object.values(playlists).map(p => p.name).join(', ');
  const target = prompt(`Enter playlist name to add to (${plNames}):`);
  if (target) {
    const found = Object.values(playlists).find(p => p.name.toLowerCase() === target.trim().toLowerCase());
    if (found) {
      found.tracks.push(track);
      localStorage.setItem('melo_playlists', JSON.stringify(playlists));
      alert(`Added to "${found.name}"!`);
    } else {
      alert("Playlist not found.");
    }
  }
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
    alert("Song link copied to clipboard!");
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
        alert("Sleep timer reached. Good night!");
      }, min * 60 * 1000);
      alert(`Sleep timer set for ${min} minutes.`);
    } else {
      sleepTimerId = null;
      alert("Sleep timer turned off.");
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
// 2. PLAYBACK ENGINE & DESYNC SHIELD
// ==========================================

window.playIndex = function (idx) {
  if (idx < 0 || idx >= playlist.length) return;

  const thisToken = ++activePlayToken;
  currentIndex = idx;
  const track = playlist[currentIndex];
  const audio = document.getElementById('audio');

  const dockTitle = document.getElementById('dockTitle');
  const dockArtist = document.getElementById('dockArtist');
  const dockThumb = document.getElementById('dockThumb');

  if (dockTitle) dockTitle.innerText = track.title;
  if (dockArtist) dockArtist.innerText = track.artist;
  if (dockThumb) dockThumb.src = track.thumbnail || '';

  syncSheetTrackInfo();

  if (audio) {
    audio.src = `/api/stream/${track.id}?quality=${selectedQuality}`;
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
            console.warn("Playback interrupted:", err);
            setPlayState(false);
          }
        });
    }
  }

  fetchLyrics(track, thisToken);
  fetchRecommendations(track.id);

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

  updateArtworkPalette(track.thumbnail);

  const cinImg = document.getElementById('cinematicArtImg');
  if (cinImg) cinImg.src = track.thumbnail || '';
  const cinTitle = document.getElementById('cinematicTitle');
  if (cinTitle) cinTitle.innerText = track.title;
  const cinArtist = document.getElementById('cinematicArtist');
  if (cinArtist) cinArtist.innerText = track.artist;
}

function renderSheetQueueList() {
  const qView = document.getElementById('sheetViewQueue');
  if (!qView) return;
  qView.innerHTML = '<div style="font-size:0.9rem;font-weight:700;color:var(--text-muted);margin-bottom:8px;padding-left:4px;">Upcoming Tracks</div>';

  const upcoming = playlist.slice(currentIndex + 1, currentIndex + 25);
  if (upcoming.length === 0) {
    qView.innerHTML += '<p style="color:var(--text-dim);font-size:0.85rem;padding:8px;">No more tracks in queue. Autoplay will buffer next songs.</p>';
    return;
  }

  upcoming.forEach((t, idx) => {
    const item = document.createElement('div');
    item.className = 'queue-row';
    const isFav = !!favorites[t.id];

    item.innerHTML = `
      <img class="queue-thumb" src="${t.thumbnail || ''}" />
      <div class="queue-info">
        <div class="queue-title">${t.title}</div>
        <div class="queue-artist">${t.artist}</div>
      </div>
      <div class="queue-actions-cluster">
        <button class="queue-action-btn" title="Add to Favorites" onclick="event.stopPropagation(); toggleFavTrackDirect('${t.id}', this)">
          ${isFav ? '♥' : '♡'}
        </button>
        <button class="queue-action-btn" title="Add to Playlist" onclick="event.stopPropagation(); addQueueTrackToPlaylist('${t.id}')">
          +
        </button>
      </div>
    `;
    item.onclick = () => window.playIndex(currentIndex + 1 + idx);
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

window.addQueueTrackToPlaylist = function (trackId) {
  const track = playlist.find(t => t.id === trackId);
  if (!track) return;
  const plNames = Object.values(playlists).map(p => p.name).join(', ');
  const target = prompt(`Enter playlist name to add "${track.title}" (${plNames}):`);
  if (target) {
    const found = Object.values(playlists).find(p => p.name.toLowerCase() === target.trim().toLowerCase());
    if (found) {
      found.tracks.push(track);
      localStorage.setItem('melo_playlists', JSON.stringify(playlists));
      alert(`Added to "${found.name}"!`);
    } else {
      alert("Playlist not found.");
    }
  }
};

async function fetchRecommendations(videoId) {
  try {
    const res = await fetch(`/api/recommendations/${videoId}`);
    const data = await res.json();
    if (data.tracks && data.tracks.length > 0) {
      const existingIds = new Set(playlist.map(t => t.id));
      const newTracks = data.tracks.filter(t => !existingIds.has(t.id));
      playlist.push(...newTracks);
    }
  } catch (e) {
    console.warn("Recommendations buffer error", e);
  }
}

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

    populateCinematicLyrics();
  } catch (err) {
    if (token === activePlayToken && container) {
      container.innerHTML = `<div class="lyrics-line">Lyrics unavailable.</div>`;
    }
  }
}

function populateCinematicLyrics() {
  const box = document.getElementById('cinematicLyricsScroll');
  if (!box) return;
  box.innerHTML = '';
  if (!parsedLyrics || parsedLyrics.length === 0) {
    box.innerHTML = '<div class="lyrics-line active">No synced lyrics available for this track.</div>';
    return;
  }
  parsedLyrics.forEach(l => {
    const div = document.createElement('div');
    div.className = 'lyrics-line';
    div.innerText = l.text;
    if (isSynced) {
      div.onclick = () => {
        const audio = document.getElementById('audio');
        if (audio) audio.currentTime = l.time;
      };
    }
    box.appendChild(div);
  });
}

function updateArtworkPalette(imgUrl) {
  if (!imgUrl) return;
  const img = new Image();
  img.crossOrigin = "Anonymous";
  img.src = imgUrl;
  img.onload = () => {
    try {
      const cvs = document.createElement("canvas");
      const c = cvs.getContext("2d");
      cvs.width = 16;
      cvs.height = 16;
      c.drawImage(img, 0, 0, 16, 16);
      const p1 = c.getImageData(3, 3, 1, 1).data;
      const p2 = c.getImageData(12, 12, 1, 1).data;

      const palR1 = p1[0]; const palG1 = p1[1]; const palB1 = p1[2];
      const palR2 = p2[0]; const palG2 = p2[1]; const palB2 = p2[2];

      currentPrimaryHex = `#${((1 << 24) + (palR1 << 16) + (palG1 << 8) + palB1).toString(16).slice(1)}`;
      const primaryCol = `rgb(${palR1}, ${palG1}, ${palB1})`;

      document.documentElement.style.setProperty('--mesh-color-1', `rgba(${palR1}, ${palG1}, ${palB1}, 0.85)`);
      document.documentElement.style.setProperty('--mesh-color-2', `rgba(${palR2}, ${palG2}, ${palB2}, 0.75)`);
      document.documentElement.style.setProperty('--play-accent-color', primaryCol);

      const luminance = (0.299 * palR1 + 0.587 * palG1 + 0.114 * palB1) / 255;
      const playSvg = document.getElementById('sheetPlayBtnSvg');
      if (playSvg) {
        playSvg.style.fill = luminance > 0.65 ? '#000000' : '#ffffff';
      }
    } catch (e) {
      currentPrimaryHex = '#fa2d48';
      document.documentElement.style.setProperty('--play-accent-color', '#ffffff');
      const playSvg = document.getElementById('sheetPlayBtnSvg');
      if (playSvg) playSvg.style.fill = '#000000';
    }
  };
}

function fmtTime(s) {
  if (isNaN(s)) return "0:00";
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return `${m}:${sec < 10 ? '0' : ''}${sec}`;
}

// ==========================================
// 3. VIEW RENDERERS (Home, Search, Favorites)
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

      <div class="hero-slider-section">
        <div class="hero-slider-wrap" id="heroSlider"></div>
        <div class="slider-dots" id="sliderDots"></div>
      </div>

      <div class="section-heading"><h2>Modes & Sonic Spaces</h2></div>
      <div class="search-mood-cards" style="margin-bottom:32px;">
        <div class="mood-card" onclick="loadShelfCategory('Deep Focus Lo-Fi Beats', 'trendingGrid')"><span>Deep Focus</span><span class="mood-icon">🧠</span></div>
        <div class="mood-card" onclick="loadShelfCategory('Late Night Acoustic Melodies', 'bollywoodGrid')"><span>Late Night</span><span class="mood-icon">🌙</span></div>
        <div class="mood-card" onclick="loadShelfCategory('Workout Gym Energy Bangers', 'punjabiGrid')"><span>Workout BPM</span><span class="mood-icon">⚡</span></div>
        <div class="mood-card" onclick="loadShelfCategory('Cinematic Ambient Soundscapes', 'indieGrid')"><span>Cinematic</span><span class="mood-icon">🌌</span></div>
        <div class="mood-card" onclick="loadShelfCategory('Retro Bollywood Classics', 'retroGrid')"><span>Retro Gold</span><span class="mood-icon">📻</span></div>
        <div class="mood-card" onclick="loadShelfCategory('Sufi Ghazals & Acoustic', 'sufiGrid')"><span>Sufi Chill</span><span class="mood-icon">🕊️</span></div>
      </div>

      <div class="section-heading" id="trendingShelf">
        <h2>Trending Across India</h2>
        <a onclick="loadShelfCategory('Top Hindi Songs 2026', 'trendingGrid')">Refresh</a>
      </div>
      <div class="capsule-grid" id="trendingGrid"></div>

      <div class="section-heading" id="bollywoodShelf">
        <h2>Bollywood Chartbusters</h2>
      </div>
      <div class="capsule-grid" id="bollywoodGrid"></div>

      <div class="section-heading" id="punjabiShelf">
        <h2>Punjabi Banger Wave</h2>
      </div>
      <div class="capsule-grid" id="punjabiGrid"></div>

      <div class="section-heading" id="indieShelf">
        <h2>Desi Indie & Acoustic Chill</h2>
      </div>
      <div class="capsule-grid" id="indieGrid"></div>

      <div class="section-heading" id="retroShelf">
        <h2>Golden Era Classics</h2>
      </div>
      <div class="capsule-grid" id="retroGrid"></div>

      <div class="section-heading" id="sufiShelf">
        <h2>Sufi & Soulful Evenings</h2>
      </div>
      <div class="capsule-grid" id="sufiGrid"></div>
    </div>
  `;

  loadHeroCatalog();
  window.loadShelfCategory('Top Hindi Songs 2026', 'trendingGrid');
  window.loadShelfCategory('Bollywood Romantic Hits', 'bollywoodGrid');
  window.loadShelfCategory('Punjabi Hits 2026', 'punjabiGrid');
  window.loadShelfCategory('Indian Indie Songs', 'indieGrid');
  window.loadShelfCategory('Retro Bollywood Classics', 'retroGrid');
  window.loadShelfCategory('Sufi Ghazals & Acoustic', 'sufiGrid');
}

async function loadHeroCatalog() {
  try {
    const res = await fetch('/api/search?query=Top%20Hindi%20Trending%20Songs%202026');
    const data = await res.json();
    heroTracks = (data.results || []).slice(0, 6);
    renderHeroSlider();
  } catch (err) {
    console.warn("Hero fetch failed:", err);
  }
}

function renderHeroSlider() {
  const slider = document.getElementById('heroSlider');
  const dots = document.getElementById('sliderDots');
  if (!slider || !dots) return;

  slider.innerHTML = '';
  dots.innerHTML = '';

  heroTracks.forEach((track, i) => {
    const slide = document.createElement('div');
    slide.className = 'hero-slide-card';
    slide.onclick = () => {
      playlist = [...heroTracks];
      window.playIndex(i);
    };
    slide.innerHTML = `
      <img class="hero-slide-img" src="${track.thumbnail || ''}" loading="lazy" />
      <div class="hero-slide-overlay">
        <div class="hero-slide-tag">Curated Soundstage</div>
        <div class="hero-slide-title">${track.title}</div>
        <div class="hero-slide-artist">${track.artist}</div>
      </div>
    `;
    slider.appendChild(slide);

    const dot = document.createElement('div');
    dot.className = `dot ${i === 0 ? 'active' : ''}`;
    dots.appendChild(dot);
  });

  if (heroTracks.length > 0) {
    updateArtworkPalette(heroTracks[0].thumbnail);
  }

  slider.addEventListener('scroll', () => {
    const index = Math.round(slider.scrollLeft / (slider.offsetWidth * 0.90));
    dots.querySelectorAll('.dot').forEach((d, idx) => {
      d.classList.toggle('active', idx === index);
    });
    if (heroTracks[index] && heroTracks[index].thumbnail) {
      updateArtworkPalette(heroTracks[index].thumbnail);
    }
  }, { passive: true });
}

window.loadShelfCategory = async function (query, containerId) {
  try {
    const res = await fetch(`/api/search?query=${encodeURIComponent(query)}`);
    const data = await res.json();
    const items = data.results || [];
    categoryData[containerId] = items;
    renderGridContainer(containerId, items.slice(0, 6));

    if (containerId === 'trendingGrid' && playlist.length === 0) {
      playlist = [...items];
    }
  } catch (err) {
    console.error("Shelf load error", err);
  }
};

function renderGridContainer(containerId, items) {
  const c = document.getElementById(containerId);
  if (!c) return;
  c.innerHTML = '';

  items.forEach((track, i) => {
    const item = document.createElement('div');
    item.className = 'poster-item';
    item.onclick = () => {
      playlist = categoryData[containerId] || items;
      window.playIndex(i);
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

function renderSearchView() {
  const viewContainer = document.getElementById('viewContainer');
  if (!viewContainer) return;
  viewContainer.innerHTML = `
    <div class="stage-content">
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

      <div class="section-heading">
        <h2>Explore by Mood & Genre</h2>
      </div>
      <div class="search-mood-cards">
        <div class="mood-card" onclick="quickSearch('Bollywood Romantic Melodies')"><span>Romance</span><span class="mood-icon">💖</span></div>
        <div class="mood-card" onclick="quickSearch('Diljit Dosanjh Punjabi Hits')"><span>Punjabi Wave</span><span class="mood-icon">🔥</span></div>
        <div class="mood-card" onclick="quickSearch('Desi Hip Hop India 2026')"><span>Desi Rap</span><span class="mood-icon">⚡</span></div>
        <div class="mood-card" onclick="quickSearch('Indian Indie Acoustic Chill')"><span>Indie Chill</span><span class="mood-icon">🌙</span></div>
        <div class="mood-card" onclick="quickSearch('Bollywood Dance Hits Party')"><span>Party Hits</span><span class="mood-icon">🎉</span></div>
        <div class="mood-card" onclick="quickSearch('South Indian Cinema Bangers')"><span>South Cinema</span><span class="mood-icon">🚀</span></div>
      </div>

      <div class="section-heading">
        <h2 id="searchResultsTitle">Trending Recommendations</h2>
      </div>
      <div class="capsule-grid" id="searchGrid"></div>

      <div class="section-heading"><h2>Bollywood Chartbusters</h2></div>
      <div class="capsule-grid" id="searchBollywoodGrid"></div>

      <div class="section-heading"><h2>Punjabi Banger Wave</h2></div>
      <div class="capsule-grid" id="searchPunjabiGrid"></div>

      <div class="section-heading"><h2>Desi Indie & Acoustic Chill</h2></div>
      <div class="capsule-grid" id="searchIndieGrid"></div>
    </div>
  `;

  const input = document.getElementById('dedicatedSearchInput');
  const clearBtn = document.getElementById('searchClearBtn');
  let searchTimer = null;

  if (input) {
    input.addEventListener('input', () => {
      clearTimeout(searchTimer);
      const q = input.value.trim();
      if (clearBtn) clearBtn.style.display = q ? 'flex' : 'none';
      if (!q) return;
      searchTimer = setTimeout(() => {
        const title = document.getElementById('searchResultsTitle');
        if (title) title.innerText = `Results for "${q}"`;
        loadSearchShelf(q, 'searchGrid', 12);
      }, 300);
    });
  }

  loadSearchShelf('Bollywood Trending 2026', 'searchGrid', 6);
  loadSearchShelf('Bollywood Romantic Hits', 'searchBollywoodGrid', 6);
  loadSearchShelf('Punjabi Hits 2026', 'searchPunjabiGrid', 6);
  loadSearchShelf('Indian Indie Songs', 'searchIndieGrid', 6);
}

window.clearSearchInput = function () {
  const input = document.getElementById('dedicatedSearchInput');
  const clearBtn = document.getElementById('searchClearBtn');
  if (input) { input.value = ''; input.focus(); }
  if (clearBtn) clearBtn.style.display = 'none';
  const title = document.getElementById('searchResultsTitle');
  if (title) title.innerText = "Trending Recommendations";
  loadSearchShelf('Bollywood Trending 2026', 'searchGrid', 6);
  loadSearchShelf('Bollywood Romantic Hits', 'searchBollywoodGrid', 6);
  loadSearchShelf('Punjabi Hits 2026', 'searchPunjabiGrid', 6);
  loadSearchShelf('Indian Indie Songs', 'searchIndieGrid', 6);
};

window.quickSearch = function (query) {
  const input = document.getElementById('dedicatedSearchInput');
  const clearBtn = document.getElementById('searchClearBtn');
  if (input) {
    input.value = query;
    if (clearBtn) clearBtn.style.display = 'flex';
  }
  const title = document.getElementById('searchResultsTitle');
  if (title) title.innerText = `Results for "${query}"`;
  loadSearchShelf(query, 'searchGrid', 12);
};

async function loadSearchShelf(query, containerId = 'searchGrid', limit = 6) {
  try {
    const res = await fetch(`/api/search?query=${encodeURIComponent(query)}`);
    const data = await res.json();
    const items = data.results || [];
    categoryData[containerId] = items;
    renderGridContainer(containerId, items.slice(0, limit));
    if (containerId === 'searchGrid') playlist = items;
  } catch (err) {
    console.error("Search shelf load failed", err);
  }
}

function renderFavoritesView() {
  const viewContainer = document.getElementById('viewContainer');
  if (!viewContainer) return;
  const favList = Object.values(favorites);
  const playlistList = Object.values(playlists);

  viewContainer.innerHTML = `
    <div class="stage-content">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" aria-label="Go Back" title="Back">
          <svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <h1 style="font-size: 1.45rem; font-weight: 800; letter-spacing: -0.02em;">Music Hub</h1>
      </div>

      <div class="playlist-editorial-hero">
        <div class="pl-hero-glow"></div>
        <div class="pl-hero-cover">🤍</div>
        <div class="pl-hero-meta">
          <div class="pl-badge-chip">Curated Collection</div>
          <h1 class="pl-title-text">Loved Songs</h1>
          <p class="pl-sub-text">${favList.length} tracks saved directly from your sessions</p>
          <div class="pl-actions-row">
            <button class="pill-action-btn" onclick="playFavoritesAll()">
              <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
              <span>Play All</span>
            </button>
            <button class="filter-chip" onclick="createCustomPlaylistDialog()">+ New Playlist</button>
          </div>
        </div>
      </div>

      <div class="section-heading"><h2>Your Playlists (${playlistList.length})</h2></div>
      <div class="capsule-grid" id="customPlaylistsGrid"></div>

      <div class="section-heading"><h2>Saved Tracks</h2></div>
      <div id="favsTracklist"></div>
    </div>
  `;

  const plGrid = document.getElementById('customPlaylistsGrid');
  if (plGrid) {
    plGrid.innerHTML = '';
    playlistList.forEach(pl => {
      const item = document.createElement('div');
      item.className = 'poster-item';
      item.onclick = () => openPlaylistDetails(pl.id);
      item.innerHTML = `
        <div class="poster-wrap" style="display:flex;align-items:center;justify-content:center;font-size:2.4rem;background:linear-gradient(135deg, #1f1f2a, #121216);">🎧</div>
        <div class="poster-title">${pl.name}</div>
        <div class="poster-subtitle">${pl.tracks.length} tracks</div>
      `;
      plGrid.appendChild(item);
    });
  }

  const fList = document.getElementById('favsTracklist');
  if (fList) {
    fList.innerHTML = '';
    if (favList.length === 0) {
      fList.innerHTML = `<p style="color:var(--text-dim);font-size:0.9rem;padding:12px;">No loved tracks yet. Click the heart icon on any song to save it here.</p>`;
    } else {
      favList.forEach((track, i) => {
        const row = document.createElement('div');
        row.className = `track-row`;
        row.onclick = () => { playlist = favList; window.playIndex(i); };
        row.innerHTML = `
          <div class="tr-num">${i + 1}</div>
          <img class="tr-thumb" src="${track.thumbnail || ''}" loading="lazy" />
          <div class="tr-info">
            <div class="tr-title">${track.title}</div>
            <div class="tr-artist">${track.artist}</div>
          </div>
          <div class="tr-album">${track.album || 'Single'}</div>
          <div class="tr-time">${track.duration}</div>
          <button class="tr-fav active" onclick="removeFavoriteItem(event, '${track.id}')">♥</button>
        `;
        fList.appendChild(row);
      });
    }
  }
}

window.playFavoritesAll = function () {
  const favList = Object.values(favorites);
  if (favList.length > 0) { playlist = favList; window.playIndex(0); }
};

window.removeFavoriteItem = function (e, trackId) {
  e.stopPropagation();
  delete favorites[trackId];
  localStorage.setItem('melo_favorites', JSON.stringify(favorites));
  renderFavoritesView();
};

window.createCustomPlaylistDialog = function () {
  const name = prompt("Enter a title for your new playlist:");
  if (name && name.trim()) {
    const id = 'pl-' + Date.now();
    playlists[id] = { id, name: name.trim(), tracks: [] };
    localStorage.setItem('melo_playlists', JSON.stringify(playlists));
    renderFavoritesView();
  }
};

function openPlaylistDetails(plId) {
  const pl = playlists[plId];
  const viewContainer = document.getElementById('viewContainer');
  if (!pl || !viewContainer) return;

  viewContainer.innerHTML = `
    <div class="stage-content">
      <div class="top-action-bar">
        <button class="circle-back-btn" onclick="goBack()" aria-label="Go Back" title="Back">
          <svg viewBox="0 0 24 24"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
        </button>
        <h1 style="font-size: 1.45rem; font-weight: 800; letter-spacing: -0.02em;">Playlist</h1>
      </div>

      <div class="playlist-editorial-hero">
        <div class="pl-hero-glow"></div>
        <div class="pl-hero-cover">🎧</div>
        <div class="pl-hero-meta">
          <div class="pl-badge-chip">Custom Mix</div>
          <h1 class="pl-title-text">${pl.name}</h1>
          <p class="pl-sub-text">${pl.tracks.length} tracks</p>
          <div class="pl-actions-row">
            ${pl.tracks.length > 0 ? `
              <button class="pill-action-btn" onclick="playlist = playlists['${plId}'].tracks; window.playIndex(0);">
                <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
                <span>Play</span>
              </button>
            ` : ''}
            <button class="filter-chip" onclick="deleteCustomPlaylist('${plId}')">Delete</button>
          </div>
        </div>
      </div>

      <div class="section-heading"><h2>Songs</h2></div>
      <div id="playlistTracksBox"></div>
    </div>
  `;

  const box = document.getElementById('playlistTracksBox');
  if (!box) return;
  if (pl.tracks.length === 0) {
    box.innerHTML = `<p style="color:var(--text-dim);font-size:0.9rem;padding:12px;">This playlist is empty. Play songs and add them from the player options.</p>`;
  } else {
    pl.tracks.forEach((track, i) => {
      const row = document.createElement('div');
      row.className = 'track-row';
      row.onclick = () => { playlist = pl.tracks; window.playIndex(i); };
      row.innerHTML = `
        <div class="tr-num">${i + 1}</div>
        <img class="tr-thumb" src="${track.thumbnail || ''}" loading="lazy" />
        <div class="tr-info">
          <div class="tr-title">${track.title}</div>
          <div class="tr-artist">${track.artist}</div>
        </div>
        <div class="tr-album">${track.album || 'Single'}</div>
        <div class="tr-time">${track.duration}</div>
      `;
      box.appendChild(row);
    });
  }
}

window.deleteCustomPlaylist = function (plId) {
  if (confirm("Are you sure you want to delete this playlist?")) {
    delete playlists[plId];
    localStorage.setItem('melo_playlists', JSON.stringify(playlists));
    renderFavoritesView();
  }
};

// ==========================================
// 4. DOM READY INITIALIZATION & EVENTS
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

  // Miniplayer Canvas Wave
  const miniWaveCtx = miniWaveCanvas ? miniWaveCanvas.getContext('2d') : null;
  let wavePhase = 0;
  let colorShift = 0;
  let currentAmplitude = 0;

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
    const midY = 5;
    const targetAmp = isPlaying ? 4.5 : 0;
    currentAmplitude += (targetAmp - currentAmplitude) * 0.07;

    if (currentAmplitude > 0.05) {
      colorShift = (colorShift + 0.15) % 360;
      wavePhase += 0.012;

      const threads = [
        { freq: 0.014, speed: 0.8, phase: 0, alpha: 0.55, shift: 0 },
        { freq: 0.021, speed: 1.0, phase: 1.6, alpha: 0.42, shift: 60 },
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

        const lightCastGrad = miniWaveCtx.createLinearGradient(0, midY - currentAmplitude, 0, h);
        lightCastGrad.addColorStop(0, `hsla(${(colorShift + t.shift) % 360}, 90%, 65%, 0.18)`);
        lightCastGrad.addColorStop(0.7, `hsla(${(colorShift + t.shift + 40) % 360}, 85%, 55%, 0.05)`);
        lightCastGrad.addColorStop(1, `transparent`);
        miniWaveCtx.fillStyle = lightCastGrad;
        miniWaveCtx.fill();
        miniWaveCtx.restore();

        miniWaveCtx.save();
        miniWaveCtx.beginPath();
        miniWaveCtx.lineWidth = 1.4;
        const strokeGrad = miniWaveCtx.createLinearGradient(0, 0, w, 0);
        strokeGrad.addColorStop(0, `hsla(${(colorShift + t.shift) % 360}, 90%, 70%, ${t.alpha})`);
        strokeGrad.addColorStop(0.5, `hsla(${(colorShift + t.shift + 80) % 360}, 90%, 65%, ${t.alpha})`);
        strokeGrad.addColorStop(1, `hsla(${(colorShift + t.shift + 160) % 360}, 90%, 70%, ${t.alpha})`);
        miniWaveCtx.strokeStyle = strokeGrad;
        for (let x = 0; x <= w; x += 2) {
          const y = midY + Math.sin(x * t.freq + (wavePhase * t.speed) + t.phase) * currentAmplitude;
          if (x === 0) miniWaveCtx.moveTo(x, y);
          else miniWaveCtx.lineTo(x, y);
        }
        miniWaveCtx.stroke();
        miniWaveCtx.restore();
      });
    }

    requestAnimationFrame(renderTidalLightWave);
  }
  setTimeout(() => { resizeMiniWaveCanvas(); renderTidalLightWave(); }, 50);

  // Scrubber Wave
  const scrubberWaveCtx = scrubberWaveCanvas ? scrubberWaveCanvas.getContext('2d') : null;
  let scrubberWavePhase = 0;

  function renderScrubberLiveWave() {
    if (!scrubberWaveCanvas || !scrubberWaveCtx) return;
    const trackBase = document.getElementById('scrubberTrackBase');
    const w = trackBase ? trackBase.offsetWidth : 300;
    const h = scrubberWaveCanvas.offsetHeight || 10;
    const dpr = window.devicePixelRatio || 1;

    if (scrubberWaveCanvas.width !== w * dpr || scrubberWaveCanvas.height !== h * dpr) {
      scrubberWaveCanvas.width = w * dpr;
      scrubberWaveCanvas.height = h * dpr;
      scrubberWaveCtx.scale(dpr, dpr);
    }

    scrubberWaveCtx.clearRect(0, 0, w, h);
    const isPlaying = audio && !audio.paused && audio.currentTime > 0 && !audio.ended;

    if (w > 0) {
      scrubberWavePhase += isPlaying ? 0.045 : 0.008;
      const midY = h / 2;
      const amp = isPlaying ? 2.8 : 0.5;

      scrubberWaveCtx.beginPath();
      scrubberWaveCtx.lineWidth = 2.4;
      const grad = scrubberWaveCtx.createLinearGradient(0, 0, w, 0);
      grad.addColorStop(0, '#fa2d48');
      grad.addColorStop(1, currentPrimaryHex);
      scrubberWaveCtx.strokeStyle = grad;

      for (let x = 0; x <= w; x += 2) {
        const y = midY + Math.sin(x * 0.08 + scrubberWavePhase) * amp;
        if (x === 0) scrubberWaveCtx.moveTo(x, y);
        else scrubberWaveCtx.lineTo(x, y);
      }
      scrubberWaveCtx.stroke();
    }

    requestAnimationFrame(renderScrubberLiveWave);
  }
  setTimeout(renderScrubberLiveWave, 60);

  if (scrubberTrackBase && audio) {
    scrubberTrackBase.addEventListener('click', (e) => {
      if (!audio.duration) return;
      const rect = scrubberTrackBase.getBoundingClientRect();
      const pct = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
      audio.currentTime = pct * audio.duration;
    });
  }

  // Fluid Mesh Animation
  const fluidCanvases = [document.getElementById('fluidMeshCanvas'), document.getElementById('cinematicMeshCanvas')];
  let fluidTime = 0;
  let palR1 = 250, palG1 = 45, palB1 = 72;
  let palR2 = 192, palG2 = 38, palB2 = 211;

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
    const isCinemaOpen = document.getElementById('cinematicOverlay')?.classList.contains('open');

    if (!isSheetOpen && !isCinemaOpen) {
      requestAnimationFrame(renderLiveFluidMesh);
      return;
    }

    fluidTime += 0.007;

    fluidCanvases.forEach(canv => {
      if (!canv) return;
      const fCtx = canv.getContext('2d');
      const w = canv.width;
      const h = canv.height;
      fCtx.clearRect(0, 0, w, h);

      const cx1 = w * (0.35 + 0.25 * Math.sin(fluidTime));
      const cy1 = h * (0.35 + 0.25 * Math.cos(fluidTime * 0.8));
      const g1 = fCtx.createRadialGradient(cx1, cy1, 0, cx1, cy1, w * 0.95);
      g1.addColorStop(0, `rgba(${palR1}, ${palG1}, ${palB1}, 0.95)`);
      g1.addColorStop(1, 'transparent');
      fCtx.fillStyle = g1;
      fCtx.fillRect(0, 0, w, h);

      const cx2 = w * (0.65 + 0.25 * Math.cos(fluidTime * 1.1));
      const cy2 = h * (0.65 + 0.25 * Math.sin(fluidTime * 0.7));
      const g2 = fCtx.createRadialGradient(cx2, cy2, 0, cx2, cy2, w * 0.9);
      g2.addColorStop(0, `rgba(${palR2}, ${palG2}, ${palB2}, 0.9)`);
      g2.addColorStop(1, 'transparent');
      fCtx.fillStyle = g2;
      fCtx.fillRect(0, 0, w, h);
    });

    requestAnimationFrame(renderLiveFluidMesh);
  }
  setTimeout(renderLiveFluidMesh, 100);

  // Audio Event Listeners
  if (audio) {
    audio.ontimeupdate = () => {
      if (audio.duration) {
        const pct = (audio.currentTime / audio.duration) * 100;
        if (dockScrubber) {
          dockScrubber.value = pct;
          dockScrubber.style.background = `linear-gradient(to right, #fa2d48 0%, #c026d3 ${pct}%, rgba(255,255,255,0.15) ${pct}%, rgba(255,255,255,0.15) 100%)`;
        }

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
          if (curTime >= parsedLyrics[i].time - 0.2) {
            activeIdx = i;
          } else {
            break;
          }
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

          const cinLines = document.querySelectorAll('#cinematicLyricsScroll .lyrics-line');
          cinLines.forEach((el, idx) => {
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

  // Keyboard Shortcuts
  window.addEventListener('keydown', (e) => {
    if (['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) return;
    if (e.code === 'Space') {
      e.preventDefault();
      window.togglePlay();
    } else if (e.key === 'Escape') {
      window.closeFullscreenPlayer();
      window.closeContextMenu();
      window.closeCinematicMode();
      window.closeSettingsModal();
    }
  });

  // Launch initial home stage
  renderHomeView();
});