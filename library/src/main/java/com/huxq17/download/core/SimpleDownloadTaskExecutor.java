package com.huxq17.download.core;

import com.huxq17.download.PumpFactory;
import com.huxq17.download.core.task.DownloadTask;
import com.huxq17.download.manager.IDownloadManager;
import com.huxq17.download.utils.LogUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class SimpleDownloadTaskExecutor extends ThreadPoolExecutor implements DownloadTaskExecutor {
    private static final int DEFAULT_THREAD_COUNT = 3;
    private ConcurrentHashMap<String, Long> times = new ConcurrentHashMap<>();
    private DownLoadLifeCycleCallback downLoadLifeCycleCallback;

    public SimpleDownloadTaskExecutor() {
        super(DEFAULT_THREAD_COUNT, DEFAULT_THREAD_COUNT, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), new DownloadRejectedExecutionHandler());
    }

    @Override
    public void init() {
        setCorePoolSize(getSafeThreadCount());
        setMaximumPoolSize(getSafeThreadCount());
        setThreadFactory(new DownloadDisPatcherThreadFactory());
        downLoadLifeCycleCallback = PumpFactory.getService(IDownloadManager.class);
    }

    public void execute(DownloadTask downloadTask) {
        if (downloadTask == null) {
            throw new NullPointerException();
        }
        super.execute(downloadTask);
        LogUtil.d("Task " + downloadTask.getName() + " is ready.");
        if (getQueue().size() + getActiveCount() > getMaxDownloadNumber()) {
            String printName = getSafeName();
            LogUtil.w(printName + " only " + getMaxDownloadNumber()
                    + " tasks can be run at the same time;but " + getActiveCount()
                    + " tasks have been run,so " + downloadTask.getName() + " is waiting.");
        }
    }

    private int getSafeThreadCount() {
        return getMaxDownloadNumber() <= 0 ? DEFAULT_THREAD_COUNT : getMaxDownloadNumber();
    }

    private String getSafeName() {
        String name = getName();
        return name != null && name.length() > 0 ? name : toString();
    }

    private void checkIsDownloadTask(Runnable runnable) {
        if (!(runnable instanceof DownloadTask)) {
            throw new IllegalArgumentException("Only DownloadTask Can be executed.but execute " + runnable.getClass().getCanonicalName());
        }
    }

    protected final void beforeExecute(Thread t, Runnable r) {
        checkIsDownloadTask(r);
        DownloadTask downloadTask = (DownloadTask) r;
        downLoadLifeCycleCallback.onDownloadStart(downloadTask);
        LogUtil.d("start run " + downloadTask.getName());
        times.put(downloadTask.getId(), System.currentTimeMillis());
    }

    @Override
    protected final void afterExecute(Runnable r, Throwable t) {
        checkIsDownloadTask(r);
        DownloadTask downloadTask = (DownloadTask) r;
        downLoadLifeCycleCallback.onDownloadEnd(downloadTask);
        Long startTime = times.remove(downloadTask.getId());
        if (startTime != null) {
            LogUtil.d("download " + downloadTask.getName() + " is stopped,and spend=" + (System.currentTimeMillis() - startTime));
        }
    }

    public void shutdown() {
        shutdownNow();
    }

    private class DownloadDisPatcherThreadFactory implements ThreadFactory {
        private AtomicInteger count = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            String threadName = getSafeName() + "-thread-" + count.addAndGet(1);
            t.setName(threadName);
            return t;
        }
    }

    private static class DownloadRejectedExecutionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            LogUtil.e("rejectedExecution");
            if (executor.isShutdown()) return;
            executor.getQueue().offer(r);
        }
    }
}