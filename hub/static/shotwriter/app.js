(() => {
  "use strict";
  const $ = (s) => document.querySelector(s);
  const API = "https://openrouter.ai/api/v1";
  const DEFAULT_LLM = "cognitivecomputations/dolphin-mistral-24b-venice-edition";

  // ---------- safe storage (sandboxed iframes can block localStorage) ----------
  const mem = {};
  const store = {
    get(k, d) { try { const v = localStorage.getItem("sw_" + k); return v == null ? d : JSON.parse(v); } catch { return k in mem ? mem[k] : d; } },
    set(k, v) { try { localStorage.setItem("sw_" + k, JSON.stringify(v)); } catch { mem[k] = v; } },
  };

  // ---------- direction options ----------
  const OPTS = {
    style: ["Auto", "Cinematic photoreal", "Documentary / handheld", "Commercial / product ad", "Music video", "Anime", "3D animation (Pixar-like)", "Claymation / stop-motion", "Film noir", "Cyberpunk", "Fantasy epic", "Horror", "Vintage 16mm film", "VHS / found footage", "UGC selfie / TikTok", "Drone aerial", "Macro / nature", "Watercolor / painterly", "Surreal / dreamlike"],
    camera: ["Auto", "Static / locked-off", "Slow push-in (dolly in)", "Pull-out (dolly out)", "Tracking / follow shot", "Orbit around subject", "Pan left", "Pan right", "Tilt up", "Tilt down", "Crane up / reveal", "Handheld", "FPV drone fly-through", "Zoom in", "Dolly zoom (vertigo)", "Whip pan", "Rack focus"],
    shot: ["Auto", "Extreme close-up", "Close-up", "Medium close-up", "Medium shot", "Full body", "Wide shot", "Extreme wide / establishing", "Low angle", "High angle", "Bird's-eye / top-down", "Over-the-shoulder", "POV", "Dutch angle"],
    lighting: ["Auto", "Golden hour", "Blue hour / dusk", "Soft overcast daylight", "Harsh midday sun", "Neon night", "Candlelight / firelight", "Moonlight", "Studio softbox", "Rim light / backlit", "Volumetric haze / god rays", "Low-key chiaroscuro", "High-key bright", "Practical lights only"],
    mood: ["Auto", "Warm & nostalgic", "Cool & melancholic", "Teal & orange blockbuster", "Desaturated gritty", "Vibrant saturated", "Pastel dreamy", "Monochrome B&W", "Eerie & tense", "Epic & awe-inspiring", "Romantic & intimate", "Playful & upbeat"],
    pacing: ["Auto", "Slow motion", "Slow & calm", "Natural real-time", "Energetic", "Fast & chaotic", "Time-lapse"],
    aspect: ["Auto", "16:9", "9:16", "1:1", "4:5", "21:9"],
    duration: ["Auto", "~3 s", "~5 s", "~8 s", "~10 s", "~15 s"],
  };
  for (const [id, list] of Object.entries(OPTS)) {
    $("#" + id).innerHTML = list.map((o) => `<option>${o}</option>`).join("");
  }

  const IDEAS = [
    "An old fisherman mends a net on a wooden pier as a storm rolls in over the sea",
    "A red fox trots through fresh snow in a silent birch forest at dawn",
    "A street dancer in a yellow jacket pops and locks under a flickering subway light",
    "A tiny robot waters a single glowing flower on the surface of the moon",
    "A barista pours latte art in slow motion in a sunlit Johannesburg café",
    "A samurai draws his sword as cherry blossoms swirl around him",
    "A luxury perfume bottle rotating on black glass with liquid gold splashing around it",
    "A girl on a bicycle rides through lavender fields, hair and dress blowing in the wind",
    "A giant whale swims slowly through clouds above a sleeping city",
    "A chef flambés a pan in a busy restaurant kitchen, flames lighting his face",
  ];

  // ---------- state ----------
  const S = {
    modelId: store.get("model", "wan22-14b-t2v"),
    task: "create",
    mode: null,
    last: null,
  };

  // ---------- model select ----------
  const sel = $("#modelSel");
  sel.innerHTML = MODEL_GROUPS.map((g) =>
    `<optgroup label="${g.label}">` +
    MODELS.filter((m) => m.group === g.id).map((m) => `<option value="${m.id}">${m.name}</option>`).join("") +
    `</optgroup>`).join("");
  if (!MODELS.find((m) => m.id === S.modelId)) S.modelId = "wan22-14b-t2v";
  sel.value = S.modelId;
  $("#customName").value = store.get("customName", "");

  const model = () => MODELS.find((m) => m.id === S.modelId);

  function renderModel() {
    const m = model();
    $("#customName").classList.toggle("hidden", m.id !== "custom");
    const chips = [];
    chips.push(`<span class="chip acc">${m.type === "video" ? "Video" : "Image"}</span>`);
    m.modes.forEach((x) => chips.push(`<span class="chip">${MODE_LABELS[x]}</span>`));
    const w = S.mode === "i2v" && m.i2vWords ? m.i2vWords : m.words;
    chips.push(`<span class="chip" title="${esc(m.lengthNote || "")}">${w[0]}–${w[1]} words</span>`);
    if (m.maxChars) chips.push(`<span class="chip">≤${m.maxChars} chars</span>`);
    if (m.structured) chips.push(`<span class="chip">Structured</span>`);
    if (m.negInline) chips.push(`<span class="chip">Inline exclusions</span>`);
    if (m.negative) chips.push(`<span class="chip">Negative prompt</span>`);
    if (m.audio) chips.push(`<span class="chip">Audio</span>`);
    if (m.multishot) chips.push(`<span class="chip">Multi-shot</span>`);
    $("#modelChips").innerHTML = chips.join("") + (m.lengthNote ? `<div class="len-note">${esc(m.lengthNote)}</div>` : "");

    // mode segmented
    if (!m.modes.includes(S.mode)) S.mode = m.modes[0];
    $("#modeSeg").innerHTML = m.modes.map((x) => `<button role="radio" data-v="${x}" aria-checked="${x === S.mode}">${MODE_LABELS[x]}</button>`).join("");

    // toggles availability
    setTog("#negTog", "#negOn", m.negative);
    setTog("#audioTog", "#audioOn", m.audio);
    setTog("#shotTog", "#multiOn", !!m.multishot);
    // video-only direction controls
    ["camera", "pacing", "duration"].forEach((id) => { $("#" + id).disabled = m.type !== "video"; });
    renderInputs();
    if (typeof renderSettingsCard === "function") renderSettingsCard();
  }
  function setTog(wrap, input, enabled) {
    $(wrap).classList.toggle("disabled", !enabled);
    if (!enabled) $(input).checked = false;
    else if (input === "#negOn" && !$(input).dataset.touched) $(input).checked = true;
  }
  $("#negOn").addEventListener("change", (e) => (e.target.dataset.touched = 1));

  function renderInputs() {
    const needsImg = ["i2v", "ref", "edit"].includes(S.mode);
    $("#imgField").classList.toggle("hidden", !needsImg);
    $("#imgLabel").textContent = S.mode === "ref" ? "Describe the reference images" : S.mode === "edit" ? "Describe the source image" : "Describe the start image";
    const L = { create: "Your idea", enhance: "Prompt to enhance", adapt: "Prompt to adapt (from any model)" };
    $("#ideaLabel").textContent = L[S.task];
    const H = {
      create: "Turn a rough idea into model-tuned prompts.",
      enhance: "Keep your concept, upgrade detail, structure and film language for the target model.",
      adapt: "Rewrite a prompt made for another model into the target model's syntax and length.",
    };
    $("#taskHint").textContent = H[S.task];
    $("#idea").placeholder = S.task === "create"
      ? (S.mode === "i2v" ? "What should happen? e.g. she turns toward the camera and smiles as wind catches her hair" : "A lone astronaut walks through a neon-lit Tokyo alley in the rain…")
      : "Paste your existing prompt here…";
  }

  sel.addEventListener("change", () => { S.modelId = sel.value; store.set("model", S.modelId); renderModel(); });
  $("#customName").addEventListener("input", (e) => store.set("customName", e.target.value));
  function segHandler(segSel, key, after) {
    $(segSel).addEventListener("click", (e) => {
      const b = e.target.closest("button[data-v]"); if (!b) return;
      S[key] = b.dataset.v;
      $(segSel).querySelectorAll("button").forEach((x) => x.setAttribute("aria-checked", x === b));
      after && after();
    });
  }
  segHandler("#taskSeg", "task", renderInputs);
  segHandler("#modeSeg", "mode", () => { renderInputs(); renderModel(); });
  $("#temp").addEventListener("input", (e) => ($("#tempVal").textContent = e.target.value));
  $("#sparkBtn").addEventListener("click", () => {
    $("#idea").value = IDEAS[Math.floor(Math.random() * IDEAS.length)];
    if (S.task !== "create") $("#taskSeg button[data-v=create]").click();
  });

  // ---------- theme ----------
  const setTheme = (t) => { document.documentElement.dataset.theme = t; store.set("theme", t);
    document.querySelector('meta[name="theme-color"]').content = t === "dark" ? "#12100d" : "#f5f1ea"; };
  setTheme(store.get("theme", matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark"));
  $("#themeBtn").addEventListener("click", () => setTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark"));

  // ---------- settings ----------
  const cfg = () => ({
    key: store.get("key", ""),
    llm: store.get("llm", DEFAULT_LLM),
    fallback: store.get("fallback", true),
  });
  function renderKeyBadge() {
    const has = !!cfg().key;
    $("#keyDot").classList.toggle("ok", has);
    $("#keyLabel").textContent = has ? "OpenRouter connected" : "Add API key";
    const empty = document.querySelector(".empty");
    if (empty) {
      empty.querySelector(".keycta")?.remove();
      if (!has) empty.insertAdjacentHTML("beforeend", `<p class="keycta" style="margin-top:16px"><button class="primary" onclick="document.getElementById('settingsBtn').click()">Add OpenRouter key</button></p>`);
    }
  }
  renderKeyBadge();
  const dlg = $("#settingsDlg");
  $("#settingsBtn").addEventListener("click", openSettings);
  function openSettings() {
    const c = cfg();
    $("#apiKey").value = c.key;
    const known = [...$("#llmSel").options].some((o) => o.value === c.llm);
    $("#llmSel").value = known ? c.llm : "__custom";
    $("#llmCustom").value = known ? "" : c.llm;
    $("#llmCustom").classList.toggle("hidden", known);
    $("#autoFallback").checked = c.fallback;
    $("#keyStatus").textContent = ""; $("#keyStatus").className = "hint";
    dlg.showModal();
  }
  $("#llmSel").addEventListener("change", (e) => $("#llmCustom").classList.toggle("hidden", e.target.value !== "__custom"));
  $("#showKey").addEventListener("click", () => {
    const i = $("#apiKey"); i.type = i.type === "password" ? "text" : "password";
    $("#showKey").textContent = i.type === "password" ? "Show" : "Hide";
  });
  $("#saveKey").addEventListener("click", () => {
    store.set("key", $("#apiKey").value.trim());
    const v = $("#llmSel").value;
    store.set("llm", v === "__custom" ? ($("#llmCustom").value.trim() || DEFAULT_LLM) : v);
    store.set("fallback", $("#autoFallback").checked);
    renderKeyBadge(); dlg.close(); toast("Settings saved");
  });
  $("#testKey").addEventListener("click", async () => {
    const k = $("#apiKey").value.trim(); const st = $("#keyStatus");
    if (!k) { st.textContent = "Paste a key first."; st.className = "hint err"; return; }
    st.textContent = "Checking…"; st.className = "hint";
    try {
      const r = await fetch(API + "/key", { headers: { Authorization: "Bearer " + k } });
      const j = await r.json();
      if (!r.ok) throw new Error(j?.error?.message || r.status);
      const d = j.data || {};
      const lim = d.limit == null ? "no limit" : `$${(d.limit - (d.usage || 0)).toFixed(2)} left of $${d.limit}`;
      st.textContent = `Key valid · ${d.label || "key"} · used $${(d.usage || 0).toFixed(4)} · ${lim}${d.is_free_tier ? " · free tier" : ""}`;
      st.className = "hint ok";
    } catch (e) { st.textContent = "Key check failed: " + e.message; st.className = "hint err"; }
  });

  // ---------- prompt engine ----------
  function buildMessages() {
    const m = model();
    const lang = $("#lang").value;
    const n = +$("#variations").value;
    const detail = $("#detail").value;
    const [lo, hi] = S.mode === "i2v" && m.i2vWords ? m.i2vWords : m.words;
    const range = detail === "concise" ? [lo, Math.round((lo + hi) / 2)] : detail === "rich" ? [Math.round((lo + hi) / 2), hi] : [lo, hi];
    const modelName = m.id === "custom" ? ($("#customName").value.trim() || "an unspecified video model") : m.name;
    const wantNeg = $("#negOn").checked && m.negative;
    const wantAudio = $("#audioOn").checked && m.audio;
    const wantMulti = $("#multiOn").checked && m.multishot;

    const dir = [];
    for (const id of ["style", "camera", "shot", "lighting", "mood", "pacing", "aspect", "duration"]) {
      const el = $("#" + id); if (el.disabled) continue;
      if (el.value !== "Auto") dir.push(`${el.previousElementSibling.textContent}: ${el.value}`);
    }

    const sys = `You are Shotwriter, an elite prompt engineer and cinematographer who writes prompts for AI ${m.type} generation models. You know each model's syntax, strengths and failure modes, and you write prompts that produce stunning, coherent results on the first try.

TARGET MODEL: ${modelName}
MODEL GUIDE:
${m.guide}

RULES
- Write every prompt specifically for ${modelName}, following the model guide above exactly.
- Each prompt must be ${range[0]}–${range[1]} words (count carefully).
- Write prompts in ${lang === "zh" ? "Simplified Chinese" : "English"}.
${m.structured ? "- Use line breaks and labelled blocks exactly as the model guide shows (inside the JSON string use \\n for line breaks). No markdown bold, headings or code fences, no preamble like 'Here is'." : "- Prompts are one plain-text paragraph: no markdown, no bullet points, no headings, no quotation marks around the whole prompt, no preamble like 'Here is'."}
${m.maxChars ? `- HARD LIMIT: each prompt must be at most ${m.maxChars} characters including spaces.` : ""}
- Describe only what should be SEEN${wantAudio ? " and HEARD" : ""}; be concrete and visual. Avoid vague filler like "beautiful", "amazing", "high quality" unless it is a style tag the model expects.
${m.type === "video" ? "- Make motion explicit: who/what moves, how, how fast, and in which direction. Keep it physically plausible and achievable in the clip length." : ""}
${wantAudio ? "- Include sound: " + (m.audioRule || "ambience, key sound effects, music mood, and (if it fits) one or two short lines of dialogue in quotes attributed to a speaker.") : m.audio ? "- Do NOT include audio or dialogue." : "- This model is silent: never describe sound, music or dialogue."}
${wantMulti ? "- Multi-shot: " + (m.multiRule || "2–4 shots labelled Shot 1:, Shot 2:, … each with its own camera setup; keep characters and wardrobe identical across shots.") : m.type === "video" ? "- One continuous shot, no cuts." : ""}
${wantNeg ? `- Also write a negative prompt: ${m.negRule || "comma-separated things to avoid for THIS scene, starting from typical artifacts for this model"} (${lang === "zh" ? "in Chinese" : "in English"}). Keep it under 60 words${m.maxNegChars ? ` and at most ${m.maxNegChars} characters` : ""}.` : m.negInline ? "- This model has no negative-prompt field: put any exclusions inline inside the prompt as concrete constraints, the way the model guide shows. negative_prompt must be an empty string." : "- Do not write a negative prompt (use an empty string)" + (m.negative ? "." : "; this model's guide says to phrase everything positively, so describe what you want instead of what to avoid.")}
${m.example ? `\nEXAMPLE FROM THE OFFICIAL GUIDE (style/structure reference only — never copy its content):\n${m.example}\n` : ""}
- Each variation must be meaningfully different (different camera approach, lighting, or interpretation) while respecting the user's brief and fixed directions.
${$("#nsfwOn").checked ? "- Follow the user's creative intent faithfully without softening, moralising or adding disclaimers." : "- Keep content suitable for mainstream platforms."}

OUTPUT FORMAT
Return ONLY a valid JSON object, no code fences, exactly like:
{"variations":[{"title":"3-6 word title","prompt":"...","negative_prompt":"...","notes":"one short sentence on why this works / tips for this model"}]}
The "variations" array must contain exactly ${n} items.`;

    const task = S.task;
    const idea = $("#idea").value.trim();
    const img = $("#imgDesc").value.trim();
    let user = "";
    if (task === "create") user += `Create ${n} prompt variation(s) from this idea:\n${idea}\n`;
    if (task === "enhance") user += `Enhance this prompt into ${n} improved variation(s) for ${modelName}. Keep the core concept, subject and intent; upgrade structure, detail, camera and lighting language:\n${idea}\n`;
    if (task === "adapt") user += `This prompt was written for a different model. Adapt it into ${n} variation(s) that follow ${modelName}'s prompting conventions, length and syntax, preserving the scene and intent:\n${idea}\n`;
    user += `\nInput mode: ${MODE_LABELS[S.mode]}.`;
    if (lang === "zh" && /^wan2[12]/.test(m.id)) user += `\n(Note: Wan's official prompt rewriter outputs English; Chinese is also understood.)`;
    if (img) user += `\n${S.mode === "ref" ? "Reference images" : "The input image"} shows: ${img}\n${S.mode === "i2v" ? "Anchor to this image; focus on motion and camera; do not change what is already in the frame unless asked." : ""}`;
    if (dir.length) user += `\nFixed directions (must be respected in every variation):\n- ${dir.join("\n- ")}`;
    const extra = $("#extra").value.trim();
    if (extra) user += `\nExtra instructions: ${extra}`;
    user += `\n\nReturn the JSON now.`;
    return [{ role: "system", content: sys }, { role: "user", content: user }];
  }

  async function callLLM(messages, llm) {
    const c = cfg();
    const r = await fetch(API + "/chat/completions", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + c.key,
        "Content-Type": "application/json",
        "HTTP-Referer": location.origin && location.origin !== "null" ? location.origin : "https://shotwriter.app",
        "X-Title": "Shotwriter Video Prompt Generator",
      },
      body: JSON.stringify({
        model: llm, messages, temperature: +$("#temp").value, max_tokens: 3000,
        response_format: { type: "json_object" },
      }),
    });
    let j = null; try { j = await r.json(); } catch {}
    if (!r.ok || j?.error) {
      const msg = j?.error?.message || `HTTP ${r.status}`;
      const e = new Error(msg); e.status = r.status || j?.error?.code; throw e;
    }
    return j.choices?.[0]?.message?.content || "";
  }

  function clip(str, max) {
    if (!max || str.length <= max) return [str, false];
    let cut = str.slice(0, max);
    const b = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf(".\n"), cut.lastIndexOf("。"));
    if (b > max * 0.6) cut = cut.slice(0, b + 1);
    return [cut.trim(), true];
  }
  function clampTake(t) {
    const m = model();
    const [p, c1] = clip(t.prompt, m.maxChars);
    const [n, c2] = clip(t.negative, m.maxNegChars);
    return { ...t, prompt: p, negative: m.negative ? n : "", trimmed: c1 || c2 };
  }
  function parseOut(txt) {
    let t = txt.trim().replace(/^```(?:json)?/i, "").replace(/```$/, "").trim();
    const a = t.indexOf("{"), b = t.lastIndexOf("}");
    if (a >= 0 && b > a) t = t.slice(a, b + 1);
    try {
      const o = JSON.parse(t);
      const arr = Array.isArray(o) ? o : o.variations || o.prompts || [];
      if (arr.length) return arr.map((v, i) => ({
        title: v.title || `Take ${i + 1}`,
        prompt: String(v.prompt || v.positive_prompt || "").trim(),
        negative: String(v.negative_prompt || v.negative || "").trim(),
        notes: String(v.notes || "").trim(),
      })).filter((v) => v.prompt).map(clampTake);
    } catch {}
    return [{ title: "Take 1", prompt: txt.trim(), negative: "", notes: "The model returned plain text instead of JSON; shown as-is." }];
  }

  // ---------- generate ----------
  const genBtn = $("#genBtn");
  genBtn.addEventListener("click", generate);
  document.addEventListener("keydown", (e) => { if ((e.ctrlKey || e.metaKey) && e.key === "Enter") generate(); });

  let busy = false;
  async function generate() {
    if (busy) return;
    if (!cfg().key) { openSettings(); toast("Add your OpenRouter API key first"); return; }
    if (!$("#idea").value.trim()) { $("#idea").focus(); toast(S.task === "create" ? "Describe your idea first" : "Paste a prompt first"); return; }
    busy = true;
    genBtn.disabled = true;
    genBtn.innerHTML = `<span class="spinner"></span><span>Writing takes…</span>`;
    const n = +$("#variations").value;
    // No takes on screen until this run renders. Left enabled, a failed run kept the previous run's
    // button live and "Copy all" copied an empty string while still saying "All prompts copied".
    $("#copyAllBtn").disabled = true;
    $("#results").innerHTML = Array.from({ length: Math.min(n, 3) }, () => `<div class="skel"></div>`).join("");
    renderSettingsCard();
    if (matchMedia("(max-width: 880px)").matches) $("#resultsPanel").scrollIntoView({ behavior: "smooth", block: "start" });

    let out, usedLLM;
    try {
      // buildMessages() used to run outside this try: anything it threw left busy=true and the
      // button stuck on "Writing takes…" for the rest of the session.
      const messages = buildMessages();
      const c = cfg();
      const llm = c.llm;
      usedLLM = llm;
      try { out = await callLLM(messages, llm); }
      catch (e) {
        const isDolphin = llm.startsWith(DEFAULT_LLM);
        if (!c.fallback || !isDolphin || [401].includes(+e.status)) throw e;
        usedLLM = llm.endsWith(":free") ? DEFAULT_LLM : DEFAULT_LLM + ":free";
        toast("Retrying with " + (usedLLM.endsWith(":free") ? "free" : "paid") + " variant…");
        out = await callLLM(messages, usedLLM);
      }
      const takes = parseOut(out);
      const m = model();
      S.last = { takes, model: m.id, modelName: m.id === "custom" ? $("#customName").value.trim() || "Custom" : m.name, task: S.task, mode: S.mode, idea: $("#idea").value.trim(), ts: Date.now(), llm: usedLLM };
      renderTakes(S.last);
      saveHistory(S.last);
    } catch (e) {
      const st = +e.status;
      const tip = st === 401 ? "Your API key was rejected. Open settings and check it." :
        st === 402 ? "Your OpenRouter account is out of credits. Add credits or switch to the :free variant in settings." :
        st === 429 ? "Rate limited (common on :free). Wait a moment or switch to the paid variant." :
        "Check your connection and try again.";
      $("#results").innerHTML = `<div class="error-card"><strong>Couldn't generate prompts</strong>${esc(e.message)}<br><span class="muted">${tip}</span></div>`;
    } finally {
      busy = false; genBtn.disabled = false;
      genBtn.innerHTML = `<span class="btn-label">Generate prompts</span><span class="kbd">Ctrl ↵</span>`;
    }
  }

  function renderSettingsCard() {
    const m = model();
    const entries = Object.entries(m.settings || {});
    const card = $("#settingsCard");
    const src = m.sources || [];
    if (!entries.length && !src.length) { card.classList.add("hidden"); return; }
    card.classList.remove("hidden");
    card.innerHTML = `<div class="ttl">Suggested settings · ${esc(m.short)}</div>` +
      entries.map(([k, v]) => `<div class="kv"><span class="k">${esc(k)}</span><span class="v">${esc(v)}</span></div>`).join("") +
      (src.length ? `<div class="sources"><div class="k">Guide sources</div>${src.map((x) => `<a href="${esc(x.url)}" target="_blank" rel="noopener">${esc(x.label)}</a>`).join("")}</div>` : "");
  }

  const wc = (s) => (/[\u4e00-\u9fff]/.test(s) ? s.replace(/\s/g, "").length + " chars" : s.split(/\s+/).filter(Boolean).length + " words");

  function renderTakes(run) {
    const m = MODELS.find((x) => x.id === run.model) || model();
    const lang = $("#lang").value;
    const defNeg = m.defaultNegative ? m.defaultNegative[lang] || m.defaultNegative.en : "";
    $("#results").innerHTML = run.takes.map((t, i) => {
      const neg = t.negative || ($("#negOn").checked && m.negative ? defNeg : "");
      return `<article class="take" data-i="${i}">
        <div class="take-head">
          <div><div class="take-num">TAKE ${String(i + 1).padStart(2, "0")} · ${esc(run.modelName)}</div><h3>${esc(t.title)}</h3></div>
          <div class="take-actions">
            <button class="ghost sm" data-act="copy">Copy</button>
            <button class="ghost sm" data-act="enhance" title="Load into Enhance">Refine</button>
          </div>
        </div>
        <div class="prompt-box" contenteditable="true" spellcheck="false" data-f="prompt">${esc(t.prompt)}</div>
        <div class="meta" style="margin-top:6px">${wc(t.prompt)}${m.maxChars ? ` · ${t.prompt.length}/${m.maxChars} chars` : ""}${t.trimmed ? ` <span class="chip warn">Trimmed to model limit</span>` : ""}</div>
        ${neg ? `<div class="sub-label"><span>Negative prompt${t.negative ? "" : " (model default)"}</span><button class="ghost sm" data-act="copyneg">Copy</button></div>
        <div class="prompt-box neg-box" contenteditable="true" spellcheck="false" data-f="neg">${esc(neg)}</div>` : ""}
        ${t.notes ? `<div class="notes">${esc(t.notes)}</div>` : ""}
      </article>`;
    }).join("");
    $("#copyAllBtn").disabled = false;
  }

  $("#results").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-act]"); if (!b) return;
    const card = b.closest(".take");
    const p = card.querySelector('[data-f=prompt]').innerText.trim();
    const n = card.querySelector('[data-f=neg]')?.innerText.trim() || "";
    if (b.dataset.act === "copy") copy(p, "Prompt copied");
    if (b.dataset.act === "copyneg") copy(n, "Negative prompt copied");
    if (b.dataset.act === "enhance") {
      $("#idea").value = p; $("#taskSeg button[data-v=enhance]").click();
      $("#idea").scrollIntoView({ behavior: "smooth", block: "center" }); $("#idea").focus();
      toast("Loaded into Enhance — tweak directions and generate");
    }
  });
  $("#copyAllBtn").addEventListener("click", () => {
    const txt = [...document.querySelectorAll(".take")].map((c, i) => {
      const t = c.querySelector("h3").textContent;
      const p = c.querySelector('[data-f=prompt]').innerText.trim();
      const n = c.querySelector('[data-f=neg]')?.innerText.trim();
      return `# ${i + 1}. ${t}\n${p}${n ? `\n\nNegative: ${n}` : ""}`;
    }).join("\n\n---\n\n");
    copy(txt, "All prompts copied");
  });

  async function copy(t, msg) {
    try { await navigator.clipboard.writeText(t); }
    catch { const ta = document.createElement("textarea"); ta.value = t; document.body.appendChild(ta); ta.select(); try { document.execCommand("copy"); } catch {} ta.remove(); }
    toast(msg);
  }

  // ---------- history ----------
  function saveHistory(run) {
    const h = store.get("history", []); h.unshift(run); store.set("history", h.slice(0, 40));
  }
  function renderHistory() {
    const h = store.get("history", []);
    $("#histList").innerHTML = h.length ? h.map((r, i) => `<div class="hist-item" data-i="${i}">
      <div class="t">${esc(r.modelName)} · ${r.task} · ${r.takes.length} take${r.takes.length > 1 ? "s" : ""}</div>
      <div class="d">${esc(r.idea)}</div>
      <div class="meta">${new Date(r.ts).toLocaleString()}</div></div>`).join("")
      : `<p class="hint">Generated prompts will appear here.</p>`;
  }
  const drawer = $("#historyDrawer"), scrim = $("#scrim");
  const openDrawer = (o) => { drawer.classList.toggle("open", o); scrim.classList.toggle("show", o); drawer.setAttribute("aria-hidden", !o); if (o) renderHistory(); };
  $("#historyBtn").addEventListener("click", () => openDrawer(true));
  $("#closeHist").addEventListener("click", () => openDrawer(false));
  scrim.addEventListener("click", () => openDrawer(false));
  $("#clearHist").addEventListener("click", () => { store.set("history", []); renderHistory(); });
  $("#histList").addEventListener("click", (e) => {
    const it = e.target.closest(".hist-item"); if (!it) return;
    const run = store.get("history", [])[+it.dataset.i];
    if (!run) return;
    if (MODELS.find((m) => m.id === run.model)) { S.modelId = run.model; sel.value = run.model; renderModel(); }
    $("#idea").value = run.idea;
    renderSettingsCard(); renderTakes(run); openDrawer(false);
  });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") openDrawer(false); });

  // ---------- utils ----------
  function esc(s) { return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
  let tt; function toast(m) { const t = $("#toast"); t.textContent = m; t.classList.add("show"); clearTimeout(tt); tt = setTimeout(() => t.classList.remove("show"), 2200); }

  renderModel();

  // ---------- PWA ----------
  window.addEventListener("load", () => {
    try { if ("serviceWorker" in navigator) navigator.serviceWorker.register("./sw.js").catch(() => {}); } catch {}
  });
})();
