package com.gagneflow.dto;

public class FileUploadRes {
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String vectorError;
    private String indexStatus = "done";

    public FileUploadRes() {
    }

    public FileUploadRes(String fileName, String filePath, Long fileSize) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
    }

    public FileUploadRes(String fileName, String filePath, Long fileSize, String vectorError) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.vectorError = vectorError;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public void setVectorError(String vectorError) {
        this.vectorError = vectorError;
    }

    public void setIndexStatus(String indexStatus) {
        this.indexStatus = indexStatus;
    }

    public String getFileName() {
        return this.fileName;
    }

    public String getFilePath() {
        return this.filePath;
    }

    public Long getFileSize() {
        return this.fileSize;
    }

    public String getVectorError() {
        return this.vectorError;
    }

    public String getIndexStatus() {
        return this.indexStatus;
    }
}
