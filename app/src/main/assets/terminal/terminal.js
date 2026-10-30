/* Only this trusted asset talks to the native bridge. xterm receives Uint8Array. */
'use strict';
const options = {cursorBlink:true, fontSize:13, fontFamily:'"Roboto Mono", "Droid Sans Mono", monospace',
  lineHeight:1.08, letterSpacing:0, scrollback:3000, scrollOnUserInput:false,
  theme:{background:'#000000',foreground:'#e6e6e6'}, allowProposedApi:false,
  linkHandler:{activate(){ /* Remote hyperlinks never navigate. */ }}};
try { const saved = Number(localStorage.getItem('terminalFontSize')); if (saved >= 8 && saved <= 28) options.fontSize = saved; } catch (ignored) {}
let cursor = 0;
let touchCleanup = () => {}, suspendTouch = () => {};
let term, online = false, line = '', ctrl = false, alt = false;
// Page lifetime only: never saved to Android preferences or browser storage.
const commands = [];
let historyIndex = -1, historyDraft = '', trackLine = true;
function remember(command) {
  if (!command.trim()) return;
  const existing = commands.indexOf(command);
  if (existing >= 0) commands.splice(existing,1);
  commands.unshift(command); commands.length = Math.min(commands.length,100);
}
function focusInput() {
  term.focus();
  TerminalNative.showKeyboard();
}
function recall(key) {
  if (ctrl || alt || !trackLine || (online && (term.buffer.active.type === 'alternate' ||
      (!line && historyIndex < 0)))) return false;
  if (!commands.length) return false;
  if (historyIndex < 0) historyDraft = line;
  historyIndex = key === '↑' ? (historyIndex+1)%commands.length : historyIndex-1;
  if (historyIndex < -1) historyIndex = -1;
  const replacement = historyIndex < 0 ? historyDraft : commands[historyIndex];
  if (online) TerminalNative.input('\x7f'.repeat(Array.from(line).length)+replacement);
  else term.write('\x1b[C'.repeat(Array.from(line).length-cursor)+'\b \b'.repeat(Array.from(line).length)+replacement);
  line = replacement; cursor = Array.from(line).length;
  return true;
}
function createTerminal() {
  touchCleanup();
  if (term) term.dispose();
  document.getElementById('screen').replaceChildren();
  term = new Terminal(options);
  term.open(document.getElementById('screen'));
  // Addons may fail on older WebViews. Dispose partial activation to restore DOM.
  let canvas;
  try {
    canvas = new CanvasAddon.CanvasAddon();
    term.loadAddon(canvas);
  } catch (failure) {
    if (canvas) {
      try { canvas.dispose(); } catch (ignored) { /* Rebuild below even if cleanup fails. */ }
      try { term.dispose(); } catch (ignored) { /* Discard any partial renderer. */ }
      document.getElementById('screen').replaceChildren();
      term = new Terminal(options);
      term.open(document.getElementById('screen'));
    }
  }
  if (term.textarea) term.textarea.setAttribute('enterkeyhint','enter');
  term.onData(input);
  installTouchSelection();
}
createTerminal();
term.write('支持 -p 端口\r\n/help 命令帮助\r\n/saved 已存凭证\r\n' +
  '/forget user@host 删除凭证\r\n输入 ssh user@host 连接\r\n-------------------------\r\n');
