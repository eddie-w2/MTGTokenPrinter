package android.content
import java.io.File
import java.io.InputStream
class AssetManager(private val root: File) { fun open(p: String): InputStream = File(root, p).inputStream() }
open class Context(root: File) { val assets = AssetManager(root) }
