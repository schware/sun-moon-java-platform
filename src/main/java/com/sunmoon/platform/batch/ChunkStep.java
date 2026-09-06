package com.sunmoon.platform.batch;

import java.util.List;

/**
 * A hand-rolled stand-in for Spring Batch's chunk-oriented Step — reads,
 * processes, and writes one chunk at a time until the reader runs dry (see
 * docs/adr/0002 for why this is hand-rolled instead of using real Spring
 * Batch). Mirrors {@code sun-moon-c-server}'s batch.h/batch.c (a struct of
 * function pointers standing in for Reader/Processor/Writer), just in Java
 * idiom (generics + interfaces) instead of C's function pointers.
 */
public final class ChunkStep<I, O> implements Step {

    private final String name;
    private final ItemReader<I> reader;
    private final ItemProcessor<I, O> processor;
    private final ItemWriter<O> writer;
    private final int chunkSize;

    public ChunkStep(String name, ItemReader<I> reader, ItemProcessor<I, O> processor, ItemWriter<O> writer, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive, got " + chunkSize);
        }
        this.name = name;
        this.reader = reader;
        this.processor = processor;
        this.writer = writer;
        this.chunkSize = chunkSize;
    }

    @Override
    public StepExecution execute() {
        int offset = 0;
        int itemsRead = 0;
        int chunksWritten = 0;
        try {
            while (true) {
                List<I> chunk = reader.readChunk(offset, chunkSize);
                if (chunk.isEmpty()) {
                    break;
                }
                List<O> processed = chunk.stream().map(processor::process).toList();
                writer.writeChunk(processed);

                itemsRead += chunk.size();
                chunksWritten++;
                offset += chunk.size();
            }
            return StepExecution.success(name, itemsRead, chunksWritten);
        } catch (RuntimeException e) {
            return StepExecution.failed(name, itemsRead, chunksWritten, e);
        }
    }
}
