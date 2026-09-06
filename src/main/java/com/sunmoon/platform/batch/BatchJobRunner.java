package com.sunmoon.platform.batch;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/** Quartz-invoked shim: unwraps the {@link Runnable} stashed in the JobDataMap by {@link BatchScheduler} and runs it. */
public final class BatchJobRunner implements Job {

    static final String JOB_KEY = "batchJobRunnable";

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Object candidate = context.getJobDetail().getJobDataMap().get(JOB_KEY);
        if (!(candidate instanceof Runnable runnable)) {
            throw new JobExecutionException("no Runnable found under key '" + JOB_KEY + "'");
        }
        runnable.run();
    }
}
