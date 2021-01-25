package com.huxq17.download.core;

import android.net.Uri;
import android.text.TextUtils;

import com.huxq17.download.DownloadProvider;
import com.huxq17.download.ErrorCode;
import com.huxq17.download.core.task.DownloadTask;
import com.huxq17.download.utils.FileUtil;
import com.huxq17.download.utils.LogUtil;
import com.huxq17.download.utils.Util;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static com.huxq17.download.utils.Util.CONTENT_LENGTH_NOT_FOUND;
import static com.huxq17.download.utils.Util.DOWNLOAD_PART;
import static com.huxq17.download.utils.Util.TRANSFER_ENCODING_CHUNKED;

public class DownloadDetailsInfo {
    private WeakReference<Object> wfExtraData;
    protected final String url;
    private String filePath;
    protected final String id;
    private final String tag;
    private final long createTime;

    private long completedSize;
    private long contentLength = CONTENT_LENGTH_NOT_FOUND;
    private int finished;
    protected volatile DownloadInfo.Status status;
    private String speed;
    private ErrorCode errorCode;
    private File tempDir;
    private List<File> downloadPartFiles = new ArrayList<>();
    private PumpFile downloadFile;
    private DownloadTask downloadTask;
    private SpeedMonitor speedMonitor;

    private DownloadProvider.CacheBean cacheBean;
    private int progress;
    /**
     * True indicate that support breakpoint download.
     */
    private int threadNum;
    private boolean isForceRetry = false;
    private DownloadRequest downloadRequest;

    private String transferEncoding;
    private String md5;

    private final Uri schemaUri;
    private PumpFile.onPathChangedListener pathChangedListener = new PumpFile.onPathChangedListener() {

        @Override
        public void onPathChanged(String filePath) {
            if (filePath != null && !filePath.equals(DownloadDetailsInfo.this.filePath)) {
                DownloadDetailsInfo.this.filePath = filePath;
                deleteTempDir();
            }
        }
    };


    public DownloadDetailsInfo(String url, String filePath) {
        this(url, filePath, null, url, System.currentTimeMillis(), null);
    }

    public DownloadDetailsInfo(String url, String filePath, String tag, String id, long createTime, Uri schemaUri) {
        this.url = url;
        if (TextUtils.isEmpty(id)) {
            this.id = url;
        } else {
            this.id = id;
        }
        this.tag = tag;
        this.filePath = filePath;
        this.createTime = createTime;
        this.schemaUri = schemaUri;
        if (filePath != null) {
            createDownloadFile(filePath);
        }
        speedMonitor = new SpeedMonitor();
    }

    public void setForceRetry(boolean isForceRetry) {
        this.isForceRetry = isForceRetry;
    }

    public boolean isForceRetry() {
        return isForceRetry;
    }

    public void setThreadNum(int threadNum) {
        this.threadNum = threadNum;
    }

    public int getThreadNum() {
        return threadNum;
    }

    public void setFilePath(String filePath) {
        if (filePath != null ) {
            //if change directory, append the name.
            if (filePath.endsWith(File.separator) && !this.filePath.endsWith(File.separator)) {
                filePath = filePath + downloadFile.getName();
            }
            if(!filePath.equals(this.filePath)){
                this.filePath = filePath;
                createDownloadFile(filePath);
                LogUtil.e("setFilePath filePath=" + filePath);
            }
        }
    }

    private PumpFile createDownloadFile(String filePath) {
        if (downloadFile == null) {
            downloadFile = new PumpFile(filePath, schemaUri);
            downloadFile.setPathChangedListener(pathChangedListener);
        } else {
            deleteDownloadFile();
            downloadFile.setPath(filePath);
        }
        return downloadFile;
    }

    public void setDownloadTask(DownloadTask downloadTask) {
        this.downloadTask = downloadTask;
    }

    public DownloadTask getDownloadTask() {
        return downloadTask;
    }

    public DownloadRequest getDownloadRequest() {
        return downloadRequest;
    }

    public void setDownloadRequest(DownloadRequest downloadRequest) {
        this.downloadRequest = downloadRequest;
    }

    public boolean isDisableBreakPointDownload() {
        return downloadRequest.isDisableBreakPointDownload();
    }

    public void setCacheBean(DownloadProvider.CacheBean cacheBean) {
        this.cacheBean = cacheBean;
    }

