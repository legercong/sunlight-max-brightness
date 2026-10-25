// SPDX-License-Identifier: GPL-3.0-only
// 阳光最大亮度（户外自动增亮）光照引擎
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Properties;

/**
 * 阳光自动增亮引擎：订阅环境光传感器，亮度状态机只在"光照跨阈值"时调用 sunlight.sh。
 * 用 app_process 以 root 运行，不依赖 android.jar（框架侧全部走反射）。
 */
public class SunlightLux {
    static final String DIR = "/data/adb/modules/sunlightmax";
    static final String SH = DIR + "/sunlight.sh";
    static final String FLAG = DIR + "/boost_on";
    static final String LOG = DIR + "/luxloop.log";
    static final String PWR = "/sys/kernel/oplus_display/power_status";

    static double ENTER = 8000.0;
    static double EXIT = 3000.0;
    static long ENTER_MS = 2000;
    static long EXIT_MS = 6000;
    static int RATE_US = 1000000;

    static Object mgr;
    static Object light;
    static Object listener;
    static Method register;
    static Method unregister;
    static long lastEventMs = 0;
    static long lastResubMs = 0;
    static boolean armed = true;
    static long highSince = -1;
    static long lowSince = -1;
    static long events = 0;

    public static void main(String[] args) throws Exception {
        Class<?> looperC = Class.forName("android.os.Looper");
        looperC.getMethod("prepareMainLooper").invoke(null);

        Class<?> vm = Class.forName("dalvik.system.VMRuntime");
        Object runtime = vm.getDeclaredMethod("getRuntime").invoke(null);
        vm.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                .invoke(runtime, (Object) new String[]{"L"});

        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getDeclaredMethod("systemMain").invoke(null);
        final Object ctx = at.getDeclaredMethod("getSystemContext").invoke(thread);

        mgr = Class.forName("android.content.Context")
                .getDeclaredMethod("getSystemService", String.class)
                .invoke(ctx, "sensor");
        Class<?> smC = Class.forName("android.hardware.SensorManager");
        Class<?> sensorC = Class.forName("android.hardware.Sensor");
        Class<?> listenerC = Class.forName("android.hardware.SensorEventListener");

        light = smC.getMethod("getDefaultSensor", int.class).invoke(mgr, Integer.valueOf(5));
        if (light == null) {
            log("找不到环境光传感器，引擎退出");
            return;
        }
        Method register = smC.getMethod("registerListener", listenerC, sensorC, int.class, int.class);
        SunlightLux.register = register;
        SunlightLux.unregister = smC.getMethod("unregisterListener", listenerC);

        loadConf();
        log("引擎启动 ENTER=" + ENTER + " EXIT=" + EXIT + " ENTER_MS=" + ENTER_MS
                + " EXIT_MS=" + EXIT_MS + " RATE_US=" + RATE_US);

        listener = Proxy.newProxyInstance(SunlightLux.class.getClassLoader(),
                new Class<?>[]{listenerC}, new LuxCb());
        register.invoke(mgr, listener, light, Integer.valueOf(RATE_US), Integer.valueOf(0));
        log("已订阅光照传感器");

        // 心跳：亮屏期间如果光照数据停滞（多半是熄屏后 HAL 不再推 on-change 事件），重新订阅一次。
        // 消息队列的超时走 monotonic 时钟，不是唤醒源，所以熄屏休眠时它不会把机器叫醒。
        Object handler = Class.forName("android.os.Handler")
                .getConstructor(Class.forName("android.os.Looper"))
                .newInstance(looperC.getMethod("getMainLooper").invoke(null));
        Runnable beat = (Runnable) Proxy.newProxyInstance(SunlightLux.class.getClassLoader(),
                new Class<?>[]{Runnable.class}, new Beat(handler));
        Class.forName("android.os.Handler").getMethod("postDelayed", Runnable.class, long.class)
                .invoke(handler, beat, 3000L);

        looperC.getMethod("loop").invoke(null);
    }

    /** 停滞自愈 + 停用检查 */
    static class Beat implements InvocationHandler {
        final Object handler;

        Beat(Object handler) {
            this.handler = handler;
        }

        public Object invoke(Object proxy, Method m, Object[] a) {
            String n = m.getName();
            if (n.equals("hashCode")) return Integer.valueOf(System.identityHashCode(proxy));
            if (n.equals("equals")) return Boolean.valueOf(proxy == a[0]);
            if (n.equals("toString")) return "LuxBeat";
            if (!n.equals("run")) return null;
            try {
                if (new File(DIR, "auto_disabled").exists()) {
                    log("检测到停用标记，引擎退出");
                    System.exit(0);
                }
                long now = elapsedRealtimeNanos() / 1000000L;
                if (screenOn() && now - lastEventMs > 6000 && now - lastResubMs > 10000) {
                    lastResubMs = now;
                    resubscribe();
                }
            } catch (Throwable t) {
                log("心跳异常：" + t);
            }
            try {
                Class.forName("android.os.Handler")
                        .getMethod("postDelayed", Runnable.class, long.class)
                        .invoke(handler, proxy, 3000L);
            } catch (Throwable t) {
                log("心跳重排失败：" + t);
            }
            return null;
        }
    }

