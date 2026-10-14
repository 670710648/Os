/**
 * CLIENT side: a small box that holds the result of one download.
 * It has only fields (no methods).
 */
public class DownloadResult {
    public long sizeBytes;       // size of the file
    public double seconds;       // time used by the workers
    public double mbPerSec;      // throughput in MB/s
    public boolean verified;     // true if size and SHA-256 are the same as the original
}
