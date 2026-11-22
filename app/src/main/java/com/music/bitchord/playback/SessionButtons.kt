package com.music.bitchord.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands as Media3SessionCommands
import com.music.bitchord.R
import com.music.bitchord.data.model.Song

/** The custom commands the session accepts, beyond Media3's own. */
@UnstableApi
object SessionCommands {
    val startBoundedContext = SessionCommand(ACTION_START_BOUNDED_CONTEXT, Bundle.EMPTY)
    val clearBoundedContext = SessionCommand(ACTION_CLEAR_BOUNDED_CONTEXT, Bundle.EMPTY)
    val favorite = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    val autoplay = SessionCommand(ACTION_TOGGLE_AUTOPLAY, Bundle.EMPTY)
    val shuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    val startStation = SessionCommand(ACTION_START_STATION, Bundle.EMPTY)
    val revertToOriginal = SessionCommand(ACTION_REVERT_TO_ORIGINAL, Bundle.EMPTY)
    val swapToVersion = SessionCommand(ACTION_SWAP_TO_VERSION, Bundle.EMPTY)
    val beginRadioQueue = SessionCommand(ACTION_BEGIN_RADIO_QUEUE, Bundle.EMPTY)
    val commitRadioQueue = SessionCommand(ACTION_COMMIT_RADIO_QUEUE, Bundle.EMPTY)
    val upgradeQuality = SessionCommand(ACTION_UPGRADE_QUALITY, Bundle.EMPTY)
    val swapVersion = SessionCommand(ACTION_SWAP_VERSION, Bundle.EMPTY)
    val queueDrag = SessionCommand(ACTION_QUEUE_DRAG, Bundle.EMPTY)
    val queueReplayHistory = SessionCommand(ACTION_QUEUE_REPLAY_HISTORY, Bundle.EMPTY)
    val dismissPlayback = SessionCommand(ACTION_DISMISS_PLAYBACK, Bundle.EMPTY)

    /** Media3's default session and library commands, plus every one of ours. */
    fun available(): Media3SessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(favorite)
            .add(startBoundedContext)
            .add(clearBoundedContext)
            .add(autoplay)
            .add(shuffle)
            .add(startStation)
            .add(revertToOriginal)
            .add(swapToVersion)
            .add(beginRadioQueue)
            .add(commitRadioQueue)
            .add(upgradeQuality)
            .add(swapVersion)
            .add(queueDrag)
            .add(queueReplayHistory)
            .add(dismissPlayback)
            .build()
}

/** Whether a station can be started off [this]: a catalogue track, not a file on the device. */
internal fun Song.canStartStation(): Boolean =
    videoId.isNotBlank() &&
        !videoId.startsWith("content://") &&
        !videoId.startsWith("file://")

/**
 * What the transport surfaces show beside play, pause and skip.
 *
 * @property favorite null when the track can't be liked from here; otherwise whether it is.
 */
data class ButtonPlan(
    val favorite: Boolean?,
    val shuffleOn: Boolean,
    val station: Boolean,
    val revert: Boolean,
)

/** The pure half of [SessionButtons]: which buttons, in which state. */
object SessionButtonRules {
    fun plan(
        song: Song?,
        canLike: Boolean,
        liked: Boolean,
        shuffleOn: Boolean,
        pinnedToOriginal: Boolean,
    ): ButtonPlan = ButtonPlan(
        favorite = if (canLike) liked else null,
        shuffleOn = shuffleOn,
        station = song?.canStartStation() == true,
        // A substituted track can go back to the YouTube upload it was
        // queued as, unless it is already pinned there or is a local file.
        revert = song != null && song.hasYouTubeOriginal() && song.localUri == null && !pinnedToOriginal,
    )
}

/**
 * The secondary buttons on every transport surface: Favorite and Shuffle on
 * the phone's notification and lock screen, with Radio and Revert added for
 * controllers that have room for them, Android Auto above all.
 *
 * The phone/lock-screen notification is driven by Media3's own internal
 * controller, so it gets an explicit override limited to Favorite/Shuffle;
 * every other connected controller falls back to the broadcast layout, which
 * also carries Radio/Revert.
 *
 * Media3 mirrors the notification controller's layout into the platform
 * session's custom actions, and Android Auto reads the platform session, not
 * the broadcast layout. So while a car is connected the notification override
 * carries the car buttons too, or Radio/Revert vanish from Auto.
 */
@UnstableApi
class SessionButtons(
    private val context: Context,
    private val session: () -> MediaSession?,
    private val currentItem: () -> MediaItem?,
    private val canLike: (MediaItem?) -> Boolean,
    private val isLiked: (MediaItem?) -> Boolean,
    private val shuffleOn: () -> Boolean = { QueueShuffle.enabled.value },
    private val pinnedToOriginal: (String) -> Boolean = OriginalVersion::isPinned,
) {
    /** Android Auto / Automotive controllers currently connected to the session. */
    private val carControllers = mutableSetOf<MediaSession.ControllerInfo>()

    fun onConnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)) {
            carControllers += controller
            refresh()
        }
    }

    fun onDisconnected(controller: MediaSession.ControllerInfo) {
        if (carControllers.remove(controller)) refresh()
    }

    /** Pushes the current button state to every surface. */
    fun refresh() {
        val session = session() ?: return
        val plan = plan()
        val car = carButtons(plan)
        session.setCustomLayout(car)
        session.mediaNotificationControllerInfo?.let {
            session.setCustomLayout(it, if (carControllers.isEmpty()) notificationButtons(plan) else car)
        }
    }

    private fun plan(): ButtonPlan {
        val item = currentItem()
        val song = item?.toSong()
        return SessionButtonRules.plan(
            song = song,
            canLike = canLike(item),
            liked = isLiked(item),
            shuffleOn = shuffleOn(),
            pinnedToOriginal = song?.let { pinnedToOriginal(it.videoId) } == true,
        )
    }

    /** Favorite and Shuffle: the only actions shown on the phone notification. */
    private fun notificationButtons(plan: ButtonPlan): List<CommandButton> {
        // The icon constant tells controllers like Android Auto what the button
        // means; the custom drawable renders the Opaline/Apple SF heart icon
        // on the notification itself.
        val favorite = plan.favorite?.let { liked ->
            CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setCustomIconResId(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
                .setSessionCommand(SessionCommands.favorite)
                .setDisplayName(if (liked) "Unlike" else "Like")
                .build()
        }
        val shuffle = CommandButton.Builder(
            if (plan.shuffleOn) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
        )
            .setSessionCommand(SessionCommands.shuffle)
            .setDisplayName(if (plan.shuffleOn) "Shuffle off" else "Shuffle on")
            .build()
        return listOfNotNull(favorite, shuffle)
    }

    /** Adds Radio/Revert for controllers with room for them, e.g. Android Auto. */
    private fun carButtons(plan: ButtonPlan): List<CommandButton> {
        val station = if (!plan.station) null else CommandButton.Builder(CommandButton.ICON_RADIO)
            .setSessionCommand(SessionCommands.startStation)
            .setDisplayName(context.getString(R.string.start_radio))
            .build()
        val revert = if (!plan.revert) null else CommandButton.Builder(CommandButton.ICON_SYNC)
            .setSessionCommand(SessionCommands.revertToOriginal)
            .setDisplayName(context.getString(R.string.revert_to_original))
            .build()
        return notificationButtons(plan) + listOfNotNull(station, revert)
    }
}
