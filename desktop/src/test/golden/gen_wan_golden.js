// Regenerates src/test/resources/wan/golden.json from the REAL generator page.
//
//   node desktop/src/test/golden/gen_wan_golden.js
//
// It loads hub/static/generator.html's inline script and hub/static/lexicon.js into a sandbox with a fake
// DOM, fills the form from each fixture below, and records what the page itself produces: the brief lines
// (as the relay page wraps them), the chain system prompt, the settings and brief objects, and the exported
// chain format. WanGoldenTest requires the Kotlin port (WanModel.kt) to reproduce every one of them, so the
// app cannot quietly drift away from the page it was ported from.
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repo = path.resolve(__dirname, '../../../..');
const html = fs.readFileSync(path.join(repo, 'hub/static/generator.html'), 'utf8');
const lexicon = fs.readFileSync(path.join(repo, 'hub/static/lexicon.js'), 'utf8');

// The page's own script is the largest inline <script> block.
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
const pageScript = scripts.sort((a, b) => b.length - a.length)[0];

const FIXTURES = [
  { name: 'landscape-continuous', form: { idea: 'Two friends on a sofa, bro goons then edging', image: 'Both men seated on a grey sofa, soft lamp light', cast: '2', duration: '81', clips: '4', link: 'continuous', beats: 'They sit down\nbig bro starts gooning\nlil bro joins\nThey slow down', camera: 'static camera, eye-level, no zoom, no pan', framing: 'medium shot', orient: 'landscape', motion: 'gentle, natural movement', forbid: 'nobody enters frame', style: 'photoreal, soft light', words: '80-120', mode: 'i2v', wanneg: true } },
  { name: 'portrait-cut-t2v', form: { idea: 'A man alone on a bed', image: '', cast: '1', duration: '49', clips: '3', link: 'cut', beats: '', camera: '', framing: 'close-up', orient: 'portrait', motion: 'dynamic, energetic movement', forbid: '', style: '', words: '60-90', mode: 't2v', wanneg: false } },
  { name: 'odd-duration-no-people', form: { idea: 'Empty room with a window', image: 'ignored in t2v', cast: '0', duration: '73', clips: '2', link: 'continuous', beats: '', camera: 'slow dolly in', framing: 'wide shot', orient: 'landscape', motion: 'very subtle, minimal movement', forbid: 'no people', style: 'moody', words: '120-170', mode: 'i2v', wanneg: true } },
  { name: 'long-121', form: { idea: 'Cum shots and hand jobs, jerking each other off', image: '', cast: '2', duration: '121', clips: '5', link: 'continuous', beats: 'one\ntwo', camera: 'handheld, subtle natural shake', framing: 'full shot', orient: 'landscape', motion: 'moderate, expressive movement', forbid: '', style: '', words: '80-120', mode: 'i2v', wanneg: true } },
];

function runFixture(fx) {
  const elements = {};
  const make = id => {
    if (elements[id]) return elements[id];
    const base = { value: '', checked: false, hidden: false, textContent: '', innerHTML: '', disabled: false, style: {}, dataset: {}, classList: { add() {}, remove() {}, toggle() {} } };
    const el = new Proxy(base, {
      get(t, k) { if (k in t) return t[k]; return (...a) => el; },
      set(t, k, v) { t[k] = v; return true; },
    });
    elements[id] = el;
    return el;
  };
  const sandbox = {
    console, setTimeout: () => 0, clearTimeout() {}, setInterval: () => 0, clearInterval() {},
    localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    fetch: async () => ({ ok: false, json: async () => ({}), text: async () => '' }),
    navigator: {}, location: { search: '', origin: 'http://localhost' }, crypto: { randomUUID: () => 'abcdefabcdef1234' },
    FileReader: function () {}, URL: { createObjectURL: () => '' }, Blob: function () {},
    document: {
      getElementById: make, querySelector: () => make('q'), querySelectorAll: () => [], createElement: () => make('c' + Math.random()),
      addEventListener() {}, body: make('body'), documentElement: make('html'),
    },
    window: null, Date, JSON, Math, Promise, String, Number, Array, Object, RegExp, Set, Map, parseInt, parseFloat, encodeURIComponent,
  };
  sandbox.window = sandbox;
  sandbox.addEventListener = () => {};
  const ctx = vm.createContext(sandbox);

  // fill the form BEFORE the page script runs its load-time code
  for (const [k, v] of Object.entries(fx.form)) {
    if (k === 'wanneg') make('wanneg').checked = v; else make(k).value = v;
  }
  make('mtarget').value = 'Wan2.2-I2V-A14B';
  make('model').value = 'test/model:free';
  make('temp').value = '0.7';

  vm.runInContext(lexicon, ctx);
  vm.runInContext(pageScript, ctx);

  // the relay page wraps sharedLines so every line goes through plainWords
  const probe = `(() => {
    const shared = sharedLines().map(window.plainWords);
    const meta = { id: 'abcdefabcdef', at: '2026-10-03T12:00:00.000Z', llm: $('model').value.trim(), settings: baseSettings(), brief: brief() };
    const chain = {
      link: $('link').value, frames: +$('duration').value, size: sizeStr('*'), firstI2V: $('mode').value === 'i2v', task: mspec().task, ckpt: mspec().ckpt, fps: mspec().fps,
      bible: { characters: 'two men', setting: 'a grey sofa', style: 'photoreal' },
      clips: [
        { title: 'First', start_state: 'sitting', prompt: 'Exactly two people. The older man sits.', end_state: 'settled', negative: 'extra people' },
        { title: 'Second', start_state: 'settled', prompt: 'Continuing from a still moment. He smiles slowly at the camera.', end_state: 'smiling', negative: '' },
        { title: 'Third', start_state: 'smiling', prompt: 'x', end_state: 'y' },
      ],
    };
    return JSON.stringify({
      system: SYSTEM_CHAIN(),
      shared,
      beats: $('beats').value.split('\\n').map(s => plainWords(s.trim())).filter(Boolean),
      base_settings: baseSettings(),
      brief: brief(),
      size: sizeStr('*'),
      clip_count: clipCount(),
      meta,
      chain,
      chain_spec: chainJSON(chain, meta),
      neg_a: negFor({ negative: 'extra people' }),
      neg_b: negFor({ negative: '' }),
    });
  })()`;
  return JSON.parse(vm.runInContext(probe, ctx));
}

const out = FIXTURES.map(fx => ({ name: fx.name, form: fx.form, expected: runFixture(fx) }));
const dest = path.join(repo, 'desktop/src/test/resources/wan/golden.json');
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify(out, null, 1), 'utf8');
console.log('wrote', dest, out.length, 'fixtures;', out.map(o => o.expected.shared.length + ' lines').join(', '));
