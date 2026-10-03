import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/**
 * CLIENT side: one TCP connection to the server.
 * Holds the SocketChannel (needed for NIO) and its streams (needed for Traditional I/O).
 * Use it with try-with-resources so the connection is always closed.
 */
public class Connection implements AutoCloseable {

    final SocketChannel channel;
    final InputStream in;
    final OutputStream out;

    public Connection(String host, int port) throws IOException {
        channel = SocketChannel.open(new InetSocketAddress(host, port));
        in = channel.socket().getInputStream();
        out = channel.socket().getOutputStream();
    }

    public void sendLine(String text) throws IOException {
        Protocol.sendLine(out, text);
    }

    public String readLine() throws IOException {
        return Protocol.readLine(in);
    }

    // Read one reply line; throws if the server closed the connection or answered "ERROR ...".
    public String readReply() throws IOException {
        String reply = readLine();
        if (reply == null) {
            throw new IOException("Server closed the connection");
        }
        if (reply.startsWith("ERROR")) {
            throw new IOException("Server replied: " + reply);
        }
        return reply;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
