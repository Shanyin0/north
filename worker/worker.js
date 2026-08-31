/**
 * 后台 · Cloudflare Worker
 *
 * 它干三件事：
 *   1. /v1/chat/completions —— 中转。手机把话发到这儿，这儿再拿真 key 去找模型。
 *      key 只存在 Worker 的机密里，手机上一份都没有。
 *   2. /backup            —— 整份备份。PUT 存一份（留 30 个版本，谁也盖不掉谁），
 *                          GET 取回来，/backup/list 看云上有哪几份。
 *   2.5 /chat/*           —— 聊天流水账。只追加，不改不删，重复自动认出来。
 *                          put 追加 / since 往回捞 / stat 看有几条 / dump 整段倒出来。
 *   3. /tool/*            —— 小工具。掷骰子、抽牌、真随机，先生要用的时候调。
 *
 * 需要在 Cloudflare 里配：
 *   机密（Settings → Variables and Secrets → 类型选 Secret）
 *     PASS        自己定一句口令。手机上填的就是这句。
 *     UPSTREAM    模型站的地址，例如 https://api.groq.com/openai
 *     UPSTREAM_KEY模型站的 key
 *   KV（Storage → KV → 建一个，然后 Bindings 里绑上）
 *     变量名写 BK
 *   D1（Storage → D1 → 建一个，然后 Bindings 里绑上）
 *     变量名写 DB。建完要执行一次建表语句，见 worker/README.md
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
        D1: !!env.DB,
        看得见的名字: names,
        上游: upHost,
        这份代码: '2026-08-31 g（备份留版本 + 聊天流水账 + 声音转一手 + 记忆独立域）'
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

    // D1 里那一行摊回手机认得的形状。extra 是整包塞进去的，取出来摊平。
    // 列名跟手机上那份 schema 一一对上 —— 以前这儿是 Phase 2 的老名字
    // （kind / text / weight / at），手机换过之后就对不上了，
    // 传上去的正文会变成空串
    const memRow = r => {
      let ex = {};
      try { ex = JSON.parse(r.extra || '{}') || {}; } catch (e) {}
      return Object.assign({
        id: r.id, rev: r.rev,
        type: r.type, title: r.title, content: r.content, summary: r.summary,
        importance: r.importance, confidence: r.confidence,
        valence: r.valence, arousal: r.arousal,
        resolved: !!r.resolved, pinned: !!r.pinned,
        createdAt: r.created_at, updatedAt: r.updated_at,
        del: r.del
      }, ex);
    };

    // ---------- 2. 备份 ----------
    // 以前这儿是「整份覆盖」：每次传上来直接盖掉 latest，另外按星期几留一份
    // 七天过期。云上最多八份，实际只有一周的历史；同一个星期几传两次，
    // 前一次当场没。传上去一份坏的，好的那份也就跟着没了。
    //
    // 现在改成留版本：每一份都带自己的时间戳单独存着，谁也盖不掉谁。
    // latest 只是个指针，指向最近那一份。
    const KEEP = 30;                        // 留最近 30 份
    const KEEP_MIN = 3;                     // 无论如何不动最近这 3 份

    if (path === '/backup' || path === '/backup/list') {
      if (!pass(req, env)) return text(whyNo(req, env), 401);
      if (!env.BK) return text('Worker 还没绑 KV（变量名要写 BK）', 500);

      // 云上现在有哪几份
      if (path === '/backup/list') {
        const ls = await env.BK.list({ prefix: 'bk:' });
        const rows = ls.keys.map(k => Object.assign(
          { id: k.name.slice(3) }, k.metadata || {}
        )).sort((a, b) => String(b.id).localeCompare(String(a.id)));
        let cur = null;
        try { cur = JSON.parse(await env.BK.get('latest_id') || 'null'); } catch (e) {}
        return json({ n: rows.length, latest: cur, list: rows });
      }

      if (req.method === 'PUT' || req.method === 'POST') {
        const body = await req.text();
        if (body.length > 24 * 1024 * 1024) return text('这一份太大了，超过 24MB', 413);
        // 空的、或者一看就不是 JSON 的，不收 —— 免得一个坏请求挤掉一份好的
        if (body.length < 2 || !/^[\s]*[\{\[]/.test(body)) return text('这不像一份备份', 400);

        const now = new Date();
        const id = now.toISOString().replace(/[-:T]/g, '').slice(0, 14);   // 20260823T0341 → 20260823034100
        const meta = { at: now.toISOString(), size: body.length };

        // 先把新的写进去，写成了才谈清理旧的。
        // 反过来的话，万一清完写失败，就是既没新的也没旧的
        await env.BK.put('bk:' + id, body, { metadata: meta });
        await env.BK.put('latest', body);                    // 老的取法还能用，不破坏旧版本 app
        await env.BK.put('latest_id', JSON.stringify(Object.assign({ id: id }, meta)));

        // 清理：只清超出 KEEP 的那些，而且永远保住最近 KEEP_MIN 份
        let pruned = 0;
        try {
          const ls = await env.BK.list({ prefix: 'bk:' });
          const names = ls.keys.map(k => k.name).sort();     // 时间戳字典序＝时间序
          const over = names.length - KEEP;
          if (over > 0) {
            const kill = names.slice(0, Math.min(over, Math.max(0, names.length - KEEP_MIN)));
            for (const nm of kill) { await env.BK.delete(nm); pruned++; }
          }
        } catch (e) { /* 清不掉就算了，多留几份不是坏事 */ }

        return json({ ok: true, id: id, size: body.length, pruned: pruned });
      }

      if (req.method === 'GET') {
        const which = url.searchParams.get('which') || 'latest';
        // which=latest 拿最近那份；which=<id> 拿指定那一份；老的 d0..d6 也还认
        const key = (which === 'latest' || /^d[0-6]$/.test(which)) ? which : ('bk:' + which);
        const v = await env.BK.get(key);
        if (v === null) return text('没有这一份', 404);
        return new Response(v, {
          headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, CORS)
        });
      }
      return text('只认 GET 和 PUT', 405);
    }

    // ---------- 2.5 聊天流水账 ----------
    // 整份备份治不了「一条一条慢慢丢」。这儿是另一条路：每说一句就往上追加一条。
    //
    // 三条铁律，写死在这儿，往后改也不许破：
    //   一、只追加。没有 UPDATE，没有 DELETE，一个都没有。
    //   二、认重复。同一条消息传一百遍也只有一行（cid+mid 唯一）。
    //       手机断网重试、换手机重传，都不会翻倍。
    //   三、手机是主，这儿是镜像。这儿挂了、满了、口令错了，手机上照样聊。
    //
    // 要在 Cloudflare 里建一个 D1，绑定名写 DB。建表语句在 worker/README.md 里。
    if (path.startsWith('/chat/')) {
      if (!pass(req, env)) return json({ error: whyNo(req, env) }, 401);
      if (!env.DB) return json({ error: 'Worker 还没绑 D1（绑定名要写 DB）。建表语句看 worker/README.md' }, 500);
      const what = path.slice('/chat/'.length);

      // 有几条、最早最晚是什么时候
      if (what === 'stat') {
        // 有些消息没有真正的时间戳（从碎片里拼回来的那批），ts 是 0 或者一个小数字。
        // 算日期范围的时候要把它们排除，不然「从 1970年1月1日 开始」——
        // 那不是真的，只是没时间戳。但条数照算，一条都不少。
        const OK = 1000000000000;      // 2001-09-09，比这小的都不是真时间戳
        const r = await env.DB.prepare(
          'SELECT COUNT(*) n, MAX(sid) top,'
          + ' MIN(CASE WHEN ts >= ? THEN ts END) lo,'
          + ' MAX(CASE WHEN ts >= ? THEN ts END) hi,'
          + ' SUM(CASE WHEN ts IS NULL OR ts < ? THEN 1 ELSE 0 END) noTs'
          + ' FROM msgs').bind(OK, OK, OK).first();
        return json({ n: (r && r.n) || 0, from: (r && r.lo) || 0, to: (r && r.hi) || 0,
                      top: (r && r.top) || 0, noTs: (r && r.noTs) || 0 });
      }

      // 往上追加。body: { msgs: [ {cid, mid, role, text, ts, extra} ... ] }
      if (what === 'put' && (req.method === 'POST' || req.method === 'PUT')) {
        let body;
        try { body = await req.json(); } catch (e) { return json({ error: '不是 JSON' }, 400); }
        const list = (body && body.msgs) || [];
        if (!Array.isArray(list)) return json({ error: 'msgs 要是个数组' }, 400);
        if (list.length > 500) return json({ error: '一次最多 500 条' }, 413);

        const st = env.DB.prepare(
          'INSERT OR IGNORE INTO msgs (cid, mid, role, text, ts, extra, got_at) VALUES (?, ?, ?, ?, ?, ?, ?)');
        const now = Date.now();
        const rows = [];
        for (const m of list) {
          if (!m || typeof m !== 'object') continue;
          const cid = String(m.cid == null ? '' : m.cid).slice(0, 64);
          const mid = String(m.mid == null ? '' : m.mid).slice(0, 64);
          if (!cid || !mid) continue;                       // 没身份的不收，不然认不出重复
          rows.push(st.bind(
            cid, mid,
            String(m.role || '').slice(0, 16),
            String(m.text == null ? '' : m.text).slice(0, 200000),
            Number(m.ts) || now,
            m.extra == null ? null : String(typeof m.extra === 'string' ? m.extra : JSON.stringify(m.extra)).slice(0, 200000),
            now));
        }
        if (!rows.length) return json({ ok: true, got: 0, note: '这一批里没有能收的' });
        await env.DB.batch(rows);
        const r = await env.DB.prepare('SELECT COUNT(*) n, MAX(sid) top FROM msgs').first();
        return json({ ok: true, got: rows.length, total: (r && r.n) || 0, top: (r && r.top) || 0 });
      }

      // 往回捞。?after=<sid>&limit=  按入库顺序，一页一页拿
      if (what === 'since' && req.method === 'GET') {
        const after = parseInt(url.searchParams.get('after') || '0', 10) || 0;
        const lim = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '200', 10) || 200, 1), 500);
        const r = await env.DB.prepare(
          'SELECT sid, cid, mid, role, text, ts, extra FROM msgs WHERE sid > ? ORDER BY sid LIMIT ?')
          .bind(after, lim).all();
        const rows = (r && r.results) || [];
        return json({ n: rows.length, next: rows.length ? rows[rows.length - 1].sid : after, rows: rows });
      }

      // 整段倒出来。?cid= 只要某一段，不给就全部
      if (what === 'dump' && req.method === 'GET') {
        const cid = url.searchParams.get('cid') || '';
        const q = cid
          ? env.DB.prepare('SELECT sid, cid, mid, role, text, ts, extra FROM msgs WHERE cid = ? ORDER BY ts, sid').bind(cid)
          : env.DB.prepare('SELECT sid, cid, mid, role, text, ts, extra FROM msgs ORDER BY ts, sid');
        const r = await q.all();
        return json({ app: 'mengxia', kind: 'chatlog', v: 1, at: Date.now(),
                      rows: (r && r.results) || [] });
      }

      return json({ error: '没有这个地址。有的是 /chat/put /chat/since /chat/stat /chat/dump' }, 404);
    }


    // ---------- 2.6 记忆（独立数据域） ----------
    // 跟上面那条聊天流水账是两回事，一个字都不共用：
    //   聊天  /chat/*  →  msgs 表      整份备份 /backup  →  KV 的 bk:*
    //   记忆  /mem/*   →  mem_items 表  记忆备份 /mem/backup → KV 的 mem:*
    //
    // 铁律跟聊天那边一样，而且更严：
    //   一、只追加。改一条记忆就多一行（同一个 id、更大的 rev），旧的那行留着。
    //   二、认重复。UNIQUE(id, rev)，传一百遍也只有一行。
    //   三、没有物理删除。手机上删一条记忆是 del=1，传上来也是新的一行。
    //   四、手机是主，这儿是镜像。这儿挂了、满了，手机上照样用。
    //
    // 建表语句在 worker/README.md 里。没建表的话这一段直接告诉你，不影响别的路由。
    if (path.startsWith('/mem/')) {
      if (!pass(req, env)) return json({ error: whyNo(req, env) }, 401);
      const what = path.slice('/mem/'.length);

      // 记忆自己的整份备份。用 KV，前缀 mem:，跟聊天那份 bk:* 谁也盖不到谁
      if (what === 'backup' || what === 'backup/list') {
        if (!env.BK) return json({ error: 'Worker 还没绑 KV（变量名要写 BK）' }, 500);
        const MKEEP = 20, MKEEP_MIN = 3;

        if (what === 'backup/list') {
          const ls = await env.BK.list({ prefix: 'mem:' });
          const rows = ls.keys.map(k => Object.assign({ id: k.name.slice(4) }, k.metadata || {}))
            .sort((a, b) => String(b.id).localeCompare(String(a.id)));
          let cur = null;
          try { cur = JSON.parse(await env.BK.get('mem_latest_id') || 'null'); } catch (e) {}
          return json({ n: rows.length, latest: cur, list: rows });
        }

        if (req.method === 'PUT' || req.method === 'POST') {
          const body = await req.text();
          if (body.length > 24 * 1024 * 1024) return json({ error: '这一份太大了，超过 24MB' }, 413);
          if (body.length < 2 || !/^[\s]*[\{\[]/.test(body)) return json({ error: '这不像一份记忆备份' }, 400);
          const now = new Date();
          const id = now.toISOString().replace(/[-:T]/g, '').slice(0, 14);
          const meta = { at: now.toISOString(), size: body.length };
          // 先写新的，写成了才清旧的
          await env.BK.put('mem:' + id, body, { metadata: meta });
          await env.BK.put('mem_latest', body);
          await env.BK.put('mem_latest_id', JSON.stringify(Object.assign({ id: id }, meta)));
          let pruned = 0;
          try {
            const ls = await env.BK.list({ prefix: 'mem:' });
            const names = ls.keys.map(k => k.name).sort();
            const over = names.length - MKEEP;
            if (over > 0) {
              const kill = names.slice(0, Math.min(over, Math.max(0, names.length - MKEEP_MIN)));
              for (const nm of kill) { await env.BK.delete(nm); pruned++; }
            }
          } catch (e) {}
          return json({ ok: true, id: id, size: body.length, pruned: pruned });
        }

        if (req.method === 'GET') {
          const which = url.searchParams.get('which') || 'mem_latest';
          const key = which === 'mem_latest' ? 'mem_latest' : ('mem:' + which);
          const v = await env.BK.get(key);
          if (v === null) return json({ error: '没有这一份' }, 404);
          return new Response(v, {
            headers: Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, CORS)
          });
        }
        return json({ error: '只认 GET 和 PUT' }, 405);
      }

      // 下面这些要 D1
      if (!env.DB) return json({ error: 'Worker 还没绑 D1（绑定名要写 DB）。建表语句看 worker/README.md' }, 500);

      // 有多少条、什么状况
      if (what === 'stat') {
        const r = await env.DB.prepare(
          'SELECT COUNT(*) rows, COUNT(DISTINCT id) n, MAX(sid) top,'
          + ' SUM(CASE WHEN del = 1 THEN 1 ELSE 0 END) delRows,'
          + ' MIN(created_at) lo, MAX(updated_at) hi FROM mem_items').first().catch(() => null);
        if (!r) return json({ error: 'mem_items 表还没建。建表语句看 worker/README.md' }, 500);
        return json({ rows: r.rows || 0, n: r.n || 0, top: r.top || 0,
                      delRows: r.delRows || 0, from: r.lo || 0, to: r.hi || 0 });
      }

      // 往上追加。body: { items: [ 一条记忆 ... ] }
      if (what === 'put' && (req.method === 'POST' || req.method === 'PUT')) {
        let body;
        try { body = await req.json(); } catch (e) { return json({ error: '不是 JSON' }, 400); }
        const list = (body && body.items) || [];
        if (!Array.isArray(list)) return json({ error: 'items 要是个数组' }, 400);
        if (list.length > 300) return json({ error: '一次最多 300 条' }, 413);

        const st = env.DB.prepare(
          'INSERT OR IGNORE INTO mem_items'
          + ' (id, rev, type, title, content, summary, importance, confidence,'
          + '  valence, arousal, resolved, pinned, created_at, updated_at,'
          + '  del, extra, got_at)'
          + ' VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)');
        const now = Date.now();
        const rows = [];
        // 数字进来先过一道：NaN、undefined、字符串都归到默认值上，
        // 不然一条脏数据能把这一整批 batch 弄挂
        // null 要单独挡一下：Number(null) 是 0，isFinite(0) 是 true，
        // 不挡的话 confidence: null 会变成 0 —— 「完全不可信」，不是「没说」
        const num = (v, dft) => {
          if (v === null || v === undefined || v === '') return dft;
          const n = Number(v);
          return isFinite(n) ? n : dft;
        };
        const pick = (a, b) => (a !== undefined && a !== null) ? a : b;
        for (const m of list) {
          if (!m || typeof m !== 'object') continue;
          const id = String(m.id == null ? '' : m.id).slice(0, 64);
          if (!id) continue;                                  // 没身份的不收
          const rev = num(m.rev, 1);
          // 正文那几样单独占列（要按它们查、按它们排）；
          // 剩下的整包塞进 extra，将来加字段不用改表
          const extra = {};
          ['tags', 'alias', 'quote', 'vec', 'activationCount', 'mentionCount',
           'lastAccess', 'source', 'relatedMemoryIds', 'tier', 'legacy',
           'mergedInto', 'maybeDup', 'dreamedAt',
           // 老名字也留着 —— 手机上要是还没更新，传上来的是这几个
           'pin', 'hits', 'hitAt', 'src']
            .forEach(k => { if (m[k] !== undefined) extra[k] = m[k]; });
          rows.push(st.bind(
            id, rev,
            String(pick(m.type, m.kind) || 'other').slice(0, 24),
            String(pick(m.title, '')).slice(0, 2000),
            String(pick(pick(m.content, m.text), '')).slice(0, 200000),
            String(pick(m.summary, '')).slice(0, 4000),
            num(pick(m.importance, m.weight), 5),
            num(m.confidence, 0.8),
            num(m.valence, 0),
            num(m.arousal, 0.3),
            m.resolved === false ? 0 : 1,
            pick(m.pinned, m.pin) ? 1 : 0,
            num(pick(m.createdAt, m.at), now),
            num(pick(m.updatedAt, m.upAt), now),
            m.del ? 1 : 0,
            JSON.stringify(extra).slice(0, 200000),
            now));
        }
        if (!rows.length) return json({ ok: true, got: 0, note: '这一批里没有能收的' });
        await env.DB.batch(rows);
        const r = await env.DB.prepare('SELECT COUNT(*) rows, COUNT(DISTINCT id) n, MAX(sid) top FROM mem_items').first();
        return json({ ok: true, got: rows.length, rows: (r && r.rows) || 0,
                      n: (r && r.n) || 0, top: (r && r.top) || 0 });
      }

      // 往回捞，按入库顺序一页一页
      if (what === 'since' && req.method === 'GET') {
        const after = parseInt(url.searchParams.get('after') || '0', 10) || 0;
        const lim = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '200', 10) || 200, 1), 300);
        const r = await env.DB.prepare(
          'SELECT sid, id, rev, type, title, content, summary, importance, confidence, valence, arousal, resolved, pinned, created_at, updated_at, del, extra'
          + ' FROM mem_items WHERE sid > ? ORDER BY sid LIMIT ?').bind(after, lim).all();
        const rows = (r && r.results) || [];
        return json({ n: rows.length, next: rows.length ? rows[rows.length - 1].sid : after,
                      rows: rows.map(memRow) });
      }

      // 整个倒出来。同一个 id 只给 rev 最大那一版 —— 那是「现在」的样子。
      // 旧的那些行一行不少地留在表里，要看历史走 /mem/since
      if (what === 'dump' && req.method === 'GET') {
        const r = await env.DB.prepare(
          'SELECT m.sid, m.id, m.rev, m.type, m.title, m.content, m.summary,'
          + ' m.importance, m.confidence, m.valence, m.arousal,'
          + ' m.resolved, m.pinned, m.created_at, m.updated_at, m.del, m.extra'
          + ' FROM mem_items m'
          + ' JOIN (SELECT id, MAX(rev) mx FROM mem_items GROUP BY id) t'
          + ' ON m.id = t.id AND m.rev = t.mx'
          + ' ORDER BY m.created_at').all();
        const rows = (r && r.results) || [];
        return json({ app: 'mengxia', kind: 'memory', v: 1, memoryVersion: 1,
                      at: Date.now(), n: rows.length, items: rows.map(memRow) });
      }

      // 单独一条现在是什么样（rev 最大那一版）。
      // 软删、恢复完了拿它对一眼最省事，不用把整个库 dump 下来
      if (what === 'one' && req.method === 'GET') {
        const id = url.searchParams.get('id') || '';
        if (!id) return json({ error: '要带 ?id=' }, 400);
        const r = await env.DB.prepare(
          'SELECT sid, id, rev, type, title, content, summary, importance, confidence, valence, arousal, resolved, pinned, created_at, updated_at, del, extra'
          + ' FROM mem_items WHERE id = ? ORDER BY rev DESC LIMIT 1').bind(id).first();
        if (!r) return json({ error: '云上没有这一条' }, 404);
        return json({ id: id, item: memRow(r) });
      }

      // 一条记忆的全部历史
      if (what === 'trace' && req.method === 'GET') {
        const id = url.searchParams.get('id') || '';
        if (!id) return json({ error: '要带 ?id=' }, 400);
        const r = await env.DB.prepare(
          'SELECT sid, id, rev, type, title, content, summary, importance, confidence, valence, arousal, resolved, pinned, created_at, updated_at, del, extra'
          + ' FROM mem_items WHERE id = ? ORDER BY rev').bind(id).all();
        return json({ id: id, rows: ((r && r.results) || []).map(memRow) });
      }

      return json({ error: '没有这个地址。有的是 /mem/put /mem/since /mem/stat /mem/dump /mem/one /mem/trace /mem/backup' }, 404);
    }

    // ---------- 3. 转一手 ----------
    // 手机上的网页去请求别人家的域名会被跨域挡住（Failed to fetch）。
    // Worker 不受这个限制，所以让它代发一次，再把结果原样带回来。
    // 只准转花园那几个域名 —— 不然这就成了谁都能用的开放代理。
    if (path === '/relay' || path === '/relay-sse') {
      // EventSource 加不了请求头，所以 /relay-sse 的口令只能挂在网址上
      const okPass = pass(req, env) || (path === '/relay-sse' && (() => {
        const want = String(env.PASS || '').trim();
        const got = String(url.searchParams.get('k') || '').trim();
        if (!want || got.length !== want.length) return false;
        let d = 0;
        for (let i = 0; i < want.length; i++) d |= got.charCodeAt(i) ^ want.charCodeAt(i);
        return d === 0;
      })());
      if (!okPass) return json({ error: whyNo(req, env) }, 401);
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

    // ---------- 3.5 声音转一手 ----------
    // 手机上的网页去请求语音站点会被跨域挡住（她看到的就是 Failed to fetch）。
    // Worker 不受这个限制。地址和 key 是手机每次带上来的 —— 这儿一份都不存。
    // 要口令才用得了，所以不是谁都能拿它当代理。
    if (path === '/voice/tts' || path === '/voice/stt') {
      if (!pass(req, env)) return json({ error: whyNo(req, env) }, 401);
      let q;
      try { q = await req.json(); } catch (e) { return json({ error: '要 JSON' }, 400); }
      const base = String(q.base || '').replace(/\/+$/, '').replace(/\/v1$/i, '');
      const key = String(q.key || '').trim();
      if (!/^https:\/\//i.test(base)) return json({ error: '地址要是 https 开头的' }, 400);
      let host = '';
      try { host = new URL(base).host; } catch (e) { return json({ error: '这不像个网址' }, 400); }
      // 别让它去戳内网
      if (/^(localhost|127\.|0\.|10\.|192\.168\.|169\.254\.|\[)/i.test(host)
          || /^172\.(1[6-9]|2\d|3[01])\./.test(host)) return json({ error: '不转内网地址' }, 403);
      if (!key) return json({ error: '没带 key' }, 400);
      const eleven = q.kind === 'eleven';

      if (path === '/voice/tts') {
        const t = String(q.text || '').slice(0, 2000);
        if (!t) return json({ error: '没有字' }, 400);
        let u, h, b;
        if (eleven) {
          u = base + '/v1/text-to-speech/' + encodeURIComponent(String(q.voice || ''));
          h = { 'Content-Type': 'application/json', 'xi-api-key': key, 'Accept': 'audio/mpeg' };
          b = JSON.stringify({ text: t, model_id: String(q.model || '') || 'eleven_multilingual_v2' });
        } else {
          u = base + '/v1/audio/speech';
          h = { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key };
          b = JSON.stringify({ model: String(q.model || ''), voice: String(q.voice || 'alloy'), input: t, response_format: 'mp3' });
        }
        let r;
        try { r = await fetch(u, { method: 'POST', headers: h, body: b }); }
        catch (e) { return json({ error: '语音站连不上：' + e.message }, 502); }
        if (!r.ok) {
          const why = await r.text();
          return json({ error: 'HTTP ' + r.status + '：' + why.replace(/\s+/g, ' ').slice(0, 200) }, 502);
        }
        return new Response(r.body, {
          status: 200,
          headers: Object.assign({ 'Content-Type': r.headers.get('Content-Type') || 'audio/mpeg' }, CORS)
        });
      }

      // 转文字：手机把录音变成 base64 送上来，这儿拼成表单再发出去
      const raw = String(q.data || '');
      if (!raw) return json({ error: '没有录音' }, 400);
      let bytes;
      try {
        const bin = atob(raw);
        bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      } catch (e) { return json({ error: '录音读不出来' }, 400); }
      if (bytes.length > 8 * 1024 * 1024) return json({ error: '录音太大了' }, 413);
      const mime = String(q.mime || 'audio/webm').split(';')[0];
      const ext = mime.indexOf('mp4') >= 0 ? 'm4a' : (mime.indexOf('ogg') >= 0 ? 'ogg' : (mime.indexOf('wav') >= 0 ? 'wav' : (mime.indexOf('mpeg') >= 0 ? 'mp3' : 'webm')));
      const fd = new FormData();
      fd.append('file', new Blob([bytes], { type: mime }), 'v.' + ext);
      let u2, h2;
      if (eleven) {
        u2 = base + '/v1/speech-to-text';
        h2 = { 'xi-api-key': key };
        fd.append('model_id', String(q.model || '') || 'scribe_v2');
        fd.append('tag_audio_events', 'true');
        fd.append('diarize', 'false');
        if (q.lang) fd.append('language_code', String(q.lang));
      } else {
        u2 = base + '/v1/audio/transcriptions';
        h2 = { 'Authorization': 'Bearer ' + key };
        fd.append('model', String(q.model || '') || 'whisper-large-v3');
        if (q.lang) fd.append('language', String(q.lang));
        fd.append('response_format', 'json');
      }
      let r2;
      try { r2 = await fetch(u2, { method: 'POST', headers: h2, body: fd }); }
      catch (e) { return json({ error: '语音站连不上：' + e.message }, 502); }
      const out = await r2.text();
      if (!r2.ok) return json({ error: 'HTTP ' + r2.status + '：' + out.replace(/\s+/g, ' ').slice(0, 200) }, 502);
      let j = null;
      try { j = JSON.parse(out); } catch (e) {}
      return json({ text: String((j && j.text) || '').trim() });
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
