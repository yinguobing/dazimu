# 华为智慧屏 HDMI 内部访问研究

记录于 2026-10-22，设备：华为 Vision 智慧屏 3（QINL-360A，HarmonyOS 3.1 / Android 12 兼容层）。

目标是回答一个问题：**能不能不经过 HDMI 采集卡，直接拿到电视 HDMI 输入的像素？**

结论先放前面：

> 技术上存在一条完整的路线（V4L2 → 原始帧），但它被 SELinux + 固件签名 + 验证启动三道锁同时封住。
> 在“无 root、无平台签名”的前提下，**采集卡是唯一可用的像素入口**。
> 唯一还开着的门是**华为工程固件**（官方签名、带调试属性），目前没有公开流通。

---

## 1. 三条取帧路线

| 路线 | 能拿到什么 | 门槛 | 结论 |
|---|---|---|---|
| USB 采集卡（UVC / Camera2） | HDMI 原始画面 | CAMERA 权限 | ✅ 生产可用，30fps 上限 |
| MediaProjection（录屏） | **合成后**的屏幕（含自家悬浮层） | 每次开机人工授权 | ⚠️ 可用于实验，见第 5 节 |
| V4L2 `/dev/video21` | **HDMI 原始帧**（不含合成层） | SELinux `camera_device`，需 root | ❌ 权限不可得 |
| TvInputFramework / 华为 tvapi | 理论上的官方接口 | 平台签名权限 | ❌ 见第 3、4 节 |

---

## 2. 设备事实

```
型号            QINL-360A（华为 Vision 智慧屏 3）
SoC             Novatek n82002（平台字符串 honghu-ntts）
内核            Linux 5.10.209-g0477b62d096a-dirty aarch64
构建            HUAWEI/QINL-360A/HWQINL-360A:12/104.3.0.252SP11C00:user/release-keys
ro.build.type   user
ro.build.tags   release-keys
ro.secure       1
ro.debuggable   0
SELinux         Enforcing
verifiedboot    green（AVB 开启）
flash.locked    1
oem_unlock      未提供
Android SPL     2021-10-05（框架侧偏旧，内核侧很新）
su              不存在
```

---

## 3. 标准 TV 输入框架：空的

```bash
adb shell dumpsys tv_input
```

```
inputMap: (空)
mHardwareList: (空)
mHdmiDeviceList: (空)
```

`pm list features` 里没有 `android.software.live_tv`。

也就是说这台电视**没有接入 AOSP 的 TvInputService**，HDMI 走的是华为/Novatek 自己的通路，第三方 App 连“输入源”都枚举不到。

相关权限全部是签名级：

```
android.permission.TV_INPUT_HARDWARE           signature|privileged|vendorPrivileged
android.permission.CAPTURE_TV_INPUT            signature|privileged
com.huawei.homevision.tvservice.ACCESS_SERVICE signature|privileged
```

---

## 4. 华为私有 tvapi：客户端能加载，服务连不上

`LauncherTv.apk`（`/system/priv-app/LauncherTv/`）里的输入源模块用的是一套私有 API：

```java
com.huawei.tvapi.SourceManager.selectSource(id)      // 切输入源
com.huawei.tvapi.SourceManager.setWindowRect(rect)   // 指定 HDMI 合成到屏幕哪块
com.huawei.tvapi.SourceManager.setVideoDisplay(...)  // 指定显示面
com.huawei.tvapi.HwtvManager.getInstance() / registerListener()
```

类由共享库 `hwtvshare` 提供（`/system/framework/hwtvshare.jar` + `.vdex`），
实现挂在系统服务 `hwtvservice`（`com.huawei.tvapi.ITvService`）和
`hwdtvservice`（`com.huawei.dtv.impl.ICommandExecutor`）上。

探针实测（`com.robin.tvapitest`，13KB）：

| 调用 | 结果 |
|---|---|
| `Class.forName("com.huawei.tvapi.SourceManager")` | ✅ 类能加载 |
| `HwtvManager.getInstance()` | ✅ 返回对象 |
| `SourceManager.getAvailSourceList()` | ❌ `null` |
| `SourceManager.getSelectSourceId()` | ❌ `-1` |
| `ServiceManager.getService("hwtvservice")` | ❌ `null`（SELinux restricted） |

同一个 `getService("hwtvservice")`，用 `app_process` 以 **shell** 身份跑：

