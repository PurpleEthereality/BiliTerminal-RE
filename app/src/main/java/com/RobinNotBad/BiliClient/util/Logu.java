package com.RobinNotBad.BiliClient.util;

/*
 * 未完工的log
 */


import android.util.Log;

public class Logu {
    public static boolean LOGV_ENABLED = false;
    public static boolean LOGD_ENABLED = false;
    public static boolean LOGI_ENABLED = false;

    public static void v(String s) {
        if (!LOGV_ENABLED) return;
        Log.v(getCaller(), s);
    }

    public static void i(String s) {
        if (!LOGI_ENABLED) return;
        Log.i(getCaller(), s);
    }

    public static void d(String s) {
        if (!LOGD_ENABLED) return;
        Log.d(getCaller(), s);
    }

    public static void w(String s) {
        Log.w(getCaller(), s);
    }

    public static void e(String s) {
        Log.e(getCaller(), s);
    }

    public static void wtf(String s) {
        Log.wtf(getCaller(), s);
    }


    public static void v(String tag, String info) {
        if (!LOGV_ENABLED) return;
        Log.v(getCaller(), tag + ">" + info);
    }

    public static void i(String tag, String info) {
        if (!LOGI_ENABLED) return;
        Log.i(getCaller(), tag + ">" + info);
    }

    public static void d(String tag, String info) {
        if (!LOGD_ENABLED) return;
        Log.d(getCaller(), tag + ">" + info);
    }

    public static void w(String tag, String info) {
        Log.w(getCaller(), tag + ">" + info);
    }

    public static void e(String tag, String info) {
        Log.e(getCaller(), tag + ">" + info);
    }

    public static void wtf(String tag, String info) {
        Log.wtf(getCaller(), tag + ">" + info);
    }

    private static String getCaller() {
        // 原来硬编码取 stack[4]，等于把"调用深度"写死：v(String) 与 v(String,String) 这类
        // 重载深度不同，一旦深度变化就会取到上一层调用者甚至越界。改为从栈顶向下找
        // 第一个不属于 Logu 的帧——那才是真正调用日志方法的业务代码。
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StackTraceElement caller = stack[stack.length - 1];
        for (int i = 3; i < stack.length; i++) {
            if (!stack[i].getClassName().equals(Logu.class.getName())) {
                caller = stack[i];
                break;
            }
        }
        String name = caller.getClassName();
        int index = name.length();
        for (; index > 1; index--) {
            if (name.charAt(index - 1) == '.') break;
        }
        return name.substring(index) + ">" + caller.getMethodName();
    }
}
