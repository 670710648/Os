import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multi-threaded file download SERVER - entry point.
 * This class only accepts connections; ClientHandler talks to each client
 * and FileSender moves the file bytes.
 *
 * Run:  java Server [port] [directory]      (defaults: 5000, ./shared)
 */
public class Server {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_PORT;
        Path dir = Path.of(args.length > 1 ? args[1] : "shared").toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            System.err.println("Directory not found: " + dir);
            System.exit(1);
        }

        // A cached pool creates one thread per active connection, so many clients
        // (10 workers each) are served at the same time.
        // On Java 21 you can use virtual threads instead:
        //   ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService pool = Executors.newCachedThreadPool();

        // SHA-256 results are cached so a big file is not re-hashed on every HASH request
        Map<String, String> hashCache = new ConcurrentHashMap<>();

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress(port));
            System.out.println("[server] listening on port " + port + ", sharing folder: " + dir);

            while (true) {
                SocketChannel client = serverChannel.accept();   // blocks until a client connects
                pool.submit(new ClientHandler(client, dir, hashCache));
            }
        } finally {
            pool.shutdown();
        }
    }
}
