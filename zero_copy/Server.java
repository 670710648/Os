import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The SERVER: main program.
 * It only waits for clients. Every client that connects is given to a THREAD POOL
 * (a fixed group of threads that are created once and re-used). A free thread runs a
 * ClientHandler that talks to that client, so many clients are served at the same time.
 * If all threads are busy, the new client waits in the pool's queue until one is free.
 *
 * Run:  java Server [port] [folder]        (default: port 5000, folder "shared")
 */
public class Server {

    // The most connections that are served at the same time.
    // A client with 10 workers uses 10 of them, so 100 is enough for several clients.
    static final int POOL_SIZE = 100;

    public static void main(String[] args) throws IOException {
        // 1) read the settings from the command line (or use the defaults)
        int port = Protocol.DEFAULT_PORT;
        String folderName = "shared";
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("The port must be a number, for example 5000 (not \""
                        + args[0] + "\")");
                System.exit(1);
            }
        }
        if (port < 1 || port > 65535) {
            System.err.println("The port must be between 1 and 65535 (not " + port + ")");
            System.exit(1);
        }
        if (args.length > 1) {
            folderName = args[1];
        }

        File folder = new File(folderName).getCanonicalFile();
        if (!folder.isDirectory()) {
            System.err.println("Directory not found: " + folder);
            System.exit(1);                      // exit code 1 = error (so a script can notice)
        }

        // 2) open the port
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        try {
            serverChannel.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            System.err.println("Cannot open port " + port + ": " + e.getMessage()
                    + "   (is another server already running on this port?)");
            System.exit(1);
        }
        System.out.println("[server] listening on port " + port + ", sharing folder: " + folder);

        // 3) loop forever: accept a client and give it to the thread pool
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        while (true) {
            SocketChannel client = null;
            try {
                client = serverChannel.accept();             // waits here until a client connects
            } catch (IOException e) {
                // For example "Too many open files". One failed accept must not stop the
                // whole server: we write a message, wait a moment and try again.
                System.out.println("[server] accept failed: " + e.getMessage());
                pause(100);
                continue;
            }
            pool.execute(new ClientHandler(client, folder));  // a free pool thread runs handler.run()
        }
    }

    private static void pause(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            // nothing to do
        }
    }
}
