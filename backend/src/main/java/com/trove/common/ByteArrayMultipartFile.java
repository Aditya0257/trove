/*
 * ============================================================================
 *  ByteArrayMultipartFile — an in-memory MultipartFile over raw bytes
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Wraps a byte[] (plus filename + content type) as a Spring MultipartFile so raw
 *  bytes from a non-HTTP-upload source can flow through the same code that handles
 *  browser uploads.
 *
 *  Business use case
 *  -----------------
 *  Forwarded documents (email/WhatsApp) arrive as bytes, not as a multipart form
 *  field. This adapter lets ingestion reuse the exact upload → dedupe → store →
 *  sidecar → extract → review pipeline, so forwarded docs behave identically to
 *  uploaded ones.
 *
 *  Design
 *  ------
 *  Minimal, immutable adapter. transferTo writes the bytes to disk (rarely used
 *  here, but part of the contract).
 * ============================================================================
 */
package com.trove.common;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class ByteArrayMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    public ByteArrayMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content != null ? content : new byte[0];
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() {
        return content;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(java.io.File dest) throws IOException {
        Files.write(dest.toPath(), content);
    }
}
