import java.io.File

val GLOBAL_FOLDER = File("mappings")

fun main() {
    val time = System.currentTimeMillis()
    GLOBAL_FOLDER.mkdirs()
    MinecraftVersion.V1_16_4.write(GLOBAL_FOLDER)
    val elapsed = (System.currentTimeMillis() - time) / 1000.0
    println("Done. Took ${elapsed}s")
}
