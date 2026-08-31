# 后台 · 部署

一个文件，贴到 Cloudflare Worker 里就行。不用装东西，不用买域名，不用备案。

## 一、先建一个 KV（放备份用的）

Cloudflare 左边菜单 → **Storage & Databases** → **KV** → **Create**

名字随便，写 `mengxia` 就行。建完不用管。

## 二、让 Worker 自己去仓库拿代码（不用手机复制粘贴）

手机上复制一百多行代码基本粘不干净，别折腾了。让 Cloudflare 自己去 GitHub 拿。

1. Cloudflare → **Compute (Workers)** → 点 `north` → **Settings** → **Build**
2. **Connect to Git** → 授权 GitHub → 选 `Shanyin0/north`
3. 两个格子这样填：
   - **Root directory**：`worker`
   - **Deploy command**：`npx wrangler deploy`
4. 保存。以后每次仓库一更新，它自己重新部署。

在这之前先把 `worker/wrangler.jsonc` 里那句 `把这里换成你的KV的ID` 换成真的 ID
（在 GitHub 网页上点铅笔改就行，手机也能改）。ID 在第一步那个 KV 的详情页上。

## 三、绑 KV

Worker 页面 → **Settings** → **Bindings** → **Add** → **KV namespace**

- Variable name：**`BK`**（必须是这三个字母）
- KV namespace：选刚才建的那个

## 四、填四个机密

同一页 → **Variables and Secrets** → **Add** → 类型选 **Secret**

| 名字 | 填什么 |
|---|---|
| `PASS` | 自己定一句口令。手机上要填一模一样的。 |
| `UPSTREAM` | 模型站地址，例如 `https://api.groq.com/openai` |
| `UPSTREAM_KEY` | 那个站的 key |

填完 Deploy 一次。

## 五、手机上填

**中转**：序章 → 通道选「自定义站点」

- 地址：`https://你的.workers.dev`
- 密钥：填 **PASS** 那句口令（不是模型的 key）

**备份**：序章 → 备份与搬家 → 存一份到云上

- 地址：同一个 `https://你的.workers.dev`
- 口令：同一句 PASS

## 它有哪些地址

| 地址 | 干什么 |
|---|---|
| `/v1/chat/completions` | 中转（OpenAI 那种格式） |
| `/v1/messages` | 中转（Anthropic 那种格式） |
| `/backup` | PUT 存一份（留 30 个版本），GET 取回来 |
| `/backup/list` | 云上现在有哪几份 |
| `/chat/put` | 聊天流水账：往上追加几条（只加不改不删） |
| `/chat/since?after=&limit=` | 从某一条之后往回捞 |
| `/chat/stat` | 云上有几条、从什么时候到什么时候 |
| `/chat/dump?cid=` | 整段倒出来 |
| `/tool/dice?n=6&k=2` | 掷骰子，真随机 |
| `/tool/pick?n=78&k=3` | 从 n 张里抽 k 张，带正逆位 |
| `/tool/coin?k=6` | 抛硬币六次（起卦用） |

除了首页，每个地址都要带 `Authorization: Bearer <PASS>`。

## 几件要知道的事

- 备份**留 30 个版本**，每一份带自己的时间戳，谁也盖不掉谁。`/backup/list` 看有哪几份，`/backup?which=<id>` 取指定那一份，`/backup?which=latest` 取最近的。
  （以前是整份覆盖 + 按星期几留七天，传上去一份坏的就把好的盖没了。旧的 `d0`–`d6` 还认，但不再往里写。）
- KV 单份上限 25MB，代码里挡在 24MB。照片多了会超，超了会告诉你。
- 免费额度：每天十万次读、一千次写。你一天传一次备份，用不完。
- **key 只在 Worker 里。** 手机上存的是 PASS，不是 key。手机丢了，去 Cloudflare 把 PASS 改掉就断了。


## 聊天流水账（要建一个 D1）

整份备份是隔一阵子拍一张全家福，两张之间发生的事拍不到。流水账不一样：
**每条消息单独往上追加一条，只加不改不删。** 手机上删掉的、改掉的、
重新生成顶掉的，云上那一份都还在。

### 建库（只做一次）

1. Cloudflare → **Storage & Databases → D1 → Create**，名字随便起（我们这个叫 `north`）
2. 进去点 **Console**（Console 是建完库、点进那个库里面才有的），把下面这段贴进去执行：

```sql
CREATE TABLE IF NOT EXISTS msgs (
  sid    INTEGER PRIMARY KEY AUTOINCREMENT,
  cid    TEXT NOT NULL,          -- 哪一段对话
  mid    TEXT NOT NULL,          -- 这条消息在手机上的 id
  role   TEXT,                   -- me / sir
  text   TEXT,
  ts     INTEGER,                -- 手机上那条消息的时间
  extra  TEXT,                   -- JSON：思考、心跳、引用这些
  got_at INTEGER,                -- 收到的时间
  UNIQUE(cid, mid)               -- 同一条传一百遍也只有一行
);
CREATE INDEX IF NOT EXISTS ix_msgs_cid_ts ON msgs (cid, ts);
```

