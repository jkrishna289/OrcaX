package com.github.jkrishna289.orcax.util

import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

/**
 * A [CoroutineExceptionHandler] that is aware of a [LoadingState] and will set it to error if an exception occurs in the coroutine.
 */
class LoadingExceptionHandler(
    private val loadingState: MutableLiveData<LoadingState>,
    private val errorMessage: String?,
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
        loadingState.postValue(
            LoadingState.Error(
                message = errorMessage,
                exception = exception,
            ),
        )
        if (autoToast) {
            postErrorToast(exception)
        }
    }
}
