package org.fluidmessages;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Sends queued messages once the phone is back online. Android runs it as soon as a network is available. */
public final class Outbox extends JobService {
    private static final int JOB = 61;
    private Thread worker;

    static void schedule(Context context) {
        var info = new JobInfo.Builder(JOB, new ComponentName(context, Outbox.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true).build();
        context.getSystemService(JobScheduler.class).schedule(info);
    }

    @Override public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            // Give the connections a moment to come back after the network returns.
            try { Thread.sleep(4000); } catch (InterruptedException stop) { return; }
            boolean done = DirectProtocols.flush(this);
            jobFinished(params, !done);
        }, "fluid-outbox");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return true;
    }
}
