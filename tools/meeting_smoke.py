"""Real-device companion smoke test; PIN is entered interactively or via --pin.
Only synthetic Prep Notes and spoken test input should be used with this script.
"""
import argparse, base64, http.cookiejar, json, pathlib, ssl, time, urllib.request, urllib.error

parser = argparse.ArgumentParser()
parser.add_argument('--pin', required=True)
parser.add_argument('--phase', choices=['setup', 'state', 'stop', 'history', 'cleanup', 'ai-test', 'concept-test', 'pdf-test', 'recap-test', 'lifecycle'], default='state')
args = parser.parse_args()
root = pathlib.Path(__file__).parent / 'out' / 'meeting'
root.mkdir(parents=True, exist_ok=True)
# adb-forwarded, device-owned self-signed certificate; production browser uses its own explicit trust choice.
context = ssl._create_unverified_context()
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context), urllib.request.HTTPCookieProcessor(cookies))
base = 'https://127.0.0.1:8443'
def request(path, method='GET', data=None, origin=True):
    headers = {'Origin': base} if origin else {}
    if data is not None: headers['Content-Type'] = 'application/json'
    body = json.dumps(data, ensure_ascii=False).encode() if data is not None else None
    with client.open(urllib.request.Request(base+path, body, headers, method=method), timeout=15) as response:
        raw = response.read().decode()
        return json.loads(raw) if 'json' in response.headers.get('Content-Type', '') else raw
request('/auth', 'POST', {'pin': args.pin})
checkpoint = root / 'smoke-session.json'
if args.phase == 'setup':
    original = request('/api/settings')
    try:
        request('/api/action', 'POST', {'action': 'start', 'consent': False})
        raise AssertionError('start without consent was accepted')
    except urllib.error.HTTPError as failure: assert failure.code == 400
    try:
        request('/api/settings', 'POST', {'fontSize': 18}, origin=False)
        raise AssertionError('mutation without Origin accepted')
    except urllib.error.HTTPError as failure: assert failure.code == 403
    note = request('/api/notes', 'POST', {'title':'검증용 회의 자료', 'text':'프로젝트 파일럿 출시일은 다음 주 화요일입니다. 담당자는 김민수입니다. 예산은 100만 원입니다.'})
    assert note['title'] == '검증용 회의 자료' and '화요일' in note['text'] and '\ufffd' not in note['text']
    imported = request('/api/import', 'POST', {'name':'검증용.txt','base64':base64.b64encode('한국어 TXT 업로드 확인'.encode()).decode()})
    assert imported['text'] == '한국어 TXT 업로드 확인'
    request('/api/settings', 'POST', {'selectedNoteIds':[note['id']], 'captions':True,'cues':True,'summaries':True})
    state = request('/api/action', 'POST', {'action':'start','consent':True})
    assert state['active']['status'] == 'running'
    result = {'id':state['active']['id'],'notes':[note['id'],imported['id']],'originalSettings':original}
    checkpoint.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'started':result['id'],'ai':state['ai'],'noteSnapshotCount':len(state['active']['noteSnapshots'])},ensure_ascii=False))
elif args.phase == 'state':
    state = request('/api/state')
    (root/'live-state.json').write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
    active = state['active']
    print(json.dumps({'status':state['status'],'ai':state['ai'],'active':None if active is None else {'id':active['id'],'status':active['status'],'utterances':active['utterances'],'cues':active['cues'],'summaryStatus':active['summaryStatus']}},ensure_ascii=False))
elif args.phase == 'stop':
    request('/api/action','POST',{'action':'stop'})
    print('stop requested')
