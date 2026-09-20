package registry

import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5227
    var dataDir = "data"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--data" -> { dataDir = args[++i] }
            "--help", "-h" -> {
                println("Usage: run --host 127.0.0.1 --port 5227 [--data ./data]")
                exitProcess(0)
            }
            else -> throw IllegalArgumentException("未知参数: ${args[i]}")
        }
        i++
    }
    val (server, service) = buildServer(Paths.get(dataDir), host, port)
    server.start()
    println("策略例外登记站已启动: http://$host:$port  (data=$dataDir, serverUtc=${service.clock.now()})")
}
