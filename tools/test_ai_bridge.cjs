// Run the actual helper module with fake transport/clock/model; no device or AI requests.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const test = require('node:test');
const flush = () => new Promise(resolve => setImmediate(resolve));
class Clock {
  now = 100000; next = 1; timers = new Map();
  add(fn, ms, interval = false) { const id = this.next++; this.timers.set(id, {fn, at:this.now+ms, ms, interval}); return id; }
  clear(id) { this.timers.delete(id); }
  async advance(ms) {
    const until = this.now + ms;
    for (let steps=0; ; steps++) {
      assert.ok(steps<2000, 'Busy loop in helper timers');
      const next = [...this.timers].filter(([,v])=>v.at<=until).sort((a,b)=>a[1].at-b[1].at)[0];
      if (!next) break;
      const [id,value]=next; this.now=value.at;
      if(value.interval)value.at+=value.ms; else this.timers.delete(id);
      value.fn(); await flush();
    }
    this.now=until; await flush();
  }
}
function harness({legacy=false,createDelay=0,promptDelay=0,token='local-test-token'}={}) {
  const clock=new Clock(), calls=[], models=[], updates=[];
  let waiting=null, availability=0, creates=0, prompts=0;
  const json=value=>({ok:true,status:200,json:async()=>value});
  const delayed=(value,ms)=>ms ? new Promise(resolve=>clock.add(()=>resolve(value),ms)) : Promise.resolve(value);
  const LanguageModel={
    availability:async()=>{availability++;return 'available';},
    create:()=>{
      creates++;
      const model={destroyed:0,destroy(){this.destroyed++;},prompt(){prompts++;return delayed('{"ok":true}',promptDelay);}};
      return delayed(model,createDelay).then(value=>{models.push(value);return value;});
    }
  };
  function fetch(url,config) {
    const p=new URL(url); const body=config.body ? JSON.parse(config.body) : null;
    calls.push({path:p.pathname,at:clock.now,body});
    if(p.pathname!='/bridge/job')return Promise.resolve(json({ok:true}));
    if(legacy)return Promise.resolve(json({job:null}));
    return new Promise((resolve,reject)=>{
      const timer=clock.add(()=>{waiting=null;resolve(json({protocol:2,job:null}));},25000);
      waiting=job=>{clock.clear(timer);waiting=null;resolve(json({protocol:2,job}));};
      config.signal?.addEventListener('abort',()=>{clock.clear(timer);waiting=null;reject(new Error('aborted'));},{once:true});
    });
  }
  let source=fs.readFileSync(path.join(__dirname,'rokid-ai/helper/lib/relay.js'),'utf8');
  source=source.replace("import { LanguageModel } from 'language-model';",'const LanguageModel=globalThis.LanguageModel;')
    .replace('export function bridge','function bridge')+'\nglobalThis.makeBridge=bridge;';
  class FakeDate extends Date { static now(){return clock.now;} }
  const context={LanguageModel,fetch,AbortController,Date:FakeDate,console,
    setTimeout:(fn,ms)=>clock.add(fn,ms),clearTimeout:id=>clock.clear(id),
    setInterval:(fn,ms)=>clock.add(fn,ms,true),clearInterval:id=>clock.clear(id)};
  vm.runInNewContext(source,context);
  const relay=context.makeBridge({setData:data=>updates.push(data)},{token,client:'widget'});
  return {clock,calls,models,updates,relay,start:async()=>{relay.start();await flush();},
    enqueue:async()=>{assert.ok(waiting);waiting({id:'test-job',system:'JSON only',prompt:'synthetic',deadline:clock.now+60000});await flush();},
    counts:()=>({availability,creates,prompts}),stop:async()=>{relay.stop();await flush();}};
}
test('idle widget is invisible and long-polls without model calls or heartbeat timers',async()=>{
  const h=harness();await h.start();await h.clock.advance(60000);
  assert.equal(h.calls.filter(c=>c.path==='/bridge/job').length,3);
  assert.equal(h.calls.filter(c=>c.path==='/bridge/heartbeat').length,1);
  assert.deepEqual(h.counts(),{availability:1,creates:0,prompts:0});assert.equal(h.updates.length,0);
  const before=h.calls.length;await h.stop();await h.clock.advance(60000);assert.equal(h.calls.length,before);
  assert.equal(h.clock.timers.size,0);
});
test('new work starts immediately, and destroys its model session afterward',async()=>{
  const h=harness();await h.start();await h.enqueue();
  assert.equal(h.counts().prompts,1);assert.equal(h.models[0].destroyed,1);
  const results=h.calls.filter(c=>c.path==='/bridge/result');assert.equal(results.length,1);assert.equal(results[0].body.ok,true);
  assert.equal(results[0].at,100000);await h.stop();
});
test('heartbeat runs only during generation and stops when the job completes',async()=>{
  const h=harness({promptDelay:25000});await h.start();await h.enqueue();await h.clock.advance(26000);
  assert.equal(h.calls.filter(c=>c.path==='/bridge/heartbeat').length,3);
  assert.equal(h.models[0].destroyed,1);await h.clock.advance(60000);
  assert.equal(h.calls.filter(c=>c.path==='/bridge/heartbeat').length,3);
  assert.equal(h.counts().availability,1);await h.stop();
});
test('missing launch token does not automatically run the diagnostic model probe',async()=>{
  const h=harness({token:''});await h.start();await h.clock.advance(90000);
  assert.deepEqual(h.counts(),{availability:0,creates:0,prompts:0});assert.equal(h.calls.length,0);assert.equal(h.updates.length,0);await h.stop();
});
test('legacy APK compatibility does not spin on immediate empty responses',async()=>{
  const h=harness({legacy:true});await h.start();await h.clock.advance(4000);
  assert.equal(h.calls.filter(c=>c.path==='/bridge/job').length,5);assert.equal(h.counts().creates,0);
  await h.stop();const before=h.calls.length;await h.clock.advance(60000);assert.equal(h.calls.length,before);
});
test('late model creation after timeout is destroyed, never used for a prompt',async()=>{
  const h=harness({createDelay:15000});await h.start();await h.enqueue();await h.clock.advance(16000);
  assert.equal(h.counts().prompts,0);assert.equal(h.models[0].destroyed,1);
  assert.match(h.calls.find(c=>c.path==='/bridge/result').body.error,/create timeout/);await h.stop();
});
test('stopping during model creation cleans up its late result',async()=>{
  const h=harness({createDelay:5000});await h.start();await h.enqueue();await h.stop();await h.clock.advance(6000);
  assert.equal(h.counts().prompts,0);assert.equal(h.models[0].destroyed,1);
  assert.equal(h.calls.filter(c=>c.path==='/bridge/result').length,0);
});