```
PROBE hwtvservice  -> android.os.BinderProxy@d3dcba   ✅
PROBE hwdtvservice -> android.os.BinderProxy@951d76b  ✅
```

**shell 能摸到 binder，App 摸不到**。但 shell 能力无法部署到老人家里（没有常驻电脑），只能用于分析。

---

## 5. MediaProjection：能截到 HDMI，但有三个硬伤

### 5.1 能截到（关键结论）

切到 HDMI 1 后用 `screencap` 和 MediaProjection 都能拿到真实画面（CCTV-1 + 电视自己的 `HDMI 1 1080P@60Hz` OSD），
说明这台电视的 HDMI 输入**不是 secure 层**，在合成器里就是普通图层。

### 5.2 踩坑：ImageReader 格式对不上

```
java.lang.UnsupportedOperationException:
  The producer output buffer format 0x11 doesn't match
  the ImageReader's configured buffer format 0x1
```

这台电视的显示管线输出 **NV12（0x11）**，`ImageReader.newInstance(..., PixelFormat.RGBA_8888, ...)` 直接崩。
改用 **SurfaceTexture + GL（GL_TEXTURE_EXTERNAL_OES）** 即可，和采集卡那条管线完全一致。

### 5.3 secure 标记会锁死整块屏幕

想过把悬浮条标成 `SurfaceView.setSecure(true)`，指望截屏时把它排除、露出下面的 HDMI 字幕。

实测：**整块屏幕都不能截了**（`screencap` 返回失败、产出 0 字节），去掉后又恢复。
说明这台电视对 secure 的处理是“整屏生效”，不是“逐层排除”。

另外 `SurfaceControl.Transaction#setSkipScreenshot` 是 Android 13（API 33）才有的，这台是 12，没有。

### 5.4 底部放大条的“自锁”是几何问题，不是 bug

数据流里最容易被忽略的一点：

```
[HDMI 层] + [我们的悬浮条] → SurfaceFlinger 合成 → 屏幕
                                  ↑
                        MediaProjection 镜像的是这个合成结果
```

它截的不是 HDMI 信号，而是**合成后的屏幕**。所以当放大条盖住自己采样的区域时，
它读到的就是自己上一帧画上去的像素 → 反馈。因为 3 倍放大是“缩小取样再拉开”，
环路增益小于 1，不会爆掉，而是**锁死**成第一帧的颜色（实测先白后黑）。

结论：**放大条必须与它采样的区域在几何上不相交**。

- 放屏幕顶部（采样 86%~95%，绘制 0%~20%）→ ✅ 正常，实测 60fps
- 放原字幕上方（采样 86%~95%，绘制 59%~86%）→ ✅ 理论可行，屏幕上会同时出现大小两行字幕
- 放屏幕底部 → ❌ 必然自锁

### 5.5 授权是每个会话一次性的

MediaProjection 的凭证绑定单次会话，进程一死（被杀/重启）就失效，没有“始终允许”，
`PROJECT_MEDIA` appop 也不能替代授权弹窗。

这直接决定了它和采集卡在**老人场景**下的差距：采集卡开机零交互，投屏每次开机要点一次“立即开始”。

---

## 6. 固件与签名：三条锁

### 6.1 OTA 客户端里有完整验签管线

`/system/app/HwOUC/HwOUC.apk` 中存在：

```
com.huawei.android.hwouc.ohupgrade.upgrade.verify.manager.a … l
verify: publickey NoSuchAlgorithmException
verifyFileJson, signedJson or files is null
systemUpdateAuth verifyAuthVersionInfo fail
start to verify firmware's encryptValue is
handleFirmwareVerifyResult verifyResult is
```

即：**公钥验签（RSA）+ 签名 JSON 清单**。改过的包进不去。

### 6.2 强制升级同样验签

华为官方支持文档描述：机身电源键长按超过 10 秒进入恢复/升级界面，屏幕依次显示
`Start authenticate` → `Upgrade fail`。`Start authenticate` 就是验签环节。

### 6.3 启动链锁死

`verifiedbootstate=green` + `flash.locked=1` + 无 OEM 解锁开关：
即使能把改过的分区写进去，bootloader 也会拒绝启动。

### 6.4 因此

