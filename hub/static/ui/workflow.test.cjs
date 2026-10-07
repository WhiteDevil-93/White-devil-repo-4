const {test} = require('node:test');
const assert = require('node:assert/strict');
const W = require('./workflow.js');
const fs = require('node:fs');
const path = require('node:path');
const source = {id:'abcdef123456', name:'Source', status:'done', kind:'chain', lines:['a','b'], parts:[{},{}]};
test('explicit source and bounded workload', () => {
  assert.deepEqual(W.cyclePlan([source], source.id, '3'), {body:{src:source.id,rounds:3},name:'Source',maxClips:12});
});
test('no implicit source selection', () => assert.throws(() => W.cyclePlan([source], '', 1)));
test('unsupported or incomplete sources rejected', () => {
  for (const patch of [{status:'rendering'},{t2v:true},{from_job:'other'},{kind:'sharpen'},{lines:[]},{parts:[]},{id:'../../private'}]) {
    assert.equal(W.eligibleCycleSource({...source,...patch}), false);
  }
});
test('round count validates edges', () => {
  for (const n of [0,11,1.5,'bad','']) assert.throws(() => W.cyclePlan([source],source.id,n));
  assert.equal(W.cyclePlan([source],source.id,10).maxClips,40);
});
test('sync failure visible and explicit retry succeeds', async () => {
  let fail=true; const statuses=[];
  const saver=W.chatSaver(async () => {if(fail) throw Error('offline');}, s=>statuses.push(s));
  await saver('payload'); assert.equal(statuses.at(-1),'Hub sync failed — retry sync');
  fail=false; await saver('payload'); assert.equal(statuses.at(-1),'Saved to hub');
});
test('pending payloads coalesce and latest is sent', async () => {
  const sent=[]; const saver=W.chatSaver(async p=>sent.push(p),()=>{});
  const first=saver('old'), second=saver('new'); await Promise.all([first,second]);
  assert.deepEqual(sent,['new']);
});
test('writes serialize and obsolete success cannot claim latest saved', async () => {
  let release; const events=[];
  const saver=W.chatSaver(p=>p==='old'?new Promise(r=>release=r):Promise.resolve(), s=>events.push(s));
  const first=saver('old'); await Promise.resolve();
  const second=saver('new'); release(); await first;
  await second; assert.equal(events.filter(s=>s==='Saved to hub').length,1);
});
test('native palettes match shipped web tokens and main text pairs meet contrast', () => {
  const root = path.resolve(__dirname, '../../..');
  const android = fs.readFileSync(path.join(root,'android/app/src/main/java/com/whitedevil/ui/theme/WdTheme.kt'),'utf8');
  const desktop = fs.readFileSync(path.join(root,'desktop/src/main/kotlin/com/whitedevil/desktop/Theme.kt'),'utf8');
  const css = fs.readFileSync(path.join(__dirname,'tokens.css'),'utf8');
  const native = (source,name) => source.match(new RegExp('(?:val '+name+'|'+name+' =)\\s*=?\\s*Color\\(0xFF([0-9A-F]{6})\\)'))[1].toLowerCase();
  const token = name => css.match(new RegExp('--'+name+':#([0-9a-f]{6})'))[1];
  for (const [name, web] of [['bg','bg'],['surface','panel'],['accent','acc'],['accentLight','acc3'],['textMetadata','dim']]) assert.equal(native(android,name),token(web));
  assert.equal(native(desktop,'primary'), token('acc'));
  assert.equal(native(desktop,'background'), token('bg'));
  function luminance(hex) {
    const rgb=hex.match(/../g).map(s=>parseInt(s,16)/255).map(v=>v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4));
    return rgb[0]*0.2126+rgb[1]*0.7152+rgb[2]*0.0722;
  }
  function contrast(a,b) { const x=luminance(a),y=luminance(b); return (Math.max(x,y)+0.05)/(Math.min(x,y)+0.05); }
  for (const [foreground, background] of [['textMetadata','surface'],['textSecondary','surface'],['onAccent','accent']]) {
    assert.ok(contrast(native(android,foreground),native(android,background)) >= 4.5, foreground+' contrast');
  }
  assert.ok(contrast(native(desktop,'onPrimary'),native(desktop,'primary'))>=4.5);
});
