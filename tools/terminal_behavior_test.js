'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {Terminal} = require('../app/src/main/assets/terminal/vendor/xterm.js');
const write = (t, data) => new Promise(resolve => t.write(data, resolve));
const text = t => t.buffer.active.getLine(0).translateToString(true);
(async () => {
  const t = new Terminal({cols:40,rows:5,allowProposedApi:true});
  // Exercise the vendored parser, not a substitute TextDecoder or regex.
  const bytes = Buffer.from('中😀');
  for (const byte of bytes) await write(t, Uint8Array.of(byte));
  assert.equal(text(t),'中😀');
  assert.equal(t.buffer.active.getLine(0).getCell(0).getWidth(),2,'CJK occupies two grid cells');
  await write(t,'\r\x1b[2'); await write(t,'K\x1b[31mred\x1b[0m');
  assert.equal(text(t),'red');
  assert.equal(t.buffer.active.getLine(0).getCell(0).getFgColor(),1);
  await write(t,'\x1b[?1049hTUI'); assert.equal(text(t).trim(),'TUI');
  await write(t,'\x1b[?1049l'); assert.equal(text(t),'red');
  await write(t,'\rX'); assert.equal(text(t),'Xed');
  t.resize(90,30); assert.equal(t.cols,90); assert.equal(t.rows,30);
  await write(t,'\r\n'+Array.from({length:300},(_,i)=>'row '+i+' 中文').join('\r\n'));
  const buffer = t.buffer.active;
  // No DOM viewport in Node: drive the actual xterm buffer scroll service.
  t._core._bufferService.scrollLines(-100); const viewport = buffer.viewportY;
  await write(t,'\r\nstream continues');
  assert.equal(buffer.viewportY,viewport,'streaming preserves historical viewport');
  assert.equal(buffer.getLine(buffer.length-1).getCell(0).getWidth(),1);
  t.dispose();

  // Run the actual page script with a minimal host DOM; its terminal parser remains real.
  const elements = new Map();
  const zoomGrid = {style:{}}; const storage = new Map();
  function element() { return {listeners:{},children:[],style:{},classList:{toggle(){},remove(){}},
    removeEventListener(type,fn){this.listeners[type]=(this.listeners[type]||[]).filter(f=>f!==fn);},
    appendChild(child){this.children.push(child);},replaceChildren(){this.children=[];},addEventListener(type,fn){(this.listeners[type] ||= []).push(fn);},remove(){},
    getBoundingClientRect(){return {width:80,height:16};},querySelector(selector){if(selector === '.xterm') return zoomGrid; return {getBoundingClientRect(){return {left:0,top:0,bottom:400,width:(terminal ? terminal.cols*terminal.options.fontSize/13 : 80)*8,height:(terminal ? terminal.rows*terminal.options.fontSize/13 : 24)*16};}};},
    clientWidth:800,clientHeight:400}; }
  for (const id of ['screen','keys','selection-highlight','selection-start','selection-end']) elements.set(id,element());
  let terminal, focuses=0, refreshes=0, gridResizes=0, disposals=0, canvasMode='ok', canvasLoads=0, canvasDisposals=0;
  class CanvasDouble {
    activate(){canvasLoads++; if(canvasMode !== 'ok') throw new Error('no canvas context');}
    dispose(){canvasDisposals++; if(canvasMode === 'dispose-fail') throw new Error('partial dispose');}
  }
  class TestTerminal extends Terminal {
    constructor(options){super(options); terminal=this;}
    resize(...size){gridResizes++; super.resize(...size);}
    dispose(){disposals++; super.dispose();}
    open(){} focus(){focuses++;} blur(){} refresh(){refreshes++;}
    loadAddon(addon){addon.activate(this);}
    scrollLines(n){this._core._bufferService.scrollLines(n);}
    // WebView selection renderer is a double; text comes from the real buffer.
    select(col,row,length){this.selection={col,row,length};}
    hasSelection(){return !!this.selection;}
    clearSelection(){this.selection=undefined;}
    getSelection(){
      if (!this.selection) return '';
      let {col,row,length}=this.selection; const lines=[];
      while(length>0){const count=Math.min(length,this.cols-col);
        lines.push(this.buffer.active.getLine(row).translateToString(false,col,col+count));
        length-=count; row++; col=0;}
      return lines.join('\n');
    }
  }
  const inputs=[],sizes=[],acks=[],copies=[]; let hides=0, shows=0, queuedFrames=false;
  const frames=[];
  const flushFrames=()=>{while(frames.length) frames.shift()();};
  const context = vm.createContext({localStorage:{getItem:k=>storage.get(k) ?? null,setItem:(k,v)=>storage.set(k,v)},Terminal:TestTerminal,CanvasAddon:{CanvasAddon:CanvasDouble},Uint8Array,atob,
    document:{getElementById:id=>elements.get(id),createElement:element,querySelectorAll:()=>[]},
    TerminalNative:{showKeyboard:()=>shows++,hideKeyboard:()=>hides++,copy:s=>copies.push(s),input:s=>inputs.push(s),resize:(...s)=>sizes.push(s),ack:id=>acks.push(id),ready(){}},
    ResizeObserver:class {observe(){}},requestAnimationFrame:fn=>{if(queuedFrames) frames.push(fn); else fn();},clearTimeout,setTimeout});
  context.window=context;
  vm.runInContext(fs.readFileSync('app/src/main/assets/terminal/terminal.js','utf8'),context);
  context.fit();
  await write(terminal,'');
  assert.equal(context.terminalCopy(), '支持 -p 端口\n/help 命令帮助\n/saved 已存凭证\n/forget user@host 删除凭证\n输入 ssh user@host 连接\n-------------------------');
  assert.equal(terminal.buffer.active.cursorX,0);
  assert.equal(terminal.buffer.active.cursorY,6,'cursor immediately follows separator');
  assert.equal(canvasLoads,1,'canvas activated after terminal open');
  const fire = s => terminal._core.coreService.triggerDataEvent(s,true);
  fire('ssh user@host'); assert.equal(inputs.length,0);
  fire('\r'); assert.deepEqual(inputs,['ssh user@host\r']);
  context.terminalStatus('[连接中]\r\n'); await write(terminal,'');
  assert.ok(context.terminalCopy().endsWith('-------------------------\nssh user@host\n[连接中]'),
    'local command echo and connection status stay on separate lines');
  context.terminalMode(true);
  fire('中文'); fire('\r'); assert.deepEqual(inputs.slice(-2),['中文','\r']);
  const rows = elements.get('keys').children;
  assert.deepEqual(rows.map(r=>r.children.map(b=>b.textContent)),
    [['ESC','/','-','HOME','↑','END','PGUP'],['TAB','CTRL','ALT','←','↓','→','PGDN']]);
  const click = label => rows.flatMap(r=>r.children).find(b=>b.textContent===label).onclick();
  for (const [label,seq] of Object.entries({ESC:'\x1b','/':'/','-':'-',HOME:'\x1b[H',
    END:'\x1b[F','↑':'\x1b[A','↓':'\x1b[B','←':'\x1b[D','→':'\x1b[C',TAB:'\t',PGUP:'\x1b[5~',PGDN:'\x1b[6~'})) {
    context.terminalMode(true); click(label); assert.equal(inputs.at(-1),seq);
  }
  fire('\x03');
  await write(terminal,'\x1b[?1h'); click('↑'); assert.equal(inputs.at(-1),'\x1bOA');
  await write(terminal,'\x1b[?1l');
  click('CTRL'); fire('c'); assert.equal(inputs.at(-1),'\x03');
  fire('c'); assert.equal(inputs.at(-1),'c');
  click('ALT'); fire('x'); assert.equal(inputs.at(-1),'\x1bx');
  assert.ok(sizes[0][0]>2 && sizes[0][1]>1);
  context.terminalKeyboard(true); assert.equal(elements.get('keys').hidden,false);
  context.terminalKeyboard(false); assert.equal(elements.get('keys').hidden,true);
  const sizeCount = sizes.length; context.fit(); context.fit();
  assert.equal(sizes.length,sizeCount,'unchanged viewport does not resize PTY');
  context.terminalBytes(Buffer.from('\x1b[2J\x1b[H<script>evil()</script>').toString('base64'),71);
  await new Promise(resolve=>setTimeout(resolve,30));
  assert.equal(text(terminal),'<script>evil()</script>'); assert.deepEqual(acks,[71]);
  context.terminalBytes(Buffer.from('中').subarray(0,1).toString('base64'),72);
  context.terminalBytes(Buffer.from('中').subarray(1).toString('base64'),73);
  await new Promise(resolve=>setTimeout(resolve,30));
  assert.ok(context.terminalCopy().includes('<script>evil()</script>'));
  assert.ok(text(terminal).endsWith('中')); assert.deepEqual(acks,[71,72,73]);
  await write(terminal,Uint8Array.of(0xe4));
  context.terminalMode(false); context.terminalMode(true);
  await write(terminal,Uint8Array.of(0xb8,0xad));
  assert.ok(!text(terminal).includes('中'), 'unfinished UTF8 cannot cross sessions');
  await write(terminal,'\x1b]0;unfinished');
  context.terminalMode(false);
  await write(terminal,'local prompt');
  assert.ok(text(terminal).includes('local prompt'), 'unfinished OSC cannot consume local input');
  // Long press selects real xterm buffer cells, then an endpoint can be adjusted.
  await write(terminal,'\r\nselect 中文 history');
  const screen = elements.get('screen');
  const emit = (name,x,y) => {
    const event = {touches:[{clientX:x,clientY:y}],preventDefault(){this.prevented=true;},
      stopImmediatePropagation(){this.stopped=true;}};
    for (const listener of screen.listeners[name] || []) listener(event);
    return event;
  };
  context.terminalKeyboard(true);
  emit('touchstart',8,16); const scroll = emit('touchmove',8,48);
  assert.equal(scroll.prevented,true,'local scroll bypasses competing xterm viewport handler');
  assert.equal(hides,1,'scroll dismisses IME'); emit('touchend',8,48);
  context.terminalKeyboard(false);
  const selectionFocus = focuses;
  emit('touchstart',0,0); await new Promise(resolve=>setTimeout(resolve,520));
  const selectionMove = emit('touchmove',80,32);
  assert.equal(selectionMove.prevented,true);
  assert.ok(terminal.hasSelection(),'long press uses xterm selection');
  const selected = terminal.getSelection();
  emit('touchend',80,32);
  assert.equal(copies.at(-1),context.terminalSelectedText());
  assert.equal(elements.get('selection-highlight').children.length,2,'only nonempty rows are highlighted');
  const handle = elements.get('selection-end'); handle.setPointerCapture=()=>{};
  handle.onpointerdown({preventDefault(){},pointerId:1});
  handle.onpointermove({preventDefault(){},clientX:160,clientY:32}); handle.onpointerup();
  assert.notEqual(terminal.getSelection(),selected,'touch endpoint adjusts selection');
  assert.equal(copies.at(-1),context.terminalSelectedText(),'handle release copies updated text');
  const dismissStart = emit('touchstart',500,100);
  assert.equal(dismissStart.stopped,true,'dismiss touch never reaches xterm');
  emit('touchend',500,100);
  assert.equal(terminal.hasSelection(),false);
  assert.equal(emit('click',500,100).stopped,true,'synthetic click cannot focus or send mouse input');
  // Real buffer: indentation, internal spaces, empty rows, wide glyph tails and padding.
  await write(terminal,'\x1b[2J\x1b[H  A  中\r\n\r\n B C');
  emit('touchstart',0,0); await new Promise(resolve=>setTimeout(resolve,520));
  emit('touchmove',700,32); emit('touchend',700,32);
  assert.equal(copies.at(-1),'  A  中\n\n B C');
  const bands = elements.get('selection-highlight').children;
  assert.equal(bands.length,2,'blank row has no filled highlight');
  assert.equal(bands[0].style.width,'56px','CJK includes both cells; no row-tail padding');
  assert.equal(bands[1].style.width,'32px','last row ends at actual text');
  emit('touchstart',700,32); emit('touchend',700,32); emit('click',700,32);
  // A tap on the continuation half selects the whole wide character.
  emit('touchstart',48,0); await new Promise(resolve=>setTimeout(resolve,520));
  emit('touchend',48,0);
  assert.equal(copies.at(-1),'中');
  assert.equal(elements.get('selection-highlight').children[0].style.width,'16px');
  emit('touchstart',0,0); emit('touchend',0,0); emit('click',0,0);
  await write(terminal,'\x1b[2J\x1b[H A  \r\nZ');
  emit('touchstart',0,0); await new Promise(resolve=>setTimeout(resolve,520));
  emit('touchmove',0,16); emit('touchend',0,16);
  assert.equal(copies.at(-1),' A  \nZ','actual written spaces are preserved');
  assert.equal(elements.get('selection-highlight').children[0].style.width,'16px','trailing spaces are not highlighted');
  await write(terminal,'!');
  assert.equal(terminal.hasSelection(),false,'buffer mutation safely dismisses stale selection');
  await write(terminal,'\x1b[2J\x1b[H'+'x'.repeat(terminal.cols-1)+'中Y');
  emit('touchstart',0,0); await new Promise(resolve=>setTimeout(resolve,520));
  emit('touchmove',16,16); emit('touchend',16,16);
  assert.equal(copies.at(-1),'x'.repeat(terminal.cols-1)+'中Y','soft wraps do not invent newlines');
  emit('touchstart',0,0); emit('touchend',0,0); emit('click',0,0);
  await write(terminal,'\x1b[2J\x1b[H  A  中\r\n B C');
  emit('touchstart',700,16); await new Promise(resolve=>setTimeout(resolve,520));
  emit('touchmove',0,0); emit('touchend',0,0);
  assert.equal(copies.at(-1),'  A  中\n B C','reverse drag preserves logical text order');
  emit('touchstart',0,0); emit('touchend',0,0); emit('click',0,0);
  await write(terminal,'\x1b[2J\x1b[HX　');
  emit('touchstart',0,0); await new Promise(resolve=>setTimeout(resolve,520));
  emit('touchmove',700,0); emit('touchend',700,0);
  assert.equal(copies.at(-1),'X　','written wide spaces survive copying');
  assert.equal(elements.get('selection-highlight').children[0].style.width,'8px','wide trailing whitespace is not highlighted');
  emit('touchstart',0,0); emit('touchend',0,0); emit('click',0,0);
  const beforeBlank=copies.length;
  emit('touchstart',700,32); await new Promise(resolve=>setTimeout(resolve,520));
  emit('touchend',700,32);
  assert.equal(copies.length,beforeBlank,'padding alone never overwrites clipboard');
  assert.equal(context.terminalCopy(),'','blank local selection cannot fall back to padded native selection');
  emit('touchstart',0,0); emit('touchend',0,0); emit('click',0,0);
  assert.equal(focuses,selectionFocus,'selection, handles, copy and dismissal never focus');
  const copiedBeforeCancel=copies.length;
  emit('touchstart',0,0); await new Promise(resolve=>setTimeout(resolve,520));
  emit('touchcancel',0,0);
  assert.equal(copies.length,copiedBeforeCancel,'cancelled gesture does not copy');
  assert.equal(terminal.hasSelection(),false);
  const dispatch = (type,touches) => {
    const e={touches,preventDefault(){this.prevented=true;},stopImmediatePropagation(){this.stopped=true;}};
    for (const fn of screen.listeners[type] || []) fn(e);
    return e;
  };
  const fingers = d => [{clientX:0,clientY:0},{clientX:d,clientY:0}];
  const zoomFocus=focuses, zoomHides=hides, zoomInputs=inputs.length;
  queuedFrames=true;
  const pinchSizes=sizes.length, pinchGrid=gridResizes, pinchTerm=terminal;
  const pinchLoads=canvasLoads, pinchDisposals=disposals, pinchRefresh=refreshes;
  const pinchText=context.terminalCopy();
  dispatch('touchstart',fingers(100));
  assert.equal(dispatch('touchmove',fingers(150)).stopped,true);
  dispatch('touchmove',fingers(140)); dispatch('touchmove',fingers(150));
  assert.equal(frames.length,1,'pinch burst schedules one frame'); flushFrames();
  assert.equal(terminal.options.fontSize,13,'pinch leaves renderer untouched'); assert.equal(zoomGrid.style.transform,'scaleX(.9) scale(1.5)');
  assert.equal(sizes.length,pinchSizes,'pinch never resizes PTY');
  assert.equal(gridResizes,pinchGrid,'pinch never reflows/clears grid');
  assert.equal(context.terminalCopy(),pinchText,'pinch retains real buffer content');
  assert.equal(refreshes,pinchRefresh,'preview does not clear canvas');
  context.scheduleFit(); flushFrames();
  assert.equal(gridResizes,pinchGrid,'layout observer cannot resize during pinch');
  const stableSizes=sizes.length;
  dispatch('touchmove',fingers(150)); flushFrames();
  assert.equal(sizes.length,stableSizes,'unchanged pinch size does not resize PTY');
  dispatch('touchmove',fingers(1000)); flushFrames(); assert.equal(zoomGrid.style.transform,'scaleX(.9) scale('+28/13+')');
  assert.equal(context.terminalCopy(),pinchText,'largest font retains content during pinch');
  dispatch('touchmove',fingers(1)); flushFrames(); assert.equal(zoomGrid.style.transform,'scaleX(.9) scale('+8/13+')');
  assert.equal(context.terminalCopy(),pinchText,'smallest font retains content during pinch');
  assert.equal(sizes.length,pinchSizes); assert.equal(gridResizes,pinchGrid);
  dispatch('touchend',fingers(1).slice(0,1));
  assert.equal(dispatch('touchmove',fingers(1).slice(0,1)).stopped,true,'remaining finger cannot scroll/select');
  dispatch('touchend',[]); flushFrames(); emit('click',0,0);
  assert.equal(storage.get('terminalFontSize'),'8');
  assert.equal(sizes.length,pinchSizes+1,'gesture completion synchronizes PTY once');
  assert.equal(gridResizes,pinchGrid+1,'gesture completion fits grid once');
  assert.equal(terminal,pinchTerm); assert.equal(canvasLoads,pinchLoads);
  assert.equal(disposals,pinchDisposals,'pinch never disposes terminal/renderer');
  assert.equal(context.terminalCopy(),pinchText,'final reflow retains content');
  assert.equal(focuses,zoomFocus); assert.equal(hides,zoomHides); assert.equal(inputs.length,zoomInputs);
  queuedFrames=false;
  await write(terminal,'\r\n'+Array.from({length:200},(_,i)=>'history '+i).join('\r\n'));
  queuedFrames=true;
  emit('touchstart',8,200);
  const historyBefore=terminal.buffer.active.viewportY;
  for(let y=216;y<=360;y+=16) emit('touchmove',8,y);
  assert.equal(frames.length,1,'scroll burst schedules one frame'); flushFrames();
  assert.equal(terminal.buffer.active.viewportY,historyBefore-Math.trunc(160/(16*terminal.options.fontSize/13)),'accumulated pixel distance scrolls real buffer');
  await write(terminal,'\r\nstream during review');
  assert.equal(terminal.buffer.active.viewportY,historyBefore-Math.trunc(160/(16*terminal.options.fontSize/13)),'output does not pull history to bottom');
  dispatch('touchend',[]); emit('click',8,360);
  assert.equal(hides,zoomHides,'hidden IME never receives repeated hide calls');
  queuedFrames=false;
  await write(terminal,'\x1b[?1000h');
  assert.equal(emit('touchstart',8,200).prevented,undefined,'remote mouse touchstart still reaches xterm');
  assert.equal(emit('touchmove',8,240).prevented,undefined,'remote mouse move still reaches xterm');
  dispatch('touchend',[]); emit('click',8,240);
  await write(terminal,'\x1b[?1000l');
  const tapFocus=focuses, tapShows=shows;
  emit('touchstart',0,0); dispatch('touchend',[]);
  assert.equal(focuses,tapFocus+1,'fresh ordinary tap can input after pinch');
  assert.equal(shows,tapShows+1,'tap explicitly requests Android IME');
  // Small touch jitter must not become a scroll. No synthetic click is required.
  emit('touchstart',0,0); emit('touchmove',9,4); dispatch('touchend',[]);
  assert.equal(shows,tapShows+2);
  // Pinch directly to tap, without a browser-generated click in between.
  dispatch('touchstart',fingers(100)); dispatch('touchend',[]);
  emit('touchstart',0,0); dispatch('touchend',[]);
  assert.equal(shows,tapShows+3);
  emit('touchstart',0,0); await new Promise(resolve=>setTimeout(resolve,520));
  dispatch('touchend',[]);
  const beforeRelease=shows;
  emit('touchstart',0,0); dispatch('touchend',[]);
  assert.equal(shows,beforeRelease,'release selection only');
  emit('touchstart',0,0); dispatch('touchend',[]);
  assert.equal(shows,beforeRelease+1,'next tap after release restores input');
  const retained=terminal, retainedLoads=canvasLoads, retainedText=context.terminalCopy();
  const retainedFont=terminal.options.fontSize, retainedInputs=inputs.length, retainedFocus=focuses;
  const retainedViewport=terminal.buffer.active.viewportY;
  const retainedCommands=vm.runInContext('commands.join("\\n")',context);
  context.terminalKeyboard(true); context.terminalBackground();
  assert.equal(elements.get('keys').hidden,true,'background hides shortcut bar');
  context.terminalResume();
  assert.equal(terminal,retained,'resume retains parser and WebView page state');
  assert.equal(canvasLoads,retainedLoads,'resume never reloads renderer');
  assert.equal(terminal.options.fontSize,retainedFont);
  assert.equal(context.terminalCopy(),retainedText);
  assert.equal(inputs.length,retainedInputs); assert.equal(focuses,retainedFocus);
  assert.ok(refreshes>0,'resume refreshes retained renderer');
  assert.equal(terminal.buffer.active.viewportY,retainedViewport);
  assert.equal(vm.runInContext('commands.join("\\n")',context),retainedCommands);
  await write(terminal,Uint8Array.of(0xe4));
  context.terminalBackground(); context.terminalResume();
  await write(terminal,Uint8Array.of(0xb8,0xad));
  assert.ok(context.terminalCopy().includes('中'),'background retains incremental UTF8 decoder');
  context.terminalMode(false);
  assert.equal(screen.listeners.touchmove.length,1,'reconnect releases previous touch handlers');
  const before=inputs.length;
  fire('ssh a'); fire('\x7f'); fire('b'); fire('\r');
  assert.equal(inputs[before],'ssh b\r');
  // History survives parser recreation but never leaves this page's memory.
  context.terminalMode(false);
  fire('ssh history@example'); fire('\r'); fire('ssh second@example'); fire('\r');
  fire('draft'); click('↑'); assert.equal(vm.runInContext('line',context),'ssh second@example');
  click('↑'); assert.equal(vm.runInContext('line',context),'ssh history@example');
  click('↓'); assert.equal(vm.runInContext('line',context),'ssh second@example');
  click('↓'); assert.equal(vm.runInContext('line',context),'draft','down restores draft');
  context.terminalMode(true);
  fire('echo shell'); fire('\r'); fire('echo shell'); fire('\r');
  assert.equal(vm.runInContext('commands.filter(c=>c === "echo shell").length',context),1);
  const rawBefore=inputs.length; click('↑');
  assert.equal(inputs[rawBefore],'\x1b[A','empty connected input preserves remote readline');
  fire('\x03'); fire('draft');
  click('↑'); assert.equal(inputs.at(-1),'\x7f'.repeat(5)+'echo shell');
  assert.equal(vm.runInContext('line',context),'echo shell');
  click('↑'); assert.equal(vm.runInContext('line',context),'ssh second@example');
  click('↓'); assert.equal(vm.runInContext('line',context),'echo shell');
  click('↓'); assert.equal(vm.runInContext('line',context),'draft');
  click('CTRL'); click('↑'); assert.equal(inputs.at(-1),'\x1b[A','modifier bypasses history');
  fire('\x03'); fire('tui text');
  await write(terminal,'\x1b[?1049h\x1b[?1h');
  click('↑'); assert.equal(inputs.at(-1),'\x1bOA','alternate-screen TUI bypasses local history');
  await write(terminal,'\x1b[?1049l\x1b[?1l');
  fire('\x03'); fire('cursor edit'); click('←'); click('↑');
  assert.equal(inputs.at(-1),'\x1b[A','unknown remote cursor edit keeps arrows remote');
  fire('\r'); fire('edited'); fire('\x7f'); fire('!'); fire('\r');
  assert.equal(vm.runInContext('commands[0]',context),'edite!','history tracks sent backspace edits');
  context.terminalMode(false);
  for (let i=0;i<105;i++) { fire('cmd '+i); fire('\r'); }
  assert.equal(vm.runInContext('commands.length',context),100);
  for (let i=0;i<100;i++) click('↑');
  assert.equal(vm.runInContext('line',context),'cmd 5');
  click('↑'); assert.equal(vm.runInContext('line',context),'cmd 104','up wraps newest');
  click('↓'); assert.equal(vm.runInContext('line',context),'','down restores empty draft');
  await write(terminal,'');
  assert.ok(context.terminalCopy().includes('cmd 104'),'offline command echo remains visible');
  canvasMode='fail'; context.terminalMode(false);
  assert.equal(canvasDisposals,1,'failed activation disposes partial canvas');
  await write(terminal,'DOM fallback works');
  assert.ok(context.terminalCopy().includes('DOM fallback works'));
  canvasMode='dispose-fail'; context.terminalMode(false);
  await write(terminal,'cleanup failure fallback');
  assert.ok(context.terminalCopy().includes('cleanup failure fallback'));
  const savedCanvas=context.CanvasAddon; delete context.CanvasAddon;
  context.terminalMode(false); await write(terminal,'missing addon fallback');
  assert.ok(context.terminalCopy().includes('missing addon fallback'));
  // The same in-place font path must work without any canvas addon.
  queuedFrames=true;
  const domTerm=terminal, domSizes=sizes.length, domGrid=gridResizes;
  const domText=context.terminalCopy(), domRefresh=refreshes;
  dispatch('touchstart',fingers(100));
  dispatch('touchmove',fingers(130)); flushFrames();
  assert.equal(terminal,domTerm); assert.equal(context.terminalCopy(),domText);
  assert.equal(gridResizes,domGrid); assert.equal(sizes.length,domSizes);
  assert.equal(refreshes,domRefresh,'DOM preview does not redraw');
  // End before the final queued frame: latest font must be applied before fit.
  dispatch('touchmove',fingers(170)); dispatch('touchend',[]); flushFrames();
  assert.equal(terminal.options.fontSize,13.6);
  assert.equal(sizes.length,domSizes+1); assert.equal(gridResizes,domGrid+1);
  assert.equal(context.terminalCopy(),domText,'DOM fallback preserves content after pinch');
  dispatch('touchstart',fingers(100)); dispatch('touchmove',fingers(120));
  dispatch('touchcancel',[]); flushFrames();
  assert.equal(sizes.length,domSizes+2,'cancel flushes final size once');
  queuedFrames=false;
  context.CanvasAddon=savedCanvas; canvasMode='ok'; context.terminalMode(true);
  terminal.dispose();
  context.terminalMode(false); fire('abc'); const beforeCursor=inputs.length; click('←'); fire('X'); click('→'); fire('Y'); assert.equal(vm.runInContext('line',context),'abXcY'); assert.equal(inputs.length,beforeCursor);
  const savedSize=terminal.options.fontSize;
  const fresh=vm.createContext({...context}); fresh.window=fresh;
  vm.runInContext(fs.readFileSync('app/src/main/assets/terminal/terminal.js','utf8'),fresh);
  assert.equal(terminal.options.fontSize,savedSize,'new page loads saved font size');
  console.log('terminal behavior: UTF8 fragments, ANSI, alternate screen, raw input, 14 keys, modifiers, base64, resize, trimmed selection geometry, spaces, wide cells, soft wraps, auto-copy, handles, dismiss/cancel, tap focus/IME, startup, memory history, visible pinch redraw and deferred PTY PASS');
})().catch(error=>{console.error(error);process.exitCode=1;});