window.terminalBytes = (encoded, id) => {
  const raw = atob(encoded);
  const bytes = Uint8Array.from(raw, c => c.charCodeAt(0));
  term.write(bytes, () => TerminalNative.ack(id));
};
window.terminalStatus = text => term.write(text);
function terminalLines() {
  const history = [], buffer = term.buffer.active;
  for (let row = 0; row < buffer.length; row++)
    history.push(buffer.getLine(row).translateToString(true).replace(/[\x00-\x1f\x7f-\x9f]/g,''));
  while (history.length && history[history.length-1] === '') history.pop();
  return history;
}
window.terminalCopy = () => {
  const selected = window.terminalSelectedText && window.terminalSelectedText();
  return selected !== undefined ? selected : term.getSelection() || terminalLines().join('\n');
};
window.terminalMode = value => {
  // reset() in xterm 5.5 does not clear its incremental UTF8 decoder. A fresh
  // instance isolates unfinished UTF8/OSC/CSI and modes between SSH sessions.
  const history = value ? [] : terminalLines();
  online = value; line = ''; cursor = 0; ctrl = alt = false; historyIndex = -1; trackLine = true;
  document.querySelectorAll('.active').forEach(b => b.classList.remove('active'));
  createTerminal(); fit();
  if (history.length) term.write(history.join('\r\n') + '\r\n');
};
function input(data) {
  if (ctrl && data.length === 1) data = String.fromCharCode(data.toUpperCase().charCodeAt(0) & 31);
  if (alt) data = '\x1b' + data;
  ctrl = alt = false;
  document.querySelectorAll('.active').forEach(b => b.classList.remove('active'));
  if (online) {
    // Track only append/backspace editing. Remote cursor/readline edits make the
    // shadow unknown; pass arrows through until Enter/Ctrl-C establishes a new line.
    for (const c of data) {
      if (c === '\r' || c === '\n') {
        if (trackLine) remember(line);
        line = ''; trackLine = true; historyIndex = -1;
      } else if (c === '\x03' || c === '\x15') {
        line = ''; trackLine = true; historyIndex = -1;
      } else if (c === '\x7f' || c === '\b') line = Array.from(line).slice(0,-1).join('');
      else if (c < ' ' || c === '\x1b') { trackLine = false; historyIndex = -1; }
      else if (trackLine) { if (line.length < 4096) line += c; else trackLine = false; }
    }
    TerminalNative.input(data); return;
  }
  if (data.startsWith('\x1b')) return;
  for (const c of data) {
    if (c === '\r' || c === '\n') {
      term.write('\r\n'); const command = line; remember(command); line = ''; cursor = 0; historyIndex = -1; TerminalNative.input(command + '\r'); return;
    } else if (c === '\x03') { line = ''; cursor = 0; historyIndex = -1; term.write('^C\r\n');
    } else if (c === '\x7f' || c === '\b') {
      if (cursor > 0) { const chars = Array.from(line); chars.splice(--cursor,1); line = chars.join(''); const tail = chars.slice(cursor).join(''); term.write('\b'+tail+' '+ '\b'.repeat(Array.from(tail).length+1)); }
    } else if (c >= ' ' && c !== '\x7f' && line.length < 4096) { const chars = Array.from(line); chars.splice(cursor++,0,c); line = chars.join(''); const tail = chars.slice(cursor).join(''); term.write(c+tail+'\b'.repeat(Array.from(tail).length)); }
  }
}
const sequences = {ESC:'\x1b',TAB:'\t',HOME:'\x1b[H',END:'\x1b[F',
  '↑':'\x1b[A','↓':'\x1b[B','←':'\x1b[D','→':'\x1b[C',PGUP:'\x1b[5~',PGDN:'\x1b[6~'};
