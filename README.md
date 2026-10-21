# 大字幕（tv-zoom）— 智慧屏字幕放大

华为 Vision 智慧屏 3（QINL-360A，Android 12）上的字幕放大应用。
**不用录屏**：画面来自 USB 采集卡，App 自己合成，所以放大条可以压在字幕上，不存在套娃。

![效果](demo-camera.png)

## 链路

```
机顶盒 ──HDMI──> 采集卡(MS2130) ──USB──> 电视
                                          ├─ 摄像头接口取帧
                                          ├─ USB 音频取声
                                          └─ GPU 合成：整幅画面 + 底部放大条
```

- 视频：Camera2 打开外置摄像头（采集卡），SurfaceTexture → OpenGL 画两遍
- 音频：AudioRecord 从 `USB-Audio - Hagibis` 录 PCM，AudioTrack 放出来
- 机顶盒音频要设成**解码**（PCM 立体声），**不要透传**（采集卡不解码）

## 遥控器

| 键 | 作用 |
|---|---|
| ← / → | 倍数 1.0×–3.0×，8 档 |
| ↑ / ↓ | 取景框上下，每档 2%，0–100（数字 = 离画面**底部**的距离） |
| OK | 放大条开关 |
| 菜单键 | 条高 8/10/12/16/20/24/30% |

调整时顶部弹 2 秒提示，参数存 SharedPreferences，下次打开沿用。

## 当前默认值

```
倍数 3.0×   条高 20%   取景框 10%
```

## 实测约束

| 项 | 值 |
|---|---|
| 采集卡 | MS2130，插在 USB 2.0 口 → **1080p30 封顶** |
| 源 | 机顶盒 50Hz，不支持改刷新率 → 50→30 抽帧，轻微规律性顿挫 |
| 音频延迟 | USB 采集+回放约 100ms，实测口型同步可接受 |
| 画面 | 1920×1080，30fps 稳定 |

> 换到 USB 3.0 口可以到 60fps；换 60Hz 源可以消除抽帧顿挫。

## 构建

```bash
./build.sh     # aapt2 + javac + d8 + apksigner，产出 build/tvzoom.apk
```

依赖：`~/dl-android/bt34/android-14`、`~/dl-android/p31/android-12/android.jar`、`~/command-line-tools/jbr`。

## 安装

```bash
adb connect <电视IP>:5555
adb install -r build/tvzoom.apk
adb shell appops set com.robin.tvzoom SYSTEM_ALERT_WINDOW allow   # 旧方案遗留，可不做
```

桌面图标「字幕放大」直接进放大画面。

## 调试

```bash
# 热调参数（不用重启）
adb shell am broadcast -a com.robin.tvzoom.SET --ei zoom 300 --ei band 20 --ei cy 10

# 看日志
adb logcat -s TVZoom:*
```

## 关键坑

1. **USB 2.0**：MS2130 在 USB2 上只有 30fps，HAL 报的帧率范围就是 `[10,30]`，请求 60 无效。
2. **镜像**：采集卡把自己报成前置摄像头，预览是镜像的。GL 里 `uFlip=1, vFlip=-1` 才正过来（180° 旋转 + 一次水平翻转）。
3. **取景框越界**：超出画面会被 `CLAMP_TO_EDGE` 拉伸成糊片，必须把取景框整体夹在画面内。
4. **取景框方向**：`cy` 是"离画面底部的距离"，数值越大取景越高。
5. **进程冻结**：电视的 `PG_ash` 会冻结后台进程；本方案是前台 Activity，不受影响。

## 历史方案（已弃用）

早期试过 MediaProjection 录屏 + 悬浮窗，受限于「录屏会录到自己」，放大条不能压住取景框，
定位被几何关系卡死。代码仍在 `LensService` / `AutoHideService` / `MainActivity` 里，未启用。
