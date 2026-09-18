/* =============================================================================
   melo-motion.js — MELO motion & performance runtime
   -----------------------------------------------------------------------------
   Loaded AFTER app.js, as a plain script. Self-contained, no dependencies, and
   deliberately defensive: every feature degrades to a no-op rather than
   throwing, because this file must never be able to break playback.

   Responsibilities
     1. Device tiering. Decide once whether this phone can afford blur and
        continuous animation, and set .melo-lite if it cannot.
     2. Battery policy. Drop to lite mode on Battery Saver or a low charge.
     3. Visibility policy. Pause every decorative animation when backgrounded.
     4. A single shared rAF loop, so N animated things cost one frame callback
        rather than N timers competing.
     5. Marquee only for text that genuinely overflows.
     6. Ripple, stagger and FLIP helpers used by the CSS layer.
   ============================================================================= */

(function () {
  'use strict';

  if (window.__meloMotion) return;               // never initialise twice

  var root = document.documentElement;
  var body = document.body || document.getElementsByTagName('body')[0];

  // ===========================================================================
  // 1. DEVICE TIER
  // ===========================================================================
  var tier = (function detectTier() {
    var cores = navigator.hardwareConcurrency || 4;
    var mem = navigator.deviceMemory || 4;        // GiB, Chrome only
    var conn = navigator.connection || {};
    var slowNet = /^(slow-2g|2g|3g)$/.test(conn.effectiveType || '');

    // Any one of these is enough to put us in the cheap bucket. Getting this
    // wrong in the conservative direction costs a little visual polish;
    // getting it wrong the other way costs dropped frames on every scroll.
    if (cores <= 4 || mem <= 2 || slowNet || conn.saveData) return 'lite';
    if (cores <= 6 || mem <= 4) return 'balanced';
    return 'full';
  })();

  function applyTier(t) {
    root.classList.toggle('melo-lite', t === 'lite');
    root.setAttribute('data-melo-tier', t);
  }
  applyTier(tier);

  // ===========================================================================
  // 2. BATTERY POLICY
  // ===========================================================================
  // Blur and infinite animations are the two things that actually show up in a
  // battery report. Below 20% (and not charging) we trade them for runtime.
  var batteryForcedLite = false;

  function watchBattery() {
    if (!navigator.getBattery) return;
    navigator.getBattery().then(function (bat) {
      function evaluate() {
        var low = bat.level <= 0.2 && !bat.charging;
        if (low !== batteryForcedLite) {
          batteryForcedLite = low;
          applyTier(low ? 'lite' : tier);
        }
      }
      evaluate();
      bat.addEventListener('levelchange', evaluate);
      bat.addEventListener('chargingchange', evaluate);
    }).catch(function () { /* unsupported; keep the detected tier */ });
  }
  watchBattery();

  // ===========================================================================
  // 3. VISIBILITY POLICY
  // ===========================================================================
  // Audio keeps playing in the background; pixels do not need to move.
  document.addEventListener('visibilitychange', function () {
    var hidden = document.visibilityState === 'hidden';
    root.classList.toggle('melo-hidden', hidden);
    if (hidden) { stopLoop(); } else if (tasks.length) { startLoop(); }
  }, { passive: true });

  // ===========================================================================
  // 4. SHARED ANIMATION LOOP
  // ===========================================================================
  var tasks = [];
  var rafId = null;

  function frame(ts) {
    rafId = null;
    for (var i = tasks.length - 1; i >= 0; i--) {
      try {
        if (tasks[i](ts) === false) tasks.splice(i, 1);
      } catch (e) {
        tasks.splice(i, 1);                       // a broken task is dropped
      }
    }
    if (tasks.length) startLoop();
  }

  function startLoop() {
    if (rafId === null && document.visibilityState !== 'hidden') {
      rafId = requestAnimationFrame(frame);
    }
  }

  function stopLoop() {
    if (rafId !== null) { cancelAnimationFrame(rafId); rafId = null; }
  }

  function addTask(fn) {
    if (tasks.indexOf(fn) === -1) tasks.push(fn);
    startLoop();
    return function remove() {
      var i = tasks.indexOf(fn);
      if (i !== -1) tasks.splice(i, 1);
    };
  }

  // ===========================================================================
  // 5. MEMOISED DOM TEXT
  // ===========================================================================
  // The progress handler rewrites four time labels several times a second.
  // Assigning identical text still dirties the node and invalidates layout, so
  // we compare first. This alone removes most of the per-frame layout work
  // during playback.
  var textCache = new WeakMap();

  function setText(el, value) {
    if (!el) return;
    if (textCache.get(el) === value) return;
    textCache.set(el, value);
    el.textContent = value;
  }

  // ===========================================================================
  // 6. MARQUEE
  // ===========================================================================
  // Only scroll text that actually overflows its container, and scale the
  // duration to the overflow distance so long and short titles move at the
  // same perceived speed.
  function applyMarquee(el) {
    if (!el || root.classList.contains('melo-lite')) return;

    var existing = el.querySelector(':scope > span');
    var text = existing ? existing.textContent : el.textContent;
    if (!text) return;

    // Measure against the un-marqueed state.
    el.classList.remove('melo-marquee');
    if (existing) el.textContent = text;

    var overflow = el.scrollWidth - el.clientWidth;
    if (overflow <= 4) return;                    // fits; leave it alone

    var span = document.createElement('span');
    span.textContent = text;
    var clone = document.createElement('span');
    clone.textContent = text;
    clone.setAttribute('aria-hidden', 'true');

    el.textContent = '';
    el.appendChild(span);
    el.appendChild(clone);
    el.classList.add('melo-marquee');
    el.style.setProperty(
      '--marquee-dur',
      Math.max(8, Math.min(26, (el.scrollWidth / 42))) + 's'
    );
  }

  function refreshMarquees() {
    ['dockTitle', 'sheetTrackTitle', 'dockArtist'].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) applyMarquee(el);
    });
    document.querySelectorAll('[data-marquee]').forEach(applyMarquee);
  }

  // ===========================================================================
  // 7. PLAYING STATE
  // ===========================================================================
  // body.is-playing drives the artwork breathing animation in CSS, so the
  // stylesheet never has to guess at playback state.
  function bindAudioState() {
    var audio = document.getElementById('audio');
    if (!audio) return;

    function playing() { body.classList.add('is-playing'); }
    function stopped() { body.classList.remove('is-playing'); }

    audio.addEventListener('play', playing, { passive: true });
    audio.addEventListener('playing', playing, { passive: true });
    audio.addEventListener('pause', stopped, { passive: true });
    audio.addEventListener('ended', stopped, { passive: true });
    audio.addEventListener('loadedmetadata', refreshMarquees, { passive: true });
  }

  // ===========================================================================
  // 8. RIPPLE
  // ===========================================================================
  // Drawn on a single pseudo-element positioned at the touch point. One node
  // per press, removed on animation end.
  function bindRipple() {
    document.addEventListener('pointerdown', function (ev) {
      if (root.classList.contains('melo-lite')) return;

      var target = ev.target.closest(
        '.ctrl-btn, .capsule-btn, .pill-action-btn, .context-action-row, .filter-chip'
      );
      if (!target || target.dataset.noRipple) return;

      var rect = target.getBoundingClientRect();
      var size = Math.max(rect.width, rect.height);

      var ink = document.createElement('span');
      ink.className = 'melo-ink';
      ink.style.cssText =
        'position:absolute;border-radius:50%;pointer-events:none;' +
        'background:radial-gradient(circle,rgba(255,255,255,.26),rgba(255,255,255,0) 70%);' +
        'width:' + size + 'px;height:' + size + 'px;' +
        'left:' + (ev.clientX - rect.left - size / 2) + 'px;' +
        'top:' + (ev.clientY - rect.top - size / 2) + 'px;' +
        'transform:scale(0);opacity:1;' +
        'transition:transform 520ms cubic-bezier(.22,1,.36,1),opacity 520ms linear;';

      var cs = getComputedStyle(target);
      if (cs.position === 'static') target.style.position = 'relative';
      if (cs.overflow !== 'hidden') target.style.overflow = 'hidden';

      target.appendChild(ink);
      requestAnimationFrame(function () {
        ink.style.transform = 'scale(2.1)';
        ink.style.opacity = '0';
      });
      setTimeout(function () { ink.remove(); }, 560);
    }, { passive: true });
  }

  // ===========================================================================
  // 9. STAGGERED REVEAL
  // ===========================================================================
  // Applied once per render, then removed, so the class cannot accumulate and
  // re-trigger on every subsequent DOM touch.
  function stagger(container) {
    if (!container || root.classList.contains('melo-lite')) return;
    container.classList.add('melo-stagger');
    setTimeout(function () { container.classList.remove('melo-stagger'); }, 900);
  }

  // Watch the main view container and stagger freshly rendered lists.
  function observeRenders() {
    var host = document.getElementById('viewContainer');
    if (!host || !window.MutationObserver) return;

    var pending = null;
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        if (records[i].addedNodes.length > 2) {
          clearTimeout(pending);
          // Coalesce bursts: a render often lands in several mutations.
          pending = setTimeout(function () {
            stagger(host.querySelector('.melo-list, .track-list, #viewContainer > *'));
            refreshMarquees();
          }, 30);
          return;
        }
      }
    }).observe(host, { childList: true, subtree: true });
  }

  // ===========================================================================
  // 10. FLIP
  // ===========================================================================
  // Measure, mutate, then animate the delta on the compositor. Used for
  // reordering the queue without layout thrash.
  function flip(elements, mutate) {
    var els = Array.prototype.slice.call(elements);
    if (root.classList.contains('melo-lite') || !els.length) { mutate(); return; }

    var first = els.map(function (el) { return el.getBoundingClientRect(); });
    mutate();
    var last = els.map(function (el) { return el.getBoundingClientRect(); });

    els.forEach(function (el, i) {
      var dx = first[i].left - last[i].left;
      var dy = first[i].top - last[i].top;
      if (!dx && !dy) return;

      el.animate(
        [
          { transform: 'translate(' + dx + 'px,' + dy + 'px)' },
          { transform: 'none' }
        ],
        { duration: 340, easing: 'cubic-bezier(.22,1,.36,1)' }
      );
    });
  }

  // ===========================================================================
  // 11. IMAGE DECODE
  // ===========================================================================
  // Decoding a cover on the main thread during a scroll is a guaranteed
  // dropped frame. These hints move the work off it.
  function tuneImages(scope) {
    (scope || document).querySelectorAll('img:not([data-melo-tuned])')
      .forEach(function (img) {
        img.dataset.meloTuned = '1';
        if (!img.loading) img.loading = 'lazy';
        img.decoding = 'async';
        img.fetchPriority = img.closest('#dockPlayerBar') ? 'high' : 'low';
      });
  }

  // ===========================================================================
  // 12. SCROLL IDLE
  // ===========================================================================
  // While a list is moving, suspend hover/animation work; resume shortly after
  // it settles. Listener is passive so it can never block the scroll itself.
  function bindScrollIdle() {
    var timer = null;
    var targets = document.querySelectorAll(
      '#viewContainer, #mainViewport, .melo-scroll'
    );

    targets.forEach(function (el) {
      el.addEventListener('scroll', function () {
        if (!root.classList.contains('melo-scrolling')) {
          root.classList.add('melo-scrolling');
        }
        clearTimeout(timer);
        timer = setTimeout(function () {
          root.classList.remove('melo-scrolling');
          tuneImages(el);
        }, 130);
      }, { passive: true });
    });
  }

  // ===========================================================================
  // BOOT
  // ===========================================================================
  function boot() {
    body = document.body;
    bindAudioState();
    bindRipple();
    bindScrollIdle();
    observeRenders();
    tuneImages();
    refreshMarquees();
    window.addEventListener('resize', debounce(refreshMarquees, 180), { passive: true });
  }

  function debounce(fn, ms) {
    var t;
    return function () {
      var a = arguments, c = this;
      clearTimeout(t);
      t = setTimeout(function () { fn.apply(c, a); }, ms);
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }

  // Public surface, used by app.js where it helps.
  window.__meloMotion = {
    tier: function () { return root.getAttribute('data-melo-tier'); },
    setText: setText,
    addTask: addTask,
    flip: flip,
    stagger: stagger,
    marquee: applyMarquee,
    refreshMarquees: refreshMarquees,
    tuneImages: tuneImages
  };
})();