for (const row of [['ESC','/','-','HOME','↑','END','PGUP'],['TAB','CTRL','ALT','←','↓','→','PGDN']]) {
  const div = document.createElement('div');
  for (const key of row) {
    const button = document.createElement('button'); button.textContent = key;
    button.addEventListener('pointerdown', e => e.preventDefault());
    button.onclick = () => {
      if (!online && !ctrl && !alt && (key === '←' || key === '→')) { const next = Math.max(0,Math.min(Array.from(line).length,cursor+(key === '←' ? -1 : 1))); if (next !== cursor) term.write(sequences[key]); cursor = next; }
      else if ((key === '↑' || key === '↓') && recall(key)) { /* Local input replacement. */ }
      else if (key === 'CTRL') { ctrl = !ctrl; button.classList.toggle('active',ctrl); }
      else if (key === 'ALT') { alt = !alt; button.classList.toggle('active',alt); }
      else {
        let sequence = sequences[key] || key;
        if (online && term.modes.applicationCursorKeysMode && ['↑','↓','←','→','HOME','END'].includes(key))
          sequence = sequence.replace('\x1b[','\x1bO');
        input(sequence);
      }
      focusInput();
    };
    div.appendChild(button);
  }
  document.getElementById('keys').appendChild(div);
}
let fitPending = false, zooming = false, lastSize = '';
function scheduleFit() {
  if (fitPending) return;
  fitPending = true;
  requestAnimationFrame(() => { fitPending = false; fit(); });
}
function fit() {
  // Keep the existing grid/buffer visible while font metrics change. Reflow and
  // the remote PTY update happen only after the gesture, including IME resizes.
  if (zooming) return;
  const screen = document.getElementById('screen');
  const grid = screen.querySelector('.xterm-screen');
  const measured = grid && grid.getBoundingClientRect();
  if (!measured || !measured.width || !measured.height) return;
  const cw = measured.width / term.cols, ch = measured.height / term.rows;
  const cols = Math.min(1000,Math.max(2,Math.floor((screen.clientWidth-24)/cw)));
  const rows = Math.min(500,Math.max(1,Math.floor((screen.clientHeight-8)/ch)));
  const size = [cols,rows,screen.clientWidth,screen.clientHeight].join(',');
  if (cols !== term.cols || rows !== term.rows) term.resize(cols,rows);
  if (size !== lastSize) {
    lastSize = size;
    TerminalNative.resize(cols,rows,screen.clientWidth,screen.clientHeight);
  }
}
let keyboardVisible = false;
window.terminalKeyboard = visible => {
  if (keyboardVisible === visible) return;
  keyboardVisible = visible;
  document.getElementById('keys').hidden = !visible;
  scheduleFit();
};
// Activity reuse retains the parser, history and zoom; stale touch selection ends.
window.terminalBackground = () => {
  suspendTouch();
  term.blur();
  window.terminalKeyboard(false);
};
window.terminalResume = () => {
  scheduleFit();
  term.refresh(0,term.rows-1);
};
// Local scrolling has one frame owner; remote mouse modes retain the xterm path.
// Touch focus is deferred until a short tap; selection and pinch never focus.
function installTouchSelection() {
  const screen = document.getElementById('screen');
  const overlay = document.getElementById('selection-highlight');
  let selectedText = '', dismissing = false, suppressClick = false;
  const startHandle = document.getElementById('selection-start');
  const endHandle = document.getElementById('selection-end');
  let pinching = false, pinchDistance = 0, pinchFont = 13, pendingFont;
  let scrollPixels = 0, scrollFrame = false, zoomFrame = false;
  const mouseMode = () => term.modes.mouseTrackingMode !== 'none' &&
    term.modes.mouseTrackingMode !== undefined;
  const consume = e => { e.preventDefault(); e.stopImmediatePropagation(); };
  const distance = touches => Math.hypot(touches[0].clientX-touches[1].clientX,
    touches[0].clientY-touches[1].clientY);
  function zoom(e) {
    consume(e);
    if (e.touches.length < 2) return;
    pendingFont = Math.max(8,Math.min(28,pinchFont*distance(e.touches)/pinchDistance));
    if (zoomFrame) return;
    zoomFrame = true;
    requestAnimationFrame(() => {
      zoomFrame = false;
      if (alive) applyZoom();
    });
  }
  function applyZoom() {
    if (pendingFont === undefined) return;
    const grid = screen.querySelector('.xterm');
    if (grid) { grid.style.transformOrigin = 'top left'; grid.style.transform = 'scaleX(.9) scale('+(pendingFont/pinchFont)+')'; }
  }
  function endZoom() {
    if (!pinching) return;
    const grid = screen.querySelector('.xterm');
    const size = pendingFont === undefined ? pinchFont : Math.round(pendingFont*10)/10;
    pendingFont = undefined;
    if (grid) grid.style.transform = '';
    options.fontSize = size;
    if (term.options.fontSize !== size) term.options.fontSize = size;
    try { localStorage.setItem('terminalFontSize',String(size)); } catch (ignored) {}
    pinching = zooming = false;
    scheduleFit();
  }
  let alive = true;
  let timer, origin, selecting = false, moved = false, anchor, extent, drag;
  const listeners = [];
  const listen = (type, fn, options) => {
    screen.addEventListener(type,fn,options);
    listeners.push(() => screen.removeEventListener(type,fn,options));
  };
  touchCleanup = () => { const grid = screen.querySelector('.xterm'); if (grid) grid.style.transform = ''; zooming = false; alive = false; clearTimeout(timer); listeners.forEach(remove => remove());
    overlay.replaceChildren(); startHandle.hidden = endHandle.hidden = true; };
  const cancelTimer = () => { clearTimeout(timer); timer = undefined; };
  suspendTouch = () => {
    cancelTimer(); scrollPixels = 0; endZoom();
    origin = undefined; dismissing = pinching = moved = false;
    if (selecting) clear();
  };
  const point = (x,y) => {
    const rect = screen.querySelector('.xterm-screen').getBoundingClientRect();
    return {col:Math.max(0,Math.min(term.cols-1,Math.floor((x-rect.left)*term.cols/rect.width))),
      row:Math.max(0,Math.min(term.buffer.active.length-1,term.buffer.active.viewportY +
        Math.floor((y-rect.top)*term.rows/rect.height)))};
  };
  const index = p => p.row * term.cols + p.col;
  function update() {
    if (!anchor || !extent) return;
    const lo = index(anchor) <= index(extent) ? anchor : extent;
    const hi = lo === anchor ? extent : anchor;
    term.select(lo.col,lo.row,index(hi)-index(lo)+1);
    overlay.replaceChildren();
    const pieces = [];
    for (let row = lo.row; row <= hi.row; row++) {
      const bufferLine = term.buffer.active.getLine(row);
      if (!bufferLine) continue;
      let from = row === lo.row ? lo.col : 0;
      let to = row === hi.row ? hi.col + 1 : term.cols;
      // Snap both edges outward around a wide glyph's continuation cell.
      if (bufferLine.getCell(from)?.getWidth() === 0) from--;
      if (to < term.cols && bufferLine.getCell(to)?.getWidth() === 0) to++;
      let contentEnd = term.cols;
      while (contentEnd && !bufferLine.getCell(contentEnd-1).getChars() &&
          bufferLine.getCell(contentEnd-1).getWidth() !== 0) contentEnd--;
      to = Math.min(to,contentEnd);
      if (row > lo.row && !bufferLine.isWrapped) pieces.push('\n');
      pieces.push(bufferLine.translateToString(false,from,Math.max(from,to)));
      let visualEnd = to;
      while (visualEnd > from) {
        let cellStart = visualEnd-1;
        if (bufferLine.getCell(cellStart).getWidth() === 0) cellStart--;
        if (cellStart < from || bufferLine.getCell(cellStart).getChars().trim()) break;
        visualEnd = cellStart;
      }
      const visibleRow = row-term.buffer.active.viewportY;
      if (visualEnd <= from || visibleRow < 0 || visibleRow >= term.rows) continue;
      const rect = screen.querySelector('.xterm-screen').getBoundingClientRect();
      const band = document.createElement('div');
      band.style.left = (rect.left+from*rect.width/term.cols)+'px';
      band.style.top = (rect.top+visibleRow*rect.height/term.rows)+'px';
      band.style.width = ((visualEnd-from)*rect.width/term.cols)+'px';
      band.style.height = (rect.height/term.rows)+'px';
      overlay.appendChild(band);
    }
    selectedText = pieces.join('');
    const rect = screen.querySelector('.xterm-screen').getBoundingClientRect();
    for (const [handle,p] of [[startHandle,anchor],[endHandle,extent]]) {
      const row = p.row-term.buffer.active.viewportY;
      handle.hidden = row < 0 || row >= term.rows;
      handle.style.left = (rect.left+(p.col+0.5)*rect.width/term.cols)+'px';
      handle.style.top = (rect.top+(row+1)*rect.height/term.rows)+'px';
    }
  }
  function clear() {
    suppressClick = true;
    selecting = false; selectedText = ''; drag = anchor = extent = undefined;
    overlay.replaceChildren(); startHandle.hidden = endHandle.hidden = true;
    term.clearSelection();
  }
  listen('touchstart', e => {
    cancelTimer();
    if (e.touches.length >= 2) {
      if (selecting) clear();
      scrollPixels = 0;
      pinching = zooming = true; dismissing = false; moved = true; suppressClick = true; origin = undefined;
      pinchDistance = Math.max(1,distance(e.touches)); pinchFont = term.options.fontSize;
      consume(e); return;
    }
    if (pinching) { consume(e); return; }
    if (e.touches.length !== 1) return;
    suppressClick = false;
    const t = e.touches[0]; origin = {x:t.clientX,y:t.clientY}; moved = false;
    if (selecting) {
      dismissing = true; e.preventDefault(); e.stopImmediatePropagation(); return;
    }
    scrollPixels = 0;
    if (!mouseMode()) consume(e);
    timer = setTimeout(() => {
      selecting = true; anchor = extent = point(origin.x,origin.y);
      term.blur(); update();
    }, 500);
  }, {capture:true,passive:false});
  listen('touchmove', e => {
    if (pinching) { zoom(e); return; }
    if (e.touches.length !== 1 || !origin) { cancelTimer(); return; }
    if (dismissing) { e.preventDefault(); e.stopImmediatePropagation(); return; }
    const t = e.touches[0];
    if (Math.hypot(t.clientX-origin.x,t.clientY-origin.y)>16) {
      cancelTimer();
      if (!moved && !selecting && keyboardVisible) { TerminalNative.hideKeyboard(); term.blur(); }
      moved = true;
    }
    if (!selecting) {
      if (moved && !mouseMode()) {
        scrollPixels += origin.y-t.clientY; origin = {x:t.clientX,y:t.clientY};
        if (!scrollFrame) {
          scrollFrame = true;
          requestAnimationFrame(() => {
            scrollFrame = false;
            if (!alive || pinching) return;
            const rect = screen.querySelector('.xterm-screen').getBoundingClientRect();
            const height = rect.height/term.rows;
            const lines = Math.trunc(scrollPixels/height);
            scrollPixels -= lines*height;
            if (lines) term.scrollLines(lines);
          });
        }
      }
      if (!mouseMode()) consume(e);
      return;
    }
    e.preventDefault(); e.stopImmediatePropagation();
    const rect = screen.querySelector('.xterm-screen').getBoundingClientRect();
    // Bounded incremental history scrolling while dragging beyond the viewport.
    if (t.clientY < rect.top || t.clientY > rect.bottom)
      term.scrollLines(t.clientY < rect.top ? -1 : 1);
    extent = point(t.clientX,t.clientY); update();
  }, {capture:true,passive:false});
  listen('touchend', e => {
    cancelTimer();
    if (pinching) { consume(e); if (!e.touches.length) { endZoom(); origin = undefined; moved = false; } return; }
    if (!selecting && !dismissing && !moved && !suppressClick && origin && !mouseMode()) focusInput();
    if (selecting || dismissing) {
      e.preventDefault(); e.stopImmediatePropagation(); suppressClick = true;
      if (dismissing) clear(); else copySelection();
    }
    if (!mouseMode()) consume(e);
    dismissing = false;
    origin = undefined;
  }, {capture:true,passive:false});
  listen('touchcancel', () => { cancelTimer(); scrollPixels = 0; suppressClick = true; endZoom();
    origin = undefined; dismissing = false; pinching = false;
    if (selecting) clear();
  }, {passive:true});
  for (const type of ['pointerdown','pointerup','mousedown','mouseup']) listen(type, e => {
    if (selecting || suppressClick || pinching || e.isPrimary === false || (!mouseMode() && (e.pointerType === 'touch' || origin))) { e.preventDefault(); e.stopImmediatePropagation(); }
  }, true);
  listen('contextmenu', e => { e.preventDefault(); });
  listen('click', e => {
    if (selecting || moved || suppressClick || !mouseMode()) { e.preventDefault(); e.stopImmediatePropagation(); suppressClick = false; }
  }, true);
  for (const [handle,side] of [[startHandle,'start'],[endHandle,'end']]) {
    handle.onpointerdown = e => {
      e.preventDefault(); drag = side; handle.setPointerCapture(e.pointerId);
    };
    handle.onpointermove = e => {
      if (!drag) return;
      e.preventDefault();
      const p = point(e.clientX,e.clientY);
      if (drag === 'start') anchor = p; else extent = p;
      update();
    };
    handle.onpointerup = () => { if (drag) copySelection(); drag = undefined; };
    handle.onpointercancel = () => { drag = undefined; };
  }
  function copySelection() { if (selectedText) TerminalNative.copy(selectedText); }
  window.terminalSelectedText = () => selecting ? selectedText : undefined;
  term.onScroll(() => { if (selecting) update(); });
  // Writes and reflow can invalidate coordinates or selected content: dismiss safely.
  function syncSelection() {
    if (!selecting) return;
    clear();
  }
  term.onWriteParsed(syncSelection);
  term.onResize(syncSelection);
}
TerminalNative.ready();
new ResizeObserver(scheduleFit).observe(document.getElementById('screen'));
scheduleFit();
