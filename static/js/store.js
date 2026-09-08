class MeloStore {
  constructor() {
    this.STORAGE_KEY = 'melo_state_v2';
    this.state = this.init();
    this.saveTimeout = null;
    this.listeners = new Set();

    // Synchronize updates across multiple browser tabs
    window.addEventListener('storage', (e) => {
      if (e.key === this.STORAGE_KEY && e.newValue) {
        try {
          this.state = JSON.parse(e.newValue);
          this.notify();
        } catch (err) {
          console.warn('[MELO:STORE] Cross-tab state synchronization failed:', err);
        }
      }
    });
  }

  init() {
    const currentRaw = localStorage.getItem(this.STORAGE_KEY);
    const legacyFavs = localStorage.getItem('melo_favorites') || localStorage.getItem('aura_favorites');
    const legacyPlaylists = localStorage.getItem('melo_playlists');

    let initial = {
      version: 2,
      favorites: {},
      playlists: {
        'pl-favorites': {
          id: 'pl-favorites',
          name: 'Favorites',
          description: 'Your loved tracks',
          tracks: []
        },
        'pl-repeat': {
          id: 'pl-repeat',
          name: 'On Repeat',
          description: 'Tracks in heavy rotation',
          tracks: []
        }
      },
      history: [],
      stats: {
        playCounts: {},
        trackTime: {},
        artistCounts: {},
        albumCounts: {},
        totalSecondsListened: 0,
        activeDays: {}
      },
      playback: {
        currentTrack: null,
        currentTime: 0,
        volume: 0.85,
        muted: false,
        shuffle: false,
        repeatMode: 'none', // 'none' | 'all' | 'one'
        queue: []
      },
      recentSearches: []
    };

    if (currentRaw) {
      try {
        const parsed = JSON.parse(currentRaw);
        return { ...initial, ...parsed };
      } catch (e) {
        console.warn("[MELO:STORE] State corrupted, falling back to clean init", e);
      }
    }

    // Migrate flat storage keys if present
    if (legacyFavs) {
      try {
        const parsedFavs = JSON.parse(legacyFavs);
        initial.favorites = parsedFavs;
        initial.playlists['pl-favorites'].tracks = Object.values(parsedFavs);
      } catch (e) {}
    }

    if (legacyPlaylists) {
      try {
        const parsedPl = JSON.parse(legacyPlaylists);
        initial.playlists = { ...initial.playlists, ...parsedPl };
      } catch (e) {}
    }

    return initial;
  }

  save() {
    if (this.saveTimeout) clearTimeout(this.saveTimeout);
    this.saveTimeout = setTimeout(() => {
      try {
        localStorage.setItem(this.STORAGE_KEY, JSON.stringify(this.state));
        // Maintain backwards compatibility with inline scripts expecting raw keys
        localStorage.setItem('melo_favorites', JSON.stringify(this.state.favorites));
        localStorage.setItem('melo_playlists', JSON.stringify(this.state.playlists));
      } catch (err) {
        console.error("[MELO:STORE] Failed to persist state to localStorage:", err);
      }
    }, 200);
    this.notify();
  }

  subscribe(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  notify() {
    this.listeners.forEach((fn) => {
      try {
        fn(this.state);
      } catch (err) {
        console.error("[MELO:STORE] Subscriber callback execution error:", err);
      }
    });
  }

  // --- Favorites Management ---
  isFavorite(trackId) {
    return !!this.state.favorites[trackId];
  }

  toggleFavorite(track) {
    if (!track || !track.id) return false;
    const exists = !!this.state.favorites[track.id];

    if (exists) {
      delete this.state.favorites[track.id];
      this.state.playlists['pl-favorites'].tracks = 
        this.state.playlists['pl-favorites'].tracks.filter(t => t.id !== track.id);
    } else {
      this.state.favorites[track.id] = track;
      this.state.playlists['pl-favorites'].tracks.unshift(track);
    }

    this.save();
    return !exists;
  }

  // --- Telemetry & Listening History (>15s) ---
  recordPlayback(track, secondsListened = 0) {
    if (!track || !track.id || secondsListened < 15) return;

    const now = Date.now();
    const today = new Date().toISOString().slice(0, 10);
    const secs = Math.floor(secondsListened);

    // Maintain history stack (bounded at 300 entries)
    this.state.history.unshift({ ...track, timestamp: now, durationPlayed: secs });
    if (this.state.history.length > 300) this.state.history.pop();

    // Update listening statistics
    this.state.stats.totalSecondsListened += secs;
    this.state.stats.playCounts[track.id] = (this.state.stats.playCounts[track.id] || 0) + 1;
    this.state.stats.trackTime[track.id] = (this.state.stats.trackTime[track.id] || 0) + secs;

    if (track.artist) {
      this.state.stats.artistCounts[track.artist] = (this.state.stats.artistCounts[track.artist] || 0) + 1;
    }
    if (track.album) {
      this.state.stats.albumCounts[track.album] = (this.state.stats.albumCounts[track.album] || 0) + 1;
    }
    this.state.stats.activeDays[today] = (this.state.stats.activeDays[today] || 0) + 1;

    // Smart Rotation Playlist: On Repeat (tracks with >= 3 verified plays)
    if (this.state.stats.playCounts[track.id] >= 3) {
      const alreadyInRepeat = this.state.playlists['pl-repeat'].tracks.some(t => t.id === track.id);
      if (!alreadyInRepeat) {
        this.state.playlists['pl-repeat'].tracks.unshift(track);
      }
    }

    this.save();
  }

  // --- Playlists Management ---
  createPlaylist(name, description = '') {
    const id = 'pl-' + Date.now();
    this.state.playlists[id] = { id, name: name.trim(), description, tracks: [] };
    this.save();
    return id;
  }

  renamePlaylist(id, newName) {
    if (this.state.playlists[id] && newName.trim()) {
      this.state.playlists[id].name = newName.trim();
      this.save();
    }
  }

  deletePlaylist(id) {
    if (id === 'pl-favorites' || id === 'pl-repeat') return;
    delete this.state.playlists[id];
    this.save();
  }

  addToPlaylist(playlistId, track) {
    if (!track || !track.id) return false;
    const target = this.state.playlists[playlistId];
    if (target) {
      const exists = target.tracks.some(t => t.id === track.id);
      if (!exists) {
        target.tracks.push(track);
        this.save();
        return true;
      }
    }
    return false;
  }

  removeFromPlaylist(playlistId, trackId) {
    const target = this.state.playlists[playlistId];
    if (target) {
      target.tracks = target.tracks.filter(t => t.id !== trackId);
      this.save();
    }
  }

  // --- Search Term Cache ---
  addRecentSearch(query) {
    const q = query.trim();
    if (!q) return;
    this.state.recentSearches = [q, ...this.state.recentSearches.filter(s => s.toLowerCase() !== q.toLowerCase())].slice(0, 8);
    this.save();
  }

  clearRecentSearches() {
    this.state.recentSearches = [];
    this.save();
  }
}

window.meloStore = new MeloStore();