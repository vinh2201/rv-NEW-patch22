package app.revanced.extension.youtube.shared

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import app.revanced.extension.shared.ResourceType
import app.revanced.extension.shared.Utils
import java.lang.ref.WeakReference

/**
 * default implementation of [PlayerControlsVisibilityObserver]
 *
 * @param activity activity that contains the controls_layout view
 */
class PlayerControlsVisibilityObserverImpl(
    private val activity: Activity,
) : PlayerControlsVisibilityObserver {

    /**
     * ID of the direct parent of player_control_play_pause_replay_button_touch_area, R.id.controls_button_group_layout
     */
    private val controlsButtonGroupId =
        Utils.getResourceIdentifier(activity, ResourceType.ID, "controls_button_group_layout")

    /**
     * id of R.id.player_control_play_pause_replay_button_touch_area
     */
    private val controlButtonId =
        Utils.getResourceIdentifier(activity, ResourceType.ID, "player_control_play_pause_replay_button_touch_area")

    /**
     * reference to the control button view
     */
    private var controlButtonView = WeakReference<View>(null)

    /**
     * is the [controlButtonView] set to a valid reference of a view?
     */
    private val isAttached: Boolean
        get() {
            val view = controlButtonView.get()
            return view != null && view.parent != null
        }

    /**
     * find and attach the player_control_play_pause_replay_button_touch_area view if needed
     */
    private fun maybeAttach() {
        if (isAttached) return

        // find parent, then player_control_play_pause_replay_button_touch_area view
        // this is needed because there may be two views where id=R.id.player_control_play_pause_replay_button_touch_area
        // because why should google confine themselves to their own guidelines...
        activity.findViewById<ViewGroup>(controlsButtonGroupId)?.let { parent ->
            parent.findViewById<View>(controlButtonId)?.let {
                controlButtonView = WeakReference(it)
            }
        }
    }

    override val playerControlsVisibility: Int
        get() {
            maybeAttach()
            return controlButtonView.get()?.visibility ?: View.GONE
        }

    override val arePlayerControlsVisible: Boolean
        get() = playerControlsVisibility == View.VISIBLE
}

/**
 * provides the visibility status of the fullscreen player controls_layout view.
 * this can be used for detecting when the player controls are shown
 */
interface PlayerControlsVisibilityObserver {
    /**
     * current visibility int of the controls_layout view
     */
    val playerControlsVisibility: Int

    /**
     * is the value of [playerControlsVisibility] equal to [View.VISIBLE]?
     */
    val arePlayerControlsVisible: Boolean
}
