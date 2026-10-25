#!/system/bin/sh
# 卸载前先停掉自动引擎，再把亮度还原成用户自己的值
D=/data/adb/modules/sunlightmax
echo 1 > $D/auto_disabled
kill "$(cat $D/luxloop.pid 2>/dev/null)" 2>/dev/null
pkill -f 'SunlightLu[x]' 2>/dev/null
sleep 1
B=$(cat $D/saved_value 2>/dev/null)
if [ -n "$B" ]; then
    settings put system screen_brightness $B
fi
if [ -f $D/was_auto ]; then
    settings put system screen_brightness_mode 1
fi
exit 0
