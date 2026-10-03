=====================================================================
 Multi-threaded File Download Client/Server
 Traditional I/O vs NIO native transfer          (English version)
 (Thai version: README_THAI.txt)
=====================================================================


1. WHAT IS IN THIS FOLDER
---------------------------------------------------------------------
  Shared by both sides
    Protocol.java       protocol constants + read/send one text line
    FileUtil.java       SHA-256 of a file (to verify downloads)

  Server side
    Server.java         main(): opens the port, accepts connections
    ClientHandler.java  one per connection (own thread): reads commands
                        LIST / INFO / HASH / GET and answers them
    FileSender.java     sends a byte range: io version and nio version

  Client side
    Client.java         main(): reads the command-line arguments
    Connection.java     one TCP connection (SocketChannel + streams)
    Downloader.java     whole download job: get size, split into ranges,
                        start the workers, verify size + SHA-256
    RangeWorker.java    ONE worker thread: own connection, own range,
                        receives the bytes (io version and nio version)
    Benchmark.java      runs all test cases and prints the table

  Tools
    TestRunner.java     automatic test cases (72 checks): java TestRunner
    MakeTestFile.java   creates a big random test file (any OS)
    HOW_TO_RUN.txt      step-by-step guide to compile, test and run

  shared/           the folder the server shares (put test files here)
  README_ENG.txt    this file
  README_THAI.txt   the same explanation in Thai

How a download flows through the code:
  Client.main -> Downloader.download -> (10 x) RangeWorker
     -> Connection -> network -> Server.accept -> ClientHandler.handleGet
     -> FileSender -> bytes back -> RangeWorker writes them into the file


2. WHAT I DID (SUMMARY)
---------------------------------------------------------------------
- Built a TCP server that handles many connections at the same time.
- Built a client that splits a file into 10 byte ranges and downloads
  them with 10 worker threads in parallel. Every worker has its own
  connection, its own offset/length and its own file handle.
- Designed a small text protocol (LIST, INFO, GET, plus HASH).
- Implemented TWO ways to move the bytes, selectable per download:
    io  = Traditional I/O  (read into a byte[] then write)
    nio = NIO native path  (FileChannel.transferTo / transferFrom)
- Added a built-in "benchmark" command that runs every case
  (io/nio x 1/10 workers x 3 runs), checks the file, and prints the
  time and MB/s so you can copy them into your report.


3. REQUIREMENTS, COMPILE AND RUN
---------------------------------------------------------------------
Requires Java 11 or newer (tested on Java 21).

Compile (in this folder):
    javac *.java

Run the automatic tests first (takes a few seconds, starts its own
server on port 5055):
    java TestRunner

Make a test file (about 500 MB, works on every OS):
    java MakeTestFile shared/test.bin 500

Terminal 1 - start the server (default port 5000, folder "shared"):
    java Server
    (or: java Server 5000 shared)

Terminal 2 - use the client:
    java Client localhost 5000 list
    java Client localhost 5000 info test.bin
    java Client localhost 5000 download test.bin out.bin            (10 workers, io)
    java Client localhost 5000 download test.bin out.bin 10 nio     (10 workers, nio)
    java Client localhost 5000 download test.bin out.bin 1 io       (1 worker,  io)
    java Client localhost 5000 benchmark test.bin 3                 (all cases, 3 runs)

download arguments:  <filename> <outputFile> [workers=10] [io|nio]
The "io|nio" choice is used by BOTH sides: the client tells the server
which method to use for sending, and uses the same method for receiving.


4. THE PROTOCOL
---------------------------------------------------------------------
Every request is one text line ending with "\n". File names must not
contain spaces. Replies are text lines, except GET which is followed
by raw bytes.

  Request                                  Reply
  ---------------------------------------  -----------------------------
  LIST                                     FILE <name> <size>  (repeated)
                                           END
  INFO <filename>                          SIZE <bytes>
  HASH <filename>                          SHA256 <hex>
  GET <filename> <offset> <length> [mode]  OK <length>\n  + exactly
                                           <length> raw bytes
  (any problem)                            ERROR <code> <message>

  mode = io (default) or nio.
  Error codes:  400 bad request / unknown command,
                404 file not found,
                416 offset/length outside the file.

One connection may carry several commands. A download worker simply
opens a connection, sends one GET, reads the bytes and closes.

HASH is an extra command I added (not required). It lets the client
compare the SHA-256 of the downloaded file with the original, which
works even when the client and server are on different machines.


5. DESIGN DECISIONS AND WHY
---------------------------------------------------------------------
a) Server concurrency = thread pool (Executors.newCachedThreadPool)
   The accept loop only accepts. Each connection is given to a pool
   thread. Reading a file and writing a socket are blocking operations,
   so "one thread per connection" is the simplest correct model.
   (Java 21 alternative, one line in Server.java: virtual threads.)

b) Client writes into ONE final file at the right position
   (no part files, no merge step).
   - The client first calls setLength(size) so the file already has its
     final size.
   - Each worker opens its OWN RandomAccessFile / FileChannel and writes
     only inside its own range [offset, offset+length). Ranges never
     overlap, and nobody shares a file position, so there is no race.
   - This is simpler than merging, and does not need extra disk space
     or a second pass over the data.

c) Range calculation
   chunk = size / workers. Worker i starts at i * chunk. The LAST
   worker takes "size - offset", so it receives the remainder and the
   ranges always cover the whole file (tested also with a 21-byte file
   and 10 workers).

