// Regression test against the real companion assets, with a long synthetic meeting.
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const assets = path.resolve(__dirname, '../app/src/main/assets/companion');
const rows = Array.from({length: 300}, (_, i) => ({id: `utterance-${i}`, text: `발화 ${i}: 회의의 처음부터 끝까지 저장된 전체 내용입니다. `.repeat(4)}));
let partial = '';
const server = http.createServer((req, res) => {
  const send = value => { res.setHeader('Content-Type', 'application/json; charset=utf-8'); res.end(JSON.stringify(value)); };
  const active = {id: 'meeting-long', title: '긴 회의 전사 회귀 테스트', status: 'running', createdAt: Date.now()-600000};
  if (req.url === '/locale') return send({tag:'ko-KR',revision:0,messages:{}});
  if (req.url === '/locale.js') {res.setHeader('Content-Type','application/javascript');return res.end("globalThis.ConversateLocale={tag:'ko-KR',revision:0,messages:{}};" + fs.readFileSync(path.join(assets,'i18n.js'),'utf8'));}
  if (req.url === '/api/state') return send({active: {...active, utterances: rows.slice(-15)}, partial, sttConnected: true, status: '인식 중', ai: {available: false}, settings: {fontSize: 16, selectedNoteIds: []}});
  if (req.url === '/api/sessions/meeting-long') return send({...active, utterances: rows});
  if (req.url === '/api/notes') return send({notes: []});
  const name = req.url === '/' ? 'index.html' : req.url.slice(1);
  if (!['index.html','app.js','style.css'].includes(name)) { res.statusCode=404; return res.end(); }
  res.setHeader('Content-Type', name.endsWith('.js') ? 'application/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html');
  res.end(fs.readFileSync(path.join(assets, name)));
});
(async () => {
  let browser;
  try {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    browser = await chromium.launch({headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || undefined});
    const width = Number(process.env.TRANSCRIPT_TEST_WIDTH || 1400);
    const page = await browser.newPage({viewport: {width, height: 1000}});
    const errors=[]; page.on('pageerror', e => errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.locator('[data-row-key="row:utterance-0"]').waitFor();
    await page.evaluate(() => clearTimeout(app.pollTimer));
    assert.equal(await page.locator('#live-transcript > .transcript-line').count(), 300);
    assert.match(await page.locator('#transcript-count').textContent(), /300/);
    const anchor = await page.evaluate(() => {
      const el=document.getElementById('live-transcript'); el.scrollTop=350;
      const top=el.getBoundingClientRect().top;
      const n=[...el.children].find(n=>n.getBoundingClientRect().bottom>top);
      window.savedTranscriptNode=n;
      return {key:n.dataset.rowKey, offset:n.getBoundingClientRect().top-top};
    });
    rows.push({id:'utterance-300',text:'새로운 확정 문장입니다.'}); partial='다음 발화의 중간 결과';
    await page.evaluate(() => refreshState());
    const after = await page.evaluate(key => {
      const el=document.getElementById('live-transcript');
      const n=[...el.children].find(n=>n.dataset.rowKey===key);
      return {sameNode:n===window.savedTranscriptNode, offset:n.getBoundingClientRect().top-el.getBoundingClientRect().top};
    },anchor.key);
    assert.equal(after.sameNode,true); assert.ok(Math.abs(after.offset-anchor.offset)<1,'New speech moved the reading anchor');
    rows[0].text='이전 발화의 확정 정정 결과입니다. '.repeat(80);
    await page.evaluate(() => refreshState());
    const correctedOffset=await page.evaluate(key=>{
      const el=document.getElementById('live-transcript');
      const n=[...el.children].find(n=>n.dataset.rowKey===key);
      return n.getBoundingClientRect().top-el.getBoundingClientRect().top;
    },anchor.key);
    assert.ok(Math.abs(correctedOffset-anchor.offset)<1,'Correction above the viewport moved the reading anchor');
    await page.getByRole('button',{name:'처음으로 ↑',exact:true}).click();
    assert.equal(await page.locator('#live-transcript').evaluate(el=>el.scrollTop),0);
    await page.getByRole('button',{name:'최신 내용으로 ↓',exact:true}).click();
    rows.push({id:'utterance-301',text:'맨 아래에서는 새 발화를 계속 따라갑니다.'});
    await page.evaluate(() => refreshState());
    const gap=await page.locator('#live-transcript').evaluate(el=>el.scrollHeight-el.scrollTop-el.clientHeight);
    assert.ok(gap<2,'Bottom following stopped');
    assert.equal(await page.locator('#live-transcript > .transcript-line:not(.partial)').count(),302);
    assert.deepEqual(errors,[]);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    console.log(`PASS (${width}px): 300+ full utterances, stable reading anchor/node, correction above viewport, first/latest navigation, bottom following, no JS errors/overflow`);
  } finally { if(browser) await browser.close(); server.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
