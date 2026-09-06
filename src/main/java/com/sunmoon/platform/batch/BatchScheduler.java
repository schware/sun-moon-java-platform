package com.sunmoon.platform.batch;

import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

/**
 * Quartz as pure Batch <em>Scheduler</em> — it only triggers a run; the
 * Job/Step/Chunk model itself lives in {@link BatchJob}/{@link ChunkStep}
 * (hand-rolled, no Spring Batch — see docs/adr/0002/0010).
 */
public final class BatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchScheduler.class);

    private final Scheduler scheduler;

    public BatchScheduler() throws SchedulerException {
        this.scheduler = StdSchedulerFactory.getDefaultScheduler();
        scheduler.start();
    }

    /** Fires {@code job} once, immediately. Cron-style recurring triggers are the same idea with a different {@link Trigger} — add when an actual schedule requirement shows up. */
    public void scheduleOnce(String jobName, Runnable job) throws SchedulerException {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(BatchJobRunner.JOB_KEY, job);

        JobDetail jobDetail = newJob(BatchJobRunner.class)
                .withIdentity(jobName)
                .usingJobData(dataMap)
                .build();

        Trigger trigger = newTrigger()
                .withIdentity(jobName + "-trigger")
                .startNow()
                .build();

        scheduler.scheduleJob(jobDetail, trigger);
        log.info("Scheduled batch job '{}' to run immediately", jobName);
    }

    public void shutdown() throws SchedulerException {
        scheduler.shutdown(true);
    }
}
