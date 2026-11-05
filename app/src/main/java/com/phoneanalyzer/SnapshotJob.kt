package com.phoneanalyzer

import android.app.job.JobParameters
import android.app.job.JobService

/** Ежедневный снимок состояния. Ошибки и результат видны в уведомлениях. */
class SnapshotJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                val apps = Collector(applicationContext).collect()
                Snapshots.save(applicationContext, apps)
                AppNotifications.show(applicationContext, 4101, "Анализ телефона", "Снимок сохранён. Приложений: ${apps.size}.")
            } catch (e: Exception) {
                AppNotifications.show(applicationContext, 4102, "Анализ телефона: ошибка", e.message ?: "Не удалось сделать снимок.")
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
