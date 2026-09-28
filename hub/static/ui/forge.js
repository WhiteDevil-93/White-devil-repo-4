// Forge Hub shared helpers: API, formatting, toast, pull-to-refresh, thumbnails, fullscreen clip viewer.
(() => {
  if (/ForgeHubApp/.test(navigator.userAgent)) document.documentElement.classList.add('app');
  try { if (window.top !== window && window.top.location.pathname.startsWith('/app/desktop')) document.documentElement.classList.add('app', 'desk'); } catch (e) {}

  const F = window.F = {};
  F.$ = id => document.getElementById(id);
  F.esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

  F.isVisible = () => {
    if (document.hidden) return false;
    try {
      if (window.frameElement) {
        const fe = window.frameElement;
        if (fe.style.display === 'none' || fe.style.visibility === 'hidden' || !fe.classList.contains('on')) {
          return false;
        }
      }
    } catch (e) {}
    return true;
  };

  let lastActive = Date.now();
  F.onVisible = fn => {
    const check = () => {
      if (F.isVisible() && Date.now() - lastActive > 20000) {
        lastActive = Date.now();
        fn();
      }
    };
    document.addEventListener('visibilitychange', check);
    window.addEventListener('focus', check);
    window.addEventListener('message', e => {
      if (e.data === 'forge:shown') check();
    });
  };

  F.api = async (path, opts) => {
    const timeout = (opts && opts.timeout) || 15000;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      const fetchOpts = Object.assign({credentials: 'same-origin', signal: controller.signal}, opts || {});
      delete fetchOpts.timeout;
      const r = await fetch(path, fetchOpts);
      const j = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(j.detail || ('HTTP ' + r.status));
      return j;
    } catch (err) {
      if (err.name === 'AbortError') throw new Error('Request timed out');
      throw err;
    } finally {
      clearTimeout(timer);
    }
  };
  F.post = (path, body, opts) => F.api(path, Object.assign({
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body)
  }, opts || {}));

  F.ago = t => {
    if (!t) return '—';
    const m = Math.round((Date.now() / 1000 - t) / 60);
    if (m < 1) return 'just now';
    if (m < 60) return m + ' min ago';
    const h = Math.floor(m / 60);
    return h < 24 ? h + ' h ago' : Math.floor(h / 24) + ' d ago';
  };
  F.dur = sec => {
    if (sec == null || !isFinite(sec)) return '—';
    const mins = Math.round(sec / 60), h = Math.floor(mins / 60), m = mins % 60;
    return h ? (m ? `${h} h ${m} min` : `${h} h`) : `${Math.max(m, 1)} min`;
  };
  F.clipUrl = name => '/clips/' + encodeURIComponent(name);
  F.thumbUrl = name => '/api/media/thumb/' + encodeURIComponent(name);
  F.isNew = c => Date.now() / 1000 - c.mtime < 3600;

  F.thumb = (c, label) => `<div class="thumb" data-clip="${F.esc(c.name)}">
      <img loading="lazy" src="${F.thumbUrl(c.name)}" onload="this.classList.add('ld')" onerror="this.classList.add('ld');this.style.opacity='.25'" alt="">
      ${label ? `<span class="badge">${F.esc(label)}</span>` : ''}${F.isNew(c) ? '<span class="new">NEW</span>' : ''}</div>`;

  let toastEl;
  F.toast = t => {
    if (!toastEl) { toastEl = document.createElement('div'); toastEl.id = 'toast'; document.body.appendChild(toastEl); }
    toastEl.textContent = t; toastEl.classList.add('on');
    clearTimeout(toastEl._t); toastEl._t = setTimeout(() => toastEl.classList.remove('on'), 3500);
  };

  F.pull = fn => {
    const ind = document.createElement('div'); ind.id = 'ptr'; ind.textContent = '↻'; document.body.appendChild(ind);
    let y0 = null, dy = 0;
    addEventListener('touchstart', e => { y0 = scrollY <= 0 && !document.querySelector('.viewer') ? e.touches[0].clientY : null; dy = 0; }, {passive: true});
    addEventListener('touchmove', e => {
      if (y0 == null) return;
      dy = e.touches[0].clientY - y0;
      if (dy > 0) ind.style.top = Math.min(dy / 2 - 44, 40) + 'px';
    }, {passive: true});
    addEventListener('touchend', async () => {
      if (y0 == null) return;
      y0 = null;
      if (dy > 120) {
        ind.style.top = '40px'; ind.classList.add('spin');
        try { await fn(); } finally { ind.classList.remove('spin'); ind.style.top = '-44px'; }
      } else ind.style.top = '-44px';
    });
  };

  // Fullscreen player over a list of clips; swipe left/right for next/previous.
  F.viewer = (clips, i, titleOf) => {
    const v = document.createElement('div');
    v.className = 'viewer';
    v.innerHTML = `<div class="top"><button class="iconbtn" data-a="close">✕</button>
        <div class="grow"><div class="trunc" style="font-weight:700" data-t></div><div class="tiny mut" data-s></div></div>
        <a class="iconbtn" data-a="dl" download>⤓</a></div>
      <video playsinline autoplay loop controls></video>
      <div class="bot"><button class="iconbtn" data-a="prev">‹</button><button class="iconbtn" data-a="next">›</button></div>`;
    document.body.appendChild(v);
    const vid = v.querySelector('video');
    const show = k => {
      i = (k + clips.length) % clips.length;
      const c = clips[i];
      vid.src = c.url || F.clipUrl(c.name);
      v.querySelector('[data-t]').textContent = titleOf ? titleOf(c) : c.name;
      v.querySelector('[data-s]').textContent = `${i + 1} of ${clips.length} · ${F.ago(c.mtime)} · ${c.mb ?? '?'} MB`;
      v.querySelector('[data-a=dl]').href = c.url || F.clipUrl(c.name);
    };
    const close = () => {
      vid.pause();
      vid.removeAttribute('src');
      vid.load();
      v.remove();
      history.state?.viewer && history.back();
    };
    v.addEventListener('click', e => {
      const a = e.target.closest('[data-a]')?.dataset.a;
      if (a === 'close') close(); else if (a === 'prev') show(i - 1); else if (a === 'next') show(i + 1);
    });
    let x0 = null;
    vid.addEventListener('touchstart', e => { x0 = e.touches[0].clientX; }, {passive: true});
    vid.addEventListener('touchend', e => {
      if (x0 == null) return;
      const dx = e.changedTouches[0].clientX - x0; x0 = null;
      if (Math.abs(dx) > 60) show(dx < 0 ? i + 1 : i - 1);
    });
    history.pushState({viewer: 1}, '');
    const onPop = () => {
      removeEventListener('popstate', onPop);
      if (v.isConnected) {
        vid.pause();
        vid.removeAttribute('src');
        vid.load();
        v.remove();
      }
    };
    addEventListener('popstate', onPop);
    show(i);
  };
})();
