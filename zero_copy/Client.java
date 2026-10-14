import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * The CLIENT: main program (command line only).
 * This class only reads the command line. The real work is done by
 * Downloader, RangeWorker, Benchmark and Connection.
 *
 * Usage:
 *   java Client <host> <port> list
 *   java Client <host> <port> info <filename>
 *   java Client <host> <port> download <filename> <outputFile> [workers=10] [io|nio]
 *   java Client <host> <port> benchmark <filename> [runs=3]
 *
 * Exit code:  0 = everything is fine
 *             1 = an error (server not found, file not found, a worker failed, ...)
 *             2 = the download finished but the file is DAMAGED (size or SHA-256 is different)
 */
public class Client {

    public static void main(String[] args) {
        if (args.length < 3) {
            printUsage();
            return;
        }

        try {
            String host = args[0];
            int port = parseNumber(args[1], "port");
            String command = args[2].toLowerCase(Locale.ROOT);   // ROOT = English rules

            if (command.equals("list")) {
                list(host, port);

            } else if (command.equals("info")) {
                checkArgCount(args, 4);
                long size = Downloader.getFileSize(host, port, args[3]);
                System.out.println(args[3] + " : " + size + " bytes");

            } else if (command.equals("download")) {
                checkArgCount(args, 5);
                String filename = args[3];
                File outFile = new File(args[4]);

                int workers = 10;
                if (args.length > 5) {
                    workers = parseNumber(args[5], "workers");
                }
                if (workers < 1 || workers > 100) {
                    // (every worker is a thread AND a connection, so a silly number such as
                    //  100000 would only make the computer and the server run out of resources)
                    throw new IllegalArgumentException("workers must be between 1 and 100");
                }
                boolean useNio = false;
                if (args.length > 6) {
                    useNio = parseUseNio(args[6]);
                }

                DownloadResult r = Downloader.download(host, port, filename, outFile,
                        workers, useNio, true);
                String check = "OK";
                if (!r.verified) {
                    check = "MISMATCH";
                }
                // Locale.ROOT: always a dot as the decimal mark, whatever the computer's language
                System.out.printf(Locale.ROOT,
                        "Done: %d bytes in %.3f s = %.1f MB/s, size+SHA-256 check: %s%n",
                        r.sizeBytes, r.seconds, r.mbPerSec, check);
                if (!r.verified) {
                    // a damaged file must NOT look like a success to a script that checks the exit code
                    System.err.println("Error: the downloaded file is DAMAGED (its size or SHA-256 "
                            + "is different from the original). Do not use these numbers.");
                    System.exit(2);
                }

            } else if (command.equals("benchmark")) {
                checkArgCount(args, 4);
                int runs = 3;
                if (args.length > 4) {
                    runs = parseNumber(args[4], "runs");
                }
                if (runs < 1) {
                    throw new IllegalArgumentException("runs must be at least 1");
                }
                Benchmark.run(host, port, args[3], runs);

            } else {
                printUsage();
            }
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null) {
                message = e.toString();          // some exceptions have no message
            }
            System.err.println("Error: " + message);
            System.exit(1);
        }
    }

    // Turn a word of the command line into a number. If it is not a number (for example the
    // user forgot the workers and typed "nio" in that place) we say clearly which value is wrong.
    static int parseNumber(String text, String name) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number (not \"" + text + "\")");
        }
    }

    // The last word of "download": io -> false, nio -> true (upper or lower case).
    // Anything else is an error. (A typo like "nioo" must NOT silently run "io",
    // because then the experiment would measure the wrong method.)
    static boolean parseUseNio(String text) {
        if (text.equalsIgnoreCase(Protocol.MODE_IO)) {
            return false;
        }
        if (text.equalsIgnoreCase(Protocol.MODE_NIO)) {
            return true;
        }
        throw new IllegalArgumentException("mode must be io or nio (not \"" + text + "\")");
    }

    // LIST: print every "FILE <name> <size>" line until the server sends "END"
    private static void list(String host, int port) throws IOException {
        try (Connection c = new Connection(host, port)) {
            c.sendLine("LIST");
            while (true) {
                String line = c.readReply();
                if (line.equals("END")) {
                    break;
                }
                System.out.println(line);
            }
        }
    }

    private static void checkArgCount(String[] args, int needed) {
        if (args.length < needed) {
            throw new IllegalArgumentException(
                    "Missing arguments (run without arguments to see usage)");
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java Client <host> <port> list");
        System.out.println("  java Client <host> <port> info <filename>");
        System.out.println("  java Client <host> <port> download <filename> <outputFile>"
                + " [workers=10] [io|nio]");
        System.out.println("  java Client <host> <port> benchmark <filename> [runs=3]");
    }
}
