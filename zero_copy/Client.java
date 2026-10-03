import java.nio.file.Path;

/**
 * Multi-threaded file download CLIENT - entry point (command line only).
 * This class only reads the arguments; the real work is in
 * Downloader, RangeWorker, Benchmark and Connection.
 *
 * Usage:
 *   java Client <host> <port> list
 *   java Client <host> <port> info <filename>
 *   java Client <host> <port> download <filename> <outputFile> [workers=10] [io|nio]
 *   java Client <host> <port> benchmark <filename> [runs=3]
 */
public class Client {

    public static void main(String[] args) {
        if (args.length < 3) {
            printUsage();
            return;
        }
        try {
            String host = args[0];
            int port = Integer.parseInt(args[1]);
            String command = args[2].toLowerCase();

            if (command.equals("list")) {
                list(host, port);

            } else if (command.equals("info")) {
                requireArgs(args, 4);
                long size = Downloader.getFileSize(host, port, args[3]);
                System.out.println(args[3] + " : " + size + " bytes");

            } else if (command.equals("download")) {
                requireArgs(args, 5);
                int workers = args.length > 5 ? Integer.parseInt(args[5]) : 10;
                boolean useNio = args.length > 6 && args[6].equalsIgnoreCase(Protocol.MODE_NIO);
                Downloader.Result r = Downloader.download(host, port, args[3], Path.of(args[4]), workers, useNio, true);
                System.out.printf("Done: %d bytes in %.3f s = %.1f MB/s, size+SHA-256 check: %s%n",
                        r.sizeBytes, r.seconds, r.mbPerSec, r.verified ? "OK" : "MISMATCH");

            } else if (command.equals("benchmark")) {
                requireArgs(args, 4);
                int runs = args.length > 4 ? Integer.parseInt(args[4]) : 3;
                Benchmark.run(host, port, args[3], runs);

            } else {
                printUsage();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    // LIST -> prints every "FILE <name> <size>" line until "END"
    private static void list(String host, int port) throws Exception {
        try (Connection c = new Connection(host, port)) {
            c.sendLine("LIST");
            String line;
            while (!(line = c.readReply()).equals("END")) {
                System.out.println(line);
            }
        }
    }

    private static void requireArgs(String[] args, int n) {
        if (args.length < n) {
            throw new IllegalArgumentException("Missing arguments (run without arguments to see usage)");
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java Client <host> <port> list");
        System.out.println("  java Client <host> <port> info <filename>");
        System.out.println("  java Client <host> <port> download <filename> <outputFile> [workers=10] [io|nio]");
        System.out.println("  java Client <host> <port> benchmark <filename> [runs=3]");
    }
}
