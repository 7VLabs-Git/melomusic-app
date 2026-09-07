class MeloStore {
  constructor() {
    this.STORAGE_KEY = 'melo_state_v2';
    this.state = this.init();
    this.saveTimeout = null;
    this.listeners = new Set();
  }

  init() {
    // Migration from old aurastream state if available
    const oldRaw = localStorage.getItem('aurastream_state_v1');
    const legacyFavs = localStorage.getItem('aura_favorites');
    const currentRaw = localStorage.getItem(this.STORAGE_KEY);

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
          description: 'Tracks in high rotation',
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
        console.warn("MeloStore: State corrupted, re-initializing", e);
      }
    } else if (oldRaw) {
      try {
        const oldData = JSON.parse(oldRaw);
        initial.favorites = oldData.favorites || {};
        initial.history = oldData.history || [];
        initial.playlists = { ...initial.playlists, ...(oldData.playlists || {}) };
        return initial;
      } catch (e) {}
    } else if (legacyFavs) {
      try {
        const parsedFavs = JSON.parse(legacyFavs);
        initial.favorites = parsedFavs;
        initial.playlists['pl-favorites'].tracks = Object.values(parsedFavs);
        return initial;
      } catch (e) {}
    }
    return initial;
  }

  // Debounced persistence avoids locking the main thread
  save() {
    if (this.saveTimeout) clearTimeout(this.saveTimeout);
    this.saveTimeout = setTimeout(() => {
      localStorage.setItem(this.STORAGE_KEY, JSON.stringify(this.state));
    }, 400);
    this.notify();
  }

  subscribe(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  notify() {
    this.listeners.forEach((fn) => fn(this.state));
  }

  // --- Favorites ---
  isFavorite(trackId) {
    return !!this.state.favorites[trackId];
  }

  toggleFavorite(track) {
    if (!track || !track.id) return;
    if (this.state.favorites[track.id]) {
      delete this.state.favorites[track.id];
      this.state.playlists['pl-favorites'].tracks = 
        this.state.playlists['pl-favorites'].tracks.filter(t => t.id !== track.id);
    } else {
      this.state.favorites[track.id] = track;
      this.state.playlists['pl-favorites'].tracks.unshift(track);
    }
    this.save();
  }

  // --- Meaningful Play History (>15s) ---
  recordPlayback(track, secondsListened = 0) {
    if (!track || !track.id) return;
    if (secondsListened < 15) return; // Prevent fast-forward/accidental skips from polluting stats

    const now = Date.now();
    const today = new Date().toISOString().slice(0, 10);

    // Update history stack
    this.state.history.unshift({ ...track, timestamp: now, durationPlayed: secondsListened });
    if (this.state.history.length > 300) this.state.history.pop();

    // Stats
    this.state.stats.totalSecondsListened += Math.floor(secondsListened);
    this.state.stats.playCounts[track.id] = (this.state.stats.playCounts[track.id] || 0) + 1;
    this.state.stats.trackTime[track.id] = (this.state.stats.trackTime[track.id] || 0) + Math.floor(secondsListened);

    if (track.artist) {
      this.state.stats.artistCounts[track.artist] = (this.state.stats.artistCounts[track.artist] || 0) + 1;
    }
    if (track.album) {
      this.state.stats.albumCounts[track.album] = (this.state.stats.albumCounts[track.album] || 0) + 1;
    }
    this.state.stats.activeDays[today] = (this.state.stats.activeDays[today] || 0) + 1;

    // Automatic Smart Playlist: On Repeat (tracks with >= 3 plays)
    if (this.state.stats.playCounts[track.id] >= 3) {
      const exists = this.state.playlists['pl-repeat'].tracks.some(t => t.id === track.id);
      if (!exists) {
        this.state.playlists['pl-repeat'].tracks.unshift(track);
      }
    }
    this.save();
  }

  // --- Playlists ---
  createPlaylist(name, description = '') {
    const id = 'pl-' + Date.now();
    this.state.playlists[id] = { id, name, description, tracks: [] };
    this.save();
    return id;
  }

  renamePlaylist(id, newName) {
    if (this.state.playlists[id]) {
      this.state.playlists[id].name = newName;
      this.save();
    }
  }

  deletePlaylist(id) {
    if (id === 'pl-favorites' || id === 'pl-repeat') return;
    delete this.state.playlists[id];
    this.save();
  }

  addToPlaylist(playlistId, track) {
    if (this.state.playlists[playlistId]) {
      const exists = this.state.playlists[playlistId].tracks.some(t => t.id === track.id);
      if (!exists) {
        this.state.playlists[playlistId].tracks.push(track);
        this.save();
      }
    }
  }

  removeFromPlaylist(playlistId, trackId) {
    if (this.state.playlists[playlistId]) {
      this.state.playlists[playlistId].tracks =
        this.state.playlists[playlistId].tracks.filter(t => t.id !== trackId);
      this.save();
    }
  }

  // --- Recent Searches ---
  addRecentSearch(query) {
    const q = query.trim();
    if (!q) return;
    this.state.recentSearches = [q, ...this.state.recentSearches.filter(s => s !== q)].slice(0, 8);
    this.save();
  }

  clearRecentSearches() {
    this.state.recentSearches = [];
    this.save();
  }
}

window.meloStore = new MeloStore();