elif args.phase == 'history':
    saved=json.loads(checkpoint.read_text(encoding='utf-8'))
    record=request('/api/sessions/'+saved['id'])
    (root/'smoke-record.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    markdown=request('/api/export/'+saved['id'])
    (root/'smoke-record.md').write_text(markdown,encoding='utf-8')
    print(json.dumps({'id':record['id'],'title':record['title'],'utterances':len(record['utterances']),'cues':len(record['cues']),'summaryStatus':record['summaryStatus'],'summary':record['summary']},ensure_ascii=False))
elif args.phase == 'cleanup':
    saved=json.loads(checkpoint.read_text(encoding='utf-8'))
    request('/api/settings','POST',saved['originalSettings'])
    for id in saved['notes']: request('/api/notes/'+id,'DELETE')
    print('synthetic prep notes removed; original settings restored; test meeting retained')
elif args.phase in ('ai-test', 'concept-test'):
    state = request('/api/state')
    assert state['ai']['available'], state['ai']
    request('/api/action','POST',{'action':'testConceptCue' if args.phase=='concept-test' else 'testAi'})
    deadline = time.monotonic()+20
    while time.monotonic()<deadline:
        state=request('/api/state')
        result=state['ai'].get('test',{})
        if result.get('status')!='pending':
            (root/('concept-cue-test.json' if args.phase=='concept-test' else 'ai-connection-test.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
            print(json.dumps(result,ensure_ascii=False))
            assert result['status']=='passed', result
            assert '\ufffd' not in json.dumps(result,ensure_ascii=False)
            break
        time.sleep(1)
    else: raise AssertionError('AI test did not finish')
elif args.phase == 'pdf-test':
    # Small valid text PDF fixture. No user file or real meeting data.
    stream=b'BT /F1 12 Tf 30 150 Td (Synthetic PDF import 100) Tj ET'
    objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',b'<< /Length '+str(len(stream)).encode()+b' >>\nstream\n'+stream+b'\nendstream']
    pdf=b'%PDF-1.4\n'; offsets=[0]
    for i,obj in enumerate(objects,1): offsets.append(len(pdf)); pdf+=str(i).encode()+b' 0 obj\n'+obj+b'\nendobj\n'
    xref=len(pdf); pdf+=b'xref\n0 6\n0000000000 65535 f \n'
    for offset in offsets[1:]: pdf+=f'{offset:010d} 00000 n \n'.encode()
    pdf+=b'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n'+str(xref).encode()+b'\n%%EOF\n'
    imported=request('/api/import','POST',{'name':'검증용.pdf','base64':base64.b64encode(pdf).decode()})
    assert '[p.1]' in imported['text'] and 'Synthetic PDF import 100' in imported['text'], imported
    request('/api/notes/'+imported['id'],'DELETE')
    print('real-device PDF text extraction + page reference + deletion passed')
elif args.phase == 'recap-test':
    saved=json.loads(checkpoint.read_text(encoding='utf-8'))
    request('/api/action','POST',{'action':'rebuildSummary','id':saved['id']})
    deadline=time.monotonic()+65
    while time.monotonic()<deadline:
        record=request('/api/sessions/'+saved['id'])
        if record['summaryStatus']!='pending':
            assert record['summaryStatus']=='complete', record['summaryStatus']
            summary=json.dumps(record['summary'],ensure_ascii=False)
            assert '\ufffd' not in summary
            transcript='\n'.join(u['text'] for u in record['utterances'])
            if '100만' not in transcript: assert '100만' not in summary, 'Prep-only budget incorrectly appeared as discussion'
            (root/'grounded-recap.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
            print(json.dumps({'summaryStatus':record['summaryStatus'],'title':record['title'],'summary':record['summary']},ensure_ascii=False))
            break
        time.sleep(1)
    else: raise AssertionError('recap did not finish')
elif args.phase == 'lifecycle':
    original=request('/api/settings')
    id=None
    try:
        request('/api/settings','POST',{'summaries':False,'cues':False})
        state=request('/api/action','POST',{'action':'start','consent':True})
        id=state['active']['id']
        start=time.monotonic()
        state=request('/api/action','POST',{'action':'pause'})
        assert state['active']['status']=='stopping'
        while time.monotonic()-start<6:
            state=request('/api/state')
            if state['active']['status']=='paused': break
            time.sleep(.2)
        assert state['active']['status']=='paused'
        time.sleep(max(0,3.2-(time.monotonic()-start)))
        state=request('/api/action','POST',{'action':'resume'})
        assert state['active']['status']=='running'
        state=request('/api/action','POST',{'action':'stop'})
        assert state['active']['status']=='stopping'
        deadline=time.monotonic()+6
        while time.monotonic()<deadline:
            state=request('/api/state')
            if state['active'] is None: break
            time.sleep(.2)
        assert state['active'] is None
        record=request('/api/sessions/'+id)
        assert record['status']=='ended' and record['summaryStatus']=='disabled'
        print('real-device start -> drain -> paused -> resume -> drain -> ended passed')
    finally:
        state=request('/api/state')
        if state['active'] and id==state['active']['id']:
            request('/api/action','POST',{'action':'stop'}); time.sleep(3)
        request('/api/settings','POST',original)
        if id: request('/api/sessions/'+id,'DELETE')
