package com.sunmoon.platform.batch;

import java.util.List;

public record JobExecution(String jobName, List<StepExecution> stepExecutions) {
    public boolean successful() {
        return stepExecutions.stream().allMatch(StepExecution::successful);
    }
}
