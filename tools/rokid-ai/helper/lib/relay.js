import { LanguageModel } from 'language-model';

// Only our app's local token is used. No Rokid account credential is requested.
export function bridge(owner, options) {
  const opts = options || {};
  const endpoint = String(opts.endpoint || 'http://127.0.0.1:8766').replace(/\/$/, '');
  const token = String(opts.token || '');
  const diagnostic = opts.client !== 'widget';
  let alive = true, started = false, available = false, model = null;
  let busy = false, legacy = false, heartbeatTimer = null, heartbeatBusy = false;
  let lastAvailability = 0, lastStatus = '', sleepWake = null;
  const requests = new Set();
  const status = text => {
    if (!alive || text === lastStatus) return;
    lastStatus = text;
    if (diagnostic) owner.setData({ status: text });
  };
  function sleep(ms) {
    if (!alive) return Promise.resolve();
    return new Promise(resolve => {
      let done = false;
      const timer = setTimeout(finish, ms);
      function finish() {
        if (done) return;
        done = true; clearTimeout(timer);
        if (sleepWake === finish) sleepWake = null;
        resolve();
      }
      sleepWake = finish;
    });
  }
  async function deadline(promise, ms, label) {
    let timer;
    try {
      return await Promise.race([promise, new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(label)), Math.max(1, ms));
      })]);
    } finally { clearTimeout(timer); }
  }
  async function request(path, body, timeout = 12000) {
    const controller = typeof AbortController === 'function' ? new AbortController() : null;
    if (controller) requests.add(controller);
    const config = { headers: { 'X-Conversate-Bridge': token }, timeout };
    if (controller) config.signal = controller.signal;
    if (body !== undefined) {
      config.method = 'POST'; config.headers['Content-Type'] = 'application/json; charset=utf-8';
      config.body = JSON.stringify(body);
    }
    try {
      const response = await deadline(fetch(endpoint + path, config), timeout, 'local relay timeout');
      if (!response.ok) throw new Error('local relay HTTP ' + response.status);
      return await response.json();
    } finally {
      if (controller) { requests.delete(controller); controller.abort(); }
    }
  }
  async function heartbeat() {
    if (!alive || heartbeatBusy) return;
    heartbeatBusy = true;
    try { await request('/bridge/heartbeat', { available, client: opts.client || 'helper' }); }
    catch (_) { /* Reconnect through the main loop. */ }
    finally { heartbeatBusy = false; }
  }
  function heartbeatMode() {
    const needed = alive && (busy || legacy);
    if (needed && !heartbeatTimer) heartbeatTimer = setInterval(heartbeat, 10000);
    if (!needed && heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
  }
  function destroyModel() {
    const current = model; model = null;
    if (current) { try { current.destroy(); } catch (_) {} }
  }
  async function createModel(system, timeout) {
    let abandoned = false;
    const pending = Promise.resolve(LanguageModel.create({ initialPrompts: [{ role: 'system', content: system }] }));
    // A late create after timeout/stop must not leave a model session behind.
    pending.then(value => {
      if (abandoned || !alive) { try { value.destroy(); } catch (_) {} }
    }, () => {});
    try {
      const value = await deadline(pending, timeout, 'create timeout');
      if (!alive) throw new Error('helper stopped');
      model = value;
      return value;
    } catch (failure) { abandoned = true; throw failure; }
  }
  async function refreshAvailability() {
    available = await deadline(LanguageModel.availability(), 8000, 'availability timeout') === 'available';
    lastAvailability = Date.now();
    await heartbeat();
  }
  async function runJob(job) {
    busy = true; heartbeatMode();
    console.log('ConversateAI job-start');
    let output = '', error = '';
    try {
      if (typeof job.id !== 'string' || typeof job.prompt !== 'string') throw new Error('invalid job');
      const until = Number(job.deadline || Date.now() + 30000);
      const remaining = () => Math.min(60000, until - Date.now() - 500);
      if (remaining() <= 0) throw new Error('expired job');
      if (Date.now() - lastAvailability >= 60000) await refreshAvailability();
      if (!available) throw new Error('Rokid AI unavailable');
      status('Rokid AI processing');
      await createModel(String(job.system || 'Follow the requested outputLanguage. Return valid JSON.'), Math.min(10000, remaining()));
      if (remaining() <= 0) throw new Error('expired job');
      output = String(await deadline(model.prompt(job.prompt), remaining(), 'prompt timeout'));
    } catch (failure) {
      console.log('ConversateAI job-error name=' + String(failure && failure.name || 'Error'));
      error = String(failure && failure.message || failure).slice(0, 240);
    } finally {
      destroyModel(); busy = false; heartbeatMode();
    }
    if (!alive) return;
    console.log('ConversateAI job-result ok=' + String(!error));
    await request('/bridge/result', error ? { id: job.id, ok: false, error } : { id: job.id, ok: true, output });
    status(error ? 'Request failed' : 'Meeting app connected');
  }
  async function loop() {
    let retry = 2000;
    let joined = false;
    const joinUntil = Date.now() + 4000;
    while (alive) {
      try {
        if (String(opts.managed) === 'true' && !joined) {
          await request('/bridge/heartbeat', { available: false, client: opts.client || 'helper' }, 1000);
          joined = true;
        }
        if (!lastAvailability || (!available && Date.now() - lastAvailability >= 60000)) await refreshAvailability();
        if (!alive) return;
        if (!available) { status('Waiting for Rokid AI'); await sleep(30000); continue; }
        const next = await request('/bridge/job?waitMs=25000', undefined, 31000);
        if (!alive) return;
        legacy = !next || next.protocol !== 2;
        heartbeatMode(); retry = 2000;
        if (!next || !next.job) {
          status('Meeting app connected');
          // Old APKs return immediately: retain a bounded compatible poll rather than spin.
          if (legacy) await sleep(1000);
          continue;
        }
        await runJob(next.job);
      } catch (failure) {
        if (!alive) return;
        if (String(opts.managed) === 'true') {
          if (!joined && Date.now() < joinUntil) { await sleep(250); continue; }
          // No app: stop. A future launcher detach starts a new bridge explicitly.
          alive = false; heartbeatMode(); destroyModel(); return;
        }
        if (lastStatus !== 'Waiting for meeting app') console.log('ConversateAI relay-retry name=' + String(failure && failure.name || 'Error'));
        legacy = false; heartbeatMode();
        status('Waiting for meeting app');
        lastAvailability = 0;
        await sleep(retry); retry = Math.min(30000, retry * 2);
      }
    }
  }
  async function probe() {
    try {
      await refreshAvailability();
      if (!alive || !available) throw new Error('Rokid AI unavailable');
      await createModel('Reply briefly in the language requested by the user.', 10000);
      const value = await deadline(model.prompt('Reply with one short meeting test sentence.'), 25000, 'prompt timeout');
      if (alive && diagnostic) owner.setData({ status: 'Rokid AI verified', result: String(value) });
    } catch (_) { status('Rokid AI check failed'); }
    finally { destroyModel(); }
  }
  return {
    start() {
      if (started) return;
      started = true;
      if (String(opts.probe || '') === 'true') probe();
      else if (token) loop();
      else status('Local pairing required'); // Never run a model just because launch params are absent.
    },
    wake() { if (sleepWake) sleepWake(); },
    stop() {
      alive = false; heartbeatMode();
      if (sleepWake) sleepWake();
      requests.forEach(controller => controller.abort()); requests.clear();
      destroyModel();
    },
  };
}

