import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared by the Server and the Client.
 * It keeps the constants and two small helper methods:
 * one to read a line of text and one to send a line of text.
 *
 * The protocol: every request is ONE line of text that ends with '\n'.
 *   LIST                                       -> FILE <name> <size> (one line per file), then END
 *   INFO <filename>                            -> SIZE <bytes>
 *   HASH <filename>                            -> SHA256 <hex text>
 *   GET <filename> <offset> <length> [io|nio]  -> OK <length>   and then exactly <length> raw bytes
 *   (any problem)                              -> ERROR <code> <message>
 * Error codes: 400 = bad request, 404 = file not found, 416 = range out of bounds
 */
public class Protocol {

    public static final int DEFAULT_PORT = 5000;
    public static final int BUFFER_SIZE = 64 * 1024;     // 64 KB
    public static final String MODE_IO = "io";           // Traditional I/O
    public static final String MODE_NIO = "nio";         // NIO transferTo / transferFrom

    // Read one line of text (everything up to '\n').
    // Returns null if the other side has closed the connection.
    // We read ONE byte at a time on purpose. A BufferedReader might read too far
    // and swallow the file bytes that come right after the header line.
    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();                  // read ONE byte (-1 means "connection closed")
            if (b == -1) {
                if (bytes.size() == 0) {
                    return null;
                }
                break;
            }
            if (b == '\n') {
                break;
            }
            bytes.write(b);
            if (bytes.size() > 1024) {
                throw new IOException("Line is too long");
            }
        }
        // Turn the bytes into text only at the end (one character may use several bytes).
        return bytes.toString(StandardCharsets.UTF_8).trim();
    }

    // Send one line of text. The "\n" at the end tells the other side the line is finished.
    public static void sendLine(OutputStream out, String text) throws IOException {
        String line = text + "\n";
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
