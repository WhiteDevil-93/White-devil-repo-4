/* Mobile ttyd / xterm.js patch: block unsolicited paste, enable one-finger scrollback.
   Installed by /app/term/ wrapper and by the Forge Hub Android WebView. */
(() => {
  const ForgeTerm = {
    install(win) {
      if (!win || win.__forgeTermPatch) return true;
      const doc = win.document;
      if (!doc) return false;
      win.__forgeTermPatch = 1;
      win.__forgeOkPaste = false;

      const style = doc.createElement('style');
      style.textContent = `
        html, body, #terminal-container, .xterm, .xterm-viewport {
          height: 100% !important;
        }
        .xterm-viewport {
          overflow-y: scroll !important;
          touch-action: pan-y !important;
          -webkit-overflow-scrolling: touch !important;
          overscroll-behavior: contain;
        }
        .xterm, .xterm-screen, .xterm-helpers, .xterm-helper-textarea, canvas {
          touch-action: pan-y !important;
        }
        /* When Hub /app/term wraps ttyd: kill the squashed duplicate header/footer. */
        html.forge-embed #chat-top,
        html.forge-embed #chat-composer { display: none !important; }
        html.forge-embed #chat-stage { padding: 0 !important; flex: 1; min-height: 0; }
        html.forge-embed #chat-thread {
          border: 0 !important; border-radius: 0 !important; box-shadow: none !important;
          flex: 1; min-height: 0;
        }
      `;
      (doc.head || doc.documentElement).appendChild(style);
      try {
        if (win.parent && win.parent !== win) doc.documentElement.classList.add('forge-embed');
      } catch (e) {
        doc.documentElement.classList.add('forge-embed');
      }

      const block = (e) => {
        if (win.__forgeOkPaste) return;
        e.preventDefault();
        e.stopImmediatePropagation();
      };
      doc.addEventListener('paste', block, true);
      doc.addEventListener('contextmenu', block, true);
      doc.addEventListener('auxclick', (e) => { if (e.button === 1) block(e); }, true);
      doc.addEventListener('mousedown', (e) => { if (e.button === 1 || e.button === 2) block(e); }, true);
      doc.addEventListener('beforeinput', (e) => {
        const t = e.inputType || '';
        if (!win.__forgeOkPaste && /Paste|insertFromDrop|insertReplacementText|insertFromYank/i.test(t)) block(e);
      }, true);

      const clip = win.navigator && win.navigator.clipboard;
      if (clip && typeof clip.readText === 'function') {
        const orig = clip.readText.bind(clip);
        clip.readText = function () {
          if (!win.__forgeOkPaste) return Promise.resolve('');
          return orig();
        };
      }

      const tuneArea = (ta) => {
        if (!ta || ta.__forgeTuned) return;
        ta.__forgeTuned = 1;
        ta.setAttribute('autocomplete', 'off');
        ta.setAttribute('autocorrect', 'off');
        ta.setAttribute('autocapitalize', 'none');
        ta.setAttribute('spellcheck', 'false');
        ta.setAttribute('data-gramm', 'false');
      };
      const scanAreas = () => doc.querySelectorAll('textarea').forEach(tuneArea);
      scanAreas();
      new MutationObserver(scanAreas).observe(doc.documentElement, {childList: true, subtree: true});

      const viewport = () => doc.querySelector('.xterm-viewport');
      const root = () => doc.querySelector('.xterm') || doc.body;
      let lastY = null, lastX = null, mode = null;
      const onStart = (e) => {
        if (e.touches.length !== 1) { mode = 'ignore'; return; }
        lastY = e.touches[0].clientY;
        lastX = e.touches[0].clientX;
        mode = null;
      };
      const onMove = (e) => {
        if (e.touches.length !== 1 || lastY == null) return;
        const y = e.touches[0].clientY, x = e.touches[0].clientX;
        const dy = y - lastY, dx = x - lastX;
        if (!mode) {
          if (Math.abs(dy) < 8 && Math.abs(dx) < 8) return;
          mode = Math.abs(dy) >= Math.abs(dx) ? 'scroll' : 'ignore';
        }
        if (mode !== 'scroll') return;
        const v = viewport();
        const term = win.term;
        if (v) v.scrollTop -= dy;
        else if (term && typeof term.scrollLines === 'function') {
          const h = (term._core && term._core._renderService && term._core._renderService.dimensions
            && term._core._renderService.dimensions.css && term._core._renderService.dimensions.css.cell
            && term._core._renderService.dimensions.css.cell.height) || 16;
          const n = Math.round(-dy / h);
          if (n) term.scrollLines(n);
        }
        lastY = y;
        lastX = x;
        e.preventDefault();
        e.stopPropagation();
      };
      const attach = () => {
        const el = root();
        if (!el || el.__forgeScroll) return;
        el.__forgeScroll = 1;
        el.addEventListener('touchstart', onStart, {passive: true, capture: true});
        el.addEventListener('touchmove', onMove, {passive: false, capture: true});
      };
      attach();
      new MutationObserver(attach).observe(doc.documentElement, {childList: true, subtree: true});

      win.ForgeTermPaste = async (text) => {
        const s = String(text || '');
        if (!s) return false;
        win.__forgeOkPaste = true;
        try {
          // Wait briefly for xterm to appear
          for (let i = 0; i < 25; i++) {
            if (win.term && typeof win.term.paste === 'function') break;
            await new Promise(r => setTimeout(r, 100));
          }
          if (win.term && typeof win.term.paste === 'function') {
            win.term.paste(s);
            return true;
          }
          const ta = doc.querySelector('.xterm-helper-textarea') || doc.querySelector('textarea');
          if (ta) {
            ta.focus();
            // Prefer insertText; fall back to InputEvent
            let ok = false;
            try { ok = doc.execCommand('insertText', false, s); } catch (e) {}
            if (!ok) {
              ta.value = s;
              ta.dispatchEvent(new InputEvent('input', {bubbles: true, data: s, inputType: 'insertText'}));
            }
            return true;
          }
          return false;
        } finally {
          setTimeout(() => { win.__forgeOkPaste = false; }, 400);
        }
      };

      win.ForgeTermScroll = (where) => {
        const v = viewport();
        const term = win.term;
        if (where === 'top') {
          if (v) v.scrollTop = 0;
          else if (term && term.scrollToTop) term.scrollToTop();
        } else if (where === 'bottom') {
          if (v) v.scrollTop = v.scrollHeight;
          else if (term && term.scrollToBottom) term.scrollToBottom();
        }
      };
      return true;
    }
  };
  window.ForgeTerm = ForgeTerm;
  if (document.currentScript && /laptop\/term/.test(location.pathname)) {
    ForgeTerm.install(window);
  }
})();