// A parked lifecycle socket has no ping, interval or reconnect timer. The APK
// sends an event only when the user opens/closes its meeting screen.
export function lifecycle(owner, options) {
  const opts = options || {};
  let socket = null, worker = null, alive = false;
  function stopWorker() { if (worker) worker.stop(); worker = null; }
  return {
    start() {
      if (alive || !opts.token) return;
      alive = true;
      const endpoint = String(opts.endpoint || 'http://127.0.0.1:8766').replace(/\/$/, '');
      socket = new WebSocket(endpoint.replace(/^http/, 'ws') + '/bridge/lifecycle');
      socket.onopen = () => { if (alive) socket.send(JSON.stringify({ token: String(opts.token) })); };
      socket.onmessage = event => {
        if (!alive) return;
        let state;
        try { state = JSON.parse(String(event.data)); } catch (_) { return; }
        if (state.active === true && !worker) {
          console.log('ConversateAI app-start');
          worker = bridge(owner, Object.assign({}, opts, { managed: 'true' }));
          worker.start();
        } else if (state.active === false) {
          stopWorker();
          console.log('ConversateAI app-stop');
        }
      };
      socket.onclose = () => { stopWorker(); console.log('ConversateAI channel-closed'); };
      socket.onerror = () => { stopWorker(); };
    },
    stop() {
      alive = false; stopWorker();
      if (socket) { socket.close(); socket = null; }
    },
  };
}