d) Header lines are read byte-by-byte
   A BufferedReader may read ahead and swallow part of the binary data
   that follows the header. Reading one byte at a time avoids that bug.
   The header is short, so the cost is negligible.

e) Always loop on partial transfers
   read(), write(), transferTo() and transferFrom() are allowed to move
   fewer bytes than requested. Every transfer is therefore in a
   "while (remaining > 0)" loop.

f) Same protocol for both I/O methods
   Only the "copy bytes" step differs between io and nio. Everything
   else (sockets, threads, protocol, file layout) is identical, so the
   comparison is fair.

g) Safety checks on the server
   - File names containing "/", "\" or ".." are rejected (a client
     cannot read files outside the shared folder).
   - Offset/length are validated (negative values, beyond end of file,
     and integer overflow are all answered with ERROR 416).


6. THE TWO I/O METHODS
---------------------------------------------------------------------
Traditional I/O (mode io)
  Server: RandomAccessFile.seek + read(byte[]) -> OutputStream.write
  Client: InputStream.read(byte[]) -> RandomAccessFile.seek + write
  Data is copied between kernel memory and a Java byte[] (user space)
  and back again.

NIO native transfer (mode nio)
  Server: FileChannel.transferTo(position, count, socketChannel)
  Client: FileChannel.transferFrom(socketChannel, position, count)
  transferTo lets the operating system send file data to the socket
  directly (sendfile on Linux, TransmitFile on Windows), so the data
  does not go through a Java array: this is "zero copy".

  IMPORTANT NOTE ABOUT THE CLIENT: in the JDK, transferFrom() from a
  SOCKET is not a true zero-copy operation. Internally it reads the
  socket into a small temporary buffer (about 8 KB) and writes that to
  the file. So the NIO benefit exists mainly on the SERVER side
  (transferTo). This is a good point to mention in your report.


7. HOW TO DO THE EXPERIMENT (what the assignment asks for)
---------------------------------------------------------------------
1. Use one big file (e.g. 500 MB - 1 GB) and the same machine settings.
   Close heavy programs while measuring.
2. Start the server once, then run:
       java Client localhost 5000 benchmark test.bin 3
   It does one warm-up run (not counted) and then runs
   io/1, io/10, nio/1, nio/10 three times each. Every run is verified
   with size + SHA-256 (column "verified" must say OK).
3. Copy the table and the averages into your report. If you want more
   runs, change the last number (e.g. 5).
   Measured time = from starting the workers to all workers finished
   (hash checking is NOT included).

Why 10 workers may NOT be 10x faster:
  - On localhost the "network" is just memory copying inside the OS
    (loopback). There is no real network link to fill up.
  - Client and server share the same CPU cores, the same disk and the
    same memory bandwidth, so 10 threads compete with each other.
  - Thread creation, 10 connections (TCP handshakes) and context
    switching add overhead; for small/fast transfers this overhead can
    be bigger than the gain.
  - Parallel downloads help most when the limit is per-connection
    latency or per-connection speed on a real network. Here the
    limit is the CPU / memory / disk, and that is shared.

Why NIO may NOT win every time:
  - Zero-copy saves CPU and memory copies, but if the bottleneck is
    somewhere else (disk, loopback, the client side) the total time
    barely changes.
  - On the client, transferFrom from a socket is not zero-copy (see
    section 6), and its small internal buffer can even be slower than
    our 64 KB byte[] loop.
  - Results vary between runs (OS scheduling, caches), so we repeat
    each case 3 times and compare averages, not single runs.

Limitations when testing on localhost (write these in the report):
  - Page cache: after the first read the file lives in RAM, so later
    runs hardly touch the disk. The warm-up run makes this equal for
    every case, but it means we measure memory speed, not disk speed.
  - Loopback network: no physical network card, no packet loss, almost
    no latency, so results are higher than on a real network.
  - Storage cache: SSD/OS write caching can hide real write cost.
  - For more realistic numbers, run the server on another computer
    (use its IP instead of "localhost") and/or use a file larger than
    your RAM.


8. EXTRA THINGS I ADDED (beyond the minimum)
---------------------------------------------------------------------
  - HASH command + automatic SHA-256 verification after each download.
  - Optional [io|nio] word in GET so one running server can be tested
    in both modes without restarting.
  - Built-in benchmark command with warm-up run and averages.
  - Per-worker progress lines (offset, length, time) in "download".
  - Path-traversal and range validation on the server.
  I kept the code simple on purpose (small files, each with one
  job, no external libraries).


9. WHAT WAS TESTED
---------------------------------------------------------------------
  - LIST / INFO / HASH / GET and all error replies (400, 404, 416).
  - 64 MB file downloaded with 1 and 10 workers in both io and nio
    mode: SHA-256 identical to the original every time.
  - A 21-byte file with 10 workers (file smaller than 10 parts).
  - 4 clients (= 40 connections) downloading at the same time.
  You can repeat all of this yourself: java TestRunner (72 checks).
  See HOW_TO_RUN.txt for the full step-by-step guide.


10. KNOWN LIMITATIONS
---------------------------------------------------------------------
  - File names with spaces are not supported (the protocol splits on
    spaces).
  - No authentication or encryption (not required by the assignment).
  - There is no retry or resume: if one worker fails, the download
    reports an error and stops.
  - Files are served only from the top level of the shared folder.
