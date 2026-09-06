package com.sunmoon.platform.batch;

import java.util.List;

/** Reads one chunk (up to {@code size} items) starting at {@code offset}; an empty list means "no more input." */
@FunctionalInterface
public interface ItemReader<T> {
    List<T> readChunk(int offset, int size);
}
