// Relay copy of the prompt generator: point "Send to runner" at the relay's /runner proxy,
// and send Wan2.2 14B chains to /runner14 (wanbot on the relay, rendering on the Thunder ComfyUI).
(async () => {
  const url = document.getElementById('rUrl'), tok = document.getElementById('rToken');
  if (!url || !tok) return;
  try {
    const r = await fetch('/api/colab/runner-token', { credentials: 'same-origin' });
    if (!r.ok) return;
    const j = await r.json();
    url.value = location.origin + j.url;
    tok.value = j.token;
    localStorage.setItem('wan_rUrl', url.value);
    localStorage.setItem('wan_rToken', tok.value);
  } catch (e) { /* offline: keep whatever was saved */ }
})();

// ?target=colab or ?target=thunder: one prompt creator per runner (own models, own saved inputs).
const GEN_TARGET = new URLSearchParams(location.search).get('target');

(() => {
  const st = document.createElement('style');
  st.textContent = `
    :root{--bg:#0b0b0c;--panel:rgba(255,255,255,.035);--line:rgba(255,255,255,.08);--ink:#ededea;--dim:#9b9ba1;
      --tungsten:#f4f1ea;--tungsten-ink:#111;--cyan:#cdb88f;--bad:#d99393}
    html,body{background:radial-gradient(1100px 520px at 50% -18%,rgba(255,255,255,.055),transparent 62%),#0b0b0c fixed;
      font-family:"Inter",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;-webkit-font-smoothing:antialiased}
    header h1{font-weight:600;letter-spacing:-.035em}
    .panel{border-radius:18px;backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px)}
    input,select,textarea{background:rgba(0,0,0,.3);border-radius:12px;padding:11px 12px}
    input:focus,select:focus,textarea:focus,button:focus-visible{outline:1px solid rgba(205,184,143,.6);outline-offset:0}
    button{border-radius:999px;background:rgba(255,255,255,.06);padding:9px 14px}
    button.go{border-radius:999px;font-weight:600}
    .gen-switch{display:flex;gap:4px;padding:3px;border:1px solid var(--line);border-radius:999px;background:rgba(0,0,0,.3);max-width:360px;margin-top:14px}
    .gen-switch a{flex:1;text-align:center;padding:8px 10px;border-radius:999px;color:var(--dim);text-decoration:none;font-size:13px;font-weight:600}
    .gen-switch a.on{background:rgba(255,255,255,.1);color:#f4f1ea}
    html,body{max-width:100%;overflow-x:hidden}
    .wrap>*,#out,#out *{min-width:0}
    @media (max-width:900px){.wrap{grid-template-columns:minmax(0,1fr);padding:12px 14px 40px}header{padding:22px 14px 6px}}
    pre{overflow-wrap:anywhere;word-break:break-word;max-width:100%}
    pre.script{overflow-wrap:normal;word-break:normal}
    #out img,#out video,#out table{max-width:100%}
    .actions{display:flex;flex-wrap:wrap;gap:8px}
    .actions select{max-width:100%;flex:1 1 100%}
    .variant{background:rgba(255,255,255,.035)}
    .settings div,.tabs button,pre{background:rgba(255,255,255,.03)}
    .settings b{color:#cdb88f}`;
  document.head.appendChild(st);
})();

