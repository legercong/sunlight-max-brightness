#!/system/bin/sh
# 阳光最大亮度：把亮度写到框架允许的绝对上限（滑条顶端只到「常规上限」）
# 用法: sh sunlight.sh on|off|toggle|auto|status|lux
DIR=/data/adb/modules/sunlightmax
FLAG=$DIR/boost_on
SAVED=$DIR/saved_value
WAS_AUTO=$DIR/was_auto
AUTO_OFF=$DIR/auto_disabled
SUPER=$DIR/lux_loop.sh
PIDF=$DIR/luxloop.pid
LOG=$DIR/luxloop.log

# 从显示服务读两个上限，读不到时退回 PKG110 的实测值
DD=$(dumpsys display)
TOP=$(echo "$DD" | grep -m1 'mScreenBrightnessNormalMaximum' | cut -d= -f2 | cut -d. -f1)
MAX=$(echo "$DD" | grep -m1 'mScreenBrightnessRangeMaximum' | cut -d= -f2 | cut -d. -f1)
[ -z "$TOP" ] && TOP=4095
[ -z "$MAX" ] && MAX=4675

CUR=$(settings get system screen_brightness | cut -d. -f1)
MODE=$(settings get system screen_brightness_mode)
[ "$CUR" = "null" -o -z "$CUR" ] && CUR=$TOP

case "$1" in
on)
    if [ "$MAX" -le "$TOP" ]; then
        echo "本机没有额外余量（常规上限 $TOP = 绝对上限 $MAX），不做修改"
        exit 0
    fi
    if [ "$CUR" -lt "$MAX" ]; then
        echo "$CUR" > $SAVED
    fi
    if [ "$MODE" != "0" ]; then
        echo 1 > $WAS_AUTO
        settings put system screen_brightness_mode 0
    fi
    settings put system screen_brightness $MAX
    echo 1 > $FLAG
    echo "阳光最大亮度已开启：$CUR -> $MAX（原值已记住，关闭时还原）"
    ;;
off)
    BACK=$(cat $SAVED 2>/dev/null)
    [ -z "$BACK" ] && BACK=$TOP
    if [ "$CUR" -ge "$MAX" ]; then
        # 亮度还停在增亮值上，说明中间没人手动改过，还原是安全的
        settings put system screen_brightness $BACK
        echo "阳光最大亮度已关闭：恢复到 $BACK"
    else
        echo "当前亮度 $CUR 是你自己设的，保持不变"
    fi
    if [ -f $WAS_AUTO ]; then
        settings put system screen_brightness_mode 1
        rm -f $WAS_AUTO
    fi
    rm -f $FLAG $SAVED
    ;;
toggle)
    if [ -f $FLAG ]; then
        sh $DIR/sunlight.sh off
    else
        sh $DIR/sunlight.sh on
    fi
    ;;
auto)
    if [ -f "$AUTO_OFF" ]; then
        rm -f "$AUTO_OFF"
        nohup sh "$SUPER" >/dev/null 2>&1 &
        echo "自动阳光增亮已开启：阳光直射自动拉满，回室内自动还原"
    else
        echo 1 > "$AUTO_OFF"
        kill "$(cat $PIDF 2>/dev/null)" 2>/dev/null
        pkill -f 'SunlightLu[x]' 2>/dev/null
        rm -f "$PIDF"
        if [ -f "$FLAG" ]; then
            # 关掉自动时如果还停在增亮值上，立刻还原，免得没人管
            sh "$0" off
            echo "自动阳光增亮已关闭：亮度完全交给你手动控制（刚才的增亮已还原）"
        else
            echo "自动阳光增亮已关闭：亮度完全交给你手动控制"
        fi
    fi
    ;;
lux)
    LASTL=$(grep -E '当前 lux|收到首个光照事件' "$LOG" 2>/dev/null | tail -n 1)
    if [ -z "$LASTL" ]; then
        echo "还没有光照记录：自动模式可能没开，或者引擎刚启动（约 16 秒记一条）"
    else
        echo "$LASTL"
        echo "（这是引擎最近一次记录的值，约 16 秒更新一次）"
    fi
    ;;
status)
    if [ -f "$AUTO_OFF" ]; then
        echo "自动模式：已关闭"
    elif [ -s "$PIDF" ] && kill -0 "$(cat $PIDF 2>/dev/null)" 2>/dev/null; then
        echo "自动模式：运行中（守护 pid $(cat $PIDF)）"
    else
        echo "自动模式：应开启但进程不在"
    fi
    if [ -f $FLAG ]; then S="增亮中"; else S="未增亮"; fi
    echo "滑条顶端=$TOP 绝对上限=$MAX 当前设置=$CUR 状态=$S"
    if [ -f $DIR/lux.conf ]; then
        T2=$(grep -E '^ENTER_LUX' $DIR/lux.conf | cut -d= -f2)
        T3=$(grep -E '^EXIT_LUX' $DIR/lux.conf | cut -d= -f2)
        echo "阈值：阳光直射 $T2 lux 以上增亮，$T3 lux 以下还原"
    fi
    LASTL=$(grep -E '当前 lux|收到首个光照事件' "$LOG" 2>/dev/null | tail -n 1)
    [ -n "$LASTL" ] && echo "最新光照：$LASTL"
    ;;
*)
    echo "用法: sh sunlight.sh on|off|toggle|auto|status|lux"
    ;;
esac
