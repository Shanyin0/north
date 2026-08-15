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
| `/backup` | PUT 存一份，GET 取回来 |
| `/tool/dice?n=6&k=2` | 掷骰子，真随机 |
| `/tool/pick?n=78&k=3` | 从 n 张里抽 k 张，带正逆位 |
| `/tool/coin?k=6` | 抛硬币六次（起卦用） |

除了首页，每个地址都要带 `Authorization: Bearer <PASS>`。

## 几件要知道的事

- 备份存两份：`latest` 是最新的，另外按星期几留一份，七天后自己过期。想取旧的：`/backup?which=d3`。
- KV 单份上限 25MB，代码里挡在 24MB。照片多了会超，超了会告诉你。
- 免费额度：每天十万次读、一千次写。你一天传一次备份，用不完。
- **key 只在 Worker 里。** 手机上存的是 PASS，不是 key。手机丢了，去 Cloudflare 把 PASS 改掉就断了。
