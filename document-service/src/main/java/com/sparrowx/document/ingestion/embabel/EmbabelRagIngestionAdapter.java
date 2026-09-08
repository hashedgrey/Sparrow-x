package com.sparrowx.document.ingestion.embabel;

import com.embabel.agent.rag.ingestion.ContentChunker;
import com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader;
import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.LeafSection;
import com.embabel.agent.rag.model.NavigableContainerSection;
import com.embabel.agent.rag.model.NavigableDocument;
import com.embabel.agent.rag.model.NavigableSection;
import com.sparrowx.document.domain.valueobjects.ChunkId;
import com.sparrowx.document.domain.valueobjects.DocumentId;
import com.sparrowx.document.domain.valueobjects.FileName;
import com.sparrowx.document.domain.valueobjects.MimeType;
import com.sparrowx.document.domain.valueobjects.ObjectKey;
import com.sparrowx.document.exceptions.DocumentExtractionException;
import com.sparrowx.document.exceptions.InvalidDocumentException;
import com.sparrowx.document.ingestion.chunking.DocumentChunkDraft;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class EmbabelRagIngestionAdapter {

    private final TikaHierarchicalContentReader contentReader;
    private final PdfPageHierarchicalContentReader pdfPageContentReader;
    private final ContentChunker contentChunker;

    public EmbabelRagIngestionAdapter(
            TikaHierarchicalContentReader contentReader,
            PdfPageHierarchicalContentReader pdfPageContentReader,
            ContentChunker contentChunker
    ) {
        this.contentReader = contentReader;
        this.pdfPageContentReader = pdfPageContentReader;
        this.contentChunker = contentChunker;
    }

    public EmbabelRagIngestionResult ingest(
            DocumentId documentId,
            ObjectKey objectKey,
            FileName fileName,
            MimeType mimeType,
            byte[] content
    ) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");

        if (content == null || content.length == 0) {
            throw InvalidDocumentException.emptyContent();
        }

        NavigableDocument document =
                parseDocument(
                        documentId,
                        objectKey,
                        fileName,
                        mimeType,
                        content
                );

        failOnParseError(document, fileName);

        Map<String, Object> sparrowMetadata = new LinkedHashMap<>();

        sparrowMetadata.put("sparrowx_document_id", documentId.value());
        sparrowMetadata.put("sparrowx_object_key", objectKey.value());
        sparrowMetadata.put("sparrowx_file_name", fileName.value()
        );
        sparrowMetadata.put("sparrowx_mime_type", mimeType.value());

        document = document.withMetadata(
                sparrowMetadata
        );

        List<Chunk> embabelChunks =
                chunkDocument(
                        document,
                        mimeType
                );

        Map<String, Object> documentMetadata = document.getMetadata();

        List<Chunk> enrichedChunks = embabelChunks.stream().map(chunk ->
                                chunk.withAdditionalMetadata(documentMetadata)).toList();

        List<DocumentChunkDraft> chunkDrafts =
                toChunkDrafts(
                        documentId,
                        enrichedChunks
                );

        return new EmbabelRagIngestionResult(
                extractText(document),
                resolvePageCount(document.getMetadata()),
                enrichedChunks,
                chunkDrafts
        );
    }

    private NavigableDocument parseDocument(
            DocumentId documentId,
            ObjectKey objectKey,
            FileName fileName,
            MimeType mimeType,
            byte[] content
    ) {
        /*
         * PDF requires page-aware hierarchy so citation provenance
         * survives chunking.
         */
        if (pdfPageContentReader.supports(mimeType)) {
            return pdfPageContentReader.parse(
                    documentId,
                    objectKey,
                    fileName,
                    content
            );
        }

        Metadata tikaMetadata =
                new Metadata();

        tikaMetadata.set(
                TikaCoreProperties.RESOURCE_NAME_KEY,
                fileName.value()
        );

        tikaMetadata.set(
                TikaCoreProperties.CONTENT_TYPE_HINT,
                mimeType.value()
        );

        try (ByteArrayInputStream inputStream =
                     new ByteArrayInputStream(content)) {

            return contentReader.parseContent(
                    inputStream,
                    objectKey.value(),
                    tikaMetadata
            );

        } catch (Exception exception) {
            throw new DocumentExtractionException(
                    "Embabel RAG failed to parse document: "
                            + fileName.value(),
                    exception
            );
        }
    }

    private List<Chunk> chunkDocument(
            NavigableDocument document,
            MimeType mimeType
    ) {
        /*
         * Important:
         *
         * A PDF root contains one container per physical page.
         *
         * Chunk each page container independently. If we chunked the
         * document root directly, Embabel could group leaves belonging
         * to different pages into the same chunk, destroying exact
         * page citation provenance.
         */
        if (pdfPageContentReader.supports(mimeType)) {

            List<Chunk> chunks =
                    new ArrayList<>();

            for (NavigableSection child :
                    document.getChildren()) {

                if (!(child instanceof NavigableContainerSection page)) {
                    continue;
                }

                for (Chunk chunk :
                        contentChunker.chunk(page)) {

                    chunks.add(chunk);
                }
            }

            return chunks;
        }

        List<Chunk> chunks =
                new ArrayList<>();

        for (Chunk chunk :
                contentChunker.chunk(document)) {

            chunks.add(chunk);
        }

        return chunks;
    }

    private List<DocumentChunkDraft> toChunkDrafts(
            DocumentId documentId,
            List<Chunk> embabelChunks
    ) {
        List<DocumentChunkDraft> chunkDrafts =
                new ArrayList<>();

        int globalChunkIndex = 0;

        for (Chunk originalChunk :
                embabelChunks) {

            /*
             * AbstractChunkingContentElementRepository normally merges
             * root metadata after chunking. SparrowX intentionally uses
             * ContentChunker directly, so reproduce only that neutral
             * metadata behavior here.
             */
            Chunk chunk = originalChunk;

            Map<String, String> metadata =
                    toStringMetadata(
                            chunk.getMetadata()
                    );

            metadata.put(
                    "embabel_parent_id",
                    chunk.getParentId()
            );

            /*
             * SparrowX requires document-global chunk indexes because
             * document_chunks is unique on:
             *
             * (document_id, chunk_index)
             *
             * Embabel's structural chunk index may restart inside each
             * page/section, so preserve Embabel's value in metadata but
             * use our own monotonically increasing persistence index.
             */
            int chunkIndex =
                    globalChunkIndex++;

            int pageStart =
                    intMetadata(
                            chunk.getMetadata(),
                            "page_start",
                            0
                    );

            int pageEnd =
                    intMetadata(
                            chunk.getMetadata(),
                            "page_end",
                            pageStart
                    );

            chunkDrafts.add(
                    new DocumentChunkDraft(
                            documentId,
                            new ChunkId(chunk.getId()),
                            chunk.getText(),
                            chunkIndex,
                            pageStart,
                            pageEnd,
                            metadata
                    )
            );
        }

        return chunkDrafts;
    }

    private void failOnParseError(
            NavigableDocument document,
            FileName fileName
    ) {
        Object error =
                document.getMetadata().get("error");

        if (error == null) {
            return;
        }

        String message =
                error.toString();

        if (message.isBlank()) {
            return;
        }

        throw new DocumentExtractionException(
                "Embabel RAG failed to parse document: "
                        + fileName.value(),
                new IllegalStateException(message)
        );
    }

    private String extractText(
            NavigableDocument document
    ) {
        StringBuilder text =
                new StringBuilder();

        for (LeafSection leaf :
                document.leaves()) {

            if (leaf.getText() == null
                    || leaf.getText().isBlank()) {
                continue;
            }

            if (!text.isEmpty()) {
                text.append("\n\n");
            }

            text.append(
                    leaf.getText().trim()
            );
        }

        return text.toString();
    }

    private int resolvePageCount(
            Map<String, Object> metadata
    ) {
        String[] candidates = {
                "xmpTPg:NPages",
                "Page-Count",
                "meta:page-count"
        };

        for (String candidate :
                candidates) {

            int value =
                    intMetadata(
                            metadata,
                            candidate,
                            -1
                    );

            if (value > 0) {
                return value;
            }
        }

        return 1;
    }

    private int intMetadata(
            Map<String, ?> metadata,
            String key,
            int fallback
    ) {
        Object value =
                metadata.get(key);

        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value == null) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.toString().trim()
            );
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Map<String, String> toStringMetadata(
            Map<String, ?> source
    ) {
        Map<String, String> result =
                new LinkedHashMap<>();

        source.forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(
                        key,
                        value.toString()
                );
            }
        });

        return result;
    }

    public record EmbabelRagIngestionResult(
            String extractedText,
            int pageCount,
            List<Chunk> embabelChunks,
            List<DocumentChunkDraft> chunks
    ) {
        public EmbabelRagIngestionResult {
            extractedText = extractedText == null ? "" : extractedText;
            pageCount = Math.max(1, pageCount);
            embabelChunks = embabelChunks == null ? List.of() : List.copyOf(embabelChunks);
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }
}