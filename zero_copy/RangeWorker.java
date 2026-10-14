import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Locale;

/**
 * CLIENT side: ONE worker. A worker is a thread that
 *   - has its OWN connection to the server,
 *   - downloads only its OWN part of the file: bytes [offset, offset + length),
 *   - writes that part at the same position in the final file.
 * The parts of different workers never overlap, so the workers never disturb each other.
 */
public class RangeWorker extends Thread {

    private int workerId;
    private String host;
    private int port;
    private String filename;
    private File outFile;
    private long offset;
    private long length;
    private boolean useNio;
    private boolean verbose;

    // null = the download of this part succeeded, otherwise the error text.
    // The main thread reads it after join(), so no extra locking is needed.
    public String error = null;

    public RangeWorker(int workerId, String host, int port, String filename, File outFile,
                       long offset, long length, boolean useNio, boolean verbose) {
        this.workerId = workerId;
        this.host = host;
        this.port = port;
        this.filename = filename;
        this.outFile = outFile;
        this.offset = offset;
        this.length = length;
        this.useNio = useNio;
        this.verbose = verbose;
    }

    // This method runs in the worker's own thread after start() is called.
    public void run() {
        long startTime = System.nanoTime();

        try (Connection c = new Connection(host, port)) {
            // 1) ask the server for our part of the file
            String mode = Protocol.MODE_IO;
            if (useNio) {
                mode = Protocol.MODE_NIO;
            }
            c.sendLine("GET " + filename + " " + offset + " " + length + " " + mode);

            // 2) the server answers "OK <length>" (or "ERROR ...")
            String reply = c.readReply();
            String[] words = reply.split(" ");
            long announced = Long.parseLong(words[1]);
            if (announced != length) {
                throw new IOException("Server announced " + announced
                        + " bytes, expected " + length);
            }

            // 3) receive the bytes and write them into the file
            if (useNio) {
                receiveWithNio(c);
            } else {
                receiveWithStreams(c);
            }
        } catch (Exception e) {
            error = e.toString();
            return;
        }

        if (verbose) {
            System.out.printf(Locale.ROOT, "  worker %2d  offset=%-12d length=%-12d %.0f ms%n",
                    workerId, offset, length, (System.nanoTime() - startTime) / 1e6);
        }
    }

    // Way 1: Traditional I/O
    // socket -> byte[] in our program -> file
    private void receiveWithStreams(Connection c) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            raf.seek(offset);                                  // jump to our part of the file
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long remaining = length;                           // bytes still missing

            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int n = c.in.read(buffer, 0, toRead);
                if (n == -1) {
                    throw new IOException("Server closed early, " + remaining + " bytes missing");
                }
                raf.write(buffer, 0, n);
                remaining = remaining - n;
            }
        }
    }

    // Way 2: NIO native transfer   (socket -> file with FileChannel.transferFrom)
    // NOTE: on this side it is NOT real "zero copy". When the source is a socket, Java still
    // reads the socket in small pieces (8 KB) into a temporary buffer and then writes each piece
    // into the file. That is 8 times more system calls than our 64 KB buffer in way 1, which is
    // one reason why "nio" is often NOT faster on the client side.
    private void receiveWithNio(Connection c) throws IOException {
        try (FileChannel fileChannel = new RandomAccessFile(outFile, "rw").getChannel()) {
            long position = offset;
            long remaining = length;

            while (remaining > 0) {
                // transferFrom may receive LESS than we asked, so we repeat until all arrived
                long received = fileChannel.transferFrom(c.channel, position, remaining);
                if (received <= 0) {
                    throw new IOException("Server closed early, " + remaining + " bytes missing");
                }
                position = position + received;
                remaining = remaining - received;
            }
        }
    }
}
