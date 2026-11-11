package com.music.bitchord.ui.components

/**
 * How long after the sheet's last moving frame its glass stays frozen.
 *
 * This has to bridge the one stretch of "in motion, but with no frame to
 * stamp": an open from a tap stamps once and then waits up to 300ms for the
 * sheet to be laid out before its slide stamps every frame. Past that it is
 * pure margin, and it doubles as the upper bound on how long a freeze whose
 * every clear was missed can last: half a second of held glass reads as the
 * sheet settling; a permanent one would read as glass broken.
 */
private const val GlassMotionFreezeWindowNs = 500_000_000L

/**
 * The "no motion yet" sentinel. Not 0: [System.nanoTime] is under no
 * contract to be nonzero, and a stamp taken at exactly zero must still read
 * as motion, not as nothing. [Long.MIN_VALUE] is a value no clock reaches
 * in practice — and even if one did, the loss would be one freeze that
 * reads as already settled, never one that sticks.
 */
private const val GlassMotionUnstamped = Long.MIN_VALUE

/**
 * The app-wide motion the glass freezes for, and the one place any part of
 * the UI can read it from without being handed a reference.
 *
 * On low-end devices the glass janked through the whole Now Playing sheet swipe.
 * The swipe is exactly when the content behind the glass changes every frame —
 * the docked artwork flies between the mini player and the player, in the app
 * window, however the sheet is being moved — so every glass surface was paying
 * a full backdrop re-record per frame of the spring, several surfaces deep.
 *
 * The trade this makes is intended, and must not be "fixed": while frozen,
 * glass shows the capture it already holds — a static snapshot, without live
 * refraction — so during the swipe the lens does not track what is passing
 * underneath it. A swipe that stays smooth is worth far more than a lens that
 * tracks the finger at the price of the swipe itself. The freeze ends within
 * [GlassMotionFreezeWindowNs] of the last moving frame, and the engine's own
 * thaw poll redraws the surface the moment it lifts, so the glass goes live
 * again with nothing left over.
 */
object GlassMotion {

    /**
     * The Now Playing sheet's motion: an open from a tap, the pull up from
     * the mini player, every close, and the sheet's own drag under M3's
     * hand. True only while that motion is actually in flight or within
     * its settle margin — never merely because the player is open.
     */
    val sheet: GlassMotionClock = GlassMotionClock()
}

/**
 * A nanosecond clock that answers "did motion happen within the last
 * [freezeWindowNs]?" — glass freezes while it does.
 */
class GlassMotionClock(
    private val freezeWindowNs: Long = GlassMotionFreezeWindowNs,
    private val now: () -> Long = System::nanoTime,
) {

    /** The instant of the last moving frame; [GlassMotionUnstamped] until one. */
    @Volatile
    private var lastMotionAtNs = GlassMotionUnstamped

    /**
     * A frame of motion happened: freeze glass from now until
     * [freezeWindowNs] after the next one.
     */
    fun moved() {
        lastMotionAtNs = now()
    }

    /** Motion is over — thaw now, rather than waiting out the window. */
    fun settled() {
        lastMotionAtNs = GlassMotionUnstamped
    }

    /**
     * Whether glass should hold its last capture: motion ran recently
     * enough that the content behind it is still moving.
     */
    fun frozen(): Boolean {
        val at = lastMotionAtNs
        return at != GlassMotionUnstamped && now() - at < freezeWindowNs
    }
}
