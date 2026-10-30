package kotlinx.coroutines.channels
class ChannelResult<T>(private val v: T?, val isSuccess: Boolean) { fun getOrNull(): T? = v }
class Channel<T>(cap: Int) {
    companion object { const val UNLIMITED = Int.MAX_VALUE }
    private val q = ArrayDeque<T>()
    fun trySend(v: T): ChannelResult<Unit> { q.addLast(v); return ChannelResult(Unit, true) }
    fun tryReceive(): ChannelResult<T> = if (q.isEmpty()) ChannelResult(null, false) else ChannelResult(q.removeFirst(), true)
    suspend fun receive(): T = if (q.isEmpty()) throw NoSuchElementException() else q.removeFirst()
}