    public DownloadProvider.CacheBean getCacheBean() {
        return cacheBean;
    }

    public String getMd5() {
        return md5 == null ? "" : md5;
    }

    public void setMD5(String md5) {
        this.md5 = md5;
    }

    public void setTransferEncoding(String transferEncoding) {
        this.transferEncoding = transferEncoding;
    }

    public boolean isChunked() {
        return TRANSFER_ENCODING_CHUNKED.equalsIgnoreCase(transferEncoding);
    }

    public void setCompletedSize(long completedSize) {
        this.completedSize = completedSize;
    }

    public void download(long length) {
        this.completedSize += length;
        speedMonitor.download(length);
    }

    public void computeSpeed() {
        this.speed = speedMonitor.getSpeed();
    }

    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    public void setFinished(int finished) {
        this.finished = finished;
    }

    public void setStatus(DownloadInfo.Status status) {
        this.status = status;
    }

    public boolean isDeleted() {
        return status == DownloadInfo.Status.DELETED;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public void setErrorCode(ErrorCode code) {
        setErrorCode(code, false);
    }

    public void setErrorCode(ErrorCode code, boolean force) {
        if (status != null && (status.isRunning() || force)) {
            this.errorCode = code;
            setStatus(DownloadInfo.Status.FAILED);
        }
    }

    public void clearErrorCode() {
        this.errorCode = null;
    }

    public DownloadInfo.Status getStatus() {
        return this.status;
    }

    public File getTempDir() {
        if (tempDir == null && this.url != null) {
            tempDir = Util.getTempDir(this.url);
        }
        return tempDir;
    }

    public boolean isFinished() {
        synchronized (this) {
            if (downloadFile == null) {
                return false;
            }
            if (this.finished == 1) {
                if (contentLength > 0 && downloadFile.length() == contentLength) {
                    return true;
                } else {
                    deleteDownloadFile();
                }
            }
            this.finished = 0;
            return false;
        }
    }

    /**
     * load completedSize if not finished.
     */
    private void loadDownloadFiles() {
        if (this.filePath == null) return;
        getTempDir();
        tempDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                if (name.startsWith(DOWNLOAD_PART)) {
                    File file = new File(dir, name);
                    downloadPartFiles.add(file);
                    completedSize += file.length();
                    return true;
                }
                return false;
            }
        });
    }

    public void calculateDownloadProgress() {
        if (isFinished()) {
            setCompletedSize(this.contentLength);
            if (this.status == null) {
                setStatus(DownloadInfo.Status.FINISHED);
            }
        } else {
            //Only load once.
            if (downloadPartFiles.size() == 0) {
                this.completedSize = 0;
                loadDownloadFiles();
            }
            if (this.status == null) {
                setStatus(DownloadInfo.Status.STOPPED);
            }
        }
        progress = (int) (completedSize * 1f / contentLength * 100);
    }

    public DownloadInfo snapshot() {
        computeSpeed();
        return new DownloadInfo(url, downloadFile, tag, id, createTime, speed, completedSize, contentLength,
                errorCode, status, finished, progress, this);
    }

    public PumpFile getDownloadFile() {
        return downloadFile;
    }

    public void deleteDownloadFile() {
        if (downloadFile != null) {
            downloadFile.delete();
        }
    }

    public void deleteTempDir() {
        //TODO 要改成Android Q 的删除方式
        if (getTempDir() != null) {
            FileUtil.deleteDir(getTempDir());
        }
    }

    public String getFilePath() {
        return filePath;
    }

    public String getName() {
        return downloadFile == null ? "" : downloadFile.getName();
    }

    public String getUrl() {
        return url;
    }

    public Uri getSchemaUri() {
        if (downloadRequest != null) {
            return downloadRequest.getUri();
        }
        return null;
    }

    public String getId() {
        return id;
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getTag() {
        return tag;
    }

    public int getFinished() {
        return finished;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getCompletedSize() {
        return completedSize;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public void setExtraData(Object extraData) {
        wfExtraData = new WeakReference<>(extraData);
    }

    public Object getWfExtraData() {
        return wfExtraData == null ? null : wfExtraData.get();
    }

    public synchronized boolean isRunning() {
        return status != null && status.isRunning();
    }
}
