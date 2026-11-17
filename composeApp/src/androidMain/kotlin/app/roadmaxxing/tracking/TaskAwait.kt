package app.roadmaxxing.tracking

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends until a Play services [Task] completes. (kotlinx-coroutines-play-services is not in the version
 * catalog; this is the few lines of it we need.)
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val e = task.exception
        when {
            e != null -> cont.resumeWithException(e)
            task.isCanceled -> cont.cancel()
            else -> @Suppress("UNCHECKED_CAST") cont.resume(task.result as T)
        }
    }
}
