import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Automated test cases. Run with:   java TestRunner
 *
 * It does everything by itself (no other setup needed):
 *   - creates small test files in a temporary folder
 *   - starts the Server on port 5055 inside this program
 *   - runs the tests and prints PASS / FAIL for each one
 *   - deletes the temporary files and exits (exit code 0 = all passed)
 *
 * Test groups:
 *   1. Protocol tests    LIST / INFO / HASH / GET and every error reply
 *   2. Download tests    7 file sizes x io/nio x 1/3/10 workers, each checked by SHA-256
 *   3. Concurrency test  4 clients (40 connections) downloading at the same time
 *   4. Robustness tests  missing file, client that disconnects in the middle
 */
public class TestRunner {

    private static final int PORT = 5055;
    private static final String HOST = "localhost";

    // name -> size. Sizes are chosen to hit the tricky cases of splitting into 10 parts.
    private static final String[] NAMES = {"empty.bin", "one.bin", "tiny.bin", "ten.bin", "odd.bin", "mid.bin", "big.bin"};
    private static final long[] SIZES = {0, 1, 9, 10, 1_000_003, 5 * 1024 * 1024 + 7, 8 * 1024 * 1024 + 7};
    //   0        -> empty file
    //   1, 9     -> smaller than the number of workers (some workers get 0 bytes)
    //   10       -> exactly 1 byte per worker
    //   1000003  -> not divisible by 10 (the last worker must take the remainder)

    private static PrintStream out;          // our own output (server logs are silenced)
    private static int passed = 0;
    private static int failed = 0;
    private static Path dataDir;
    private static Path outDir;

    public static void main(String[] args) throws Exception {
        out = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); // hide the server's log lines

        dataDir = Files.createTempDirectory("zc_test_shared");
        outDir = Files.createTempDirectory("zc_test_out");
        try {
            for (int i = 0; i < NAMES.length; i++) {
                makeFile(NAMES[i], SIZES[i]);
            }
            startServer();

            section("1. Protocol tests");
            protocolTests();
            section("2. Download tests (file size x mode x workers)");
            downloadTests();
            section("3. Concurrency test");
            concurrencyTest();
            section("4. Robustness tests");
            robustnessTests();
        } finally {
            deleteFolder(dataDir);
            deleteFolder(outDir);
        }

