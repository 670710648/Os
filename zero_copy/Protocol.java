import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared by Server and Client: protocol constants + the two helpers that
 * read / write one text line.
 *
 * Protocol (each request is ONE text line ending with '\n'):
 *   LIST                                   -> FILE <name> <size> ...  then  END
 *   INFO <filename>                        -> SIZE <bytes>
 *   HASH <filename>                        -> SHA256 <hex>
 *   GET <filename> <offset> <length> [io|nio]
 *                                          -> OK <length>\n + exactly <length> raw bytes
 *   any problem                            -> ERROR <code> <message>
 *
 * Error codes: 400 bad request, 404 file not found, 416 range out of bounds.
 */
public final class Protocol {

    private Protocol() { }   // only static members, nobody should create an object

    public static final int DEFAULT_PORT = 5000;
    public static final int BUFFER_SIZE = 64 * 1024;   // 64 KB copy buffer
    public static final int MAX_LINE = 1024;           // longest header line we accept

    public static final String MODE_IO = "io";         // Traditional I/O
    public static final String MODE_NIO = "nio";       // NIO transferTo / transferFrom

    // Read one text line byte-by-byte. We deliberately do NOT use BufferedReader,
    // because it could read ahead and swallow the payload bytes after the header.
    // Returns null when the other side closed the connection.
    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return buf.toString(StandardCharsets.UTF_8).replace("\r", "");
            }
            buf.write(b);
            if (buf.size() > MAX_LINE) {
                throw new IOException("Header line too long");
            }
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
    }

    public static void sendLine(OutputStream out, String text) throws IOException {
        out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