window.addEventListener('DOMContentLoaded', () => {
  const sel = document.getElementById('mtarget');
  if (GEN_TARGET === 'colab' || GEN_TARGET === 'thunder') {
    const is14 = GEN_TARGET === 'thunder';
    [...sel.options].forEach(o => { if (/A14B/.test(o.value) !== is14) o.remove(); });
    if (!sel.value) sel.selectedIndex = 0;
    sel.dispatchEvent(new Event('change', {bubbles: true}));
    const h = document.querySelector('header');
    h.querySelector('h1').textContent = is14 ? 'Thunder 14B prompts' : 'Colab 5B prompts';
    h.querySelector('p').textContent = is14
      ? 'Chains for Wan 2.2 14B on the Thunder L40 (1280×720, 16 fps, 5 s clips). "Send to runner" queues them on the L40.'
      : 'Chains for Wan 2.2 TI2V-5B Turbo on Colab G4 (1280×704, 16 fps). "Send to runner" queues them on Colab.';
    const sw = document.createElement('nav');
    sw.className = 'gen-switch';
    sw.innerHTML = `<a href="?target=colab" class="${is14 ? '' : 'on'}">Colab · 5B</a><a href="?target=thunder" class="${is14 ? 'on' : ''}">Thunder · 14B</a>`;
    h.appendChild(sw);
    document.title = h.querySelector('h1').textContent;
  }

  // Remember every form input between visits, separately per target (the page itself only keeps the runner and OpenRouter fields).
  const FORM_KEY = 'wan_form' + (GEN_TARGET ? '_' + GEN_TARGET : '');
  const SKIP = new Set(['key', 'model', 'rUrl', 'rToken', 'rModel', 'rSeed']);
  const fields = [...document.querySelectorAll('input[id], select[id], textarea[id]')]
    .filter(el => !SKIP.has(el.id) && el.type !== 'file' && el.type !== 'password');
  const saved = JSON.parse(localStorage.getItem(FORM_KEY) || localStorage.getItem('wan_form') || '{}');
  if (!localStorage.getItem(FORM_KEY)) delete saved.mtarget;
  const apply = () => fields.forEach(el => {
    if (!(el.id in saved)) return;
    if (el.type === 'checkbox' || el.type === 'radio') el.checked = saved[el.id]; else el.value = saved[el.id];
  });
  apply();
  fields.forEach(el => { if (el.id in saved) el.dispatchEvent(new Event('change', {bubbles: true})); });
  apply();
  if (!sel.value) { sel.selectedIndex = 0; sel.dispatchEvent(new Event('change', {bubbles: true})); }
  const store = () => {
    const o = {};
    fields.forEach(el => { o[el.id] = el.type === 'checkbox' || el.type === 'radio' ? el.checked : el.value; });
    localStorage.setItem(FORM_KEY, JSON.stringify(o));
  };
  document.addEventListener('input', store, true);
  document.addEventListener('change', store, true);

  // Downloads: the app's downloader can't take blob: URLs, so park the file on the relay and download it over https.
  // Chains: run the batch loop on the relay (/api/gen) so it keeps going with the screen off.
  const origFetch = window.fetch.bind(window);
  const api = async (path, body) => {
    const r = await origFetch(path, body === undefined ? {credentials: 'same-origin'} :
      {method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    const j = await r.json().catch(() => ({}));
    // FastAPI validation errors put a list of objects in `detail`; stringifying it as-is shows "[object Object]".
    if (!r.ok) {
      const err = new Error(
        typeof j.detail === 'string' ? j.detail
        : j.detail ? JSON.stringify(j.detail)
        : 'HTTP ' + r.status);
      err.status = r.status;  // the relay answered: distinguishes a rejection from an unreachable relay
      throw err;
    }
    return j;
  };

  const queue = [];
  let busy = false;
  window.download = (name, text) => {
    queue.push([name, text]);
    if (busy) return;
    busy = true;
    (async () => {
      while (queue.length) {
        const [n, t] = queue.shift();
        try {
          const {url} = await api('/api/gen/file', {name: n, text: t});
          const a = document.createElement('a');
          a.href = url; a.download = n; document.body.appendChild(a); a.click(); a.remove();
          await new Promise(r => setTimeout(r, 700));
        } catch (e) { alert('Download failed: ' + e.message); }
      }
      busy = false;
    })();
  };

  if (typeof runChain !== 'function') return;
  const status = () => document.getElementById('status');
  const KEYJOB = 'wan_genjob';
  async function follow(id){
    let shown = -1;
    for (;;) {
      let j;
      try { j = await api('/api/gen/jobs/' + id); }
      catch (e) {
        // A job the relay no longer has (404) never comes back — stop, don't poll forever.
        if (/No such generation job|HTTP 404/i.test(e.message || '')) {
          localStorage.removeItem(KEYJOB);
          status().className = 'status';
          status().textContent = 'That generation is no longer on the relay. Start a new one.';
          return;
        }
        // Any other answer FROM the relay (bad id, corrupt job file) is permanent too: a gateway
        // timeout is the only server status worth retrying. Retrying the rest says "unreachable" forever.
        if (e.status && e.status !== 502 && e.status !== 504) {
          localStorage.removeItem(KEYJOB);
          status().className = 'status err';
          status().textContent = 'Relay rejected the generation: ' + e.message;
          return;
        }
        status().textContent = 'Relay unreachable, retrying… (generation continues on the relay)';
        await new Promise(r => setTimeout(r, 5000));
        continue;
      }
      if (j.chain.clips.length !== shown) { shown = j.chain.clips.length; if (shown) renderChain(j.chain, j.meta); }
      if (j.status === 'done') {
        localStorage.removeItem(KEYJOB);
        pushHistory({kind: 'chain', idea: j.idea, data: j.chain, meta: j.meta});
        status().className = 'status';
        status().textContent = `Done — ${shown} clips` + (j.count > 1 ? ` in ${j.count} batches.` : '.') + (shown !== j.total ? ` (asked for ${j.total})` : '') + ' Generated on the relay.';
        return;
      }
      if (j.status === 'error') { localStorage.removeItem(KEYJOB); throw new Error(j.error || 'generation failed'); }
      status().className = 'status';
      status().textContent = j.count > 1
        ? `Generating batch ${Math.max(1, j.next)}/${j.count} on the relay (${shown} of ${j.total} clips so far). You can turn the screen off.`
        : 'Generating chain on the relay. You can turn the screen off.';
      await new Promise(r => setTimeout(r, 3000));
    }
  }
  window.runChain = async () => {
    const n = clipCount();
    const r = await api('/api/gen/chain', {
      idea: $('idea').value.trim(), system: SYSTEM_CHAIN(), shared: sharedLines(),
      beats: $('beats').value.split('\n').map(s => plainWords(s.trim())).filter(Boolean), link: $('link').value,
      total: n, chunk: CHUNK, model: $('model').value.trim(), temperature: +$('temp').value, key: $('key').value.trim() || null,
      meta: metaNow(),
      base: {link: $('link').value, frames: +$('duration').value, size: sizeStr('*'), firstI2V: $('mode').value === 'i2v',
             task: mspec().task, ckpt: mspec().ckpt, fps: mspec().fps}
    });
    localStorage.setItem(KEYJOB, r.id);
    const rb = document.getElementById('resume'); if (rb) rb.hidden = true;
    await follow(r.id);
  };
  const running = localStorage.getItem(KEYJOB);
  if (running) follow(running).catch(e => { status().className = 'status err'; status().textContent = e.message; });
});

(() => {
  const RUNNER = location.origin + '/runner/', RUNNER14 = location.origin + '/runner14/';
  const ids14 = new Set(JSON.parse(localStorage.getItem('wan_jobs14') || '[]'));
  const remember = id => { ids14.add(id); localStorage.setItem('wan_jobs14', JSON.stringify([...ids14].slice(-200))); };
  const target = () => { try { return typeof mspec === 'function' ? mspec().runner : null; } catch (e) { return null; } };
  const orig = window.fetch.bind(window);
  window.fetch = async (input, opts = {}) => {
    let u = typeof input === 'string' ? input : input.url;
    if (!u.startsWith(RUNNER)) return orig(input, opts);
    const path = u.slice(RUNNER.length);
    if (path === 'jobs' && (opts.method || 'GET').toUpperCase() === 'POST' && opts.body) {
      const body = JSON.parse(opts.body);
      body.runner = body.runner || {};
      const t = target();
      if (t && (!body.runner.model || /14b/i.test(t) !== /14b/i.test(body.runner.model))) body.runner.model = t;
      opts = Object.assign({}, opts, { body: JSON.stringify(body) });
      if (/14b/i.test(body.runner.model || '')) {
        const res = await orig(RUNNER14 + path, opts);
        const clone = res.clone();
        try { const j = await clone.json(); if (j && j.id) remember(j.id); } catch (e) {}
        return res;
      }
      return orig(u, opts);
    }
    const m = path.match(/^jobs\/([\w-]+)/);
    if (m && ids14.has(m[1])) return orig(RUNNER14 + path, opts);
    return orig(input, opts);
  };
})();

const plainWords = window.plainWords || (s => s);
if (typeof sharedLines === 'function') {
  const baseShared = sharedLines;
  window.sharedLines = () => baseShared().map(plainWords);
}
if (typeof buildSingleMsg === 'function') {
  const baseSingle = buildSingleMsg;
  window.buildSingleMsg = () => plainWords(baseSingle());
}
if (typeof buildChainMsg === 'function') {
  const baseChain = buildChainMsg;
  window.buildChainMsg = b => plainWords(baseChain(b));
}
if (typeof callModel === 'function') {
  const baseCall = callModel;
  window.callModel = async (...a) => plainWords(await baseCall(...a));
}
