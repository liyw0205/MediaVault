package com.mediavault

import android.app.Application
import com.mediavault.data.LibraryRepository
import com.mediavault.scrape.ScrapeManager

class MediaVaultApp : Application() {
    lateinit var repository: LibraryRepository
        private set
    lateinit var scrapeManager: ScrapeManager
        private set

    override fun onCreate() {
        super.onCreate()
        repository = LibraryRepository(this)
        scrapeManager = ScrapeManager(this, repository)
        repository.reload()
        scrapeManager.restoreJobHint()
    }
}