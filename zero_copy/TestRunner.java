import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Automatic test cases.   Run with:   java TestRunner
 *
 * It does everything by itself:
 *   - creates small test files in a temporary folder
 *   - starts the Server (port 5055) inside this program
 *   - runs the checks and prints PASS or FAIL for each one
 *   - deletes the temporary files at the end
 * The exit code is 0 if everything passed, 1 if something failed.
 *
 * Test groups:
 *   1. Protocol tests     LIST / INFO / HASH / GET and every error answer
 *   2. Download tests     7 file sizes x io/nio x 1/3/10 workers, each checked with SHA-256
 *   3. Concurrency test   4 clients (40 connections) downloading at the same time
 *   4. Robustness tests   missing file, client that disconnects, old output file,
 *                         idle connection, Turkish language rules
 *   5. Client checks      the io/nio word on the command line, the benchmark and its CSV file
 *   6. Failure tests      a fake server that misbehaves on purpose: damaged bytes, a connection
 *                         that closes too early, and the exit codes of the real Client program
 */
public class TestRunner {

    static final int PORT = 5055;
    static final String HOST = "localhost";

    // The test files. The sizes test the tricky cases of cutting a file into 10 parts:
    //   0        = empty file
    //   1, 9     = smaller than the number of workers (some workers get 0 bytes)
    //   10       = exactly 1 byte per worker
    //   1000003  = not divisible by 10 (the last worker must take the rest)
    static final String[] NAMES = {"empty.bin", "one.bin", "tiny.bin", "ten.bin",
                                   "odd.bin", "mid.bin", "big.bin"};
    static final long[] SIZES = {0, 1, 9, 10, 1000003, 5 * 1024 * 1024 + 7, 8 * 1024 * 1024 + 7};

    static PrintStream out;          // our own output (the server's log lines are hidden)
    static int passed = 0;
    static int failed = 0;
    static File dataDir;             // the folder shared by the test server
    static File outDir;              // the folder for the downloaded files
    static String serverError = null;

