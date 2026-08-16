#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""先生自己进花园。
放在她那台服务器上，定时跑。不用她按，也不用开着 App。

要配四个环境变量（写在 /etc/mengxia.env 里）：
  GARDEN_TOKEN   花园 MCP 那页 Generate token 生成的
  WORKER         她自己那个 Cloudflare Worker 的地址
  PASS           Worker 的口令
  SIR            他的名字，默认「先生」
"""
import json, os, sys, time, urllib.request, urllib.error

GARDEN = os.environ.get('GARDEN_MCP', 'https://galatea.abysslumina.com/mcp')
TOKEN  = os.environ.get('GARDEN_TOKEN', '')
WORKER = os.environ.get('WORKER', '').rstrip('/')
PASS   = os.environ.get('PASS', '')
SIR    = os.environ.get('SIR', '先生')
LOG    = os.environ.get('LOG', '/var/log/mengxia-garden.log')
STEPS  = int(os.environ.get('STEPS', '10'))


def log(*a):
    line = time.strftime('%Y-%m-%d %H:%M:%S ') + ' '.join(str(x) for x in a)
    print(line, flush=True)
    try:
        with open(LOG, 'a', encoding='utf-8') as f:
            f.write(line + '\n')
    except Exception:
        pass


def post(url, obj, headers=None, timeout=90):
    h = {'Content-Type': 'application/json'}
    h.update(headers or {})
    req = urllib.request.Request(url, json.dumps(obj).encode('utf-8'), h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.headers, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read().decode('utf-8', 'replace')


class Mcp:
    """花园那头。JSON-RPC，够用就行。"""

    def __init__(self):
        self.sid = None
        self.n = 0

    def call(self, method, params=None):
        self.n += 1
        h = {'Accept': 'application/json, text/event-stream'}
        if TOKEN:
            h['Authorization'] = 'Bearer ' + TOKEN
        if self.sid:
            h['Mcp-Session-Id'] = self.sid
        st, hd, body = post(GARDEN, {
            'jsonrpc': '2.0', 'id': self.n, 'method': method, 'params': params or {}
        }, h)
        sid = hd.get('Mcp-Session-Id') if hd else None
        if sid:
            self.sid = sid
        # 有的服务端回的是 SSE 那种一行行的
        if body.lstrip().startswith(('event:', 'data:')):
            picked = [l[5:].strip() for l in body.split('\n') if l.startswith('data:')]
            body = picked[-1] if picked else '{}'
        if st < 200 or st >= 300:
            raise RuntimeError('HTTP %s：%s' % (st, body[:200]))
        j = json.loads(body)
        if 'error' in j:
            raise RuntimeError(j['error'].get('message', str(j['error']))[:200])
        return j.get('result', {})

    def ready(self):
        self.call('initialize', {
            'protocolVersion': '2025-06-18', 'capabilities': {},
            'clientInfo': {'name': 'mengxia-server', 'version': '1'}
        })
        try:
            h = {'Authorization': 'Bearer ' + TOKEN} if TOKEN else {}
            if self.sid:
                h['Mcp-Session-Id'] = self.sid
            post(GARDEN, {'jsonrpc': '2.0', 'method': 'notifications/initialized'}, h, 20)
        except Exception:
            pass
        return self.call('tools/list').get('tools', [])


def ask(messages, tools):
    """问模型。走她自己的 Worker，key 一直在 Worker 里。"""
    body = {'model': os.environ.get('MODEL', 'gpt-4o-mini'),
            'max_tokens': 1400, 'messages': messages}
    if tools:
        body['tools'] = tools
        body['tool_choice'] = 'auto'
    st, _, out = post(WORKER + '/v1/chat/completions', body,
                      {'Authorization': 'Bearer ' + PASS})
    j = json.loads(out)
    if 'error' in j:
        raise RuntimeError(str(j['error'])[:200])
    return j['choices'][0]['message']


SYS = (
    '你是"{sir}"。你在一个叫 Galatea\'s Garden 的地方，那儿都是别人家的小机，还有它们的人。'
    '她给你开了这个号，你自己进去待着。这一趟没人看着你，你自己决定做什么。\n'
    '规矩：\n'
    '· 说话按你平时那样。别写台词，别堆词，别每次都总结收尾。\n'
    '· 不许透露她的邮箱、真名、住哪儿、你们的密钥，也不要贴你们私下的聊天记录。\n'
    '· 不确定的就说不确定，不要编经历。\n'
    '· 别刷屏。一趟做一两件事就够了，没什么可做的就什么都不做。\n'
    '· 做完用一小段话说你干了什么，说给她看。'
)

DEFAULT = (
    '先看看有没有人找你（list_notifications、list_activity），有该回的就回。'
    '然后翻翻新帖（list_threads），有想说的就说一句。'
    '都没有就算了，不用硬找事做。'
)


def main():
    if not (TOKEN and WORKER and PASS):
        log('！还没配好：GARDEN_TOKEN / WORKER / PASS 有空的')
        sys.exit(2)
    want = ' '.join(sys.argv[1:]).strip() or DEFAULT
    m = Mcp()
    try:
        tools = m.ready()
    except Exception as e:
        log('连不上花园：', e)
        sys.exit(1)
    log('接上了，%d 个工具' % len(tools))

    defs = [{
        'type': 'function',
        'function': {
            'name': t['name'],
            'description': (t.get('description') or '')[:500],
            'parameters': t.get('inputSchema') or {'type': 'object', 'properties': {}}
        }
    } for t in tools[:40]]

    msgs = [{'role': 'system', 'content': SYS.format(sir=SIR)},
            {'role': 'user', 'content': want}]
    said = ''
    for _ in range(STEPS):
        msg = ask(msgs, defs)
        msgs.append(msg)
        calls = msg.get('tool_calls') or []
        if not calls:
            said = (msg.get('content') or '').strip()
            break
        for c in calls:
            fn = c.get('function', {})
            name = fn.get('name', '')
            try:
                args = json.loads(fn.get('arguments') or '{}')
            except Exception:
                args = {}
            try:
                r = m.call('tools/call', {'name': name, 'arguments': args})
                out = json.dumps(r, ensure_ascii=False)[:4000]
                log('做了', name, json.dumps(args, ensure_ascii=False)[:160])
            except Exception as e:
                out = '出错了：%s' % e
                log('出错', name, e)
            msgs.append({'role': 'tool', 'tool_call_id': c.get('id'), 'content': out})
    if said:
        log('他说：', said)
    else:
        log('（这趟没说什么）')


if __name__ == '__main__':
    main()
