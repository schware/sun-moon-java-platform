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

    /**
     * 같은 job 을 {@code everySeconds} 마다 되풀이합니다.
     *
     * <p>{@code scheduleOnce} 와 달리 첫 실행을 한 주기만큼 미룹니다 —
     * 기동 직후에는 마이그레이션과 연결 풀이 아직 자리를 잡는 중이고,
     * 그때 배치가 끼어들면 첫 회차가 이유 없이 실패합니다.
     *
     * <p>밀린 실행은 몰아서 돌리지 않습니다. 매출 분해는 다음 회차에
     * 어차피 같은 것을 다시 집으므로, 몰아치는 것은 손해뿐입니다.
     */
    public void scheduleEvery(String jobName, int everySeconds, Runnable job) throws SchedulerException {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(BatchJobRunner.JOB_KEY, job);

        JobDetail jobDetail = newJob(BatchJobRunner.class)
                .withIdentity(jobName)
                .usingJobData(dataMap)
                .build();

        Trigger trigger = newTrigger()
                .withIdentity(jobName + "-trigger")
                .startAt(new java.util.Date(System.currentTimeMillis() + everySeconds * 1000L))
                .withSchedule(org.quartz.SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(everySeconds)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();

        scheduler.scheduleJob(jobDetail, trigger);
        log.info("Scheduled batch job '{}' every {}s", jobName, everySeconds);
    }

    public void shutdown() throws SchedulerException {
        scheduler.shutdown(true);
    }
}
