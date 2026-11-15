package com.slimecastle.bingohelper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Small persistent settings shared between the activity and the overlay service. */
object AppPrefs {

    private const val NAME = "bingo_prefs"
    private const val KEY_RESET_MODE = "reset_mode"
    private const val KEY_CALIBRATION = "calibration"
    private const val KEY_MOVE_WAIT_MS = "move_wait_ms"
    private const val KEY_BEFORE_RESTART_WAIT_MS = "before_restart_wait_ms"
    private const val KEY_RESTART_WAIT_MS = "restart_wait_ms"
    private const val KEY_REWARD_CLOSE_WAIT_MS = "reward_close_wait_ms"
    private const val KEY_PANEL_COLLAPSED = "panel_collapsed"

    const val RESET_MODE_X1 = 1
    const val RESET_MODE_X10 = 10
    const val DEFAULT_MOVE_WAIT_MS = 200
    const val DEFAULT_BEFORE_RESTART_WAIT_MS = 500
    const val DEFAULT_RESTART_WAIT_MS = 500
    const val DEFAULT_REWARD_CLOSE_WAIT_MS = 100

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun resetMode(context: Context): Int {
        val value = prefs(context).getInt(KEY_RESET_MODE, RESET_MODE_X1)
        return if (value == RESET_MODE_X10) RESET_MODE_X10 else RESET_MODE_X1
    }

    fun setResetMode(context: Context, mode: Int) {
        prefs(context).edit()
            .putInt(KEY_RESET_MODE, if (mode == RESET_MODE_X10) RESET_MODE_X10 else RESET_MODE_X1)
            .apply()
    }

    fun moveWaitMs(context: Context): Int =
        prefs(context).getInt(KEY_MOVE_WAIT_MS, DEFAULT_MOVE_WAIT_MS)

    fun setMoveWaitMs(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MOVE_WAIT_MS, value).apply()
    }

    fun beforeRestartWaitMs(context: Context): Int =
        prefs(context).getInt(KEY_BEFORE_RESTART_WAIT_MS, DEFAULT_BEFORE_RESTART_WAIT_MS)

    fun setBeforeRestartWaitMs(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_BEFORE_RESTART_WAIT_MS, value).apply()
    }

    fun restartWaitMs(context: Context): Int =
        prefs(context).getInt(KEY_RESTART_WAIT_MS, DEFAULT_RESTART_WAIT_MS)

    fun setRestartWaitMs(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_RESTART_WAIT_MS, value).apply()
    }

    fun rewardCloseWaitMs(context: Context): Int =
        prefs(context).getInt(KEY_REWARD_CLOSE_WAIT_MS, DEFAULT_REWARD_CLOSE_WAIT_MS)

    fun setRewardCloseWaitMs(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_REWARD_CLOSE_WAIT_MS, value).apply()
    }

    fun panelCollapsed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PANEL_COLLAPSED, false)

    fun setPanelCollapsed(context: Context, collapsed: Boolean) {
        prefs(context).edit().putBoolean(KEY_PANEL_COLLAPSED, collapsed).apply()
    }

    fun calibration(context: Context): Calibration? {
        val raw = prefs(context).getString(KEY_CALIBRATION, null) ?: return null
        return try {
            Calibration.fromJson(JSONObject(raw))
        } catch (_: Throwable) {
            null
        }
    }

    fun saveCalibration(context: Context, calibration: Calibration) {
        prefs(context).edit()
            .putString(KEY_CALIBRATION, calibration.toJson())
            .apply()
    }

    fun clearCalibration(context: Context) {
        prefs(context).edit().remove(KEY_CALIBRATION).apply()
    }
}
