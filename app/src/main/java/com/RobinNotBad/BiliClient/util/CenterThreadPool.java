package com.RobinNotBad.BiliClient.util;

import android.os.Handler;
import android.os.Looper;

import androidx.core.util.Consumer;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Dispatchers;

/**
 * @author silent碎月
 * 核心运行线程池
 * BuildersKt.launch 系列调用可以在java端调起协程,更加轻量
 */
public class CenterThreadPool {

    private static final boolean FORCE_DISABLED = false;
    private static final Handler MAIN_THREAD_HANDLER = new Handler(Looper.getMainLooper());
    private static final CoroutineScope COROUTINE_SCOPE;

    static {
        // 原来这里按 SDK_INT < 17 分出一个裸线程池分支；本项目 minSdk 24，该分支不可达，
        // 连带 THREAD_POOL 字段、getThreadPoolInstance()（首行拿到 null 就返回）以及
        // run() 里的 `else if (THREAD_POOL != null)` 全是死代码，已一并删除。
        COROUTINE_SCOPE = CoroutineScopeKt.CoroutineScope((CoroutineContext) Dispatchers.getIO());
    }


    /**
     * 在后台运行, 用于网络请求等耗时操作
     *
     * @param runnable 要运行的任务
     */
    public static void run(Runnable runnable) {
        try {
            BuildersKt.launch(COROUTINE_SCOPE, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT, (CoroutineScope scope, Continuation<? super Unit> continuation) -> {
                try {
                    runnable.run();
                } catch (Throwable e) {
                    // 协程体内未捕获异常没有 CoroutineExceptionHandler 兜底，
                    // 会冒泡到 Thread.setDefaultUncaughtExceptionHandler（ErrorCatch），直接杀掉整个应用。
                    // 后台任务失败不应该拖垮 App，这里兜住并只记日志。
                    MsgUtil.err(e);
                }
                return Unit.INSTANCE;
            });
        } catch (Throwable e) {
            // 调度本身失败（极小概率）时再放手一博：原先这里只是把 new Thread 注释掉，
            // 等于任务被静默丢弃；现在真的开一条裸线程兜底。
            try {
                new Thread(runnable).start();
            } catch (Throwable t) {
                MsgUtil.err(e);
            }
        }
    }

    /**
     * 在后台运行, 用于网络请求等耗时操作, 有返回值,
     * 在fragment, activity等位置使用LiveData.observe()获取返回值, 会自动切到主线程,不需要再runOnUiThread().
     *
     * @param supplier 要运行的任务
     * @param <T>      返回值类型
     * @return LiveData包装的返回值
     */
    public static <T> LiveData<Result<T>> supplyAsyncWithLiveData(Callable<T> supplier) {
        MutableLiveData<Result<T>> retval = new MutableLiveData<>();
        CenterThreadPool.run(() -> {
            try {
                T res = supplier.call();
                retval.postValue(Result.success(res));
            } catch (Exception e) {
                retval.postValue(Result.failure(e));
                MsgUtil.err(e);
            }
        });
        return retval;
    }

    /**
     * 在后台运行， 有返回值
     * 使用 CenterThreadPool.observe方法对返回值进行观察
     *
     * @param supplier 一个带返回值的lambda表达式或Supplier的实现类
     * @param <T>      返回值类型
     * @return 返回一个可供CenterThreadPool观察的Future对象
     */
    public static <T> Future<T> supplyAsyncWithFuture(Callable<T> supplier) {
        FutureTask<T> ftask = new FutureTask<>(supplier);
        CenterThreadPool.run(ftask);
        return ftask;
    }

    /**
     * 对Deferred 对象进行观察， 无需切换线程， 自动在ui线程进行观察
     *
     * @param deferred 一个将要在未来返回一个 T 类型对象的对象
     * @param consumer 对T进行观察的lambda表达式或者类
     * @param <T>      要观察的类型
     */
    public static <T> void observe(Future<T> deferred, Consumer<T> consumer) {
        CenterThreadPool.run(() -> {
            try {
                T value = deferred.get();
                CenterThreadPool.runOnUiThread(() -> consumer.accept(value));
            } catch (Throwable ignored) {
            }
        });
    }


    public static <T> void observe(Future<T> future, Consumer<T> consumer, Consumer<Throwable> onFailure) {
        CenterThreadPool.run(() -> {
            try {
                T value = future.get();
                CenterThreadPool.runOnUiThread(() -> consumer.accept(value));
            } catch (Exception e) {
                onFailure.accept(e);
            }
        });
    }

    /**
     * 在主线程运行, 用于更新UI, 例如Toast, Snackbar等
     *
     * @param runnable 要运行的任务
     */
    public static void runOnUiThread(Runnable runnable) {
        MAIN_THREAD_HANDLER.post(runnable);
    }

    public static void runOnUIThreadAfter(long time, TimeUnit unit, Runnable runnable) {
        long millis = TimeUnit.MILLISECONDS.convert(time, unit);
        MAIN_THREAD_HANDLER.postDelayed(runnable, millis);
    }

    public static void runOnUIThreadAfter(long time, Runnable runnable) {
        MAIN_THREAD_HANDLER.postDelayed(runnable, time);
    }

}