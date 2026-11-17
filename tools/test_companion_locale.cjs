// Language fixtures exercise the production assets; original transcript stays verbatim.
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright');
const assets = path.resolve(__dirname, '../app/src/main/assets/companion');
const english = JSON.parse(fs.readFileSync(path.join(assets,'messages.json'),'utf8'));
let locale = {tag:'en-US',revision:1,messages:english};
const active = {id:'locale-test',title:'사용자가 입력한 회의 제목',status:'running',createdAt:Date.now(),utterances:[{id:'u1',text:'회의 원문은 번역하거나 변경하지 않습니다.'}]};
const server = http.createServer((req,res)=>{
  const json = obj => {res.setHeader('Content-Type','application/json');res.end(JSON.stringify(obj));};
  if(req.url==='/locale') return json(locale);
  if(req.url==='/locale.js'){res.setHeader('Content-Type','application/javascript');return res.end('globalThis.ConversateLocale='+JSON.stringify(locale)+';\n'+fs.readFileSync(path.join(assets,'i18n.js'),'utf8'));}
  if(req.url==='/api/state') return json({active,settings:{},sttConnected:true,ai:{available:false},languageTag:locale.tag});
  if(req.url==='/api/sessions/locale-test') return json(active);
  if(req.url==='/api/notes') return json({notes:[]});
  if(req.url==='/api/sessions') return json({sessions:[]});
  const file=req.url==='/'?'index.html':req.url.slice(1);
  if(!['index.html','app.js','style.css'].includes(file)){res.statusCode=404;return res.end();}
  res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html');
  res.end(fs.readFileSync(path.join(assets,file)));
});
(async()=>{
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const browser=await chromium.launch({channel:process.env.PLAYWRIGHT_CHANNEL || undefined,headless:true});
  try{
    for(const width of [1400,390]){
      locale={tag:'en-US',revision:1,messages:english};
      const page=await browser.newPage({viewport:{width,height:1000}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
      await page.goto(`http://127.0.0.1:${server.address().port}/`);
      await page.locator('[data-row-key="row:u1"]').waitFor();
      assert.equal(await page.locator('html').getAttribute('lang'),'en-US');
      assert.equal(await page.locator('[data-row-key="row:u1"]').textContent(),active.utterances[0].text);
      assert.equal(await page.locator('#meeting-title').textContent(),active.title);
      await page.locator('[data-page=settings]').click();
      assert.equal(await page.locator('#setting-font option[value="14"]').textContent(),'Small · 14');
      const settings=await page.locator('#page-settings').textContent();
      assert.ok(!/[가-힣]/.test(settings),'Untranslated Korean UI remained in English settings');
      if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)) console.log(await page.evaluate(()=>({scroll:document.documentElement.scrollWidth,width:innerWidth,body:document.body.scrollWidth})), await page.evaluate(()=>[...document.querySelectorAll('*')].filter(el=>el.getBoundingClientRect().right>innerWidth).map(el=>({tag:el.tagName,id:el.id,cls:el.className,right:el.getBoundingClientRect().right})).slice(-12)));
      await page.screenshot({path:`tools/out/meeting/locale-debug-${width}.png`,fullPage:true});
      assert.ok(!(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)),'English mobile overflow');
      await page.screenshot({path:`tools/out/meeting/locale-en-${width}.png`,fullPage:true});
      locale={tag:'ja-JP',revision:2,messages:{...english,'실시간 자막':'リアルタイム字幕','설정':'設定'}};
      await page.getByText('リアルタイム字幕',{exact:true}).first().waitFor();
      assert.equal(await page.locator('html').getAttribute('lang'),'ja-JP');
      await page.locator('[data-page=live]').click();
      assert.equal(await page.locator('[data-row-key="row:u1"]').textContent(),active.utterances[0].text);
      assert.deepEqual(errors,[]);
      await page.close();console.log('PASS locale:',width,'English UI/options, live Japanese update, original transcript/title, no overflow/errors');
    }
  }finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);server.close();process.exitCode=1;});
