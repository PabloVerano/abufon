package com.abufon.app

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The v4 time picker (JSON states.parent.timePicker, preview landscape_time_picker): "Desde" or
 * "Hasta" with its hint, an hour wheel (00-23) and a minute wheel (00, 15, 30, 45) on the
 * gp_wheel_band, and Cancelar / Guardar. Only Guardar changes anything.
 */
object TimePicker {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /**
     * Shows the picker for [minuteOfDay] (snapped to 15 minutes) and returns its dialog; the
     * caller dismisses it when it goes away. [onTouch] runs on every touch (for the settings'
     * 3-minute timer), [onSave] with the chosen minute of the day.
     */
    fun show(
        activity: Activity,
        title: String,
        hint: String,
        minuteOfDay: Int,
        onTouch: () -> Unit,
        onSave: (Int) -> Unit,
    ): PanelDialog {
        fun dp(v: Number) = activity.dpx(v)
        val navy = activity.getColor(R.color.gp_navy)
        val dialog = PanelDialog(activity, activity.getColor(R.color.gp_scrim_light), onTouch)
        val start = ScheduleBar.snap15(minuteOfDay)

        val hours = WheelView(activity).apply {
            values = (0..23).map { "%02d".format(it) }
            index = start / 60
        }
        val minutes = WheelView(activity).apply {
            values = listOf("00", "15", "30", "45")
            index = start % 60 / 15
        }
        val wheels = FrameLayout(activity).apply {
            addView(View(activity).apply { setBackgroundResource(R.drawable.bg_wheel_band) },
                FrameLayout.LayoutParams(dp(400), dp(64), Gravity.CENTER))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(hours, LinearLayout.LayoutParams(dp(160), WRAP))
                addView(activity.parentText(":", 48f, true, navy).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(58), WRAP).apply { bottomMargin = dp(4) })
                addView(minutes, LinearLayout.LayoutParams(dp(160), WRAP))
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }

        fun button(primary: Boolean, text: String, onClick: () -> Unit) = activity.face(
            if (primary) R.drawable.btn_parent_primary else R.drawable.btn_parent_secondary,
            activity.parentText(text, 24f, true, if (primary) activity.getColor(R.color.gp_white) else navy),
        ) { onClick() }

        val buttons = LinearLayout(activity).apply {
            addView(button(false, activity.getString(R.string.cancel)) { dialog.dismissQuietly() },
                LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(button(true, activity.getString(R.string.save)) {
                dialog.dismissQuietly()
                onSave(hours.target * 60 + minutes.target * 15)
            }, LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(16) })
        }

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_lock_panel)
            elevation = dp(16).toFloat()
            setPadding(dp(40), dp(32), dp(40), dp(32))
            addView(activity.parentText(title, 32f, true, navy))
            addView(activity.parentText(hint, 18f, false, activity.getColor(R.color.gp_text_secondary)),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
            addView(wheels, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
            addView(View(activity), LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(buttons, LinearLayout.LayoutParams(MATCH, dp(68)))
        }
        dialog.setPanel(panel, dp(560), dp(620))
        dialog.show()
        return dialog
    }
}