    static void resubscribe() {
        try {
            unregister.invoke(mgr, listener);
            register.invoke(mgr, listener, light, Integer.valueOf(RATE_US), Integer.valueOf(0));
            lastEventMs = elapsedRealtimeNanos() / 1000000L;
            log("光照数据停滞，已重新订阅");
        } catch (Throwable t) {
            log("重新订阅失败：" + t);
        }
    }

    static class LuxCb implements InvocationHandler {
        public Object invoke(Object proxy, Method m, Object[] a) {
            String n = m.getName();
            if (n.equals("hashCode")) return Integer.valueOf(System.identityHashCode(proxy));
            if (n.equals("equals")) return Boolean.valueOf(proxy == a[0]);
            if (n.equals("toString")) return "LuxListener";
            if (!n.equals("onSensorChanged")) return null;
            try {
                Object ev = a[0];
                float lux = ((float[]) ev.getClass().getField("values").get(ev))[0];
                long ts = ev.getClass().getField("timestamp").getLong(ev);
                long now = elapsedRealtimeNanos();
                if (now - ts > 3000000000L) return null;   // 陈旧数据（息屏期间攒下的）
                tick(lux, now / 1000000L);
            } catch (Throwable t) {
                log("事件处理异常：" + t);
            }
            return null;
        }
    }

    static void tick(double lux, long nowMs) {
        events++;
        lastEventMs = nowMs;
        if (events == 1) log("收到首个光照事件 lux=" + lux);
        if (events % 60 == 0) log("当前 lux=" + lux + "（可增亮=" + armed + " 增亮中=" + flagExists() + "）");

        if (lux >= ENTER) {
            lowSince = -1;
            if (highSince < 0) highSince = nowMs;
            if (armed && nowMs - highSince >= ENTER_MS && !flagExists()) {
                if (!screenOn()) return;
                if (!run("on")) return;
                if (!flagExists()) {
                    log("本机亮度没有额外余量，引擎退出");
                    System.exit(0);
                }
                armed = false;
                highSince = -1;
                log("阳光直射 lux=" + lux + " → 已拉到绝对上限");
            }
        } else if (lux <= EXIT) {
            highSince = -1;
            if (lowSince < 0) lowSince = nowMs;
            if (nowMs - lowSince >= EXIT_MS) {
                if (flagExists()) {
                    if (!screenOn()) return;
                    run("off");
                    log("回到室内 lux=" + lux + " → 已恢复默认亮度");
                }
                lowSince = -1;
                armed = true;
            }
        } else {
            highSince = -1;
            lowSince = -1;
        }
    }

    /** 屏幕是否点亮：kernel power :2 表示亮屏，:3/:0 是 doze 或更深休眠 */
    static boolean screenOn() {
        try (BufferedReader r = new BufferedReader(new FileReader(PWR))) {
            String line = r.readLine();
            if (line == null) return true;
            int i = line.indexOf("kernel power");
            if (i < 0) return true;
            String digits = line.substring(i + 12).replaceAll("[^0-9]", "");
            if (digits.isEmpty()) return true;
            return digits.charAt(0) == '2';
        } catch (Throwable t) {
            return true;   // 读不到就当亮屏，宁可多亮一下也不要失灵
        }
    }

    /** 返回 false 表示脚本没跑起来 */
    static boolean run(String verb) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c",
                    "sh " + SH + " " + verb + " >/dev/null 2>&1"});
            int rc = p.waitFor();
            if (rc != 0) log("sunlight.sh " + verb + " 返回 " + rc);
            return true;
        } catch (Throwable t) {
            log("调用 sunlight.sh 失败：" + t);
            return false;
        }
    }

    static boolean flagExists() {
        return new File(FLAG).exists();
    }

    static long elapsedRealtimeNanos() throws Exception {
        return ((Long) Class.forName("android.os.SystemClock")
                .getMethod("elapsedRealtimeNanos").invoke(null)).longValue();
    }

    static void loadConf() {
        File f = new File(DIR, "lux.conf");
        if (!f.exists()) return;
        Properties p = new Properties();
        try (FileReader r = new FileReader(f)) {
            p.load(r);
            ENTER = dbl(p, "ENTER_LUX", ENTER);
            EXIT = dbl(p, "EXIT_LUX", EXIT);
            ENTER_MS = (long) dbl(p, "ENTER_MS", ENTER_MS);
            EXIT_MS = (long) dbl(p, "EXIT_MS", EXIT_MS);
            RATE_US = (int) dbl(p, "RATE_US", RATE_US);
        } catch (Throwable t) {
            log("lux.conf 读取失败，用默认值：" + t);
        }
        if (EXIT >= ENTER) {
            log("lux.conf 里 EXIT_LUX 必须小于 ENTER_LUX，用默认值");
            ENTER = 8000.0;
            EXIT = 3000.0;
        }
    }

    static double dbl(Properties p, String k, double def) {
        String v = p.getProperty(k);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    static void log(String s) {
        try {
            File f = new File(LOG);
            if (f.length() > 262144) f.renameTo(new File(LOG + ".1"));
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date())
                        + " " + s + "\n");
            }
        } catch (Throwable ignore) {
            // 日志失败不影响主流程
        }
    }
}
