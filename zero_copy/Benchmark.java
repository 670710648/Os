import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLIENT side: runs every experiment case and prints a table.
 *   io / nio  x  1 / 10 workers  x  <runs> runs
 * One warm-up download is done first (not counted) so the file is already in the
 * OS page cache for every case.
 */
public class Benchmark {

    public static void run(String host, int port, String filename, int runs) throws Exception {
        Path tmp = Path.of("benchmark_download.tmp");
        String[] modes = {Protocol.MODE_IO, Protocol.MODE_NIO};
        int[] workerCounts = {1, 10};

        System.out.println("Warm-up run (not counted) ...");
        Downloader.download(host, port, filename, tmp, 1, false, false);

        System.out.println();
        System.out.printf("%-6s %-8s %-4s %-10s %-10s %-9s%n", "mode", "workers", "run", "time(s)", "MB/s", "verified");

        List<String> summary = new ArrayList<>();
        for (String mode : modes) {
            for (int workers : workerCounts) {
                double sumTime = 0;
                double sumSpeed = 0;
                for (int run = 1; run <= runs; run++) {
                    Downloader.Result r = Downloader.download(
                            host, port, filename, tmp, workers, mode.equals(Protocol.MODE_NIO), false);
                    sumTime += r.seconds;
                    sumSpeed += r.mbPerSec;
                    System.out.printf("%-6s %-8d %-4d %-10.3f %-10.1f %-9s%n",
                            mode, workers, run, r.seconds, r.mbPerSec, r.verified ? "OK" : "MISMATCH");
                }
                summary.add(String.format("%-6s %-8d avg time = %.3f s   avg speed = %.1f MB/s",
                        mode, workers, sumTime / runs, sumSpeed / runs));
            }
        }

        System.out.println();
        System.out.println("=== Average over " + runs + " runs ===");
        for (String line : summary) {
            System.out.println(line);
        }
        Files.deleteIfExists(tmp);
    }
}
