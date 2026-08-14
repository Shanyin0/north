# 梦匣 · Android 壳

这是把网页版梦匣装进手机 App 的一层壳（一个 WebView，不到 200 行）。

- 前端还是放在 GitHub Pages 上，App 只是把它包起来：**改前端不用重装 App**，重开一次就是新的。
- 网址写在 `app/build.gradle` 的 `SITE_URL`；万一打不开，App 里会弹框让你手填。
- 打包不用电脑：仓库 Actions 里的「打包梦匣 APK」跑完，APK 会发到 Releases 的 `apk` 这个 tag 下。
- 签名用的是仓库里的 `app/mengxia.keystore`（自签名，仅自用）。因为签名固定，所以每次更新都能直接覆盖安装。
