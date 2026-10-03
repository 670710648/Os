import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs in its own thread, one per connection. Reads commands from one client
 * (LIST / INFO / HASH / GET) and answers them, until the client disconnects.
 */
public class ClientHandler implements Runnable {

    private final SocketChannel channel;
    private final Path baseDir;
    private final Map<String, String> hashCache;

    public ClientHandler(SocketChannel channel, Path baseDir, Map<String, String> hashCache) {
        this.channel = channel;
        this.baseDir = baseDir;
        this.hashCache = hashCache;
    }

    @Override
    public void run() {
        String who = String.valueOf(channel.socket().getRemoteSocketAddress());
        try (channel) {
            InputStream in = channel.socket().getInputStream();
            OutputStream out = channel.socket().getOutputStream();

            String line;
            while ((line = Protocol.readLine(in)) != null) {
                String[] parts = line.trim().split("\\s+");
                String cmd = parts[0].toUpperCase();

                if (cmd.equals("LIST")) {
                    handleList(out);
                } else if (cmd.equals("INFO")) {
                    handleInfo(parts, out);
                } else if (cmd.equals("HASH")) {
                    handleHash(parts, out);
                } else if (cmd.equals("GET")) {
                    handleGet(parts, out);
                } else {
                    Protocol.sendLine(out, "ERROR 400 Unknown command");
                }
            }
        } catch (IOException e) {
            // Normal when a client disconnects in the middle of a transfer.
            log(who + " connection ended: " + e.getMessage());
        }
    }

    // LIST -> FILE <name> <size> ... END
    private void handleList(OutputStream out) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(baseDir)) {
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        Collections.sort(files);
        for (Path f : files) {
            Protocol.sendLine(out, "FILE " + f.getFileName() + " " + Files.size(f));
        }
        Protocol.sendLine(out, "END");
    }

    // INFO <filename> -> SIZE <bytes>
    private void handleInfo(String[] parts, OutputStream out) throws IOException {
        if (parts.length != 2) {
            Protocol.sendLine(out, "ERROR 400 Usage: INFO <filename>");
            return;
        }
        Path file = resolveFile(parts[1]);
        if (file == null) {
            Protocol.sendLine(out, "ERROR 404 File not found");
            return;
        }
        Protocol.sendLine(out, "SIZE " + Files.size(file));
    }

    // HASH <filename> -> SHA256 <hex>   (lets the client verify its download)
    private void handleHash(String[] parts, OutputStream out) throws IOException {
        if (parts.length != 2) {
            Protocol.sendLine(out, "ERROR 400 Usage: HASH <filename>");
            return;
        }
        Path file = resolveFile(parts[1]);
        if (file == null) {
            Protocol.sendLine(out, "ERROR 404 File not found");
            return;
        }
        String key = file + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file);
        String hash = hashCache.get(key);
        if (hash == null) {
            hash = FileUtil.sha256(file);
            hashCache.put(key, hash);
        }
        Protocol.sendLine(out, "SHA256 " + hash);
    }

    // GET <filename> <offset> <length> [io|nio]
    private void handleGet(String[] parts, OutputStream out) throws IOException {
        if (parts.length != 4 && parts.length != 5) {
            Protocol.sendLine(out, "ERROR 400 Usage: GET <filename> <offset> <length> [io|nio]");
            return;
        }

        Path file = resolveFile(parts[1]);
        if (file == null) {
            Protocol.sendLine(out, "ERROR 404 File not found");
            return;
        }

        long offset;
        long length;
        try {
            offset = Long.parseLong(parts[2]);
            length = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            Protocol.sendLine(out, "ERROR 400 Offset and length must be numbers");
            return;
        }

        String mode = parts.length == 5 ? parts[4].toLowerCase() : Protocol.MODE_IO;
        if (!mode.equals(Protocol.MODE_IO) && !mode.equals(Protocol.MODE_NIO)) {
            Protocol.sendLine(out, "ERROR 400 Mode must be io or nio");
            return;
        }

        long size = Files.size(file);
        // "length > size - offset" is written this way to avoid overflow of offset + length
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            Protocol.sendLine(out, "ERROR 416 Range out of bounds (file size is " + size + ")");
            return;
        }

        // Header first, then exactly <length> bytes of payload.
        Protocol.sendLine(out, "OK " + length);
        if (mode.equals(Protocol.MODE_NIO)) {
            FileSender.sendWithNio(file, offset, length, channel);
        } else {
            FileSender.sendWithStreams(file, offset, length, out);
        }
        log("GET " + file.getFileName() + " offset=" + offset + " length=" + length + " mode=" + mode);
    }

    // Only allow plain file names inside the shared folder (blocks ../ tricks).
    private Path resolveFile(String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        Path p = baseDir.resolve(name).normalize();
        if (!baseDir.equals(p.getParent()) || !Files.isRegularFile(p)) {
            return null;
        }
        return p;
    }

    private static void log(String msg) {
        System.out.println("[server] " + msg);
    }
}
