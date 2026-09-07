class MeloPlayer {
  constructor() {
    this.audio = new Audio();
    this.audio.preload = 'auto';

    this.queue = [];
    this.originalQueue = [];
    this.currentIndex = -1;

    this.isShuffle = false;
    this.repeatMode = 'none'; // 'none' | 'all' | 'one'
    this.playbackStartTime = 0;
    this.currentTrackDurationListened = 0;

    this.sleepTimerId = null;
    this.sleepTimerEndEpoch = 0;

    this.retryAttempts = 0;
    this.maxRetries = 2;

    this.setupListeners();
    this.restoreState();
  }

  setupListeners() {
    this.audio.addEventListener('timeupdate', () => {
      this.currentTrackDurationListened += 0.25;
      window.dispatchEvent(new CustomEvent('player:timeupdate', {
        detail: {
          currentTime: this.audio.currentTime,
          duration: this.audio.duration || 0
        }
      }));
    });

    this.audio.addEventListener('ended', () => {
      this.flushPlaybackStats();
      if (this.repeatMode === 'one') {
        this.audio.currentTime = 0;
        this.audio.play();
      } else {
        this.next();
      }
    });

    this.audio.addEventListener('play', () => {
      this.playbackStartTime = Date.now();
      this.updateMediaSessionState('playing');
      window.dispatchEvent(new CustomEvent('player:playstate', { detail: { playing: true } }));
    });

    this.audio.addEventListener('pause', () => {
      this.updateMediaSessionState('paused');
      window.dispatchEvent(new CustomEvent('player:playstate', { detail: { playing: false } }));
    });

    // Graceful error recovery: expired CDN URL / 403 fallback
    this.audio.addEventListener('error', (e) => {
      console.warn("MeloPlayer: Audio error encountered", e);
      if (this.currentIndex !== -1 && this.retryAttempts < this.maxRetries) {
        this.retryAttempts++;
        console.log(`MeloPlayer: Retrying stream resolution (${this.retryAttempts}/${this.maxRetries})...`);
        setTimeout(() => this.playIndex(this.currentIndex, false), 1000);
      } else {
        window.dispatchEvent(new CustomEvent('app:toast', {
          detail: { message: "Stream unavailable. Skipping to next song...", type: "error" }
        }));
        this.next();
      }
    });
  }

  restoreState() {
    const pb = window.meloStore.state.playback;
    if (!pb) return;

    this.queue = pb.queue || [];
    this.originalQueue = [...this.queue];
    this.currentIndex = pb.currentTrack ? this.queue.findIndex(t => t.id === pb.currentTrack.id) : -1;
    this.isShuffle = pb.shuffle || false;
    this.repeatMode = pb.repeatMode || 'none';
    this.audio.volume = pb.volume !== undefined ? pb.volume : 0.85;

    if (pb.currentTrack) {
      window.dispatchEvent(new CustomEvent('player:trackchanged', { detail: pb.currentTrack }));
    }
  }

  saveState() {
    const currentTrack = this.queue[this.currentIndex] || null;
    window.meloStore.state.playback = {
      currentTrack,
      currentTime: this.audio.currentTime,
      volume: this.audio.volume,
      muted: this.audio.muted,
      shuffle: this.isShuffle,
      repeatMode: this.repeatMode,
      queue: this.queue
    };
    window.meloStore.save();
  }

  flushPlaybackStats() {
    if (this.currentIndex !== -1 && this.queue[this.currentIndex]) {
      window.meloStore.recordPlayback(this.queue[this.currentIndex], this.currentTrackDurationListened);
    }
    this.currentTrackDurationListened = 0;
  }

  loadQueue(tracks, startIndex = 0) {
    if (!tracks || tracks.length === 0) return;
    this.flushPlaybackStats();
    this.originalQueue = [...tracks];

    if (this.isShuffle) {
      const selected = tracks[startIndex];
      const others = tracks.filter((_, idx) => idx !== startIndex);
      this.queue = [selected, ...this.shuffleArray(others)];
      this.playIndex(0);
    } else {
      this.queue = [...tracks];
      this.playIndex(startIndex);
    }
  }

  enqueue(track, playNext = false) {
    if (!track || !track.id) return;
    if (playNext && this.currentIndex !== -1) {
      this.queue.splice(this.currentIndex + 1, 0, track);
      this.originalQueue.splice(this.currentIndex + 1, 0, track);
    } else {
      this.queue.push(track);
      this.originalQueue.push(track);
      if (this.currentIndex === -1) {
        this.playIndex(0);
        return;
      }
    }
    this.saveState();
    window.dispatchEvent(new CustomEvent('player:queueupdated', { detail: this.queue }));
  }

  playIndex(index, resetRetries = true) {
    if (index < 0 || index >= this.queue.length) return;
    if (resetRetries) this.retryAttempts = 0;

    this.flushPlaybackStats();
    this.currentIndex = index;
    const track = this.queue[this.currentIndex];

    this.audio.src = `/api/stream/${track.id}`;
    this.audio.load();
    this.audio.play()
      .then(() => {
        this.setupMediaSession(track);
        this.preloadNextTrack();
      })
      .catch((err) => console.warn("MeloPlayer: Play blocked/interrupted", err));

    this.saveState();
    window.dispatchEvent(new CustomEvent('player:trackchanged', { detail: track }));
  }

  preloadNextTrack() {
    const nextIdx = this.currentIndex + 1;
    if (nextIdx < this.queue.length) {
      const nextTrack = this.queue[nextIdx];
      // Warm up the backend stream cache ahead of time
      fetch(`/api/stream/${nextTrack.id}`, { headers: { Range: 'bytes=0-1' } }).catch(() => {});
    }
  }

  togglePlay() {
    if (!this.audio.src && this.queue.length > 0) {
      this.playIndex(0);
      return;
    }
    if (this.audio.paused) {
      this.audio.play();
    } else {
      this.audio.pause();
    }
  }

  next() {
    if (this.currentIndex + 1 < this.queue.length) {
      this.playIndex(this.currentIndex + 1);
    } else if (this.repeatMode === 'all') {
      this.playIndex(0);
    } else {
      this.audio.pause();
    }
  }

  previous() {
    // Intelligent Previous: if played > 3 seconds, restart current track
    if (this.audio.currentTime > 3.0) {
      this.audio.currentTime = 0;
      return;
    }
    if (this.currentIndex - 1 >= 0) {
      this.playIndex(this.currentIndex - 1);
    } else {
      this.audio.currentTime = 0;
    }
  }

  seek(seconds) {
    if (this.audio.duration) {
      this.audio.currentTime = Math.max(0, Math.min(seconds, this.audio.duration));
    }
  }

  setVolume(val) {
    this.audio.volume = Math.max(0, Math.min(1, val));
    this.saveState();
  }

  toggleMute() {
    this.audio.muted = !this.audio.muted;
    return this.audio.muted;
  }

  toggleShuffle() {
    this.isShuffle = !this.isShuffle;
    const currentTrack = this.queue[this.currentIndex];

    if (this.isShuffle) {
      const rest = this.originalQueue.filter(t => t.id !== currentTrack.id);
      this.queue = [currentTrack, ...this.shuffleArray(rest)];
      this.currentIndex = 0;
    } else {
      this.queue = [...this.originalQueue];
      this.currentIndex = this.queue.findIndex(t => t.id === currentTrack.id);
    }
    this.saveState();
    window.dispatchEvent(new CustomEvent('player:queueupdated', { detail: this.queue }));
    return this.isShuffle;
  }

  cycleRepeat() {
    if (this.repeatMode === 'none') this.repeatMode = 'all';
    else if (this.repeatMode === 'all') this.repeatMode = 'one';
    else this.repeatMode = 'none';

    this.saveState();
    return this.repeatMode;
  }

  shuffleArray(arr) {
    const a = [...arr];
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [a[i], a[j]] = [a[j], a[i]];
    }
    return a;
  }

  setSleepTimer(minutes) {
    if (this.sleepTimerId) clearTimeout(this.sleepTimerId);
    if (minutes <= 0) {
      this.sleepTimerId = null;
      this.sleepTimerEndEpoch = 0;
      return;
    }
    this.sleepTimerEndEpoch = Date.now() + minutes * 60 * 1000;
    this.sleepTimerId = setTimeout(() => {
      this.audio.pause();
      this.sleepTimerId = null;
      this.sleepTimerEndEpoch = 0;
      window.dispatchEvent(new CustomEvent('app:toast', {
        detail: { message: "Sleep timer reached. Good night!", type: "info" }
      }));
    }, minutes * 60 * 1000);
  }

  setupMediaSession(track) {
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: track.title,
        artist: track.artist,
        album: track.album || 'MELO Music',
        artwork: [{ src: track.thumbnail || '', sizes: '512x512', type: 'image/jpeg' }]
      });

      navigator.mediaSession.setActionHandler('play', () => this.togglePlay());
      navigator.mediaSession.setActionHandler('pause', () => this.togglePlay());
      navigator.mediaSession.setActionHandler('previoustrack', () => this.previous());
      navigator.mediaSession.setActionHandler('nexttrack', () => this.next());
      navigator.mediaSession.setActionHandler('seekto', (details) => {
        if (details.seekTime !== undefined) this.seek(details.seekTime);
      });
    }
  }

  updateMediaSessionState(state) {
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = state;
    }
  }
}

window.meloPlayer = new MeloPlayer();