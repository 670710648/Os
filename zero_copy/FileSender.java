import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;

/**
 * SERVER side: the two ways to send a part of a file to a client.
 * This is where "Traditional I/O" and "NIO" are different.
 */
public class FileSender {

    // Way 1: Traditional I/O
    // file -> byte[] in our program -> socket
    public static void sendWithStreams(File file, long offset, long length, OutputStream out)
            throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);                                  // jump to the start of our part
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long remaining = length;                           // bytes we still have to send

            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int n = raf.read(buffer, 0, toRead);           // n = how many bytes we really got
                if (n == -1) {
                    throw new IOException("File ended too early");
                }
                out.write(buffer, 0, n);
                remaining = remaining - n;
            }
            out.flush();
        }
    }

    // Way 2: NIO native transfer   (file -> socket with FileChannel.transferTo)
    // The operating system moves the bytes from the file to the socket by itself: no byte[] in
    // our program. On Linux one call to sendfile() can move a whole part of the file.
    // This is the real "zero copy" side of the experiment.
    public static void sendWithNio(File file, long offset, long length, SocketChannel channel)
            throws IOException {
        try (FileChannel fileChannel = new RandomAccessFile(file, "r").getChannel()) {
            long position = offset;
            long remaining = length;

            while (remaining > 0) {
                // transferTo may send LESS than we asked, so we must repeat until all is sent
                long sent = fileChannel.transferTo(position, remaining, channel);
                if (sent <= 0) {
                    throw new IOException("transferTo sent nothing");
                }
                position = position + sent;
                remaining = remaining - sent;
            }
        }
    }
}
