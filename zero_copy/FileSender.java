import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * SERVER side: the two ways of sending a byte range of a file to a client.
 * This is the part of the experiment that differs between "io" and "nio".
 */
public final class FileSender {

    private FileSender() { }

    // Traditional I/O: file -> byte[] (user space) -> socket
    public static void sendWithStreams(Path file, long offset, long length, OutputStream out) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long remaining = length;
            while (remaining > 0) {
                int n = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    throw new EOFException("File ended early");
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
            out.flush();
        }
    }

    // NIO native transfer: file -> socket directly inside the OS (zero-copy where supported)
    public static void sendWithNio(Path file, long offset, long length, SocketChannel channel) throws IOException {
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            long position = offset;
            long remaining = length;
            while (remaining > 0) {
                // transferTo may send fewer bytes than asked, so we must loop
                long n = fc.transferTo(position, remaining, channel);
                if (n <= 0) {
                    throw new EOFException("transferTo made no progress");
                }
                position += n;
                remaining -= n;
            }
        }
    }
}