    public static void main(String[] args) throws Exception {
        out = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));   // hide the server's log

        dataDir = Files.createTempDirectory("zc_test_shared").toFile();
        outDir = Files.createTempDirectory("zc_test_out").toFile();
        for (int i = 0; i < NAMES.length; i++) {
            makeFile(NAMES[i], SIZES[i]);
        }
        // two files that a client can NOT ask for (a space / ".." in the name).
        // LIST must not show them.
        Files.write(new File(dataDir, "my file.txt").toPath(), "x".getBytes());
        Files.write(new File(dataDir, "a..b.txt").toPath(), "y".getBytes());
        startServer();

        title("1. Protocol tests");
        protocolTests();
        title("2. Download tests (file size x mode x workers)");
        downloadTests();
        title("3. Concurrency test");
        concurrencyTest();
        title("4. Robustness tests");
        robustnessTests();
        title("5. Client checks");
        clientTests();
        title("6. Failure tests (a fake server that misbehaves on purpose)");
        failureTests();

        deleteFolder(dataDir);
        deleteFolder(outDir);

        out.println();
        out.println("==================================================");
        out.println(" RESULT: " + passed + " passed, " + failed + " failed");
        out.println("==================================================");
        if (failed == 0) {
            System.exit(0);
        } else {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 1. Protocol tests: we send text commands by hand and look at the answer
    // ------------------------------------------------------------------
    static void protocolTests() throws Exception {
        byte[] odd = Files.readAllBytes(new File(dataDir, "odd.bin").toPath());

        check("LIST shows every file with its size", listTest());
        check("LIST hides names that cannot be requested (space, ..)", listHidesBadNamesTest());

        check("INFO returns the size (SIZE <bytes>)", ask("INFO odd.bin").equals("SIZE 1000003"));
        check("INFO of an empty file returns SIZE 0", ask("INFO empty.bin").equals("SIZE 0"));
        check("INFO of a missing file -> ERROR 404", ask("INFO nope.bin").startsWith("ERROR 404"));
        check("INFO without file name -> ERROR 400", ask("INFO").startsWith("ERROR 400"));

        String realHash = FileUtil.sha256(new File(dataDir, "odd.bin"));
        check("HASH equals the real SHA-256", ask("HASH odd.bin").equals("SHA256 " + realHash));
        check("HASH of a missing file -> ERROR 404", ask("HASH nope.bin").startsWith("ERROR 404"));

        check("Unknown command -> ERROR 400", ask("HELLO").startsWith("ERROR 400"));

        check("GET with too few arguments -> ERROR 400",
                ask("GET odd.bin 0").startsWith("ERROR 400"));
        check("GET with non-numeric offset -> ERROR 400",
                ask("GET odd.bin abc 10").startsWith("ERROR 400"));
        check("GET with invalid mode -> ERROR 400",
                ask("GET odd.bin 0 10 fast").startsWith("ERROR 400"));
        check("GET with negative offset -> ERROR 416",
                ask("GET odd.bin -1 10").startsWith("ERROR 416"));
        check("GET with negative length -> ERROR 416",
                ask("GET odd.bin 0 -5").startsWith("ERROR 416"));
        check("GET range beyond end of file -> ERROR 416",
                ask("GET odd.bin 1000000 10").startsWith("ERROR 416"));
        check("GET with huge length (overflow) -> ERROR 416",
                ask("GET odd.bin 10 9223372036854775807").startsWith("ERROR 416"));
        check("GET of a missing file -> ERROR 404",
                ask("GET nope.bin 0 10").startsWith("ERROR 404"));
        // a real file OUTSIDE the shared folder: the server must refuse to send it
        Files.write(new File(outDir, "secret.txt").toPath(), "secret".getBytes());
        check("GET with ../ path -> ERROR 404 (path traversal blocked)",
                ask("GET ../" + outDir.getName() + "/secret.txt 0 6").startsWith("ERROR 404"));
        check("GET with \\ path -> ERROR 404", ask("GET ..\\odd.bin 0 10").startsWith("ERROR 404"));

        check("GET length 0 -> OK 0", ask("GET odd.bin 5 0").equals("OK 0"));
        check("GET last byte of the file works (edge of range)",
                getBytesEqual("odd.bin", odd, odd.length - 1, 1, "io"));

        check("GET returns exactly the requested bytes (io)",
                getBytesEqual("odd.bin", odd, 12345, 50000, "io"));
        check("GET returns exactly the requested bytes (nio)",
                getBytesEqual("odd.bin", odd, 12345, 50000, "nio"));
        check("GET whole file returns identical bytes (nio)",
                getBytesEqual("odd.bin", odd, 0, odd.length, "nio"));
        check("GET without mode word defaults to io", defaultModeTest(odd));

        check("Several commands on ONE connection (INFO, GET, INFO)", severalCommandsTest(odd));
        check("Error does not close the connection (next command still works)",
                errorKeepsConnectionTest());
    }

    // LIST must return one line for each of the 7 files and then END
    static boolean listTest() {
        try {
            Connection c = new Connection(HOST, PORT);
            c.sendLine("LIST");
            ArrayList<String> lines = new ArrayList<String>();
            String line = c.readLine();
            while (!line.equals("END")) {
                lines.add(line);
                line = c.readLine();
            }
            c.close();
            return lines.size() == NAMES.length && lines.contains("FILE odd.bin 1000003");
        } catch (Exception e) {
            return false;
        }
    }

    // "my file.txt" and "a..b.txt" are in the shared folder, but LIST must not show them,
    // because a client could never download them (the server would answer ERROR).
    static boolean listHidesBadNamesTest() {
        try {
            Connection c = new Connection(HOST, PORT);
            c.sendLine("LIST");
            String all = "";
            String line = c.readLine();
            while (!line.equals("END")) {
                all = all + line + "\n";
                line = c.readLine();
            }
            c.close();
            return all.contains("odd.bin") && !all.contains("my file") && !all.contains("a..b");
        } catch (Exception e) {
            return false;
        }
    }

    // GET without the mode word must work like "io"
    static boolean defaultModeTest(byte[] odd) {
        try {
            Connection c = new Connection(HOST, PORT);
            c.sendLine("GET odd.bin 100 200");
            String header = c.readLine();
            byte[] got = c.in.readNBytes(200);
            c.close();
            return header.equals("OK 200") && Arrays.equals(got, Arrays.copyOfRange(odd, 100, 300));
        } catch (Exception e) {
            return false;
        }
    }

    // One connection can carry several commands one after another
    static boolean severalCommandsTest(byte[] odd) {
        try {
            Connection c = new Connection(HOST, PORT);
            c.sendLine("INFO odd.bin");
            boolean first = c.readLine().equals("SIZE 1000003");

            c.sendLine("GET odd.bin 0 10 nio");
            boolean second = c.readLine().equals("OK 10")
                    && Arrays.equals(c.in.readNBytes(10), Arrays.copyOfRange(odd, 0, 10));

            c.sendLine("INFO odd.bin");
            boolean third = c.readLine().equals("SIZE 1000003");
            c.close();
            return first && second && third;
        } catch (Exception e) {
            return false;
        }
    }

    // After an ERROR answer the connection must stay open
    static boolean errorKeepsConnectionTest() {
        try {
            Connection c = new Connection(HOST, PORT);
            c.sendLine("GET nope.bin 0 1");
            boolean first = c.readLine().startsWith("ERROR 404");
            c.sendLine("INFO odd.bin");
            boolean second = c.readLine().equals("SIZE 1000003");
            c.close();
            return first && second;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 2. Download tests: every combination of file size, mode and workers
    // ------------------------------------------------------------------
    static void downloadTests() {
        int[] workerCounts = {1, 3, 10};
        String[] modes = {"io", "nio"};

        for (int i = 0; i < NAMES.length; i++) {
            for (String mode : modes) {
                for (int workers : workerCounts) {
                    boolean useNio = mode.equals("nio");
                    String label = String.format("%-10s (%8d bytes)  mode=%-3s workers=%-2d",
                            NAMES[i], SIZES[i], mode, workers);
                    check(label, downloadAndCheck(NAMES[i], SIZES[i], workers, useNio, "single"));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. Concurrency: 4 clients download at the same time
    // ------------------------------------------------------------------
    // Each client runs in its own thread.
    static class ClientThread extends Thread {
        int number;
        boolean ok = false;

        ClientThread(int number) {
            this.number = number;
        }

        public void run() {
            boolean useNio = (number % 2 == 1);          // clients 1 and 3 use nio
            ok = downloadAndCheck("big.bin", SIZES[6], 10, useNio, "par" + number);
        }
    }

    static void concurrencyTest() throws InterruptedException {
        ClientThread[] clients = new ClientThread[4];
        for (int i = 0; i < 4; i++) {
            clients[i] = new ClientThread(i);
            clients[i].start();
        }
        boolean allOk = true;
        for (int i = 0; i < 4; i++) {
            clients[i].join();
            if (!clients[i].ok) {
                allOk = false;
            }
        }
        check("4 clients x 10 workers at the same time (mixed io/nio)", allOk);
    }

    // ------------------------------------------------------------------
    // 4. Robustness
    // ------------------------------------------------------------------
    static void robustnessTests() throws Exception {
        // a) downloading a file that does not exist must fail with an error message
        boolean errorOk = false;
        try {
            Downloader.download(HOST, PORT, "nope.bin", new File(outDir, "nope.out"),
                    10, false, false);
        } catch (Exception e) {
            errorOk = e.getMessage().contains("404");
        }
        check("Downloading a missing file fails with an error (no crash, no hang)", errorOk);

        // b) a client that disconnects in the middle must not break the server
        try {
            Connection c = new Connection(HOST, PORT);
            c.sendLine("GET big.bin 0 " + SIZES[6] + " io");
            c.readLine();
            c.in.readNBytes(10);                         // read only a few bytes ...
            c.close();                                   // ... and hang up
        } catch (Exception e) {
            // ignore
        }
        Thread.sleep(300);
        check("Server keeps working after a client disconnects in the middle of a transfer",
                ask("INFO odd.bin").equals("SIZE 1000003"));

        // c) downloading over an old, bigger file must give the correct new file
        File target = new File(outDir, "again.out");
        Files.write(target.toPath(), new byte[3000000]);           // 3 MB of old junk
        boolean overwriteOk = false;
        try {
            DownloadResult r = Downloader.download(HOST, PORT, "odd.bin", target, 10, true, false);
            overwriteOk = r.verified && target.length() == 1000003;
        } catch (Exception e) {
            overwriteOk = false;
        }
        check("Downloading again over an existing output file works (file is reset)", overwriteOk);

        // d) a client that connects and then says nothing must be disconnected by the server
        check("Server closes a connection that stays idle (idle timeout)", idleTest());

        // e) the Turkish language rules must not break the commands
        check("Commands also work when the computer language is Turkish (Locale)", turkishTest());
    }

    // Connect and send nothing. The server must close the connection after the idle timeout.
    // (We make the timeout short, but only during this test.)
    static boolean idleTest() {
        int oldTimeout = ClientHandler.idleTimeoutMs;
        ClientHandler.idleTimeoutMs = 700;
        try {
            Connection c = new Connection(HOST, PORT);
            c.channel.socket().setSoTimeout(5000);       // so that this test can never hang
            int b = c.in.read();                         // -1 means: the server closed the connection
            c.close();
            return b == -1;
        } catch (Exception e) {
            return false;                                // (also when we waited 5 seconds for nothing)
        } finally {
            ClientHandler.idleTimeoutMs = oldTimeout;
        }
    }

    // In the Turkish rules the letter "i" becomes "İ" (with a dot) in upper case.
    // A lower-case command and an upper-case mode word must still work.
    static boolean turkishTest() {
        Locale oldLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            boolean a = ask("info odd.bin").equals("SIZE 1000003");
            boolean b = ask("GET odd.bin 0 4 IO").equals("OK 4");
            return a && b;
        } finally {
            Locale.setDefault(oldLocale);
        }
    }

    // ------------------------------------------------------------------
    // 5. Client checks: things that belong to the client program
    // ------------------------------------------------------------------
    static void clientTests() {
        check("Mode word: io / nio / NIO are accepted, a typo like nioo is an error",
                modeWordTest());
        check("A word where a number is expected gives a clear message (workers, port, runs)",
                numberWordTest());
        check("Benchmark: CSV file has a header and 4 rows, all verified, temp file removed",
                benchmarkTest());
        check("Benchmark: the CSV file is still correct when the computer language is German",
                germanCsvTest());
    }

    // A word where a number is expected (the user forgot the workers and typed "nio" there)
    // must give a clear message, not "For input string".
    static boolean numberWordTest() {
        boolean accepted = (Client.parseNumber("10", "workers") == 10);
        try {
            Client.parseNumber("nio", "workers");
            return false;
        } catch (IllegalArgumentException e) {
            return accepted && e.getMessage().startsWith("workers must be a number");
        }
    }

    static boolean modeWordTest() {
        boolean accepted = !Client.parseUseNio("io")
                && Client.parseUseNio("nio")
                && Client.parseUseNio("NIO");
        try {
            Client.parseUseNio("nioo");
            return false;                                // a typo must throw an exception
        } catch (IllegalArgumentException e) {
            return accepted;
        }
    }

    // Run the real benchmark (1 run per case, on the 5 MB file) and read its CSV file.
    // The CSV goes into the temporary folder, so a real benchmark_results.csv is not touched.
    static boolean benchmarkTest() {
        try {
            File csv = new File(outDir, "benchmark_test.csv");
            Benchmark.run(HOST, PORT, "mid.bin", 1, csv);

            List<String> lines = Files.readAllLines(csv.toPath());
            boolean ok = (lines.size() == 5)
                    && lines.get(0).equals("mode,workers,run,seconds,mb_per_sec,verified");
            for (int i = 1; i < lines.size(); i++) {
                if (!lines.get(i).endsWith(",true")) {
                    ok = false;                          // a run was not verified
                }
            }
            boolean tempGone = !new File("benchmark_download.tmp").exists();
            return ok && tempGone;
        } catch (Exception e) {
            out.println("      error: " + e);
            return false;
        }
    }

    // In the German rules the decimal mark is a COMMA (0,006 instead of 0.006). If the program
    // used the computer's language for numbers, a comma would appear INSIDE a number of the
    // CSV file and break its columns. Every data row must still have exactly 6 fields.
    static boolean germanCsvTest() {
        Locale oldLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            File csv = new File(outDir, "benchmark_german.csv");
            Benchmark.run(HOST, PORT, "odd.bin", 1, csv);

            List<String> lines = Files.readAllLines(csv.toPath());
            boolean ok = (lines.size() == 5);
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).split(",").length != 6) {
                    ok = false;                          // a decimal comma broke the columns
                }
            }
            return ok;
        } catch (Exception e) {
            out.println("      error: " + e);
            return false;
        } finally {
            Locale.setDefault(oldLocale);
        }
    }

    // ------------------------------------------------------------------
    // 6. Failure tests: a fake server that does the wrong thing on purpose
    // ------------------------------------------------------------------
    static void failureTests() throws Exception {
        // a) the server sends correct sizes and the correct HASH, but WRONG bytes
        FakeServer badServer = new FakeServer("badbytes");
        badServer.start();
        boolean damagedDetected = false;
        try {
            DownloadResult r = Downloader.download(HOST, badServer.port(), "x.bin",
                    new File(outDir, "damaged.out"), 4, false, false);
            damagedDetected = !r.verified;
        } catch (Exception e) {
            damagedDetected = false;
        }
        check("A damaged download is detected (verified = false)", damagedDetected);

        // b) the server closes the connection in the middle of a part
        FakeServer shortServer = new FakeServer("short");
        shortServer.start();
        File partial = new File(outDir, "partial.out");
        boolean failedAndCleaned = false;
        try {
            Downloader.download(HOST, shortServer.port(), "x.bin", partial, 4, false, false);
        } catch (Exception e) {
            failedAndCleaned = e.getMessage().contains("Server closed early") && !partial.exists();
        }
        check("A failed download is reported and the half-filled output file is deleted",
                failedAndCleaned);

        // c) the exit code of the real Client program (it runs in a separate Java process)
        check("Client exit code is 0 for a good download",
                runClient(PORT, "download", "odd.bin", new File(outDir, "exit_ok.out").getPath(),
                        "10", "nio") == 0);
        check("Client exit code is 1 for an error (file not found)",
                runClient(PORT, "info", "nope.bin") == 1);
        check("Client exit code is 2 for a damaged download",
                runClient(badServer.port(), "download", "x.bin",
                        new File(outDir, "exit_bad.out").getPath(), "4", "io") == 2);
    }

    // Run the real Client program in a separate Java process and return its exit code.
    static int runClient(int port, String... arguments) throws Exception {
        ArrayList<String> command = new ArrayList<String>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("Client");
        command.add(HOST);
        command.add(String.valueOf(port));
        for (String argument : arguments) {
            command.add(argument);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);      // we only need the exit code
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;                                   // the client did not finish: a failure
        }
        return process.exitValue();
    }

    // A fake file server that misbehaves on purpose. It pretends to have ONE file, "x.bin",
    // of 1000 bytes. The real content of that file is 1000 times the letter 'A'.
    //   mode "badbytes": every answer is correct (also HASH), but GET sends 'B' instead of 'A'
    //   mode "short"   : GET announces the right length, sends only half of it, then closes
    static class FakeServer extends Thread {
        ServerSocket serverSocket;
        String mode;
        String realHash;

        FakeServer(String mode) throws Exception {
            this.mode = mode;
            byte[] real = new byte[1000];
            Arrays.fill(real, (byte) 'A');
            File realFile = new File(outDir, "fake_real_" + mode + ".bin");
            Files.write(realFile.toPath(), real);
            realHash = FileUtil.sha256(realFile);        // the HASH of the REAL content
            serverSocket = new ServerSocket(0);          // port 0 = any free port
            setDaemon(true);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        public void run() {
            try {
                while (true) {
                    Socket socket = serverSocket.accept();
                    FakeConnection connection = new FakeConnection(socket, mode, realHash);
                    connection.setDaemon(true);
                    connection.start();
                }
            } catch (Exception e) {
                // the test program is ending
            }
        }
    }

    // One connection of the fake server (the same job as ClientHandler, but it cheats).
    static class FakeConnection extends Thread {
        Socket socket;
        String mode;
        String realHash;

        FakeConnection(Socket socket, String mode, String realHash) {
            this.socket = socket;
            this.mode = mode;
            this.realHash = realHash;
        }

        public void run() {
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                while (true) {
                    String line = Protocol.readLine(in);
                    if (line == null) {
                        break;
                    }
                    String[] parts = line.split("\\s+");
                    if (parts[0].equals("INFO")) {
                        Protocol.sendLine(out, "SIZE 1000");
                    } else if (parts[0].equals("HASH")) {
                        Protocol.sendLine(out, "SHA256 " + realHash);
                    } else if (parts[0].equals("GET")) {
                        int length = Integer.parseInt(parts[3]);
                        byte[] data = new byte[length];
                        Arrays.fill(data, (byte) 'A');
                        if (mode.equals("badbytes")) {
                            Arrays.fill(data, (byte) 'B');
                        }
                        Protocol.sendLine(out, "OK " + length);
                        if (mode.equals("short")) {
                            out.write(data, 0, length / 2);
                            out.flush();
                            break;                       // close the connection too early
                        }
                        out.write(data);
                        out.flush();
                    }
                }
            } catch (Exception e) {
                // the client went away
            } finally {
                try {
                    socket.close();
                } catch (Exception e) {
                    // nothing to do
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------

    // Print PASS or FAIL for one test and count it.
    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            out.println("  PASS  " + name);
        } else {
            failed++;
            out.println("  FAIL  " + name);
        }
    }

    static void title(String text) {
        out.println();
        out.println("--- " + text);
    }

    // Send ONE command on a new connection and return the first line of the answer.
    static String ask(String command) {
        try {
            Connection c = new Connection(HOST, PORT);
            c.sendLine(command);
            String reply = c.readLine();
            c.close();
            if (reply == null) {
                return "(connection closed)";
            }
            return reply;
        } catch (Exception e) {
            return "EXCEPTION " + e;
        }
    }

    // GET a range and compare the bytes we received with the same range of the original file.
    static boolean getBytesEqual(String name, byte[] original, int offset, int length,
                                 String mode) {
        try {
            Connection c = new Connection(HOST, PORT);
            c.sendLine("GET " + name + " " + offset + " " + length + " " + mode);
            String header = c.readLine();
            byte[] got = c.in.readNBytes(length);
            c.close();
            byte[] expected = Arrays.copyOfRange(original, offset, offset + length);
            return header.equals("OK " + length) && Arrays.equals(got, expected);
        } catch (Exception e) {
            return false;
        }
    }

    // Download with the real client code, then compare size and SHA-256 with the ORIGINAL file.
    static boolean downloadAndCheck(String name, long size, int workers, boolean useNio,
                                    String tag) {
        String modeName = "io";
        if (useNio) {
            modeName = "nio";
        }
        File target = new File(outDir, tag + "_" + name + "_" + workers + modeName);
        try {
            DownloadResult r = Downloader.download(HOST, PORT, name, target,
                    workers, useNio, false);
            boolean sameSize = (target.length() == size);
            String originalHash = FileUtil.sha256(new File(dataDir, name));
            boolean sameHash = FileUtil.sha256(target).equals(originalHash);
            target.delete();
            return r.verified && sameSize && sameHash;
        } catch (Exception e) {
            out.println("      error: " + e);
            return false;
        }
    }

    // Create a file of random bytes (the same size always gives the same content).
    static void makeFile(String name, long size) throws Exception {
        byte[] data = new byte[(int) size];
        new Random(size).nextBytes(data);
        Files.write(new File(dataDir, name).toPath(), data);
    }

    // The test server runs in its own thread, so the tests can run at the same time.
    static class ServerThread extends Thread {
        public void run() {
            try {
                Server.main(new String[]{String.valueOf(PORT), dataDir.getPath()});
            } catch (Exception e) {
                serverError = e.toString();
            }
        }
    }

    static void startServer() throws Exception {
        ServerThread server = new ServerThread();
        server.setDaemon(true);
        server.start();

        // wait until the server accepts connections (at most 5 seconds)
        for (int i = 0; i < 50; i++) {
            try {
                Connection c = new Connection(HOST, PORT);
                c.close();
                return;                                  // the server is ready
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        out.println("Could not start the server on port " + PORT
                + " (is the port already in use?) " + serverError);
        System.exit(2);
    }

    static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        folder.delete();
    }
}
