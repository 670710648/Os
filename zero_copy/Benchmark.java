import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * CLIENT side: runs the experiment of the assignment and prints a table.
 *   io / nio   x   1 / 10 workers   x   several runs
 *
 * To make the comparison fair:
 *   - every case (for example "nio, 10 workers") gets ONE warm-up download that is
 *     not counted. The first download of a new case is slower, because Java has to
 *     load and compile the code it uses, and the file has to get into the memory
 *     cache of the operating system.
 *   - every download goes into a brand-new file, so all runs start in the same way.
 *
 * Every counted run is also saved in a CSV file, which you can open with Excel.
 *
 * Numbers are always written with Locale.ROOT, so the decimal mark is always a DOT (0.006),
 * whatever the language of the computer. On a German computer a plain "%.3f" would write
 * 0,006 and the comma would break the columns of the CSV file.
 */
public class Benchmark {

    public static void run(String host, int port, String filename, int runs) throws Exception {
        run(host, port, filename, runs, new File("benchmark_results.csv"));
    }

    public static void run(String host, int port, String filename, int runs, File csvFile)
            throws Exception {
        File tempFile = new File("benchmark_download.tmp");
        String[] modes = {Protocol.MODE_IO, Protocol.MODE_NIO};
        int[] workerCounts = {1, 10};

        if (runs < 3) {
            System.out.println("NOTE: the assignment asks for at least 3 runs of each case.");
        }
        System.out.println("Every case is warmed up once first (the warm-up is not counted).");

        PrintWriter csv = new PrintWriter(csvFile);
        csv.println("mode,workers,run,seconds,mb_per_sec,verified");

        System.out.println();
        System.out.printf("%-6s %-8s %-4s %-10s %-10s %-9s%n",
                "mode", "workers", "run", "time(s)", "MB/s", "verified");

        String summary = "";                    // the average lines are collected here
        try {
            for (String mode : modes) {
                for (int workers : workerCounts) {

                    double totalTime = 0;
                    double totalSpeed = 0;
                    boolean useNio = mode.equals(Protocol.MODE_NIO);

                    // warm-up for THIS case: the result is not printed, but it must be correct
                    DownloadResult warmUp = downloadOnce(host, port, filename, tempFile,
                            workers, useNio);
                    if (!warmUp.verified) {
                        throw new IOException("The warm-up download is damaged (SHA-256 is "
                                + "different) for " + mode + " with " + workers + " workers");
                    }

                    for (int run = 1; run <= runs; run++) {
                        DownloadResult r = downloadOnce(host, port, filename, tempFile,
                                workers, useNio);
                        totalTime = totalTime + r.seconds;
                        totalSpeed = totalSpeed + r.mbPerSec;

                        String check = "OK";
                        if (!r.verified) {
                            check = "MISMATCH";
                        }
                        System.out.printf(Locale.ROOT, "%-6s %-8d %-4d %-10.3f %-10.1f %-9s%n",
                                mode, workers, run, r.seconds, r.mbPerSec, check);
                        csv.println(mode + "," + workers + "," + run + ","
                                + String.format(Locale.ROOT, "%.3f", r.seconds) + ","
                                + String.format(Locale.ROOT, "%.1f", r.mbPerSec) + ","
                                + r.verified);

                        // a damaged download means the experiment is not valid: stop here
                        // (the bad row is already printed and saved in the CSV file)
                        if (!r.verified) {
                            throw new IOException("A download is damaged (SHA-256 is different) for "
                                    + mode + " with " + workers + " workers, run " + run
                                    + ". The results are not valid.");
                        }
                    }

                    String line = String.format(Locale.ROOT,
                            "%-6s %-8d avg time = %.3f s   avg speed = %.1f MB/s%n",
                            mode, workers, totalTime / runs, totalSpeed / runs);
                    summary = summary + line;
                }
            }
        } finally {
            csv.close();
            tempFile.delete();
        }

        System.out.println();
        System.out.println("=== Average over " + runs + " runs ===");
        System.out.print(summary);
        System.out.println();
        System.out.println("Every run was saved in " + csvFile.getPath());
    }

    // One download into a brand-new file (the old file is deleted first).
    private static DownloadResult downloadOnce(String host, int port, String filename,
                                               File tempFile, int workers, boolean useNio)
            throws Exception {
        tempFile.delete();
        return Downloader.download(host, port, filename, tempFile, workers, useNio, false);
    }
}
