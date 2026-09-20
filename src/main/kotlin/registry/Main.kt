package registry

import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5227
    var dataDir = "data"
    var blobDir = "blobs"
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--host" -> host = args[++index]
            "--port" -> port = args[++index].toInt()
            "--data" -> dataDir = args[++index]
            "--blobs" -> blobDir = args[++index]
            "--help", "-h" -> {
                println("用法: run --host 127.0.0.1 --port 5227 [--data data] [--blobs blobs]")
                exitProcess(0)
            }
            else -> {
                System.err.println("未知参数: ${args[index]}")
                exitProcess(2)
            }
        }
        index++
    }

    val dataPath = Paths.get(dataDir, "state.json")
    val blobPath = Paths.get(blobDir)
    val store = FileStore(dataPath)
    val clock = ServiceClock()
    val service = RegistryService(store, clock, blobPath)
    SeedData.installIfEmpty(service, store)
    val server = WebServer(service, host, port)
    server.start()
}
