# 阳光最大亮度 · 户外自动增亮（KernelSU 模块）

让一加 Ace 5（PKG110）在**日常保持手动亮度**的前提下，被太阳直射时自动把屏幕拉到面板绝对上限，回室内自动还原。

- 实测机型：OnePlus Ace 5 / PKG110，ColorOS 15–16，KernelSU
- 增益：滑条顶端 4095 → 面板绝对上限 4675，**稳定亮度约 +7%**
- 常驻成本：亮屏时约 0.2 秒 CPU / 分钟，内存 PSS 约 95 MB
- 需要 root。不修改系统分区，不覆盖任何框架文件
- 作者：legercong(https://github.com/legercong) （by qwen3.8 flash）

---

## 装完是什么表现

| 场景 | 行为 |
|---|---|
| 日常室内 | 完全不动你的亮度，自动亮度保持关闭 |
| 太阳直射到光感 | 约 2 秒后亮度拉到 4675（越过滑条顶端） |
| 回到室内 | 约 6 秒后还原成**你自己原来的亮度** |
| 增亮期间你自己拖过滑条 | 模块不覆盖你的操作，保持你设的值 |
| 熄屏 / 口袋里 | 不做任何改动 |
| 重启后 | 自动恢复工作，不需要再点任何东西 |

## 下载与安装

1. 确认已 root 并装了 KernelSU
2. 下载 `install/sunlight-max-brightness-v2.2.zip`（或 Releases 里的同名文件）
3. KernelSU 管理器 → 模块 → 安装模块 → 选这个 zip → 重启
4. 装完不需要任何操作，模块默认就是开启状态

## 怎么用

**模块页面上的「执行动作」按钮 = 自动模式开关**（点一下关闭，再点开启；关闭时如果正在增亮会立刻还原亮度）。

终端里（`su` 之后）：

```sh
sh /data/adb/modules/sunlightmax/sunlight.sh status   # 在跑没有、上限值、当前亮度、阈值、最新光照
sh /data/adb/modules/sunlightmax/sunlight.sh lux      # 只看最近一次光照读数（约 16 秒更新一条）
sh /data/adb/modules/sunlightmax/sunlight.sh auto     # 开/关自动模式
sh /data/adb/modules/sunlightmax/sunlight.sh on       # 手动顶到上限（一次性）
sh /data/adb/modules/sunlightmax/sunlight.sh off      # 手动还原
```

## 调阈值（第一次到户外请务必看一眼）

编辑 `/data/adb/modules/sunlightmax/lux.conf`：

```
ENTER_LUX=12000    # 高于此值算阳光直射，开始计时增亮
EXIT_LUX=3000      # 低于此值算回到室内（必须小于 ENTER_LUX）
ENTER_MS=2000      # 高于阈值持续多久才拉满
EXIT_MS=6000       # 低于阈值持续多久才还原（调大可避免路过树荫就掉亮度）
RATE_US=1000000    # 采样间隔（微秒），一般不用动
```

参考：室内灯光 100–500，阴天户外 1000–10000，太阳直射 30000+（本机光感量程上限 65535）。
改完执行两次 `sunlight.sh auto`（先关后开）或直接重启生效。

## 这个模块到底改了你手机什么（可核对）

全部代码就在这个仓库里，一共 6 个 shell 脚本 + 1 个 11 KB 的 Java 源文件编译出的 dex。它写入的**只有两处**：

```
settings put system screen_brightness        # 亮度值
settings put system screen_brightness_mode   # 仅当你开着自动亮度时临时关掉，还原时改回
```

除此之外只读不写：`dumpsys display` 取两个上限值、`/sys/kernel/oplus_display/power_status` 判断亮屏、环境光传感器取光照。不挂载、不改分区、不注入 Zygisk、不联网、不收集任何数据。运行期文件都在 `/data/adb/modules/sunlightmax/` 里（日志 `luxloop.log`、状态标记 `boost_on`、你的原亮度 `saved_value`）。

## 原理

ColorOS 的亮度滑条顶端只到「常规上限」`mScreenBrightnessNormalMaximum`（本机 4095，对应背光原始值 3515），而显示服务允许写入的绝对上限是 `mScreenBrightnessRangeMaximum`（本机 4675 = `mMaxPanelBrightness`）。用 `settings put system screen_brightness 4675` 就能越过滑条顶端。再往上写会被系统夹回来，所以不存在"写挂黑屏"。

这台机器的出厂高亮模式（HBM）在这块屏上不存在（显示配置里 `mHbmData=null`），也没有任何属性或节点能抬天花板，所以 4675 就是软件能碰到的顶。

系统自动亮度被你关着，就没有任何东西在读光照（`dumpsys display` 里 `mAmbientLightSensor` 是空的，事件里 `lux=-1.0`）。所以本模块自带一个很小的 root `app_process` 进程，只订阅环境光传感器这一个数据源，在光照跨阈值时调用脚本写一次亮度。

## 实测成本（同一台机器 A/B）

- **CPU**：亮屏时约 0.2 秒 / 分钟（< 0.4% 单核）
- **内存**：常驻 PSS 约 95 MB。这是 `app_process` 的固定底座，加 `-Xmx` / `-Xms` / `HeapGrowthLimit` 都压不下去（实测过）
- **休眠**：开着它和关掉它都能正常进入 `Dozing` 并走到 `kernel power :0`，没有发现它阻碍休眠
- 两点如实说明：① 熄屏期间这颗光感仍以约 3.7 Hz 推事件，只是引擎在熄屏状态不会执行任何写操作；② 本机 `/sys/power/suspend_stats` 读不出数值，所以"每秒省多少毫安"我没有实测答案

不想要这个常驻进程 → 点一下模块按钮关掉自动模式，内存立刻释放；或者用 v1.0 的一键版（无常驻，但要手点）。

## 兼容性

两个上限值是运行时从系统读的，不是写死的，所以其他欧加/一加机型也能用。装完先跑一次 `status`：

```
滑条顶端=xxx 绝对上限=xxx
```

**两个值相等 = 这台机器没有额外余量**，模块会明确提示并拒绝做任何修改，引擎自己退出不会反复重试。这种情况下不要期待任何亮度提升。

## 已知边界

1. 增亮/还原/不覆盖手动值/熄屏不误触发/重启自启，全部在本机完整验证过；默认 `ENTER_LUX=12000` 也是作者在一加 Ace 5 **户外实测**后取的。但户外条件千差万别（隔着玻璃、阴天、光感被贴膜或保护壳挡住），第一次仍建议用 `lux` 命令看一眼自己机上的实际读数再微调。
2. 光感不支持批量读取（no batching），偶尔熄屏后数据变旧；引擎会丢弃 3 秒前的读数，并在亮屏且停滞超过 6 秒时自己重新订阅一次（日志出现「光照数据停滞，已重新订阅」属正常自愈）。
3. 太阳下屏幕本身会热，机身温控介入后会把亮度压回去，此时模块写的值仍是 4675 但显示没那么亮——这是温控，不是模块坏了。边充边玩更容易遇到。
4. 常驻 95 MB 内存换自动功能，是本机能做到的最低成本方案，但不是零成本。

## 反馈时请带上

```
su -c 'sh /data/adb/modules/sunlightmax/sunlight.sh status'
```

的完整输出，加上机型 / ColorOS 版本 / KernelSU 版本，以及户外用 `lux` 命令读到的数值。只有"没效果"三个字的反馈基本无法定位。

## 自己构建

```sh
javac --release 8 -encoding UTF-8 src/SunlightLux.java
java -cp <build-tools>/lib/d8.jar com.android.tools.r8.D8 --release --min-api 28 \
     --output . SunlightLux.class 'SunlightLux$LuxCb.class' 'SunlightLux$Beat.class'
# 把 classes.dex 压成 SunlightLux.jar 放进 module/，再用 python zipfile 打成模块 zip
# （ZipInfo.create_system=3，脚本 0o100750、数据 0o100644；不需要 META-INF）
```

## 许可与免责声明

GPL-3.0-only，见 `LICENSE`。改动系统亮度设置存在理论风险，请在自己设备上使用；刷机前建议备份。转发请保留出处，二次分发必须按 GPL 公开源码。
