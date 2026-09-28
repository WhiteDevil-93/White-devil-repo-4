/* Shotwriter on the relay: run the shared shorthand lexicon over what goes to OpenRouter and what comes back,
   so slang never reaches the prompt model or the final prompts. Loaded after /app/lexicon.js. */
(() => {
  const plain = window.plainWords;
  if (!plain) return;
  const base = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    const url = typeof input === 'string' ? input : input.url;
    if (!/openrouter\.ai\/api\/v1\/chat\/completions/.test(url) || !init || typeof init.body !== 'string') return base(input, init);
    let body;
    try { body = JSON.parse(init.body); } catch { return base(input, init); }
    body.messages = (body.messages || []).map(m => m.role === 'user' && typeof m.content === 'string' ? {...m, content: plain(m.content)} : m);
    const r = await base(input, {...init, body: JSON.stringify(body)});
    if (!r.ok) return r;
    const j = await r.json();
    for (const c of j.choices || []) if (c.message && typeof c.message.content === 'string') c.message.content = plain(c.message.content);
    return new Response(JSON.stringify(j), {status: r.status, statusText: r.statusText, headers: {'Content-Type': 'application/json'}});
  };
})();
