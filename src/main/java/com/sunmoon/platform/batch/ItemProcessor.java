package com.sunmoon.platform.batch;

@FunctionalInterface
public interface ItemProcessor<I, O> {
    O process(I item);
}
