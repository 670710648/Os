import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * A helper tool: creates a file full of random bytes to use in the experiment.
 * It works on Windows, Linux and macOS and needs no administrator rights.
 *
 * Usage:   java MakeTestFile <outputFile> <sizeInMB>
 * Example: java MakeTestFile shared/test.bin 500
 */
public class MakeTestFile {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Usage: java MakeTestFile <outputFile> <sizeInMB>");
            System.out.println("Example: java MakeTestFile shared/test.bin 500");
            return;
        }

        File file = new File(args[0]).getAbsoluteFile();
        int sizeMb = Integer.parseInt(args[1]);

        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();                         // create the folder if it does not exist
        }

        Random random = new Random(12345);           // a fixed number: same content every time
        byte[] block = new byte[1024 * 1024];        // 1 MB
        FileOutputStream out = new FileOutputStream(file);
        for (int i = 1; i <= sizeMb; i++) {
            random.nextBytes(block);                 // fill the block with random bytes
            out.write(block);
            if (i % 100 == 0) {
                System.out.println("  written " + i + " MB ...");
            }
        }
        out.close();

        System.out.println("Created " + file + " (" + file.length() + " bytes)");
    }
}
