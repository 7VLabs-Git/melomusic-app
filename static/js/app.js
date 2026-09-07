document.addEventListener('DOMContentLoaded', () => {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/static/sw.js').catch(() => {});
  }

  const mainView = document.getElementById('mainView');
  const searchInput = document.getElementById('searchInput');
  const suggestionsBox = document.getElementById('suggestionsBox');
  const lyricsOverlay = document.getElementById('lyricsOverlay');
  const queueDrawer = document.getElementById('queueDrawer');
  const contextMenu = document.getElementById('contextMenu');
  const genericModal = document.getElementById('genericModal');

  let currentAbortController = null;
  let activeContextTrack = null;
  let lyricsCache = {};

  // ==========================================
  // ROUTING SYSTEM
  // ==========================================
  function navigate(hash) {
    window.location.hash = hash;
  }

  window.addEventListener('hashchange', handleRoute);

  function handleRoute() {
    const raw = window.location.hash.slice(1) || 'home';
    const [route, param] = raw.split('/');

    document.querySelectorAll('[data-route]').forEach(el => {
      el.classList.toggle('active', el.getAttribute('data-route') === route);
    });

    if (route === 'home') renderHomeView();
    else if (route === 'explore') renderExploreView();
    else if (route === 'library') renderLibraryView();
    else if (route === 'album' && param) renderAlbumView(param);
    else if (route === 'artist' && param) renderArtistView(param);
    else if (route === 'playlist' && param) renderPlaylistView(param);
    else if (route === 'stats') renderStatsView();
    else renderHomeView();

    mainView.scrollTop = 0;
  }

  // ==========================================
  // VIEW: HOME DASHBOARD
  // ==========================================
  function renderHomeView() {
    const greeting = getGreeting();
    const history = window.meloStore.state.history.slice(0, 6);
    const favorites = Object.values(window.meloStore.state.favorites).slice(0, 6);
    const onRepeat = window.meloStore.state.playlists['pl-repeat'].tracks.slice(0, 6);

    mainView.innerHTML = `
      <div class="stage-content">
        <div class="filter-shelf">
          <div class="filter-chip active" onclick="loadCatalogGenre('Trending Worldwide')">All</div>
          <div class="filter-chip" onclick="loadCatalogGenre('Top Pop Hits')">Pop</div>
          <div class="filter-chip" onclick="loadCatalogGenre('Lo-Fi Chill Sessions')">Chill & Lo-Fi</div>
          <div class="filter-chip" onclick="loadCatalogGenre('Electronic Dance Euphoria')">Electronic</div>
          <div class="filter-chip" onclick="loadCatalogGenre('Acoustic Intimate Sessions')">Acoustic</div>
          <div class="filter-chip" onclick="loadCatalogGenre('Deep Focus Ambient')">Focus</div>
        </div>

        <section class="editorial-hero">
          <div class="hero-ambient-glow"></div>
          <div class="hero-meta">
            <div class="hero-tag">Curated Soundstage</div>
            <h1 class="hero-title">${greeting}</h1>
            <p class="hero-desc">Experience lossless acoustics, editorial discographies, and smooth playback engineered for uninterrupted listening.</p>
            <button class="pill-action-btn" id="heroPlayBtn">
              <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
              <span>Play Quick Mix</span>
            </button>
          </div>
        </section>

        ${history.length > 0 ? `
          <div class="section-heading">
            <h2>Recently Played</h2>
            <a onclick="window.location.hash='library'">View Library</a>
          </div>
          <div class="capsule-grid" id="recentGrid"></div>
        ` : ''}

        ${onRepeat.length > 0 ? `
          <div class="section-heading">
            <h2>Heavy Rotation</h2>
          </div>
          <div class="capsule-grid" id="onRepeatGrid"></div>
        ` : ''}

        <div class="section-heading">
          <h2>Trending Worldwide</h2>
          <a onclick="loadCatalogGenre('Top Billboard & Global Hits')">Refresh</a>
        </div>
        <div class="capsule-grid" id="trendingGrid"></div>

        <div class="section-heading">
          <h2>Top Songs</h2>
        </div>
        <div id="songsList"></div>
      </div>
    `;

    if (history.length > 0) renderCards('recentGrid', history);
    if (onRepeat.length > 0) renderCards('onRepeatGrid', onRepeat);

    loadCatalogGenre('Top Billboard & Global Hits', 'trendingGrid', 'songsList');

    const heroBtn = document.getElementById('heroPlayBtn');
    if (heroBtn) {
      heroBtn.onclick = () => {
        const pool = onRepeat.length > 0 ? onRepeat : (favorites.length > 0 ? favorites : history);
        if (pool.length > 0) window.meloPlayer.loadQueue(pool, 0);
        else loadCatalogGenre('Top Global Hits');
      };
    }
  }

  function getGreeting() {
    const h = new Date().getHours();
    if (h < 12) return 'Good morning';
    if (h < 17) return 'Good afternoon';
    if (h < 21) return 'Good evening';
    return 'Late night vibes';
  }

  // Helper: Card Renderer
  function renderCards(containerId, items) {
    const el = document.getElementById(containerId);
    if (!el) return;
    el.innerHTML = '';

    items.forEach((item, idx) => {
      const card = document.createElement('div');
      card.className = 'media-card';
      const isArtist = item.type === 'artist';

      card.innerHTML = `
        <div class="card-thumb-wrap">
          <img class="card-thumb ${isArtist ? 'circle' : ''}" src="${item.thumbnail || ''}" loading="lazy" />
          <div class="play-bubble"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></div>
        </div>
        <div class="card-title" title="${item.title || item.name}">${item.title || item.name}</div>
        <div class="card-subtitle">${item.artist || (isArtist ? 'Artist' : 'Single')}</div>
      `;

      card.onclick = () => {
        if (isArtist) navigate(`artist/${item.browseId || item.id}`);
        else if (item.type === 'album') navigate(`album/${item.browseId || item.id}`);
        else window.meloPlayer.loadQueue(items, idx);
      };

      card.oncontextmenu = (e) => showContextMenu(e, item);
      el.appendChild(card);
    });
  }

  // Helper: Tracklist Renderer
  function renderTrackRows(containerId, tracks) {
    const el = document.getElementById(containerId);
    if (!el) return;
    el.innerHTML = '';

    const currentTrack = window.meloPlayer.queue[window.meloPlayer.currentIndex];

    tracks.forEach((track, idx) => {
      const row = document.createElement('div');
      const isCurrent = currentTrack && currentTrack.id === track.id;
      row.className = `track-row ${isCurrent ? 'playing' : ''}`;

      const isFav = window.meloStore.isFavorite(track.id);

      row.innerHTML = `
        <div class="tr-num">${isCurrent ? '<div class="sound-bars"><div class="sound-bar"></div><div class="sound-bar"></div><div class="sound-bar"></div></div>' : idx + 1}</div>
        <img class="tr-thumb" src="${track.thumbnail || ''}" loading="lazy" />
        <div class="tr-info">
          <div class="tr-title">${track.title}</div>
          <div class="tr-artist">${track.artist}</div>
        </div>
        <div class="tr-album">${track.album || 'Single'}</div>
        <div class="tr-time">${track.duration || '3:30'}</div>
        <button class="tr-action ${isFav ? 'favorited' : ''}" onclick="event.stopPropagation(); toggleFavoriteTrack('${track.id}', this)">
          ${isFav ? '♥' : '♡'}
        </button>
      `;

      row.onclick = () => window.meloPlayer.loadQueue(tracks, idx);
      row.oncontextmenu = (e) => showContextMenu(e, track);
      el.appendChild(row);
    });
  }

  async function loadCatalogGenre(query, gridId = 'trendingGrid', listId = 'songsList') {
    try {
      const res = await fetch(`/api/search?query=${encodeURIComponent(query)}`);
      const data = await res.json();
      if (data.results) {
        if (gridId) renderCards(gridId, data.results.slice(0, 6));
        if (listId) renderTrackRows(listId, data.results);
      }
    } catch (e) {
      console.warn("Catalog fetch error", e);
    }
  }

  // ==========================================
  // VIEW: EXPLORE
  // ==========================================
  function renderExploreView() {
    mainView.innerHTML = `
      <div class="stage-content">
        <div class="section-heading"><h2>Explore & New Releases</h2></div>
        <div class="capsule-grid" id="exploreGrid"></div>
        <div class="section-heading"><h2>Global Discovery Tracklist</h2></div>
        <div id="exploreSongs"></div>
      </div>
    `;
    loadCatalogGenre('New International Releases & Viral', 'exploreGrid', 'exploreSongs');
  }

  // ==========================================
  // VIEW: LIBRARY
  // ==========================================
  function renderLibraryView() {
    const favs = Object.values(window.meloStore.state.favorites);
    const playlists = Object.values(window.meloStore.state.playlists);

    mainView.innerHTML = `
      <div class="stage-content">
        <div class="section-heading">
          <h2>Playlists (${playlists.length})</h2>
          <a id="createNewPlaylistBtn">+ New Playlist</a>
        </div>
        <div class="capsule-grid" id="playlistShelf"></div>

        <div class="section-heading">
          <h2>Loved Tracks (${favs.length})</h2>
          ${favs.length > 0 ? '<a id="playFavsBtn">Play All</a>' : ''}
        </div>
        <div id="favsList"></div>
      </div>
    `;

    const shelf = document.getElementById('playlistShelf');
    playlists.forEach(pl => {
      const card = document.createElement('div');
      card.className = 'media-card';
      card.innerHTML = `
        <div class="card-thumb-wrap" style="display:flex;align-items:center;justify-content:center;font-size:2rem;background:#18181f;">
          🎵
        </div>
        <div class="card-title">${pl.name}</div>
        <div class="card-subtitle">${pl.tracks.length} tracks</div>
      `;
      card.onclick = () => navigate(`playlist/${pl.id}`);
      shelf.appendChild(card);
    });

    if (favs.length > 0) {
      renderTrackRows('favsList', favs);
      document.getElementById('playFavsBtn').onclick = () => window.meloPlayer.loadQueue(favs, 0);
    } else {
      document.getElementById('favsList').innerHTML = `<p style="color:var(--text-muted);font-size:0.9rem;">No favorite songs added yet.</p>`;
    }

    document.getElementById('createNewPlaylistBtn').onclick = () => {
      openModal("Create Playlist", "Give your playlist an evocative title.", (name) => {
        if (name && name.trim()) {
          const id = window.meloStore.createPlaylist(name.trim());
          navigate(`playlist/${id}`);
        }
      });
    };
  }

  // ==========================================
  // VIEW: PLAYLIST DETAIL
  // ==========================================
  function renderPlaylistView(id) {
    const pl = window.meloStore.state.playlists[id];
    if (!pl) {
      navigate('library');
      return;
    }

    mainView.innerHTML = `
      <div class="stage-content">
        <div class="editorial-hero" style="min-height:220px; align-items:center; gap:28px;">
          <div class="hero-ambient-glow"></div>
          <div style="width:140px;height:140px;border-radius:18px;background:var(--accent-gradient);display:flex;align-items:center;justify-content:center;font-size:3rem;flex-shrink:0;">
            🎵
          </div>
          <div class="hero-meta">
            <div class="hero-tag">Playlist</div>
            <h1 class="hero-title" style="font-size:2.2rem;">${pl.name}</h1>
            <p class="hero-desc">${pl.tracks.length} songs • ${pl.description || 'Personal Collection'}</p>
            <div style="display:flex;gap:12px;">
              ${pl.tracks.length > 0 ? `
                <button class="pill-action-btn" id="plPlayBtn"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg> <span>Play</span></button>
              ` : ''}
              ${id !== 'pl-favorites' && id !== 'pl-repeat' ? `
                <button class="filter-chip" id="plDeleteBtn" style="border-color:rgba(255,255,255,0.2);">Delete</button>
              ` : ''}
            </div>
          </div>
        </div>

        <div id="plTracklist"></div>
      </div>
    `;

    if (pl.tracks.length > 0) {
      renderTrackRows('plTracklist', pl.tracks);
      document.getElementById('plPlayBtn').onclick = () => window.meloPlayer.loadQueue(pl.tracks, 0);
    } else {
      document.getElementById('plTracklist').innerHTML = `<p style="color:var(--text-muted);font-size:0.9rem;">No tracks inside this playlist. Search songs and click "Add to Playlist" to populate.</p>`;
    }

    const delBtn = document.getElementById('plDeleteBtn');
    if (delBtn) {
      delBtn.onclick = () => {
        window.meloStore.deletePlaylist(id);
        showToast("Playlist deleted", "info");
        navigate('library');
      };
    }
  }

  // ==========================================
  // VIEW: ALBUM DETAIL
  // ==========================================
  async function renderAlbumView(browseId) {
    mainView.innerHTML = `<div class="stage-content"><p style="color:var(--text-muted)">Loading album metadata...</p></div>`;
    try {
      const res = await fetch(`/api/album/${browseId}`);
      const album = await res.json();

      mainView.innerHTML = `
        <div class="stage-content">
          <div class="editorial-hero" style="min-height:240px; gap:32px;">
            <div class="hero-ambient-glow"></div>
            <img src="${album.thumbnail}" style="width:160px;height:160px;border-radius:16px;object-fit:cover;box-shadow:0 10px 30px rgba(0,0,0,0.8);" />
            <div class="hero-meta">
              <div class="hero-tag">Album • ${album.year || 'Latest'}</div>
              <h1 class="hero-title" style="font-size:2.2rem;">${album.title}</h1>
              <p class="hero-desc">By <a style="color:#fff;cursor:pointer;" onclick="window.location.hash='artist/${album.artistId}'">${album.artist}</a> • ${album.tracks.length} tracks</p>
              <button class="pill-action-btn" id="albumPlayBtn">
                <svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
                <span>Play Album</span>
              </button>
            </div>
          </div>
          <div id="albumTracks"></div>
        </div>
      `;

      renderTrackRows('albumTracks', album.tracks);
      document.getElementById('albumPlayBtn').onclick = () => window.meloPlayer.loadQueue(album.tracks, 0);
    } catch (e) {
      mainView.innerHTML = `<div class="stage-content"><p style="color:var(--accent)">Failed to load album information.</p></div>`;
    }
  }

  // ==========================================
  // VIEW: ARTIST DETAIL
  // ==========================================
  async function renderArtistView(browseId) {
    mainView.innerHTML = `<div class="stage-content"><p style="color:var(--text-muted)">Loading artist discography...</p></div>`;
    try {
      const res = await fetch(`/api/artist/${browseId}`);
      const artist = await res.json();

      mainView.innerHTML = `
        <div class="stage-content">
          <div class="editorial-hero" style="min-height:240px; gap:32px;">
            <div class="hero-ambient-glow"></div>
            <img src="${artist.thumbnail}" style="width:160px;height:160px;border-radius:50%;object-fit:cover;box-shadow:0 10px 30px rgba(0,0,0,0.8);" />
            <div class="hero-meta">
              <div class="hero-tag">Artist</div>
              <h1 class="hero-title" style="font-size:2.4rem;">${artist.name}</h1>
              <p class="hero-desc">${artist.description ? artist.description.slice(0, 180) + '...' : 'Verified Artist'}</p>
              ${artist.topSongs.length > 0 ? `
                <button class="pill-action-btn" id="artistPlayBtn"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg> <span>Play Popular</span></button>
              ` : ''}
            </div>
          </div>

          <div class="section-heading"><h2>Popular Tracks</h2></div>
          <div id="artistSongs"></div>

          ${artist.albums.length > 0 ? `
            <div class="section-heading" style="margin-top:40px;"><h2>Discography & Albums</h2></div>
            <div class="capsule-grid" id="artistAlbums"></div>
          ` : ''}
        </div>
      `;

      renderTrackRows('artistSongs', artist.topSongs);
      if (artist.topSongs.length > 0) {
        document.getElementById('artistPlayBtn').onclick = () => window.meloPlayer.loadQueue(artist.topSongs, 0);
      }

      if (artist.albums.length > 0) {
        const grid = document.getElementById('artistAlbums');
        artist.albums.forEach(alb => {
          const card = document.createElement('div');
          card.className = 'media-card';
          card.innerHTML = `
            <div class="card-thumb-wrap">
              <img class="card-thumb" src="${alb.thumbnail || ''}" loading="lazy" />
              <div class="play-bubble"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></div>
            </div>
            <div class="card-title">${alb.title}</div>
            <div class="card-subtitle">${alb.year || 'Album'}</div>
          `;
          card.onclick = () => navigate(`album/${alb.browseId}`);
          grid.appendChild(card);
        });
      }
    } catch (e) {
      mainView.innerHTML = `<div class="stage-content"><p style="color:var(--accent)">Failed to load artist details.</p></div>`;
    }
  }

  // ==========================================
  // VIEW: STATS
  // ==========================================
  function renderStatsView() {
    const stats = window.meloStore.state.stats;
    const minutes = Math.floor(stats.totalSecondsListened / 60);
    const hours = (minutes / 60).toFixed(1);

    const sortedSongs = Object.entries(stats.playCounts)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5);

    const sortedArtists = Object.entries(stats.artistCounts)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5);

    mainView.innerHTML = `
      <div class="stage-content">
        <div class="section-heading"><h2>Listening Analytics</h2></div>
        <div style="display:grid;grid-template-columns:repeat(auto-fit, minmax(220px, 1fr));gap:20px;margin-bottom:40px;">
          <div style="background:var(--surface-card);border:1px solid var(--border-subtle);border-radius:16px;padding:24px;">
            <div style="font-size:0.8rem;color:var(--text-muted);text-transform:uppercase;font-weight:700;">Total Time Streamed</div>
            <div style="font-size:2.2rem;font-weight:800;color:#fff;margin-top:6px;">${hours} <span style="font-size:1rem;color:var(--text-muted);">hours</span></div>
          </div>
          <div style="background:var(--surface-card);border:1px solid var(--border-subtle);border-radius:16px;padding:24px;">
            <div style="font-size:0.8rem;color:var(--text-muted);text-transform:uppercase;font-weight:700;">Completed Streams</div>
            <div style="font-size:2.2rem;font-weight:800;color:#fff;margin-top:6px;">${Object.values(stats.playCounts).reduce((a, b) => a + b, 0)}</div>
          </div>
          <div style="background:var(--surface-card);border:1px solid var(--border-subtle);border-radius:16px;padding:24px;">
            <div style="font-size:0.8rem;color:var(--text-muted);text-transform:uppercase;font-weight:700;">Active Days</div>
            <div style="font-size:2.2rem;font-weight:800;color:#fff;margin-top:6px;">${Object.keys(stats.activeDays).length}</div>
          </div>
        </div>

        <div style="display:grid;grid-template-columns:1fr 1fr;gap:40px;">
          <div>
            <div class="section-heading"><h2>Top Artists</h2></div>
            <div style="display:flex;flex-direction:column;gap:8px;">
              ${sortedArtists.map(([art, count], i) => `
                <div style="display:flex;justify-content:space-between;padding:12px;background:var(--surface-card);border-radius:10px;">
                  <span>${i + 1}. <strong>${art}</strong></span>
                  <span style="color:var(--text-muted)">${count} plays</span>
                </div>
              `).join('') || '<p style="color:var(--text-muted)">Listen to more tracks to generate insights.</p>'}
            </div>
          </div>
          <div>
            <div class="section-heading"><h2>Top Replayed Songs</h2></div>
            <div style="display:flex;flex-direction:column;gap:8px;">
              ${sortedSongs.map(([id, count], i) => {
                const track = window.meloStore.state.history.find(t => t.id === id);
                return `
                  <div style="display:flex;justify-content:space-between;padding:12px;background:var(--surface-card);border-radius:10px;">
                    <span>${i + 1}. <strong>${track ? track.title : 'Track'}</strong></span>
                    <span style="color:var(--text-muted)">${count} plays</span>
                  </div>
                `;
              }).join('') || '<p style="color:var(--text-muted)">Play counts appear after 15s of active streaming.</p>'}
            </div>
          </div>
        </div>
      </div>
    `;
  }

  // ==========================================
  // LIVE SEARCH & SUGGESTIONS
  // ==========================================
  let debounceTimer = null;

  searchInput.addEventListener('input', () => {
    clearTimeout(debounceTimer);
    const q = searchInput.value.trim();
    if (!q) {
      suggestionsBox.classList.remove('open');
      return;
    }

    debounceTimer = setTimeout(async () => {
      try {
        const res = await fetch(`/api/suggestions?query=${encodeURIComponent(q)}`);
        const data = await res.json();
        if (data.suggestions && data.suggestions.length > 0) {
          suggestionsBox.innerHTML = data.suggestions.map(s => `
            <div class="sug-item"><span>🔍</span> <span>${s}</span></div>
          `).join('');
          suggestionsBox.classList.add('open');

          suggestionsBox.querySelectorAll('.sug-item').forEach(item => {
            item.onclick = () => {
              searchInput.value = item.querySelector('span:last-child').innerText;
              suggestionsBox.classList.remove('open');
              performSearch(searchInput.value);
            };
          });
        }
      } catch (e) {}
    }, 250);
  });

  searchInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      suggestionsBox.classList.remove('open');
      performSearch(searchInput.value);
    }
  });

  async function performSearch(query) {
    const q = query.trim();
    if (!q) return;

    if (currentAbortController) currentAbortController.abort();
    currentAbortController = new AbortController();

    window.meloStore.addRecentSearch(q);

    mainView.innerHTML = `
      <div class="stage-content">
        <div class="section-heading"><h2>Searching for "${q}"...</h2></div>
        <div id="searchGrid" class="capsule-grid"></div>
        <div id="searchList"></div>
      </div>
    `;

    try {
      const res = await fetch(`/api/search?query=${encodeURIComponent(q)}`, {
        signal: currentAbortController.signal
      });
      const data = await res.json();

      if (data.results && data.results.length > 0) {
        document.querySelector('.section-heading h2').innerText = `Results for "${q}"`;
        renderCards('searchGrid', data.results.slice(0, 6));
        renderTrackRows('searchList', data.results);
      } else {
        document.querySelector('.section-heading h2').innerText = `No results found for "${q}".`;
      }
    } catch (e) {
      if (e.name !== 'AbortError') {
        document.querySelector('.section-heading h2').innerText = `Search error occurred. Please retry.`;
      }
    }
  }

  // ==========================================
  // CONTEXT MENU (Right Click)
  // ==========================================
  function showContextMenu(e, track) {
    e.preventDefault();
    activeContextTrack = track;

    contextMenu.style.top = `${Math.min(e.clientY, window.innerHeight - 240)}px`;
    contextMenu.style.left = `${Math.min(e.clientX, window.innerWidth - 220)}px`;
    contextMenu.classList.add('open');
  }

  window.addEventListener('click', () => contextMenu.classList.remove('open'));

  document.getElementById('ctxPlayNext').onclick = () => {
    if (activeContextTrack) {
      window.meloPlayer.enqueue(activeContextTrack, true);
      showToast("Playing next", "info");
    }
  };

  document.getElementById('ctxAddToQueue').onclick = () => {
    if (activeContextTrack) {
      window.meloPlayer.enqueue(activeContextTrack, false);
      showToast("Added to queue", "info");
    }
  };

  document.getElementById('ctxAddToPlaylist').onclick = () => {
    if (!activeContextTrack) return;
    const playlists = Object.values(window.meloStore.state.playlists);
    const options = playlists.map(p => p.name).join(', ');

    openModal("Add to Playlist", `Type existing playlist name (${options}):`, (name) => {
      const target = playlists.find(p => p.name.toLowerCase() === name.toLowerCase());
      if (target) {
        window.meloStore.addToPlaylist(target.id, activeContextTrack);
        showToast(`Added to ${target.name}`, "info");
      } else {
        showToast("Playlist not found", "error");
      }
    });
  };

  // ==========================================
  // FULLSCREEN LYRICS
  // ==========================================
  async function openLyrics() {
    const track = window.meloPlayer.queue[window.meloPlayer.currentIndex];
    if (!track) return;

    lyricsOverlay.classList.add('open');
    document.getElementById('lyricsCover').src = track.thumbnail || '';
    document.getElementById('lyricsBackdrop').style.backgroundImage = `url(${track.thumbnail})`;
    document.getElementById('lyricsTitle').innerText = track.title;
    document.getElementById('lyricsArtist').innerText = track.artist;

    const list = document.getElementById('lyricsLines');
    list.innerHTML = `<div class="lyrics-line active">Fetching synced lyrics...</div>`;

    if (lyricsCache[track.id]) {
      renderLyricsLines(lyricsCache[track.id]);
      return;
    }

    try {
      const res = await fetch(`/api/lyrics/${track.id}`);
      const data = await res.json();
      lyricsCache[track.id] = data.lines;
      renderLyricsLines(data.lines);
    } catch (e) {
      list.innerHTML = `<div class="lyrics-line">Unable to load lyrics.</div>`;
    }
  }

  function renderLyricsLines(lines) {
    const list = document.getElementById('lyricsLines');
    list.innerHTML = '';
    lines.forEach((line, i) => {
      const el = document.createElement('div');
      el.className = `lyrics-line ${i === 0 ? 'active' : ''}`;
      el.innerText = line;
      el.onclick = () => {
        list.querySelectorAll('.lyrics-line').forEach(l => l.classList.remove('active'));
        el.classList.add('active');
      };
      list.appendChild(el);
    });
  }

  document.getElementById('closeLyricsBtn').onclick = () => lyricsOverlay.classList.remove('open');

  // ==========================================
  // QUEUE DRAWER
  // ==========================================
  function toggleQueueDrawer() {
    queueDrawer.classList.toggle('open');
    if (queueDrawer.classList.contains('open')) {
      renderQueueItems();
    }
  }

  function renderQueueItems() {
    const list = document.getElementById('queueList');
    list.innerHTML = '';

    window.meloPlayer.queue.forEach((track, i) => {
      const isCurrent = i === window.meloPlayer.currentIndex;
      const item = document.createElement('div');
      item.className = `queue-item ${isCurrent ? 'active' : ''}`;
      item.innerHTML = `
        <img src="${track.thumbnail || ''}" style="width:36px;height:36px;border-radius:6px;object-fit:cover;" />
        <div class="queue-item-meta">
          <div class="queue-item-title">${track.title}</div>
          <div class="queue-item-artist">${track.artist}</div>
        </div>
        <div class="queue-actions">
          <button style="background:none;border:none;color:var(--text-dim);cursor:pointer;" onclick="event.stopPropagation(); window.meloPlayer.queue.splice(${i}, 1); renderQueueItems();">✕</button>
        </div>
      `;
      item.onclick = () => window.meloPlayer.playIndex(i);
      list.appendChild(item);
    });
  }

  document.getElementById('queueToggleBtn').onclick = toggleQueueDrawer;
  document.getElementById('closeQueueBtn').onclick = () => queueDrawer.classList.remove('open');
  document.getElementById('clearQueueBtn').onclick = () => {
    window.meloPlayer.queue = [];
    window.meloPlayer.currentIndex = -1;
    renderQueueItems();
    showToast("Queue cleared", "info");
  };

  // ==========================================
  // MODALS & TOAST NOTIFICATIONS
  // ==========================================
  function openModal(title, desc, onConfirm) {
    genericModal.classList.add('open');
    document.getElementById('modalTitle').innerText = title;
    document.getElementById('modalDesc').innerText = desc;
    const inp = document.getElementById('modalInput');
    inp.value = '';
    inp.focus();

    document.getElementById('modalConfirmBtn').onclick = () => {
      genericModal.classList.remove('open');
      onConfirm(inp.value);
    };
    document.getElementById('modalCancelBtn').onclick = () => genericModal.classList.remove('open');
  }

  function showToast(message, type = 'info') {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.innerHTML = `<span>${type === 'error' ? '⚠️' : '✓'}</span> <span>${message}</span>`;
    container.appendChild(toast);
    setTimeout(() => toast.remove(), 2800);
  }
  window.addEventListener('app:toast', (e) => showToast(e.detail.message, e.detail.type));

  // ==========================================
  // PLAYER SYNC & CONTROLS
  // ==========================================
  const dockTitle = document.getElementById('dockTitle');
  const dockArtist = document.getElementById('dockArtist');
  const dockThumb = document.getElementById('dockThumb');
  const playBtn = document.getElementById('dockPlayBtn');
  const scrubber = document.getElementById('dockScrubber');
  const timeCur = document.getElementById('timeCurrent');
  const timeDur = document.getElementById('timeDuration');
  const shuffleBtn = document.getElementById('shuffleBtn');
  const repeatBtn = document.getElementById('repeatBtn');

  window.addEventListener('player:trackchanged', (e) => {
    const t = e.detail;
    dockTitle.innerText = t.title;
    dockArtist.innerText = t.artist;
    dockThumb.src = t.thumbnail || '';
    handleRoute(); // re-render rows to update playing wave
  });

  window.addEventListener('player:playstate', (e) => {
    playBtn.innerHTML = e.detail.playing
      ? `<svg viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>`
      : `<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>`;
  });

  window.addEventListener('player:timeupdate', (e) => {
    const { currentTime, duration } = e.detail;
    if (duration > 0) {
      scrubber.value = (currentTime / duration) * 100;
      timeCur.innerText = formatTime(currentTime);
      timeDur.innerText = formatTime(duration);
    }
  });

  playBtn.onclick = () => window.meloPlayer.togglePlay();
  document.getElementById('dockNextBtn').onclick = () => window.meloPlayer.next();
  document.getElementById('dockPrevBtn').onclick = () => window.meloPlayer.previous();

  scrubber.oninput = (e) => {
    if (window.meloPlayer.audio.duration) {
      window.meloPlayer.seek((e.target.value / 100) * window.meloPlayer.audio.duration);
    }
  };

  document.getElementById('dockVol').oninput = (e) => window.meloPlayer.setVolume(e.target.value);

  shuffleBtn.onclick = () => {
    const active = window.meloPlayer.toggleShuffle();
    shuffleBtn.classList.toggle('active', active);
    showToast(active ? "Shuffle on" : "Shuffle off", "info");
  };

  repeatBtn.onclick = () => {
    const mode = window.meloPlayer.cycleRepeat();
    repeatBtn.classList.toggle('active', mode !== 'none');
    repeatBtn.title = `Repeat: ${mode}`;
    showToast(`Repeat: ${mode}`, "info");
  };

  document.getElementById('lyricsBtn').onclick = openLyrics;
  document.getElementById('playerMeta').onclick = openLyrics;

  window.toggleFavoriteTrack = function(id, btn) {
    const track = window.meloPlayer.queue.find(t => t.id === id) ||
                  window.meloStore.state.history.find(t => t.id === id);
    if (track) {
      window.meloStore.toggleFavorite(track);
      const isFav = window.meloStore.isFavorite(id);
      btn.classList.toggle('favorited', isFav);
      btn.innerText = isFav ? '♥' : '♡';
      showToast(isFav ? "Saved to Favorites" : "Removed from Favorites", "info");
    }
  };

  function formatTime(secs) {
    if (isNaN(secs)) return "0:00";
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  }

  // ==========================================
  // KEYBOARD ACCESSIBILITY
  // ==========================================
  window.addEventListener('keydown', (e) => {
    if (['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) return;

    if (e.code === 'Space') {
      e.preventDefault();
      window.meloPlayer.togglePlay();
    } else if (e.key === 'ArrowRight') {
      window.meloPlayer.seek(window.meloPlayer.audio.currentTime + 5);
    } else if (e.key === 'ArrowLeft') {
      window.meloPlayer.seek(window.meloPlayer.audio.currentTime - 5);
    } else if (e.key.toLowerCase() === 'n') {
      window.meloPlayer.next();
    } else if (e.key.toLowerCase() === 'p') {
      window.meloPlayer.previous();
    } else if (e.key.toLowerCase() === 'm') {
      const muted = window.meloPlayer.toggleMute();
      showToast(muted ? "Muted" : "Unmuted", "info");
    } else if (e.key.toLowerCase() === 'l') {
      openLyrics();
    } else if (e.key.toLowerCase() === 'q') {
      toggleQueueDrawer();
    } else if (e.key === 'Escape') {
      lyricsOverlay.classList.remove('open');
      queueDrawer.classList.remove('open');
      genericModal.classList.remove('open');
    }
  });

  // Initial Route
  handleRoute();
});