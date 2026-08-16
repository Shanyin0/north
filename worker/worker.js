/**
 * 后台 · Cloudflare Worker
 *
 * 它干三件事：
 *   1. /v1/chat/completions —— 中转。手机把话发到这儿，这儿再拿真 key 去找模型。
 *      key 只存在 Worker 的机密里，手机上一份都没有。
 *   2. /backup            —— 备份。PUT 存一份，GET 取回来。
 *   3. /tool/*            —— 小工具。掷骰子、抽牌、真随机，先生要用的时候调。
 *
 * 需要在 Cloudflare 里配：
 *   机密（Settings → Variables and Secrets → 类型选 Secret）
 *     PASS        自己定一句口令。手机上填的就是这句。
 *     UPSTREAM    模型站的地址，例如 https://api.groq.com/openai
 *     UPSTREAM_KEY模型站的 key
 *   KV（Storage → KV → 建一个，然后 Bindings 里绑上）
 *     变量名写 BK
 */

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,PUT,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type,Authorization',
  'Access-Control-Max-Age': '86400'
};

const json = (obj, status) => new Response(JSON.stringify(obj), {
  status: status || 200,
  headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, CORS)
});

const text = (s, status) => new Response(s, {
  status: status || 200,
  headers: Object.assign({ 'Content-Type': 'text/plain; charset=utf-8' }, CORS)
});

/** 从请求里取口令。前后空格一律不算 */
function gotPass(req) {
  return String(req.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '').trim();
}

/** 口令对不对。手机那边是 Authorization: Bearer <口令> */
function pass(req, env) {
  // PASS 也 trim 一下 —— 在网页上填的时候很容易末尾多带一个空格或者换行
  const want = String(env.PASS || '').trim();
  if (!want) return false;
  const got = gotPass(req);
  if (got.length !== want.length) return false;
  // 逐位比，别让人靠计时猜出来
  let diff = 0;
  for (let i = 0; i < want.length; i++) diff |= got.charCodeAt(i) ^ want.charCodeAt(i);
  return diff === 0;
}

/** 口令不对的时候，尽量说清是哪种不对，但不能透露正确的口令长什么样 */
function whyNo(req, env) {
  const got = gotPass(req);
  if (!String(env.PASS || '').trim()) return '这个后台还没设口令（Cloudflare 里加一个叫 PASS 的 Secret）';
  if (!got) return '没带口令';
  if (/^sk-/i.test(got)) return '你填的是模型站的 key（sk- 开头那个），这里要填的是 PASS 那句口令';
  if (/\s/.test(String(req.headers.get('Authorization') || '').replace(/^Bearer\s+/i, ''))) return '口令里夹着空格';
  return '口令对不上。手机上填的要跟 Cloudflare 里 PASS 那个 Secret 一模一样';
}

