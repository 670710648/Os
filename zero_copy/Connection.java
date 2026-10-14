import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;

/**
 * CLIENT side: one TCP connection to the server.
 * It keeps the channel (needed by NIO) and the two streams (needed by Traditional I/O).
 * "implements AutoCloseable" lets us use it in  try (Connection c = new Connection(...)) { ... }
 * so that the connection is closed automatically at the end of the block.
 */
public class Connection implements AutoCloseable {

    public SocketChannel channel;
    public InputStream in;
    public OutputStream out;

    // Connect to the server. Throws IOException if the server cannot be reached.
    public Connection(String host, int port) throws IOException {
        try {
            channel = SocketChannel.open(new InetSocketAddress(host, port));
        } catch (UnresolvedAddressException e) {
            // Java gives an exception WITHOUT a message when the host name is wrong,
            // which would print "Error: null". We give a clear message instead.
            throw new IOException("Cannot find host \"" + host + "\" (check the name or IP)");
        }
        in = channel.socket().getInputStream();
        out = channel.socket().getOutputStream();
    }

    public void sendLine(String text) throws IOException {
        Protocol.sendLine(out, text);
    }

    public String readLine() throws IOException {
        return Protocol.readLine(in);
    }

    // Read one answer line from the server.
    // If the server closed the connection, or answered "ERROR ...", we throw an exception.
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

    public void close() throws IOException {
        channel.close();
    }
}
