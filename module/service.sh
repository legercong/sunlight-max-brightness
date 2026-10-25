#!/system/bin/sh
# 开机后拉起自动阳光增亮引擎；用户停用过就不再启动，本脚本随即退出
DIR=/data/adb/modules/sunlightmax
[ -f "$DIR/auto_disabled" ] && exit 0
(
    until [ "$(getprop sys.boot_completed)" = "1" ]; do
        sleep 2
    done
    sleep 15
    nohup sh $DIR/lux_loop.sh >/dev/null 2>&1 &
) &
exit 0
