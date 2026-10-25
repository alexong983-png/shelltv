# ShellTV —— 网页 + 播放器 空壳 App

底部两个标签：
- 网页：WebView 加载你输入的网址，支持视频全屏、返回键后退，网址会被记住。
- 播放器：ExoPlayer 播放 m3u8 / mp4 等直播或点播流，也可加载 M3U 播放列表并选台。

## 打包 APK
1. 安装 Android Studio（Koala 或更新），File > Open 选择本目录，等待 Gradle 同步。
2. Build > Build Bundle(s) / APK(s) > Build APK(s)。
3. 生成位置：app/build/outputs/apk/debug/app-debug.apk
