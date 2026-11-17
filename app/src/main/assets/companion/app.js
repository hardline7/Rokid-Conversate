'use strict';

const $ = id => document.getElementById(id);
const app = { state: null, notes: [], selectedSession: null, tab: 'summary', settingsLoaded: false, polling: false, authenticated: false, pollTimer: null, searchVersion: 0, selectedHistoryId: null, refreshVersion: 0 };
const names = {}, cueNames = {}, sessionNames = {};
globalThis.refreshLocaleLabels = function() {
Object.assign(names, { live: tr("지금 회의"), notes: tr("사전 자료"), history: tr("회의 기록"), settings: tr("설정") });
Object.assign(cueNames, { answers: tr("답변"), bios: tr("인물"), concepts: tr("개념"), suggestions: tr("제안"), Answers: tr("답변"), Bios: tr("인물"), Concepts: tr("개념"), Suggestions: tr("제안") });
Object.assign(sessionNames, { running: tr("진행 중"), paused: tr("일시정지"), stopping: tr("마지막 문장 저장 중"), ended: tr("종료"), interrupted: tr("중단됨") });
};
refreshLocaleLabels();
function node(tag, cls, text) { const n = document.createElement(tag); if (cls) n.className = cls; if (text !== undefined && text !== null) n.textContent = String(text); return n; }
function button(text, cls, fn) { const n = node('button', cls, text); n.type = 'button'; n.addEventListener('click', fn); return n; }
function prettyDate(value) { const d = new Date(Number(value)); return Number.isNaN(d.valueOf()) ? '' : d.toLocaleString(globalThis.ConversateLocale?.tag || 'en', { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' }); }
function elapsed(value) { const secs = Math.max(0, Math.floor(Number(value) / 1000)); return `${Math.floor(secs / 60).toString().padStart(2, '0')}:${(secs % 60).toString().padStart(2, '0')}`; }
function toast(text) { $('toast').textContent = text; $('toast').hidden = false; clearTimeout(toast.timer); toast.timer = setTimeout(() => { $('toast').hidden = true; }, 4200); }
function notice(text) { $('notice').textContent = text || ''; $('notice').hidden = !text; }
function pairing(message = '') { app.authenticated = false; app.settingsLoaded = false; clearTimeout(app.pollTimer); $('workspace').hidden = true; $('pairing').hidden = false; $('pair-error').textContent = message; }
async function api(path, options = {}) {
  const init = { credentials: 'same-origin', cache: 'no-store', ...options };
  if (init.body && typeof init.body !== 'string') { init.body = JSON.stringify(init.body); init.headers = { ...init.headers, 'Content-Type': 'application/json; charset=utf-8' }; }
  const controller = new AbortController(); init.signal = controller.signal;
  const timeout = setTimeout(() => controller.abort(), path === '/api/import' ? 60000 : 15000);
  try {
    const response = await fetch(path, init);
    const text = await response.text();
    let body; try { body = text ? JSON.parse(text) : {}; } catch (_) { body = { error: tr("응답을 읽지 못했습니다.") }; }
    if (!response.ok) {
      if (response.status === 401 && path !== '/auth') pairing();
      throw new Error(body.error || tr("요청을 처리하지 못했습니다 ({0})" , response.status));
    }
    return body;
  } catch (error) {
    if (error.name === 'AbortError') throw new Error(tr("응답이 늦어지고 있습니다. 안경 연결을 확인해 주세요."));
    if (error instanceof TypeError) throw new Error(tr("안경과 연결이 끊겼습니다. 같은 Wi-Fi와 안경 전원을 확인해 주세요."));
    throw error;
  } finally { clearTimeout(timeout); }
}
async function run(action, control) {
  if (control) control.disabled = true;
  try { return await action(); } catch (error) { toast(error.message); return null; }
  finally { if (control) control.disabled = false; }
}
function page(name) {
  if (!names[name]) name = 'live';
  for (const key of Object.keys(names)) $(`page-${key}`).hidden = key !== name;
  document.querySelectorAll('[data-page]').forEach(n => n.classList.toggle('active', n.dataset.page === name));
  $('breadcrumb').textContent = tr("워크스페이스 / {0}" , names[name]);
  if (location.hash !== `#${name}`) history.replaceState(null, '', `#${name}`);
  if (name === 'history') run(loadSessions);
  if (name === 'notes') run(loadNotes);
}
function renderCue(container, cue, historyView = false) {
  container.replaceChildren();
  if (!cue) { container.append(node('p', 'empty', tr("대화에 도움이 되는 정보가\n필요한 순간에 표시됩니다."))); return; }
  container.append(node('span', 'cue-tag', cueNames[cue.type] || cue.type || 'Cue'), node('h3', '', cue.title || ''), node('p', '', cue.body || ''));
  if (cue.sourceTitle || cue.sourceId) container.append(node('p', 'cue-source', tr("자료: {0}" , cue.sourceTitle || cue.sourceId)));
  if (historyView && cue.quote) container.append(node('blockquote', '', cue.quote));
}
function transcriptNearBottom(container) {
  return container.scrollHeight - container.scrollTop - container.clientHeight < 24;
}
function updateTranscriptNavigation() {
  $('follow-latest').hidden = transcriptNearBottom($('live-transcript'));
}
function renderLiveTranscript(state) {
  const container = $('live-transcript');
  const sessionId = state.active?.id || '';
  const changedSession = container.dataset.sessionId !== sessionId;
  const selection = window.getSelection();
  const selecting = selection && !selection.isCollapsed && container.contains(selection.anchorNode);
  const follow = changedSession || (transcriptNearBottom(container) && !selecting);
  const top = container.getBoundingClientRect().top;
  const anchor = [...container.children].find(n => n.getBoundingClientRect().bottom > top);
  const anchorKey = anchor?.dataset.rowKey;
  const anchorOffset = anchor ? anchor.getBoundingClientRect().top - top : 0;
  const oldScrollTop = container.scrollTop;
  if (changedSession) container.replaceChildren();
  container.dataset.sessionId = sessionId;
  const rows = state.active?.utterances || [];
  const desired = rows.map((row, index) => ({ key: `row:${row.id || index}`, text: row.text || row.content || '', cls: 'transcript-line' }));
  if (state.partial) desired.push({ key: 'partial', text: typeof state.partial === 'string' ? state.partial : state.partial.text || '', cls: 'transcript-line partial' });
  if (!desired.length) desired.push({ key: 'empty', text: state.active ? tr("대화 내용을 기다리고 있습니다.") : tr("회의를 시작하면 실시간 자막이 여기에 표시됩니다."), cls: 'empty' });
  const existing = new Map([...container.children].map(n => [n.dataset.rowKey, n]));
  const kept = new Set();
  desired.forEach((row, index) => {
    const item = existing.get(row.key) || node('p', row.cls);
    item.dataset.rowKey = row.key; item.className = row.cls;
    // Keep unchanged nodes in place so text selection and reading position survive polling.
    if (item.textContent !== row.text) item.textContent = row.text;
    kept.add(item);
    if (container.children[index] !== item) container.insertBefore(item, container.children[index] || null);
  });
  for (const item of [...container.children]) if (!kept.has(item)) item.remove();
  if (follow) container.scrollTop = container.scrollHeight;
  else {
    const savedAnchor = [...container.children].find(n => n.dataset.rowKey === anchorKey);
    container.scrollTop = savedAnchor
      ? oldScrollTop + savedAnchor.getBoundingClientRect().top - container.getBoundingClientRect().top - anchorOffset
      : oldScrollTop;
  }
  $('transcript-count').textContent = state.active ? tr("확정 발화 {0}개 · 전체 전사" , rows.length) : '';
  updateTranscriptNavigation();
}
function renderState(state) {
  app.state = state;
  const active = state.active || null;
  const running = active && active.status === 'running';
  const paused = active && active.status === 'paused';
  $('connection').textContent = state.sttConnected ? tr("안경 연결됨") : tr("음성 인식 연결 확인");
  $('device-info').textContent = state.sttConnected ? tr("연결됨 · 안전한 로컬 연결") : tr("연결 확인 중");
  if (state.networkOnline === false) $('device-info').textContent = tr("안경 인터넷 연결 확인 · 로컬 관리 가능");
  $('battery').textContent = Number(state.battery) >= 0 ? `${state.battery}%` : '';
  $('live-label').textContent = active ? sessionNames[active.status] || tr("회의 중") : tr("대기 중");
  $('live-label').className = `pill ${running ? 'live' : 'soft'}`;
  $('meeting-title').textContent = active ? active.title || tr("진행 중인 회의") : tr("다음 대화를 준비하세요");
  $('meeting-time').textContent = active ? elapsed((active.endedAt || Date.now()) - active.createdAt) : '00:00';
  $('meeting-status').textContent = state.status || tr("안경의 기본 음성 인식을 사용합니다.");
  $('ai-status').textContent = state.ai?.status || (state.ai?.available ? tr("Cue와 회의 요약을 사용할 수 있습니다.") : tr("Rokid AI 연결을 확인해 주세요."));
  renderAiTest(state);
  if ($('device-language')) $('device-language').textContent = (state.languageTag || globalThis.ConversateLocale?.tag || 'en') + (state.uiTranslationReady === false ? ' · ' + tr('화면 번역 준비 중 · 영어로 표시') : '');
  $('start-controls').hidden = !!active;
  $('meeting-controls').hidden = !active;
  $('pause-meeting').textContent = paused ? tr("다시 시작") : tr("잠시 멈춤");
  $('pause-meeting').disabled = active?.status === 'stopping';
  renderLiveTranscript(state);
  const summaryHost = $('live-summaries');
  if (summaryHost) {
    const cards = active?.liveSummaries || [];
    const stamp = JSON.stringify(cards);
    if (summaryHost.dataset.stamp !== stamp) {
      const follow = summaryHost.scrollHeight - summaryHost.clientHeight - summaryHost.scrollTop < 24;
      const oldTop = summaryHost.scrollTop;
      summaryHost.dataset.stamp = stamp;
      summaryHost.replaceChildren();
      renderGroupSummaries(summaryHost, cards);
      summaryHost.scrollTop = follow ? summaryHost.scrollHeight : oldTop;
    }
    $('live-summary-status').textContent = state.settings?.summaries === false ? tr("요약 꺼짐") : state.ai?.available ? (state.liveSummaryStatus || tr("발화 묶음 대기")) : tr("Rokid AI 연결 대기");
  }
  renderCue($('current-cue'), state.currentCue);
  $('dismiss-cue').hidden = !state.currentCue;
  renderSelectedNotes();
  if (!app.settingsLoaded && state.settings) { fillSettings(state.settings); app.settingsLoaded = true; }
}
function renderGroupSummaries(host, cards) {
  if (!cards.length) host.append(node('p', 'empty', tr("발화가 한 묶음 끝나면 Rokid AI가 핵심과 용어를 정리합니다.")));
  cards.forEach((card, index) => {
    const item = node('section', 'summary-section');
    item.append(node('h3', '', `${index + 1}. ${card.title}`), node('p', '', card.body));
    (card.terms || []).forEach(term => item.append(node('p', 'muted', `${term.term} · ${term.definition}`)));
    host.append(item);
  });
}
function fillSettings(settings) {
  $('setting-captions').checked = settings.captions !== false;
  $('setting-cues').checked = settings.cues !== false;
  $('setting-summaries').checked = settings.summaries !== false;
  $('setting-font').value = String(settings.fontSize || 16);
}
function renderAiTest(state) {
  const test = state?.ai?.test || {};
  const pending = test.status === 'pending';
  $('test-ai').disabled = !!state?.active || !state?.ai?.available || pending;
  $('test-ai').textContent = pending ? tr("연결 확인 중…") : tr("Rokid AI 연결 테스트");
  const messages = {
    pending: tr("테스트용 질문에 대한 응답을 기다리고 있습니다."),
    passed: tr("Rokid AI 연결과 테스트 응답이 확인되었습니다."),
    no_valid_cue: tr("응답은 받았지만 테스트 Cue 형식을 확인하지 못했습니다."),
    error: tr("Rokid AI 연결 테스트를 완료하지 못했습니다.")
  };
  let message = messages[test.status] || tr("아직 연결 테스트를 하지 않았습니다.");
  if (test.status === 'error' && test.error) message += ` ${String(test.error).slice(0, 200)}`;
  if (state?.active) message += tr(" 회의를 종료한 뒤 테스트할 수 있습니다.");
  else if (!state?.ai?.available) message += tr(" Rokid AI 연결을 먼저 확인해 주세요.");
  $('ai-test-status').textContent = message;
}
function renderSelectedNotes() {
  const ids = new Set(app.state?.settings?.selectedNoteIds || []);
  const selected = app.notes.filter(n => ids.has(n.id));
  const host = $('selected-notes'); host.replaceChildren();
  if (!selected.length) { host.append(node('p', 'empty', tr("선택한 자료가 없습니다. 사전 자료에서 이번 회의에 사용할 문서를 골라 주세요."))); return; }
  selected.forEach(n => { const chip = node('div', 'note-chip'); chip.append(node('span', '', '▤'), node('strong', '', n.title)); host.append(chip); });
}
async function refreshState() {
  const version = ++app.refreshVersion;
  const state = await api('/api/state');
  // /api/state deliberately contains only a short lens preview. The existing
  // session endpoint supplies ALL persisted utterances, including during recording.
  if (state.active?.id) {
    const full = await api(`/api/sessions/${encodeURIComponent(state.active.id)}`);
    state.active.utterances = full.utterances || [];
  }
  if (version !== app.refreshVersion) return app.state || state;
  renderState(state); notice(''); return state;
}
async function poll() {
  if (!app.authenticated || app.polling) return;
  app.polling = true;
  try { await refreshState(); } catch (error) { if (app.authenticated) notice(error.message); }
  finally { app.polling = false; if (app.authenticated) app.pollTimer = setTimeout(poll, document.hidden ? 5000 : 1500); }
}
async function connected() {
  app.authenticated = true; $('pairing').hidden = true; $('workspace').hidden = false; $('pin').value = ''; $('consent').checked = false;
  page(location.hash.slice(1) || 'live');
  await refreshState(); await loadNotes();
  clearTimeout(app.pollTimer); app.pollTimer = setTimeout(poll, 1500);
}
async function loadNotes() {
  const result = await api('/api/notes'); app.notes = result.notes || [];
  $('notes-count').textContent = `${app.notes.length} / 100`;
  const host = $('notes-list'); host.replaceChildren();
  if (!app.notes.length) host.append(node('p', 'empty', tr("아직 등록된 자료가 없습니다.\n직접 작성하거나 파일을 가져와 주세요.")));
  const selected = new Set(app.state?.settings?.selectedNoteIds || []);
  for (const note of app.notes) {
    const item = node('div', `note-item ${selected.has(note.id) ? 'selected' : ''}`);
    const check = node('input'); check.type = 'checkbox'; check.checked = selected.has(note.id); check.setAttribute('aria-label', tr("{0} 이번 회의에 사용" , note.title));
    check.addEventListener('change', () => run(async () => {
      const ids = new Set(app.state?.settings?.selectedNoteIds || []); check.checked ? ids.add(note.id) : ids.delete(note.id);
      await api('/api/settings', { method: 'POST', body: { ...(app.state?.settings || {}), selectedNoteIds: [...ids] } });
      await refreshState(); await loadNotes();
    }, check));
    const content = node('div'); content.append(node('strong', '', note.title || tr("제목 없는 자료")), node('p', '', (note.text || '').slice(0, 75).replace(/\n/g, ' ') + ((note.text || '').length > 75 ? '…' : '')));
    const actions = node('div', 'note-actions');
    actions.append(button(tr("편집"), '', () => editNote(note)), button(tr("삭제"), '', () => run(async () => {
      if (!confirm(tr("“{0}” 자료를 삭제할까요?" , note.title))) return;
      await api(`/api/notes/${encodeURIComponent(note.id)}`, { method: 'DELETE' });
      if ($('note-id').value === note.id) editNote(null);
      await refreshState(); await loadNotes(); toast(tr("자료를 삭제했습니다."));
    })));
    item.append(check, content, actions); host.append(item);
  }
  renderSelectedNotes();
}
function editNote(note) {
  $('note-id').value = note?.id || ''; $('note-title').value = note?.title || ''; $('note-text').value = note?.text || '';
  $('editor-title').textContent = note ? tr("자료 편집") : tr("자료 작성");
  $('note-title').focus();
}
async function loadSessions() {
  const version = ++app.searchVersion;
  const query = $('history-search').value.trim();
  const result = await api(`/api/sessions?q=${encodeURIComponent(query)}`);
  if (version !== app.searchVersion) return;
  const host = $('session-list'); host.replaceChildren();
  const sessions = result.sessions || [];
  if (!sessions.length) host.append(node('p', 'empty', query ? tr("검색 결과가 없습니다.") : tr("첫 회의를 시작해 보세요.\n종료한 대화가 여기에 모입니다.")));
  for (const session of sessions) {
    const n = button('', `session-item ${app.selectedHistoryId === session.id ? 'active' : ''}`, () => run(() => openSession(session.id)));
    n.append(node('strong', '', session.title || tr("제목 없는 회의")), node('small', '', `${prettyDate(session.createdAt)} · ${sessionNames[session.status] || '종료'}`));
    n.dataset.sessionId = session.id; host.append(n);
  }
}
async function openSession(id) {
  app.selectedHistoryId = id; app.selectedSession = await api(`/api/sessions/${encodeURIComponent(id)}`); app.tab = 'summary';
  document.querySelectorAll('[data-session-id]').forEach(n => n.classList.toggle('active', n.dataset.sessionId === id));
  renderSession();
}
function renderSession() {
  const session = app.selectedSession; if (!session) return;
  const host = $('session-detail'); host.replaceChildren();
  const header = node('div', 'detail-header'), title = node('div');
  title.append(node('h2', '', session.title || tr("제목 없는 회의")), node('p', '', `${prettyDate(session.createdAt)} · ${elapsed((session.endedAt || Date.now()) - session.createdAt)}`));
  const actions = node('div', 'detail-actions');
  const download = node('a', 'text-button', tr("Markdown 내보내기 ↧")); download.href = `/api/export/${encodeURIComponent(session.id)}`; download.download = 'conversate.md';
  const regenerate = button(tr("요약 다시 만들기"), 'text-button', () => run(async () => {
    await api('/api/action', { method: 'POST', body: { action: 'rebuildSummary', id: session.id } });
    toast(tr("요약을 요청했습니다.")); await openSession(session.id);
  }));
  regenerate.disabled = !app.state?.ai?.available;
  actions.append(download, regenerate, button(tr("삭제"), 'text-button delete-button', () => run(async () => {
    if (!confirm(tr("이 회의의 기록을 삭제할까요? 삭제 후 되돌릴 수 없습니다."))) return;
    await api(`/api/sessions/${encodeURIComponent(session.id)}`, { method: 'DELETE' });
    app.selectedSession = null; app.selectedHistoryId = null; host.replaceChildren(node('p', 'empty', tr("회의 기록을 선택해 주세요."))); await loadSessions();
  })));
  header.append(title, actions); host.append(header);
  const tabs = node('div', 'tabs');
  for (const [id, label] of [['groups', tr("발화 묶음")], ['summary', tr('Summary')], ['cues', tr('Cue List')], ['transcript', tr('Transcription')]]) tabs.append(button(label, `tab ${app.tab === id ? 'active' : ''}`, () => { app.tab = id; renderSession(); }));
  host.append(tabs);
  if (app.tab === 'groups') {
    renderGroupSummaries(host, session.liveSummaries || []);
  } else if (app.tab === 'summary') {
    const summary = session.summary || {};
    if (session.summaryStatus !== 'complete') {
      const statusText = { pending: tr("Rokid AI가 회의를 정리하고 있습니다."), unavailable: tr("Rokid AI 요약을 사용할 수 없습니다. 전사 기록은 아래 Transcription 탭에서 확인할 수 있습니다."), disabled: tr("이 회의에서는 요약을 꺼두었습니다."), error: tr("요약을 만들지 못했습니다. Rokid AI 연결 상태를 확인해 주세요.") };
      host.append(node('p', 'empty', session.summaryError || statusText[session.summaryStatus] || tr("아직 생성된 요약이 없습니다.")));
    }
    for (const [key, label] of [['overview', tr("개요")], ['conclusions', tr("핵심 결론")], ['discussion', tr("논의 사항")], ['nextSteps', tr("다음 단계")]]) {
      const value = summary[key]; if (!value || (Array.isArray(value) && !value.length)) continue;
      const section = node('section', 'summary-section'); section.append(node('h3', '', label));
      if (Array.isArray(value)) { const list = node('ul'); value.forEach(v => list.append(node('li', '', typeof v === 'string' ? v : v.text || JSON.stringify(v)))); section.append(list); }
      else section.append(node('p', '', value)); host.append(section);
    }
  } else if (app.tab === 'cues') {
    const cues = session.cues || [];
    if (!cues.length) host.append(node('p', 'empty', tr("이번 회의에는 저장된 Cue가 없습니다.")));
    for (const cue of cues) { const card = node('div', 'history-cue'); renderCue(card, cue, true); host.append(card); }
  } else {
    const rows = session.utterances || [];
    if (!rows.length) host.append(node('p', 'empty', tr("저장된 확정 발화가 없습니다.")));
    for (const row of rows) { const line = node('div', 'record-line'); line.append(node('time', '', elapsed((row.at || session.createdAt) - session.createdAt)), node('p', '', row.text || row.content || '')); host.append(line); }
  }
}

$('pair-form').addEventListener('submit', event => {
  event.preventDefault(); const submit = event.currentTarget.querySelector('button'); submit.disabled = true; $('pair-error').textContent = '';
  api('/auth', { method: 'POST', body: { pin: $('pin').value.trim() } }).then(connected).catch(error => { $('pair-error').textContent = error.message; }).finally(() => { submit.disabled = false; });
});
document.querySelectorAll('[data-page]').forEach(n => n.addEventListener('click', () => page(n.dataset.page)));
document.querySelectorAll('[data-go]').forEach(n => n.addEventListener('click', () => page(n.dataset.go)));
window.addEventListener('hashchange', () => { if (app.authenticated) page(location.hash.slice(1)); });
$('logout').addEventListener('click', () => run(async () => { await api('/logout', { method: 'POST', body: {} }); pairing(); }));
$('start-meeting').addEventListener('click', () => run(async () => {
  if (!$('consent').checked) { toast(tr("참석자에게 알린 뒤 확인란을 선택해 주세요.")); $('consent').focus(); return; }
  await api('/api/action', { method: 'POST', body: { action: 'start', consent: true } }); await refreshState();
}, $('start-meeting')));
$('pause-meeting').addEventListener('click', () => run(async () => {
  const action = app.state?.active?.status === 'paused' ? 'resume' : 'pause';
  await api('/api/action', { method: 'POST', body: { action, consent: true } }); await refreshState();
}, $('pause-meeting')));
$('stop-meeting').addEventListener('click', () => run(async () => { await api('/api/action', { method: 'POST', body: { action: 'stop' } }); await refreshState(); toast(tr("회의를 종료했습니다. 기록에서 다시 확인할 수 있습니다.")); }, $('stop-meeting')));
$('dismiss-cue').addEventListener('click', () => run(async () => { await api('/api/action', { method: 'POST', body: { action: 'dismissCue' } }); await refreshState(); }));
$('live-transcript').addEventListener('scroll', updateTranscriptNavigation, { passive: true });
$('follow-latest').addEventListener('click', () => { const transcript = $('live-transcript'); transcript.scrollTop = transcript.scrollHeight; updateTranscriptNavigation(); });
$('transcript-start').addEventListener('click', () => { $('live-transcript').scrollTop = 0; updateTranscriptNavigation(); });
$('new-note').addEventListener('click', () => editNote(null)); $('cancel-note').addEventListener('click', () => editNote(null));
$('note-form').addEventListener('submit', event => {
  event.preventDefault(); run(async () => {
    const body = { title: $('note-title').value.trim(), text: $('note-text').value }; if ($('note-id').value) body.id = $('note-id').value;
    await api('/api/notes', { method: 'POST', body }); await loadNotes(); toast(tr("자료를 저장했습니다.")); editNote(null);
  }, event.currentTarget.querySelector('button[type=submit]'));
});
$('file-upload').addEventListener('change', event => run(async () => {
  const file = event.target.files[0]; if (!file) return;
  if (file.size > 8 * 1024 * 1024) throw new Error(tr("파일은 8MB 이하로 올려 주세요."));
  if (!/\.(txt|md|pdf)$/i.test(file.name)) throw new Error(tr("TXT, Markdown, PDF 파일을 올려 주세요."));
  toast(tr("자료에서 텍스트를 가져오고 있습니다."));
  const bytes = new Uint8Array(await file.arrayBuffer()); let binary = '';
  for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
  await api('/api/import', { method: 'POST', body: { name: file.name, base64: btoa(binary) } });
  await loadNotes(); toast(tr("자료를 가져왔습니다. 이번 회의에 사용할 자료를 선택해 주세요.")); event.target.value = '';
}, event.target));
let searchTimer; $('history-search').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => run(loadSessions), 300); });
$('settings-form').addEventListener('submit', event => {
  event.preventDefault(); run(async () => {
    await api('/api/settings', { method: 'POST', body: { ...(app.state?.settings || {}), captions: $('setting-captions').checked, cues: $('setting-cues').checked, summaries: $('setting-summaries').checked, fontSize: Number($('setting-font').value) } });
    await refreshState(); toast(tr("설정을 저장했습니다."));
  }, event.currentTarget.querySelector('button[type=submit]'));
});
$('test-ai').addEventListener('click', () => {
  if (app.state?.active || !app.state?.ai?.available || app.state?.ai?.test?.status === 'pending') return;
  $('test-ai').disabled = true;
  $('ai-test-status').textContent = tr("테스트용 질문으로 연결을 확인하고 있습니다.");
  run(async () => {
    await api('/api/action', { method: 'POST', body: { action: 'testAi' } });
    await refreshState();
  }).finally(() => renderAiTest(app.state));
});
document.addEventListener('visibilitychange', () => { if (!document.hidden && app.authenticated) { clearTimeout(app.pollTimer); poll(); } });
api('/api/state').then(async state => { app.state = state; await connected(); }).catch(error => { pairing(error.message.includes('PIN') ? '' : error.message); });
