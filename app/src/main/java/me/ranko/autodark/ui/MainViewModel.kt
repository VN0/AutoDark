package me.ranko.autodark.ui

import android.app.Activity
import android.app.Application
import android.app.UiModeManager
import androidx.core.content.ContextCompat.getSystemService
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.lifecycle.*
import kotlinx.coroutines.*
import me.ranko.autodark.Constant
import me.ranko.autodark.Constant.*
import me.ranko.autodark.R
import me.ranko.autodark.Utils.DarkTimeUtil
import me.ranko.autodark.core.DarkModeSettings
import me.ranko.autodark.core.DarkModeSettings.Companion.setForceDark
import timber.log.Timber
import java.time.LocalTime

const val SP_MAIN_FILE_NAME = "audoDark"
const val SP_KEY_MASTER_SWITCH = "switch"

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val darkSettings = DarkModeSettings(application)

    private var sp = application.getSharedPreferences(SP_MAIN_FILE_NAME, Activity.MODE_PRIVATE)

    private var mUiManager: UiModeManager =
        getSystemService(getApplication(), UiModeManager::class.java)!!

    /**
     * Control the main switch on/off
     * <p>
     * Set to Off, all the pending alarm will be canceled.
     * */
    val switch = ObservableBoolean(false)

    /**
     * Show summary text message wen main switch triggered
     *
     * @see    onDarkSwitchChanged
     * */
    val summaryText = ObservableField<String>()

    /**
     * Progress that indicates change fore-dark job status
     *
     * @see  triggerForceDark
     * @see  JOB_STATUS_SUCCEED
     * @see  JOB_STATUS_FAILED
     * @see  JOB_STATUS_PENDING
     * */
    private val _forceDarkStatus = MutableLiveData<Int>()
    val forceDarkStatus: LiveData<Int>
        get() = _forceDarkStatus

    /**
     * Control permission dialog
     * */
    private val _requireAdb = MutableLiveData<Boolean>()
    val requireAdb: LiveData<Boolean>
        get() = _requireAdb

    private val sudoJob = Job()
    private val uiScope = CoroutineScope(Dispatchers.Main.plus(sudoJob))

    init {
        switch.set(sp.getBoolean(SP_KEY_MASTER_SWITCH, false))
    }

    /**
     * Turn main switch on or off
     * <p>
     * Set <strong>on</strong> will recreate all the pending alarm
     * <p>
     * Set <strong>off</strong> will cancel all current alarm
     *
     * @see    DarkModeSettings.setAllAlarm
     * @see    DarkModeSettings.cancelAllAlarm
     * */
    fun triggerMasterSwitch() {
        if (!checkPermissionGranted()) {
            _requireAdb.value = true
            return
        }

        val status = !switch.get()
        switch.set(status)

        // delay 260L to let button animation finish
        uiScope.launch {
            delay(260L)
            if (status) {
                darkSettings.setAllAlarm()
            } else {
                darkSettings.cancelAllAlarm()
            }
        }

        sp.edit().putBoolean(SP_KEY_MASTER_SWITCH, status).apply()
        onDarkSwitchChanged()
    }

    /**
     * Update summary when dark mode triggered
     * <p>
     * Show a summary text message to user
     *
     * */
    private fun onDarkSwitchChanged() {
        val context = getApplication<Application>()

        if (!switch.get()) { // master switch is off now
            sendDelayText(context.getString(R.string.dark_mode_disabled))
        } else {
            val time: LocalTime
            val textRes: Int
            when (mUiManager.nightMode) {
                UiModeManager.MODE_NIGHT_NO -> {
                    textRes = R.string.dark_mode_summary_off_start
                    time = darkSettings.getStartTime()
                }

                UiModeManager.MODE_NIGHT_YES, UiModeManager.MODE_NIGHT_AUTO -> {
                    textRes = R.string.dark_mode_summary_on_start
                    time = darkSettings.getEndTime()
                }

                else -> {
                    Timber.e("System ui manager returned error code.")
                    return
                }
            }

            val displayTime = DarkTimeUtil.getDisplayFormattedString(time)
            sendDelayText(context.getString(textRes, displayTime))
        }
    }

    /**
     * A config change will recreate view
     * delay the summary text a little while
     * */
    private fun sendDelayText(text: String) = uiScope.launch {
        delay(700L)
        summaryText.set(text)
    }

    /**
     * Control force-dark mode on/off
     * This experimental feature will be removed on further android version
     *
     * @see Constant.COMMAND_GET_FORCE_DARK
     * @see Constant.COMMAND_SET_FORCE_DARK_OFF
     * @see Constant.COMMAND_SET_FORCE_DARK_ON
     * */
    suspend fun triggerForceDark(enabled: Boolean) = uiScope.launch {
        _forceDarkStatus.value = JOB_STATUS_PENDING

        withContext(Dispatchers.Main) {
            val result = setForceDark(enabled)
            // Show force-dark job result
            _forceDarkStatus.value = if (result) JOB_STATUS_SUCCEED else JOB_STATUS_FAILED
        }
    }

    /**
     * User want trigger dark mode manually
     * switch mode now
     * */
    fun setDarkModeManually() {
        if (!switch.get()) {
            triggerMasterSwitch()
        } else {
            val state = mUiManager.nightMode == UiModeManager.MODE_NIGHT_NO
            DarkModeSettings.setDarkMode(mUiManager, getApplication(), state)
        }
    }

    fun onRequireAdbConsumed() {
        _requireAdb.postValue(false)
    }

    override fun onCleared() {
        super.onCleared()
        sudoJob.cancel()
    }

    companion object {
        class Factory(private val application: Application) : ViewModelProvider.Factory {
            override fun <T : ViewModel?> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(application) as T
                }
                throw IllegalArgumentException("Unable to construct viewModel")
            }
        }
    }
}