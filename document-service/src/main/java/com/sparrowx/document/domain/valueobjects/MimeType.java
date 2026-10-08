package com.sparrowx.document.domain.valueobjects;

import java.util.Locale;
import java.util.Set;

public record MimeType(String value) {

    public static final String PDF = "application/pdf";
    public static final String DOC = "application/msword";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String XLS = "application/vnd.ms-excel";
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String TEXT = "text/plain";
    public static final String MARKDOWN = "text/markdown";
    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String TIFF = "image/tiff";

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            PDF,
            DOC,
            DOCX,
            XLS,
            XLSX,
            TEXT,
            MARKDOWN,
            PNG,
            JPEG,
            TIFF
    );

    public MimeType {
        requireText(value, "mimeType");
        value = value.trim().toLowerCase(Locale.ROOT);

        if (!SUPPORTED_TYPES.contains(value)) {
            throw new IllegalArgumentException("Unsupported mimeType: " + value);
        }
    }

    public static MimeType of(String value) {
        return new MimeType(value);
    }

    public boolean isPdf() {
        return PDF.equals(value);
    }

    public boolean isDocx() {
        return DOCX.equals(value);
    }

    public boolean isXlsx() {
        return XLSX.equals(value);
    }

    public boolean isText() {
        return TEXT.equals(value);
    }

    public boolean isMarkdown() {
        return MARKDOWN.equals(value);
    }

    public boolean isTextual() {
        return isText() || isMarkdown();
    }

    public boolean isImage() {
        return value.startsWith("image/");
    }

    private static void requireText(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }
    }
}