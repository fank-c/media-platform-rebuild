package com.calles.platform.transcode.application.client;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * 将本地临时物理文件适配为 Spring Web {@link MultipartFile} 的轻量级实现。
 *
 * <p>用于转码完成后将本地切片文件通过 OpenFeign Multipart 协议流式直传至 {@code file-service}。</p>
 */
public class FileSystemMultipartFile implements MultipartFile {

    private final File file;
    private final String contentType;

    public FileSystemMultipartFile(File file, String contentType) {
        this.file = file;
        this.contentType = (contentType != null && !contentType.isBlank()) ? contentType : "video/mp4";
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return file.getName();
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return !file.exists() || file.length() == 0;
    }

    @Override
    public long getSize() {
        return file.length();
    }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new FileInputStream(file);
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        Files.copy(file.toPath(), dest.toPath());
    }
}