- **刷改版固件**：签名挡住，不可行
- **救砖包**（民间维修圈流通，如 HEGE-560 的 1.0.1.21）：官方原版，能救砖，不能 root
- **工程固件**：官方签名但带调试属性（`ro.debuggable=1`、adb root），是唯一“合法过验签且有 root”的东西

---

## 7. V4L2：门是真的，钥匙不在

```bash
adb shell 'ls -l /sys/class/video4linux/'
```

```
video21 -> ../../devices/platform/ndp-hdmi/video4linux/video21        ← HDMI 输入
video20 -> ../../devices/platform/ndp-extin/video4linux/video20        ← 外部输入
video24 -> ../../devices/platform/ndp-extin/video4linux/video24
video66 -> ../../devices/platform/ndp-extin/video4linux/video66
video28 -> ../../devices/platform/ndp-hdmi_switch/video4linux/video28
video10..17 -> imvq-mfc-dec       （编解码）
video30..33 -> imvq-venc
video40     -> imvq-img-dec
video0/1    -> USB（采集卡）
```

内核模块：`ndp_hdmi`、`ndp_hdmi_switch`、`ndp_khdmi`。

**HDMI 原始帧确实在内存里，而且走标准 V4L2**。不是直通显示，也不是私有通路。

但是：

```
crw-rw-rw- 1 system camera 81, 18 /dev/video21
u:object_r:camera_device:s0
```

DAC 全开，SELinux 标签是 `camera_device`：

```bash
adb shell dd if=/dev/video21 of=/dev/null bs=1 count=1
# dd: /dev/video21: Permission denied
```

App（untrusted_app）和 shell 都被拒。**只有 root 或 SELinux permissive 才能打开。**

拿到之后的收益是决定性的：

```
/dev/video21 (V4L2) → 原始 HDMI 帧 → GL 管线 → 全屏 + 底部放大条
```

一次解决三个问题：不用采集卡、不用每次授权、没有套娃。

---

## 8. 现在的判断

```
找到工程固件 → 能刷（官方签名） → adb root → setenforce 0 → 打开 /dev/video21
   缺货            可行              可行         可行              可行
```

唯一缺的一环是**工程固件**（或任何能让 `ro.debuggable=1` 的官方签名包）。
目前公开渠道没有 QINL-360A / honghu-ntts 的工程固件。

不建议的方向：

- 改固件刷入（签名 + AVB 双锁，必砖）
- 运行时提权（内核 5.10.209 很新，框架 SPL 2021-10 虽有窗口但没有公开可用链）
- 拆机接 UART（AVB 仍在，且失去保修、静电风险）

## 9. 如果哪天拿到工程固件

按这个顺序验证：

```bash
# 1. 确认进的是工程版本
adb shell getprop ro.build.type          # 期望 eng / userdebug
adb shell getprop ro.debuggable          # 期望 1
adb root

# 2. 放开 SELinux（临时）
adb shell setenforce 0
adb shell getenforce                     # Permissive

# 3. 验证 HDMI 节点可读
adb shell 'cat /proc/version'
adb shell 'ls -l /dev/video21'
# 用 v4l2 工具或自写客户端做 VIDIOC_QUERYCAP / REQBUFS / DQBUF
```

客户端可以参考 `~/developer/hdmizoom/`（GL 放大条骨架）和 `~/developer/tv-zoom/`
（完整产品逻辑：倍数、字幕位置、遥控器交互）。

## 10. 复现命令附录

```bash
ADB=~/dl-android/platform-tools/adb
D=<电视IP>:5555

# TV 输入框架
$ADB -s $D shell dumpsys tv_input

# 服务与权限
$ADB -s $D shell service list | grep -iE "tv|hdmi"
$ADB -s $D shell dumpsys package | grep -A3 "TV_INPUT_HARDWARE"

# V4L2
$ADB -s $D shell 'ls -l /sys/class/video4linux/'
$ADB -s $D shell 'ls -Z /dev/video21'

# 启动链
$ADB -s $D shell 'getprop ro.boot.verifiedbootstate; getprop ro.boot.flash.locked'
```

---

## 附：这一轮的副产品

- `~/developer/hdmizoom/` —— 无采集卡原型：MediaProjection + SurfaceTexture + GL 悬浮条，实测 60fps
- `com.robin.tvapitest`、`com.robin.mpprobe` —— 两个一次性探针，结论已记录，源码未保留
