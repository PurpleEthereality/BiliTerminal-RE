package com.RobinNotBad.BiliClient;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.RobinNotBad.BiliClient.activity.CatchActivity;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

public class ErrorCatch implements Thread.UncaughtExceptionHandler {
    @SuppressLint("StaticFieldLeak")
    public static ErrorCatch instance;
    private Context context;
    /** 安装本处理器之前的那一个（可能来自框架或崩溃上报 SDK），用于处理完自己的逻辑后交还。 */
    private Thread.UncaughtExceptionHandler previousHandler;

    public static ErrorCatch getInstance() {
        if (instance == null) instance = new ErrorCatch();
        return instance;
    }

    public void init(Context context) {
        this.context = context;
        // 记住被本类顶掉的那一个：不保存的话，任何先注册的崩溃上报都会静默失效
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        previousHandler = (previous == this) ? null : previous;
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        Writer writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);

        try {
            Intent intent = new Intent(context, CatchActivity.class);
            intent.putExtra("stack", writer.toString());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); //这句是安卓4必须有的
            context.startActivity(intent);
        } catch (Throwable t) {
            t.printStackTrace();
        }

        throwable.printStackTrace();
        // startActivity 是异步的，立即杀进程会让崩溃页来不及起来；
        // 这里等一小段时间让 CatchActivity 完成启动、崩溃信息有机会落地。
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }

        // 交还给安装本处理器之前的处理器，让先注册的崩溃上报/终止逻辑仍有机会执行
        // （框架默认实现会自行结束进程；自定义实现若只是上报，则继续走下面的 killProcess）
        Thread.UncaughtExceptionHandler previous = previousHandler;
        if (previous != null) {
            try {
                previous.uncaughtException(thread, throwable);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
