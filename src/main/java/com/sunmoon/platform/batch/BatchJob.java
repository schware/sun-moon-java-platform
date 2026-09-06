package com.sunmoon.platform.batch;

import java.util.ArrayList;
import java.util.List;

public final class BatchJob {

    private final String name;
    private final List<Step> steps;

    public BatchJob(String name, List<Step> steps) {
        this.name = name;
        this.steps = List.copyOf(steps);
    }

    public JobExecution execute() {
        List<StepExecution> executions = new ArrayList<>();
        for (Step step : steps) {
            StepExecution execution = step.execute();
            executions.add(execution);
            if (!execution.successful()) {
                break;
            }
        }
        return new JobExecution(name, executions);
    }
}