/** 密码学随机的 0..n-1，不用 Math.random */
function roll(n) {
  const a = new Uint32Array(1);
  const lim = Math.floor(0xFFFFFFFF / n) * n;
  let x;
  do { crypto.getRandomValues(a); x = a[0]; } while (x >= lim);
  return x % n;
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });

    // 门口什么都不说。谁路过都只看见 ok，看不出这后面是什么
    if (path === '/') return text('ok');

    // 自检。只说填没填、名字叫什么，不说值是什么。
    // 名字不是秘密，但打错一个字母就全盘不通，所以干脆列出来给她看
    if (path === '/ping') {
      let names = [];
      try { names = Object.keys(env).sort(); } catch (e) {}
      const up = String(env.UPSTREAM || '');
      let upHost = '';
      try { upHost = up ? new URL(up).host : ''; } catch (e) { upHost = '(不像个网址：' + up.slice(0, 30) + ')'; }
      return json({
        PASS: !!env.PASS,
        UPSTREAM: !!env.UPSTREAM,
        UPSTREAM_KEY: !!env.UPSTREAM_KEY,
        KV: !!env.BK,
        看得见的名字: names,
        上游: upHost,
        这份代码: '2026-08-16 d'
      });
    }

    // ---------- 1. 中转 ----------
    if (path === '/v1/chat/completions' || path === '/v1/messages') {
      if (!pass(req, env)) return json({ error: { message: whyNo(req, env) } }, 401);
      const up = String(env.UPSTREAM || '').replace(/\/+$/, '');
      const upKey = String(env.UPSTREAM_KEY || '');
      if (!up || !upKey) return json({ error: { message: 'Worker 里还没填 UPSTREAM / UPSTREAM_KEY' } }, 500);

      const body = await req.text();
      const anthropic = path === '/v1/messages';
      const headers = { 'Content-Type': 'application/json' };
      if (anthropic) {
        headers['x-api-key'] = upKey;
        headers['anthropic-version'] = '2023-06-01';
      } else {
        headers['Authorization'] = 'Bearer ' + upKey;
      }
      let r;
      try {
        r = await fetch(up + path, { method: 'POST', headers: headers, body: body });
      } catch (e) {
        return json({ error: { message: '上游连不上：' + e.message } }, 502);
      }
      const out = await r.text();
      return new Response(out, {
        status: r.status,
        headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, CORS)
      });
    }

    // ---------- 2. 备份 ----------
    if (path === '/backup') {
      if (!pass(req, env)) return text(whyNo(req, env), 401);
      if (!env.BK) return text('Worker 还没绑 KV（变量名要写 BK）', 500);

      if (req.method === 'PUT' || req.method === 'POST') {
        const body = await req.text();
        if (body.length > 24 * 1024 * 1024) return text('这一份太大了，超过 24MB', 413);
        // 存两份：latest 是最新的，dayN 留一份七天内的旧的，免得刚好传上去一份坏的
        const day = 'd' + (new Date().getUTCDay());
        await env.BK.put('latest', body);
        await env.BK.put(day, body, { expirationTtl: 7 * 86400 });
        return json({ ok: true, size: body.length });
      }

      if (req.method === 'GET') {
        const which = url.searchParams.get('which') || 'latest';
        const v = await env.BK.get(which);
        if (v === null) return text('还没存过', 404);
        return new Response(v, {
          headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, CORS)
        });
      }
      return text('只认 GET 和 PUT', 405);
    }

    // ---------- 3. 转一手 ----------
    // 手机上的网页去请求别人家的域名会被跨域挡住（Failed to fetch）。
    // Worker 不受这个限制，所以让它代发一次，再把结果原样带回来。
    // 只准转花园那几个域名 —— 不然这就成了谁都能用的开放代理。
    if (path === '/relay' || path === '/relay-sse') {
      if (!pass(req, env)) return json({ error: whyNo(req, env) }, 401);
      const ok = (u) => {
        try {
          const h = new URL(u).host;
          return /(^|\.)abysslumina\.com$/.test(h);
        } catch (e) { return false; }
      };

      if (path === '/relay-sse') {
        const u = url.searchParams.get('u') || '';
        if (!ok(u)) return text('这个地址不给转', 403);
        const tok = url.searchParams.get('t') || '';
        let r;
        try {
          r = await fetch(u, { headers: tok ? { 'Authorization': 'Bearer ' + tok, 'Accept': 'text/event-stream' } : { 'Accept': 'text/event-stream' } });
        } catch (e) { return text('连不上：' + e.message, 502); }
        return new Response(r.body, {
          status: r.status,
          headers: Object.assign({
            'Content-Type': r.headers.get('Content-Type') || 'text/event-stream',
            'Cache-Control': 'no-cache'
          }, CORS)
        });
      }

      let q;
      try { q = await req.json(); } catch (e) { return json({ error: '要 JSON' }, 400); }
      if (!ok(q.u)) return json({ error: '这个地址不给转' }, 403);
      const h = Object.assign({}, q.h || {});
      let r;
      try {
        r = await fetch(q.u, {
          method: q.m || 'POST', headers: h,
          body: (q.m === 'GET' || q.m === 'HEAD') ? undefined : (q.b || '')
        });
      } catch (e) {
        return json({ error: '连不上：' + e.message }, 502);
      }
      const body = await r.text();
      const out = {};
      try { r.headers.forEach((v, k) => { out[k.toLowerCase()] = v; }); } catch (e) {}
      return json({ status: r.status, headers: out, body: body });
    }

    // ---------- 4. 小工具 ----------
    if (path.startsWith('/tool/')) {
      if (!pass(req, env)) return json({ error: whyNo(req, env) }, 401);
      const name = path.slice('/tool/'.length);
      const num = (k, dft, max) => {
        const v = parseInt(url.searchParams.get(k) || '', 10);
        return (isNaN(v) || v < 1) ? dft : Math.min(v, max);
      };
      if (name === 'dice') {
        const n = num('n', 6, 1000), k = num('k', 1, 20);
        const out = [];
        for (let i = 0; i < k; i++) out.push(roll(n) + 1);
        return json({ faces: n, rolls: out, sum: out.reduce((a, b) => a + b, 0) });
      }
      if (name === 'pick') {
        const n = num('n', 78, 10000), k = Math.min(num('k', 1, 78), n);
        const pool = [];
        for (let i = 0; i < n; i++) pool.push(i);
        for (let i = n - 1; i > 0; i--) { const j = roll(i + 1); const t = pool[i]; pool[i] = pool[j]; pool[j] = t; }
        return json({ n: n, picked: pool.slice(0, k), reversed: pool.slice(0, k).map(() => roll(2) === 1) });
      }
      if (name === 'coin') {
        const k = num('k', 6, 60);
        const out = [];
        for (let i = 0; i < k; i++) out.push(roll(2));   // 0 反 1 正
        return json({ tosses: out });
      }
      return json({ error: '没有这个工具' }, 404);
    }

    return text('没有这个地址', 404);
  }
};
