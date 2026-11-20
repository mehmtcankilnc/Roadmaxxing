package app.roadmaxxing.shared.testdata

/**
 * Timing budgets for performance assertions. Strict on a developer machine; on CI (GitHub sets `CI=true`) shared
 * VMs make JIT, GC and noisy neighbours add tens of milliseconds, so budgets are multiplied by [CI_FACTOR].
 * Deterministic assertions (sizes, counts, tile loads) must not use this.
 */
object PerfBudget {
    const val CI_FACTOR = 3.0
    val onCi: Boolean = System.getenv("CI") == "true"

    /** [localMs] on a developer machine, [CI_FACTOR] times as much on CI. */
    fun ms(localMs: Long): Long = if (onCi) (localMs * CI_FACTOR).toLong() else localMs
    fun ms(localMs: Double): Double = if (onCi) localMs * CI_FACTOR else localMs
}
