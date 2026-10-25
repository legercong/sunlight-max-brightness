#!/system/bin/sh
# 自动阳光增亮的看护脚本：引擎异常退出时重启；被停用则退出
DIR=/data/adb/modules/sunlightmax
PIDF=$DIR/luxloop.pid
ERR=$DIR/luxloop.err
echo $$ > $PIDF

while :; do
    [ -f "$DIR/auto_disabled" ] && break
    if [ -f "$ERR" ] && [ "$(wc -c < $ERR)" -gt 262144 ]; then
        : > $ERR
    fi
    /system/bin/app_process -Djava.class.path=$DIR/SunlightLux.jar /system/bin SunlightLux >> $ERR 2>&1
    RC=$?
    # 0 = 引擎自己正常退出（本机无余量 / 找不到光感 / 已被停用），不再重启
    [ "$RC" = "0" ] && break
    [ -f "$DIR/auto_disabled" ] && break
    sleep 10
done

rm -f $PIDF
exit 0
