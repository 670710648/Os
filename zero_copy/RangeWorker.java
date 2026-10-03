import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;

/**
 * CLIENT side: one worker. It has its OWN connection, its OWN file handle and
 * downloads only its OWN byte range [offset, offset + length), writing it at the
 * same position of the final file. Ranges never overlap, so workers never conflict.
 */
public class RangeWorker implements Callable<Void> {

    private final int id;
    private final String host;
    private final int port;
    private final String filename;
    private final Path outFile;
    private final long offset;
    private final long length;
    private final boolean useNio;
    private final boolean verbose;

    public RangeWorker(int id, String host, int port, String filename, Path outFile,
                       long offset, long length, boolean useNio, boolean verbose) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.filename = filename;
        this.outFile = outFile;
        this.offset = offset;
        this.length = length;
        this.useNio = useNio;
        this.verbose = verbose;
    }

    @Override
    public Void call() throws IOException {
        long t0 = System.nanoTime();

        try (Connection c = new Connection(host, port)) {
            String mode = useNio ? Protocol.MODE_NIO : Protocol.MODE_IO;
            c.sendLine("GET " + filename + " " + offset + " " + length + " " + mode);

            String reply = c.readReply();                     // "OK <length>"
            long announced = Long.parseLong(reply.split(" ")[1]);
            if (announced != length) {
                throw new IOException("Server announced " + announced + " bytes, expected " + length);
            }

            if (useNio) {
                receiveWithNio(c);
            } else {
                receiveWithStreams(c);
            }
        }

        if (verbose) {
            System.out.printf("  worker %2d  offset=%-12d length=%-12d %.0f ms%n",
                    id, offset, length, (System.nanoTime() - t0) / 1e6);
        }
        return null;
    }

    // Traditional I/O: socket -> byte[] (user space) -> RandomAccessFile
    private void receiveWithStreams(Connection c) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(outFile.toFile(), "rw")) {
            raf.seek(offset);
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long remaining = length;
            while (remaining > 0) {
                int n = c.in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    throw new EOFException("Server closed early, " + remaining + " bytes missing");
                }
                raf.write(buffer, 0, n);
                remaining -= n;
            }
        }
    }

    // NIO native transfer: socket channel -> file channel at a given position
    private void receiveWithNio(Connection c) throws IOException {
        try (FileChannel fc = FileChannel.open(outFile, StandardOpenOption.WRITE)) {
            long position = offset;
            long remaining = length;
            while (remaining > 0) {
                // transferFrom may move fewer bytes than asked, so we must loop
                long n = fc.transferFrom(c.channel, position, remaining);
                if (n <= 0) {
                    throw new EOFException("Server closed early, " + remaining + " bytes missing");
                }
                position += n;
                remaining -= n;
            }
        }
    }
}
