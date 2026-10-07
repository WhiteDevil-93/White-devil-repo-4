const {test} = require('node:test');
const assert = require('node:assert/strict');
const W = require('./workflow.js');
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
