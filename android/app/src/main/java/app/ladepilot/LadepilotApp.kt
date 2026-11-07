package app.ladepilot

import android.app.Application
import app.ladepilot.data.DataRepo

class LadepilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DataRepo.ensureLoaded(this)
    }
}
