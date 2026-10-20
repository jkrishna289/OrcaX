package com.github.jkrishna289.orcax.util

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.github.jkrishna289.orcax.OrcaApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

/**
 * A general [CoroutineExceptionHandler] which can optionally show a [Toast] when an exception is thrown
 *
 * @param autoToast automatically show a toast with the exception's message
 */
class ExceptionHandler(
    private val autoToast: Boolean = false,
) : CoroutineExceptionHandler {
    override val key: CoroutineContext.Key<*>
        get() = CoroutineExceptionHandler

    override fun handleException(
        context: CoroutineContext,
        exception: Throwable,
    ) {
        if (exception is CancellationException) {
            // Don't log/toast cancellations
            return
        }
        Timber.e(exception, "Exception in coroutine")

        if (autoToast) {
            postErrorToast(exception)
        }
    }
}

/**
 * Posts rather than blocks: this runs on whichever thread threw, often Main, where waiting on a
 * Main dispatch would deadlock.
 */
internal fun postErrorToast(exception: Throwable) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(OrcaApplication.instance, "Error: ${exception.message}", Toast.LENGTH_LONG).show()
    }
}
