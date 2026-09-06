package com.sunmoon.platform.batch;

public record StepExecution(
        String stepName,
        int itemsRead,
        int chunksWritten,
        boolean successful,
        Throwable failure
) {
    public static StepExecution success(String stepName, int itemsRead, int chunksWritten) {
        return new StepExecution(stepName, itemsRead, chunksWritten, true, null);
    }

    public static StepExecution failed(String stepName, int itemsRead, int chunksWritten, Throwable failure) {
        return new StepExecution(stepName, itemsRead, chunksWritten, false, failure);
    }
}