        out.println();
        out.println("==================================================");
        out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        out.println("==================================================");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------
    // 1. Protocol tests (raw text commands, no Downloader)
    // ------------------------------------------------------------------
    private static void protocolTests() throws Exception {
        byte[] odd = Files.readAllBytes(dataDir.resolve("odd.bin"));

        test("LIST shows every file with its size", () -> {
            try (Connection c = new Connection(HOST, PORT)) {
                c.sendLine("LIST");
                List<String> lines = new ArrayList<>();
                String line;
                while (!(line = c.readLine()).equals("END")) {
                    lines.add(line);
                }
                return lines.size() == NAMES.length && lines.contains("FILE odd.bin 1000003");
            }
        });

        test("INFO returns the size (SIZE <bytes>)", () -> reply("INFO odd.bin").equals("SIZE 1000003"));
        test("INFO of an empty file returns SIZE 0", () -> reply("INFO empty.bin").equals("SIZE 0"));
        test("INFO of a missing file -> ERROR 404", () -> reply("INFO nope.bin").startsWith("ERROR 404"));
        test("INFO without file name -> ERROR 400", () -> reply("INFO").startsWith("ERROR 400"));

        test("HASH equals the real SHA-256", () ->
                reply("HASH odd.bin").equals("SHA256 " + FileUtil.sha256(dataDir.resolve("odd.bin"))));
        test("HASH of a missing file -> ERROR 404", () -> reply("HASH nope.bin").startsWith("ERROR 404"));

        test("Unknown command -> ERROR 400", () -> reply("HELLO").startsWith("ERROR 400"));

        test("GET with too few arguments -> ERROR 400", () -> reply("GET odd.bin 0").startsWith("ERROR 400"));
        test("GET with non-numeric offset -> ERROR 400", () -> reply("GET odd.bin abc 10").startsWith("ERROR 400"));
        test("GET with invalid mode -> ERROR 400", () -> reply("GET odd.bin 0 10 fast").startsWith("ERROR 400"));
        test("GET with negative offset -> ERROR 416", () -> reply("GET odd.bin -1 10").startsWith("ERROR 416"));
        test("GET with negative length -> ERROR 416", () -> reply("GET odd.bin 0 -5").startsWith("ERROR 416"));
        test("GET range beyond end of file -> ERROR 416", () -> reply("GET odd.bin 1000000 10").startsWith("ERROR 416"));
        test("GET with huge numbers (overflow) -> ERROR 416",
                () -> reply("GET odd.bin 9223372036854775807 9223372036854775807").startsWith("ERROR 416"));
        test("GET of a missing file -> ERROR 404", () -> reply("GET nope.bin 0 10").startsWith("ERROR 404"));
        test("GET with ../ path -> ERROR 404 (path traversal blocked)", () -> reply("GET ../odd.bin 0 10").startsWith("ERROR 404"));
        test("GET with \\ path -> ERROR 404", () -> reply("GET ..\\odd.bin 0 10").startsWith("ERROR 404"));

        test("GET length 0 -> OK 0", () -> reply("GET odd.bin 5 0").equals("OK 0"));
        test("GET last byte of the file works (edge of range)", () -> getBytesEqual("odd.bin", odd, odd.length - 1, 1, "io"));

        test("GET returns exactly the requested bytes (io)", () -> getBytesEqual("odd.bin", odd, 12345, 50000, "io"));
        test("GET returns exactly the requested bytes (nio)", () -> getBytesEqual("odd.bin", odd, 12345, 50000, "nio"));
        test("GET whole file returns identical bytes (nio)", () -> getBytesEqual("odd.bin", odd, 0, odd.length, "nio"));
        test("GET without mode word defaults to io", () -> {
            try (Connection c = new Connection(HOST, PORT)) {
                c.sendLine("GET odd.bin 100 200");
                return c.readLine().equals("OK 200")
                        && Arrays.equals(c.in.readNBytes(200), Arrays.copyOfRange(odd, 100, 300));
            }
        });

        test("Several commands on ONE connection (INFO, GET, INFO)", () -> {
            try (Connection c = new Connection(HOST, PORT)) {
                c.sendLine("INFO odd.bin");
                boolean a = c.readLine().equals("SIZE 1000003");
                c.sendLine("GET odd.bin 0 10 nio");
                boolean b = c.readLine().equals("OK 10") && Arrays.equals(c.in.readNBytes(10), Arrays.copyOfRange(odd, 0, 10));
                c.sendLine("INFO odd.bin");
                boolean d = c.readLine().equals("SIZE 1000003");
                return a && b && d;
            }
        });

        test("Error does not close the connection (next command still works)", () -> {
            try (Connection c = new Connection(HOST, PORT)) {
                c.sendLine("GET nope.bin 0 1");
                boolean a = c.readLine().startsWith("ERROR 404");
                c.sendLine("INFO odd.bin");
                return a && c.readLine().equals("SIZE 1000003");
            }
        });
    }

