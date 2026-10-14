import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * CLIENT side: the whole download job.
 *   1. ask the server for the file size
 *   2. cut the file into parts, one part for each worker
 *   3. start the workers (threads) and wait until all of them are finished
 *   4. check that the downloaded file is correct (size and SHA-256)
 */
public class Downloader {

    // Ask the server: INFO <filename>  ->  SIZE <bytes>
    public static long getFileSize(String host, int port, String filename) throws IOException {
        try (Connection c = new Connection(host, port)) {
            c.sendLine("INFO " + filename);
            String reply = c.readReply();                  // "SIZE 104857600"
            String[] words = reply.split(" ");
            return Long.parseLong(words[1]);
        }
    }

    // Ask the server: HASH <filename>  ->  SHA256 <hex>
    public static String getServerHash(String host, int port, String filename) throws IOException {
        try (Connection c = new Connection(host, port)) {
            c.sendLine("HASH " + filename);
            String reply = c.readReply();                  // "SHA256 3ccca89a..."
            String[] words = reply.split(" ");
            return words[1];
        }
    }

    public static DownloadResult download(String host, int port, String filename, File outFile,
                                          int workers, boolean useNio, boolean verbose)
            throws IOException, InterruptedException {

        // 1) get the size of the file
        long size = getFileSize(host, port, filename);

        // 2) create the output file with its final size, so that every worker
        //    can write its part at the right position
        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            raf.setLength(size);
        }

        // 3) cut the file into parts: every worker gets size / workers bytes,
        //    and the LAST worker also gets the bytes that are left over
        long chunk = size / workers;
        RangeWorker[] list = new RangeWorker[workers];

        long startTime = System.nanoTime();
        for (int i = 0; i < workers; i++) {
            long offset = i * chunk;
            long length = chunk;
            if (i == workers - 1) {
                length = size - offset;
            }
            list[i] = new RangeWorker(i, host, port, filename, outFile,
                    offset, length, useNio, verbose);
            list[i].start();                               // the worker starts running in parallel
        }

        // 4) wait until every worker has finished
        for (int i = 0; i < workers; i++) {
            list[i].join();
        }
        double seconds = (System.nanoTime() - startTime) / 1e9;

        // 5) did any worker fail?
        for (int i = 0; i < workers; i++) {
            if (list[i].error != null) {
                // The file already has its full size (step 2), but some parts of it were never
                // filled. Delete it, so nobody mistakes it for a finished download.
                // (All workers are finished here, so nobody is using the file any more.)
                outFile.delete();
                throw new IOException("A worker failed: " + list[i].error);
            }
        }

        // 6) check the file (this check is NOT counted in the measured time)
        DownloadResult result = new DownloadResult();
        result.sizeBytes = size;
        result.seconds = seconds;
        result.mbPerSec = (size / 1024.0 / 1024.0) / seconds;

        boolean sameSize = (outFile.length() == size);
        String myHash = FileUtil.sha256(outFile);
        String serverHash = getServerHash(host, port, filename);
        result.verified = sameSize && myHash.equalsIgnoreCase(serverHash);
        return result;
    }
}
