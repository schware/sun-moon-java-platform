package com.sunmoon.platform.batch;

import java.util.List;

/** Writes one processed chunk. Called once per chunk (the "commit interval"), not once per item. */
@FunctionalInterface
public interface ItemWriter<O> {
    void writeChunk(List<O> items);
}