    // ------------------------------------------------------------------
    // 2. Download tests: the whole matrix
    // ------------------------------------------------------------------
    private static void downloadTests() throws Exception {
        int[] workerCounts = {1, 3, 10};
        String[] modes = {"io", "nio"};
        for (int i = 0; i < NAMES.length; i++) {
            for (String mode : modes) {
                for (int workers : workerCounts) {
                    String name = NAMES[i];
                    long size = SIZES[i];
                    String label = String.format("%-10s (%8d bytes)  mode=%-3s workers=%-2d", name, size, mode, workers);
                    test(label, () -> downloadAndCheck(name, size, workers, mode.equals("nio")));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. Concurrency: several clients at the same time
    // ------------------------------------------------------------------
    private static void concurrencyTest() throws Exception {
        test("4 clients x 10 workers at the same time (mixed io/nio)", () -> {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    final int n = i;
                    results.add(pool.submit(() -> downloadAndCheck("big.bin", 8 * 1024 * 1024 + 7, 10, n % 2 == 1, "par" + n)));
                }
                boolean all = true;
                for (Future<Boolean> f : results) {
                    all &= f.get();
                }
                return all;
            } finally {
                pool.shutdownNow();
            }
        });
    }

    // ------------------------------------------------------------------
    // 4. Robustness
    // ------------------------------------------------------------------
    private static void robustnessTests() throws Exception {
        test("Downloading a missing file fails with an error (no crash, no hang)", () -> {
            try {
                Downloader.download(HOST, PORT, "nope.bin", outDir.resolve("nope.out"), 10, false, false);
                return false;                        // should have thrown
            } catch (IOException expected) {
                return expected.getMessage().contains("404");
            }
        });

        test("Server keeps working after a client disconnects in the middle of a transfer", () -> {
            try (Connection c = new Connection(HOST, PORT)) {
                c.sendLine("GET big.bin 0 " + (8 * 1024 * 1024 + 7) + " io");
                c.readLine();
                c.in.readNBytes(10);                 // read a few bytes, then drop the connection
            }
            Thread.sleep(300);
            return reply("INFO odd.bin").equals("SIZE 1000003");
        });

        test("Downloading again over an existing output file works (file is reset)", () -> {
            Path target = outDir.resolve("again.out");
            Files.write(target, new byte[3_000_000]); // old, larger, junk content
            Downloader.Result r = Downloader.download(HOST, PORT, "odd.bin", target, 10, true, false);
            return r.verified && Files.size(target) == 1_000_003;
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private interface Check {
        boolean run() throws Exception;
    }

    private static void test(String name, Check check) {
        boolean ok;
        String problem = "";
        try {
            ok = check.run();
        } catch (Exception e) {
            ok = false;
            problem = "  (" + e + ")";
        }
        if (ok) {
            passed++;
        } else {
            failed++;
        }
        out.println((ok ? "  PASS  " : "  FAIL  ") + name + problem);
    }

    private static void section(String title) {
        out.println();
        out.println("--- " + title);
    }

    // send one command on a fresh connection, return the first reply line
    private static String reply(String command) throws IOException {
        try (Connection c = new Connection(HOST, PORT)) {
            c.sendLine(command);
            return c.readLine();
        }
    }

    // GET a range and compare the received bytes with the same range of the original
    private static boolean getBytesEqual(String name, byte[] original, int offset, int length, String mode) throws IOException {
        try (Connection c = new Connection(HOST, PORT)) {
            c.sendLine("GET " + name + " " + offset + " " + length + " " + mode);
            if (!c.readLine().equals("OK " + length)) {
                return false;
            }
            byte[] got = c.in.readNBytes(length);
            return Arrays.equals(got, Arrays.copyOfRange(original, offset, offset + length));
        }
    }

    private static boolean downloadAndCheck(String name, long size, int workers, boolean nio) throws Exception {
        return downloadAndCheck(name, size, workers, nio, "single");
    }

    // Download with the real client code, then check size + SHA-256 against the ORIGINAL file.
    private static boolean downloadAndCheck(String name, long size, int workers, boolean nio, String tag) throws Exception {
        Path target = outDir.resolve(tag + "_" + name + "_" + workers + (nio ? "nio" : "io"));
        Downloader.Result r = Downloader.download(HOST, PORT, name, target, workers, nio, false);
        boolean sameAsOriginal = Files.size(target) == size
                && FileUtil.sha256(target).equals(FileUtil.sha256(dataDir.resolve(name)));
        Files.deleteIfExists(target);
        return r.verified && sameAsOriginal;
    }

    private static void makeFile(String name, long size) throws IOException {
        byte[] data = new byte[(int) size];
        new Random(size).nextBytes(data);
        Files.write(dataDir.resolve(name), data);
    }

    // Run the real Server in a background thread and wait until it accepts connections.
    private static void startServer() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                Server.main(new String[]{String.valueOf(PORT), dataDir.toString()});
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "test-server");
        t.setDaemon(true);
        t.start();

        for (int i = 0; i < 50; i++) {
            if (failure.get() != null) {
                break;
            }
            try {
                new Connection(HOST, PORT).close();
                return;                              // server is up
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        out.println("Could not start the server on port " + PORT + " (is the port already in use?) " + failure.get());
        System.exit(2);
    }

    private static void deleteFolder(Path dir) {
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp files, not important
        }
    }
}
