import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared by the Server and the Client.
 * Calculates the SHA-256 "fingerprint" of a file.
 * If two files have the same SHA-256, they are (practically) identical,
 * so we use it to check that a downloaded file is not damaged.
 */
public class FileUtil {

    public static String sha256(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];

            // read the file piece by piece and feed every piece to the digest
            int n = in.read(buffer);
            while (n != -1) {
                digest.update(buffer, 0, n);
                n = in.read(buffer);
            }

            // turn the 32 result bytes into 64 hex characters, e.g. "3ccca89a..."
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }
}
