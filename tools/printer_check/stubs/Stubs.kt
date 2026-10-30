// Minimal stand-ins so the printer driver code can be compiled and its byte output checked on a desktop JVM.
@file:Suppress("unused", "UNUSED_PARAMETER")
package kotlinx.coroutines

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

interface CoroutineScope
object TestScope : CoroutineScope
class Job
fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Job {
    block.startCoroutine(this, Continuation(EmptyCoroutineContext) { it.getOrThrow() })
    return Job()
}
suspend fun delay(ms: Long) {}
suspend fun <T> withTimeoutOrNull(ms: Long, block: suspend CoroutineScope.() -> T): T? = try { block(TestScope) } catch (e: NoSuchElementException) { null }
class CancellationException(msg: String? = null) : IllegalStateException(msg)
