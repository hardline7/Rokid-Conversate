import { LanguageModel } from 'language-model';

// This helper uses the documented host capability, never its credential/config API.
// The token below is supplied at launch by our installer and belongs to Conversate.
const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));
async function deadline(promise, ms, label) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(label)), ms);
    })]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export function bridge(owner, options) {
  const opts = options || {};
  let alive = true;
  let model = null;
  let started = false;
  let available = false;
  let heartbeatTimer = null;
  let heartbeatBusy = false;
  let lastHeartbeatLog = 0;
  const endpoint = String(opts.endpoint || 'http://127.0.0.1:8766').replace(/\/$/, '');
  const token = String(opts.token || '');
  const status = (text) => { if (alive) owner.setData({ status: text }); };
  async function request(path, body) {
    const config = { headers: { 'X-Conversate-Bridge': token } };
    if (body !== undefined) {
      config.method = 'POST';
      config.headers['Content-Type'] = 'application/json; charset=utf-8';
      config.body = JSON.stringify(body);
    }
    const response = await deadline(fetch(endpoint + path, config), 12000, 'local relay timeout');
    if (response.status === 204) return null;
    if (!response.ok) throw new Error('local relay HTTP ' + response.status);
    return response.json();
  }
  async function runProbe() {
    status('Checking Rokid AI');
    const availability = await deadline(LanguageModel.availability(), 8000, 'availability timeout');
    console.log('ConversateAI availability=' + availability);
    if (availability !== 'available') throw new Error('Rokid AI unavailable');
    model = await deadline(LanguageModel.create({
      initialPrompts: [{ role: 'system', content: 'Reply briefly in the language requested by the user.' }],
    }), 10000, 'create timeout');
    try {
      const result = await deadline(model.prompt('Reply with one short meeting test sentence.'), 25000, 'prompt timeout');
      console.log('ConversateAI probe-result=' + String(result));
      if (alive) owner.setData({ status: 'Rokid AI verified', result: String(result) });
      return result;
    } finally {
      if (model) model.destroy();
      model = null;
    }
  }
  async function loop() {
    async function heartbeat() {
      if (!alive || heartbeatBusy) return;
      heartbeatBusy = true;
      try {
        await request('/bridge/heartbeat', { available, status: available ? 'Rokid AI connected' : 'Waiting for Rokid AI', client: opts.client || 'helper' });
        if (Date.now() - lastHeartbeatLog >= 30000) {
          lastHeartbeatLog = Date.now();
          console.log('ConversateAI heartbeat source=' + String(opts.client || 'helper'));
        }
      }
      catch (_) { /* The app can be restarted while the helper stays open. */ }
      finally { heartbeatBusy = false; }
    }
    heartbeatTimer = setInterval(heartbeat, 5000);
    while (alive) {
      try {
        available = await deadline(LanguageModel.availability(), 8000, 'availability timeout') === 'available';
        await heartbeat();
        if (!available) {
          status('Waiting for Rokid AI');
          await sleep(10000);
          continue;
        }
        status('Meeting app connected');
        while (alive) {
          const next = await request('/bridge/job');
          if (!next || !next.job) { await sleep(1000); continue; }
          const job = next.job;
          if (typeof job.id !== 'string' || typeof job.prompt !== 'string') {
            throw new Error('invalid job');
          }
          let output = '';
          let error = '';
          try {
            status('Rokid AI processing');
            const remaining = Math.max(1000, Math.min(60000, Number(job.deadline || (Date.now() + 30000)) - Date.now() - 500));
            model = await deadline(LanguageModel.create({ initialPrompts: [{
              role: 'system', content: String(job.system || 'Follow the requested outputLanguage. Return valid JSON.'),
            }] }), 10000, 'create timeout');
            output = String(await deadline(model.prompt(job.prompt), remaining, 'prompt timeout'));
          } catch (failure) {
            error = String(failure && failure.message || failure).slice(0, 240);
          } finally {
            if (model) model.destroy();
            model = null;
          }
          if (!alive) return;
          await request('/bridge/result', error ? { id: job.id, ok: false, error } : { id: job.id, ok: true, output });
          status(error ? 'Request failed' : 'Meeting app connected');
        }
      } catch (failure) {
        console.log('ConversateAI relay-error=' + String(failure && failure.message || failure));
        status('Waiting for meeting app');
        await sleep(3000);
      }
    }
  }
  return {
    start() {
      if (started) return;
      started = true;
      if (String(opts.probe || '') === 'true' || !token) {
        runProbe().catch(failure => {
          console.log('ConversateAI probe-error=' + String(failure && failure.message || failure));
          status('Rokid AI check failed');
        });
      } else {
        loop();
      }
    },
    stop() {
      alive = false;
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      if (model) { model.destroy(); model = null; }
    },
  };
}
