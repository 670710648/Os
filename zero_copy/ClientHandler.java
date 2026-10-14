import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Talks to ONE client. The Server creates one ClientHandler (and one thread) per connection.
 * It reads commands (LIST / INFO / HASH / GET) and sends the answers,
 * until the client closes the connection.
 */
public class ClientHandler implements Runnable {

    // If a client sends nothing for this long (in milliseconds), the server closes the
    // connection. Without it, a client that connects and then does nothing would keep one
    // server thread alive for ever. (It is not "final" only so that TestRunner can make it short.)
    static int idleTimeoutMs = 60000;

    private SocketChannel channel;    // the connection to this client
    private File folder;              // the folder that is shared
    private InputStream in;           // used to read what the client sends
    private OutputStream out;         // used to send answers to the client

    public ClientHandler(SocketChannel channel, File folder) {
        this.channel = channel;
        this.folder = folder;
    }

    // This method runs inside the new thread (started by Server).
    public void run() {
        try {
            in = channel.socket().getInputStream();
            out = channel.socket().getOutputStream();
            channel.socket().setSoTimeout(idleTimeoutMs);    // only affects READING commands

            while (true) {
                String line = Protocol.readLine(in);
                if (line == null) {
                    break;                       // the client closed the connection
                }
                handleCommand(line);
            }
        } catch (SocketTimeoutException e) {
            System.out.println("[server] closing a connection that was idle for too long");
        } catch (IOException e) {
            // normal when a client disconnects in the middle of a transfer
            System.out.println("[server] connection ended: " + e.getMessage());
        } finally {
            try {
                channel.close();                 // always close the connection at the end
            } catch (IOException e) {
                // nothing more we can do
            }
        }
    }

    // Look at the first word of the line and call the right method.
    private void handleCommand(String line) throws IOException {
        String[] parts = line.split("\\s+");     // split on spaces, e.g. "GET a.bin 0 10"
        // Locale.ROOT = always the English rules. (With the Turkish rules "info".toUpperCase()
        // would give "İNFO", and the command would not be recognised.)
        String command = parts[0].toUpperCase(Locale.ROOT);

        if (command.equals("LIST")) {
            handleList();
        } else if (command.equals("INFO")) {
            handleInfo(parts);
        } else if (command.equals("HASH")) {
            handleHash(parts);
        } else if (command.equals("GET")) {
            handleGet(parts);
        } else {
            Protocol.sendLine(out, "ERROR 400 Unknown command");
        }
    }

    // LIST  ->  FILE <name> <size> ... and then END
    // Only files that a client can really ask for are listed (see isAllowedName).
    private void handleList() throws IOException {
        File[] files = folder.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                if (f.isFile() && isAllowedName(f.getName())) {
                    Protocol.sendLine(out, "FILE " + f.getName() + " " + f.length());
                }
            }
        }
        Protocol.sendLine(out, "END");
    }

    // INFO <filename>  ->  SIZE <bytes>
    private void handleInfo(String[] parts) throws IOException {
        if (parts.length != 2) {
            Protocol.sendLine(out, "ERROR 400 Usage: INFO <filename>"
                    + "   (the file name cannot contain spaces)");
            return;
        }
        File file = findFile(parts[1]);
        if (file == null) {
            Protocol.sendLine(out, "ERROR 404 File not found");
            return;
        }
        Protocol.sendLine(out, "SIZE " + file.length());
    }

    // HASH <filename>  ->  SHA256 <hex>   (the client uses it to check its download)
    private void handleHash(String[] parts) throws IOException {
        if (parts.length != 2) {
            Protocol.sendLine(out, "ERROR 400 Usage: HASH <filename>"
                    + "   (the file name cannot contain spaces)");
            return;
        }
        File file = findFile(parts[1]);
        if (file == null) {
            Protocol.sendLine(out, "ERROR 404 File not found");
            return;
        }
        Protocol.sendLine(out, "SHA256 " + FileUtil.sha256(file));
    }

    // GET <filename> <offset> <length> [io|nio]  ->  OK <length> and then the raw bytes
    private void handleGet(String[] parts) throws IOException {
        // check the number of words
        if (parts.length != 4 && parts.length != 5) {
            Protocol.sendLine(out, "ERROR 400 Usage: GET <filename> <offset> <length> [io|nio]"
                    + "   (the file name cannot contain spaces)");
            return;
        }

        // check the file
        File file = findFile(parts[1]);
        if (file == null) {
            Protocol.sendLine(out, "ERROR 404 File not found");
            return;
        }

        // check that offset and length are numbers
        long offset;
        long length;
        try {
            offset = Long.parseLong(parts[2]);
            length = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            Protocol.sendLine(out, "ERROR 400 Offset and length must be numbers");
            return;
        }

        // check the mode (io is the default)
        String mode = Protocol.MODE_IO;
        if (parts.length == 5) {
            mode = parts[4].toLowerCase(Locale.ROOT);
        }
        if (!mode.equals(Protocol.MODE_IO) && !mode.equals(Protocol.MODE_NIO)) {
            Protocol.sendLine(out, "ERROR 400 Mode must be io or nio");
            return;
        }

        // check that the range is inside the file
        // (we write "length > size - offset" instead of "offset + length > size"
        //  because offset + length could overflow when the numbers are huge)
        long size = file.length();
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            Protocol.sendLine(out, "ERROR 416 Range out of bounds (file size is " + size + ")");
            return;
        }

        // everything is fine: first the header line, then exactly <length> bytes
        Protocol.sendLine(out, "OK " + length);
        if (mode.equals(Protocol.MODE_NIO)) {
            FileSender.sendWithNio(file, offset, length, channel);
        } else {
            FileSender.sendWithStreams(file, offset, length, out);
        }
        System.out.println("[server] GET " + file.getName() + " offset=" + offset
                + " length=" + length + " mode=" + mode);
    }

    // Is this file name allowed? The same rule is used by LIST and by findFile,
    // so LIST never shows a file that the client cannot download.
    //   - / \ : and ..  are refused, so a client cannot read files outside the
    //     shared folder (for example "../secret.txt").
    //   - a space (or tab) is refused, because every command is split on spaces:
    //     the name "my file.txt" would arrive as two words and could never be found.
    static boolean isAllowedName(String name) {
        if (name.contains("/") || name.contains("\\") || name.contains(":")
                || name.contains("..")) {
            return false;
        }
        return !Pattern.compile("\\s").matcher(name).find();
    }

    // Find a file in the shared folder by its name.
    // Returns null if the name is not allowed or the file does not exist.
    private File findFile(String name) {
        if (!isAllowedName(name)) {
            return null;
        }
        File file = new File(folder, name);
        if (!file.isFile()) {
            return null;
        }
        return file;
    }
}