## 记忆那张表（独立数据域，跟上面那张没有任何关系）

同一个 D1 库里另建一张表。**不要动 msgs**，那张表是聊天的。

整段复制到 **D1 → north → Console**，跑一次就行。跑第二次也不会出事
（都带 `IF NOT EXISTS`）。

```sql
CREATE TABLE IF NOT EXISTS mem_items (
  sid        INTEGER PRIMARY KEY AUTOINCREMENT,
  id         TEXT    NOT NULL,               -- 这条记忆的身份，一旦生成就不变
  rev        INTEGER NOT NULL DEFAULT 1,     -- 改一次加一。旧的那一行留着，不覆盖
  type       TEXT    NOT NULL DEFAULT 'other',
  title      TEXT    NOT NULL DEFAULT '',
  content    TEXT    NOT NULL DEFAULT '',    -- 正文
  summary    TEXT    NOT NULL DEFAULT '',
  importance REAL    NOT NULL DEFAULT 5,     -- 0..10，要紧程度
  confidence REAL    NOT NULL DEFAULT 0.8,   -- 0..1，有多确定
  valence    REAL    NOT NULL DEFAULT 0,     -- -1..1，愉不愉快
  arousal    REAL    NOT NULL DEFAULT 0.3,   -- 0..1，起伏多大
  resolved   INTEGER NOT NULL DEFAULT 1,     -- 0 = 还悬着
  pinned     INTEGER NOT NULL DEFAULT 0,     -- 1 = 钉住，永不衰减
  created_at INTEGER NOT NULL DEFAULT 0,     -- 记下来的时候
  updated_at INTEGER NOT NULL DEFAULT 0,     -- 这一版是什么时候写的
  del        INTEGER NOT NULL DEFAULT 0,     -- 1 = 手机上删掉了。这儿只标记，不真删
  extra      TEXT    NOT NULL DEFAULT '{}',  -- JSON：标签、别名、原话、向量、来源、关联…
  got_at     INTEGER NOT NULL DEFAULT 0,     -- 收到的时间
  UNIQUE(id, rev)                            -- 同一版传一百遍也只有一行
);
CREATE INDEX IF NOT EXISTS ix_mem_id    ON mem_items (id, rev);
CREATE INDEX IF NOT EXISTS ix_mem_upat  ON mem_items (updated_at);
CREATE INDEX IF NOT EXISTS ix_mem_type  ON mem_items (type);
```

列名跟手机上那份 schema 一一对上。以前这儿写的是更早一版的名字
（`kind` / `text` / `weight` / `at`），手机那边换过之后就对不上了 ——
按那份建表，传上去的记忆正文会变成空串。这张表还没建过，所以直接按
现在的字段来，不涉及改表。

正文那几样单独占列（要按它们查、按它们排），剩下的整包塞在 `extra` 里，
将来加字段不用动表结构。

三条规矩，跟聊天那张一样，而且更严：

- **只追加。** 改一条记忆＝多一行（同 id、更大的 rev），旧的那行一直在。
- **没有物理删除。** 手机上删一条是 `del=1`，传上来也只是新的一行。
  恢复也一样：又是新的一行，`del=0`，rev 更大。
- **手机是主，这儿是镜像。** 这儿挂了、满了、口令错了，手机上照样用。

口子：`/mem/put` 追加 · `/mem/since` 往回捞 · `/mem/stat` 看多少 ·
`/mem/dump` 每个 id 的最新一版 · `/mem/one?id=` 单独一条现在什么样 ·
`/mem/trace?id=` 一条的全部历史 ·
`/mem/backup` 记忆自己的整份备份（KV 前缀 `mem:`，跟聊天那份 `bk:*` 分开）。

3. **不要在网页上加绑定。** 这个 Worker 从 Git 部署，bindings 以
   `wrangler.jsonc` 为准，网页上手动加的下一次部署会被冲掉。
   在 D1 那个库右边的 `...` → **Copy binding**，把复制到的
   `database_name` 和 `database_id` 填进 `wrangler.jsonc` 的 `d1_databases`
4. 推一次代码，Cloudflare 自动部署。访问 `/ping`，看到 `"D1": true` 就成了

### 手机上

序章 → 备份与搬家 → **聊天流水账**。地址和口令跟备份用的是同一对，
不用再填。配好云之后默认就开着，聊完一轮自己往上送。

- **现在送一次** —— 手动补一遍
- **云上有多少** —— 看云上几条、本地还有几条没送上去
- **把云上那份捞下来看看** —— 存成文件，**本地一个字都不动**

### 几件要知道的事

- **只往外送，不往回写。** 这条路永远不会改你手机上的东西。要用云上那份，
  自己捞下来看了再决定。
- **传不上去不会打扰你。** 网不好就静悄悄记着，下次自己补。水位线只有传成了
  才往前推 —— 不然那几条就被永远跳过去了。
- **图片不往上送**，一张几百 KB，流水账要的是话。有图的那条会记一个「当时有图」。
- 免费额度：D1 每天五百万行读、十万行写。一天几百条消息，用不完。
