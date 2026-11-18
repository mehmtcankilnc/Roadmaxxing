package app.roadmaxxing.ui.summary

/** Drives whose summary preview already played its route animation in this app session. */
internal object SummaryAnimationLog {
    private val played = mutableSetOf<Long>()

    /** True the first time it is asked for [driveId]; false afterwards. */
    fun shouldPlay(driveId: Long): Boolean = played.add(driveId)
}
