import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Creates a file of random bytes to use for the experiment.
 * Works on Windows / Linux / macOS and needs no administrator rights.
 *
 * Usage:  java MakeTestFile <outputFile> <sizeInMB>
 * Example: java MakeTestFile shared/test.bin 500
 */
public class MakeTestFile {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Usage: java MakeTestFile <outputFile> <sizeInMB>");
            System.out.println("Example: java MakeTestFile shared/test.bin 500");
            return;
        }
        Path file = Path.of(args[0]).toAbsolutePath();
        int sizeMb = Integer.parseInt(args[1]);
        Files.createDirectories(file.getParent());

        Random random = new Random(12345);           // fixed seed: same content every time
        byte[] block = new byte[1024 * 1024];        // 1 MB
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), block.length)) {
            for (int i = 0; i < sizeMb; i++) {
                random.nextBytes(block);
                out.write(block);
                if ((i + 1) % 100 == 0) {
                    System.out.println("  written " + (i + 1) + " MB ...");
                }
            }
        }
        System.out.println("Created " + file + " (" + Files.size(file) + " bytes)");
    }
}
