import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * CLIENT side: the whole download job.
 *   1. ask the server for the file size
 *   2. split the file into byte ranges (one per worker)
 *   3. start the RangeWorkers and wait for all of them
 *   4. verify size + SHA-256
 */
public class Downloader {

    public static class Result {
        public long sizeBytes;
        public double seconds;
        public double mbPerSec;
        public boolean verified;
    }

    public static long getFileSize(String host, int port, String filename) throws IOException {
        try (Connection c = new Connection(host, port)) {
            c.sendLine("INFO " + filename);
            String reply = c.readReply();                    // "SIZE <bytes>"
            return Long.parseLong(reply.split(" ")[1]);
        }
    }

    public static String getServerHash(String host, int port, String filename) throws IOException {
        try (Connection c = new Connection(host, port)) {
            c.sendLine("HASH " + filename);
            String reply = c.readReply();                    // "SHA256 <hex>"
            return reply.split(" ")[1];
        }
    }

    public static Result download(String host, int port, String filename, Path outFile,
                                  int workers, boolean useNio, boolean verbose) throws Exception {
        // 1) ask the server for the size
        long size = getFileSize(host, port, filename);

        // 2) create the final file with its final size, so every worker can
        //    write into its own region with a positional write
        try (RandomAccessFile raf = new RandomAccessFile(outFile.toFile(), "rw")) {
            raf.setLength(size);
        }

        // 3) split: every worker gets size/workers bytes, the LAST worker gets the rest
        long chunk = size / workers;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<Void>> futures = new ArrayList<>();

        long start = System.nanoTime();
        try {
            for (int i = 0; i < workers; i++) {
                long offset = (long) i * chunk;
                long length = (i == workers - 1) ? size - offset : chunk;
                futures.add(pool.submit(
                        new RangeWorker(i, host, port, filename, outFile, offset, length, useNio, verbose)));
            }

            // wait for every worker; if one failed, report its error
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new IOException("A worker failed: " + cause.getMessage(), cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        // 4) verify size + SHA-256 (this check is NOT included in the measured time)
        Result r = new Result();
        r.sizeBytes = size;
        r.seconds = seconds;
        r.mbPerSec = (size / 1024.0 / 1024.0) / seconds;
        r.verified = Files.size(outFile) == size
                && FileUtil.sha256(outFile).equalsIgnoreCase(getServerHash(host, port, filename));
        return r;
    }
}
