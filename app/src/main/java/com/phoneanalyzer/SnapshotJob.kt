package com.phoneanalyzer

import android.app.job.JobParameters
import android.app.job.JobService

/** Ежедневное снятие снимка в фоне. */
class SnapshotJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { Snapshots.save(applicationContext, Collector(applicationContext).collect()) } catch (e: Exception) { }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
