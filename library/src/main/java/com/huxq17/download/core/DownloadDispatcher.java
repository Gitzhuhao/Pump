package com.huxq17.download.core;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.text.format.Formatter;

import com.huxq17.download.ErrorCode;
import com.huxq17.download.PumpFactory;
import com.huxq17.download.TaskManager;
import com.huxq17.download.core.service.IDownloadConfigService;
import com.huxq17.download.core.task.DownloadTask;
import com.huxq17.download.core.task.Task;
import com.huxq17.download.db.DBService;
import com.huxq17.download.core.service.IDownloadManager;
import com.huxq17.download.core.service.IMessageCenter;
import com.huxq17.download.utils.LogUtil;
import com.huxq17.download.utils.Util;

import java.io.File;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


public class DownloadDispatcher extends Task {
    private DownloadManager downloadManager;
    private AtomicBoolean isRunning = new AtomicBoolean();
    private AtomicBoolean isCanceled = new AtomicBoolean();
    private final ConcurrentLinkedQueue<DownloadRequest> requestQueue = new ConcurrentLinkedQueue<>();

    private Lock lock = new ReentrantLock();
    private Condition consumer = lock.newCondition();
    private HashSet<DownloadTaskExecutor> downloadTaskExecutors = new HashSet<>(1);
    private DownloadTaskExecutor defaultTaskExecutor;
    private DownloadInfoManager downloadInfoManager;

    DownloadDispatcher(DownloadManager downloadManager) {
        this.downloadManager = downloadManager;
    }

    public void start() {
        if (isRunning.getAndSet(true)) {
            return;
        }
        isCanceled.getAndSet(false);
        TaskManager.execute(this);
        downloadInfoManager = DownloadInfoManager.getInstance();
        defaultTaskExecutor = new SimpleDownloadTaskExecutor();
    }

    void enqueueRequest(final DownloadRequest downloadRequest) {
        start();
        if (isRunning()) {
            if (!requestQueue.contains(downloadRequest)) {
                requestQueue.add(downloadRequest);
                signalConsumer();
            } else {
                printExistRequestWarning(downloadRequest);
            }
        }
    }

    void consumeRequest() {
        waitForConsumer();
        DownloadRequest downloadRequest = requestQueue.poll();
        DownloadTask downloadTask = null;
        if (downloadRequest != null) {
            if (!downloadManager.isTaskRunning(downloadRequest.getId())) {
                downloadTask = createTaskFromRequest(downloadRequest);
            } else {
                printExistRequestWarning(downloadRequest);
            }
        }
        if (downloadTask != null) {
            DownloadTaskExecutor downloadTaskExecutor = downloadTask.getRequest().getDownloadExecutor();
            if (downloadTaskExecutor == null) {
                downloadTaskExecutor = defaultTaskExecutor;
            }
            if (!downloadTaskExecutors.contains(downloadTaskExecutor)) {
                downloadTaskExecutor.init();
                downloadTaskExecutors.add(downloadTaskExecutor);
            }
            downloadTaskExecutor.execute(downloadTask);
        }
    }

    @Override
    public void execute() {
        while (isRunnable()) {
            consumeRequest();
        }
        isRunning.getAndSet(false);
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    @Override
    public synchronized void cancel() {
        isCanceled.getAndSet(true);
        signalConsumer();
        downloadTaskExecutors.clear();
        if (defaultTaskExecutor != null) {
            defaultTaskExecutor.shutdown();
        }
    }

    boolean isBlockForConsumeRequest() {
        return requestQueue.isEmpty() && isRunnable();
    }

    void waitForConsumer() {
        lock.lock();
        try {
            while (isBlockForConsumeRequest()) {
                consumer.await();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    boolean isRunnable() {
        return isRunning() && !isCanceled.get();
    }

    void signalConsumer() {
        lock.lock();
        try {
            consumer.signal();
        } finally {
            lock.unlock();
        }
    }

    void printExistRequestWarning(DownloadRequest request) {
        LogUtil.w("task " + request.getName() + " already enqueue,we need do nothing.");
    }

    DownloadTask createTaskFromRequest(DownloadRequest downloadRequest) {
        //TODO 后续要加上剩余存储空间size的校验
//        if (!isUsableSpaceEnough(downloadRequest)) {
//            return null;
//        }
        String url = downloadRequest.getUrl();
        String id = downloadRequest.getId();
        String tag = downloadRequest.getTag();
        String filePath = downloadRequest.getFilePath();
        Uri schemaUri = downloadRequest.getUri();
        DownloadDetailsInfo downloadInfo = downloadRequest.getDownloadInfo();
        if (downloadInfo == null) {
            downloadInfo = createDownloadInfo(id, url, filePath, tag,schemaUri);
            downloadRequest.setDownloadInfo(downloadInfo);
        }
        if (downloadInfo.getFilePath() != null && downloadRequest.getFilePath() == null) {
            downloadRequest.setFilePath(downloadInfo.getFilePath());
        }
        downloadInfo.setDownloadRequest(downloadRequest);
        downloadInfo.setStatus(DownloadInfo.Status.STOPPED);
        return new DownloadTask(downloadRequest);
    }

    boolean isUsableSpaceEnough(DownloadRequest downloadRequest) {
        long downloadDirUsableSpace;
        String filePath = downloadRequest.getFilePath();
        if (filePath == null) {
            downloadDirUsableSpace = Util.getUsableSpace(new File(Util.getPumpCachePath(PumpFactory.getService(IDownloadManager.class).getContext())));
        } else {
            downloadDirUsableSpace = Util.getUsableSpace(new File(filePath));
        }
        long dataFileUsableSpace = Util.getUsableSpace(Environment.getDataDirectory());
        long minUsableStorageSpace = getMinUsableStorageSpace();
        if (downloadDirUsableSpace <= minUsableStorageSpace || dataFileUsableSpace <= minUsableStorageSpace) {
            Context context = PumpFactory.getService(IDownloadManager.class).getContext();
            String dataFileAvailableSize = Formatter.formatFileSize(context, dataFileUsableSpace);
            String downloadFileAvailableSize = Formatter.formatFileSize(context, downloadDirUsableSpace);
            LogUtil.e("Data directory usable space is " + dataFileAvailableSize + " and download directory usable space is " + downloadFileAvailableSize);
            DownloadDetailsInfo downloadInfo = downloadInfoManager.createDownloadInfo(downloadRequest.getUrl(),
                    filePath, downloadRequest.getTag(), downloadRequest.getId(), System.currentTimeMillis(), downloadRequest.getUri(),false);
            downloadInfo.setErrorCode(ErrorCode.ERROR_USABLE_SPACE_NOT_ENOUGH);
            PumpFactory.getService(IMessageCenter.class).notifyProgressChanged(downloadInfo);
            return false;
        }
        return true;
    }

    long getMinUsableStorageSpace() {
        return PumpFactory.getService(IDownloadConfigService.class).getMinUsableSpace();
    }

    DownloadDetailsInfo createDownloadInfo(String id, String url, String filePath, String tag,Uri schemaUri) {
        DownloadDetailsInfo downloadInfo = DBService.getInstance().getDownloadInfo(id);
        if (downloadInfo != null) {
            return downloadInfo;
        }
        //create a new instance if not found.
        downloadInfo = downloadInfoManager.createDownloadInfo(url, filePath, tag, id, System.currentTimeMillis(),schemaUri);
        DBService.getInstance().updateInfo(downloadInfo);
        return downloadInfo;
    }
}