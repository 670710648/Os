=====================================================================
 Multi-threaded File Download Client/Server
 เปรียบเทียบ Traditional I/O กับ NIO Native Transfer     (ฉบับภาษาไทย)
 (English version: README_ENG.txt)
=====================================================================


1. ไฟล์ในโฟลเดอร์นี้
---------------------------------------------------------------------
  ใช้ร่วมกันทั้งสองฝั่ง
    Protocol.java       ค่าคงที่ของ protocol + อ่าน/ส่งข้อความ 1 บรรทัด
    FileUtil.java       คำนวณ SHA-256 ของไฟล์ (ไว้ตรวจไฟล์ที่ดาวน์โหลด)

  ฝั่ง Server
    Server.java         main(): เปิดพอร์ต และรับ connection
    ClientHandler.java  1 ตัวต่อ 1 connection (มี thread ของตัวเอง):
                        อ่านคำสั่ง LIST / INFO / HASH / GET แล้วตอบ
    FileSender.java     ส่งข้อมูลช่วงที่ขอ: แบบ io และแบบ nio

  ฝั่ง Client
    Client.java         main(): อ่านอาร์กิวเมนต์จาก command line
    Connection.java     1 TCP connection (SocketChannel + streams)
    Downloader.java     งานดาวน์โหลดทั้งหมด: ขอขนาดไฟล์, แบ่งช่วง,
                        เริ่ม worker, ตรวจ size + SHA-256
    RangeWorker.java    worker 1 ตัว: connection ของตัวเอง ช่วงของตัวเอง
                        รับข้อมูล (แบบ io และแบบ nio)
    Benchmark.java      รันทุกกรณีทดสอบแล้วแสดงตาราง

  เครื่องมือ
    TestRunner.java     test case อัตโนมัติ (72 ข้อ): java TestRunner
    MakeTestFile.java   สร้างไฟล์สุ่มขนาดใหญ่สำหรับทดสอบ (ทุกระบบปฏิบัติการ)
    HOW_TO_RUN.txt      คู่มือทีละขั้น: compile, ทดสอบ, รันโปรแกรม

  shared/           โฟลเดอร์ที่เซิร์ฟเวอร์แชร์ (ใส่ไฟล์ทดสอบที่นี่)
  README_THAI.txt   ไฟล์นี้
  README_ENG.txt    คำอธิบายเดียวกันเป็นภาษาอังกฤษ

ลำดับการทำงานของการดาวน์โหลดในโค้ด:
  Client.main -> Downloader.download -> (10 ตัว) RangeWorker
     -> Connection -> เครือข่าย -> Server.accept -> ClientHandler.handleGet
     -> FileSender -> ส่งข้อมูลกลับ -> RangeWorker เขียนลงไฟล์

หมายเหตุ: ไฟล์นี้เป็น UTF-8 ถ้าเปิดแล้วเห็นตัวอักษรเพี้ยน
ให้เปิดด้วย VS Code หรือ Notepad (Windows 10 ขึ้นไป) แบบ UTF-8


2. สิ่งที่ทำ (สรุป)
---------------------------------------------------------------------
- เขียน TCP Server ที่รองรับหลาย connection พร้อมกัน
- เขียน Client ที่แบ่งไฟล์เป็น 10 ช่วง (byte range) แล้วดาวน์โหลดพร้อมกัน
  ด้วย 10 worker (thread) โดยแต่ละ worker มี connection, offset, length
  และ file handle ของตัวเอง
- ออกแบบ protocol แบบข้อความสั้น ๆ: LIST, INFO, GET และ HASH (เพิ่มเอง)
- เขียนวิธีส่งข้อมูล 2 แบบ เลือกได้ในแต่ละการดาวน์โหลด
    io  = Traditional I/O  (อ่านใส่ byte[] แล้วเขียนออก)
    nio = NIO native       (FileChannel.transferTo / transferFrom)
- เพิ่มคำสั่ง "benchmark" ในตัว: รันทุกกรณีให้อัตโนมัติ
  (io/nio x 1/10 workers x 3 รอบ) ตรวจไฟล์ แล้วแสดงเวลาและ MB/s
  เอาไปใส่รายงานได้เลย


3. สิ่งที่ต้องมี วิธี compile และวิธีรัน
---------------------------------------------------------------------
ต้องใช้ Java 11 ขึ้นไป (ทดสอบกับ Java 21)

Compile (ในโฟลเดอร์นี้):
    javac *.java

รัน test อัตโนมัติก่อน (ใช้เวลาไม่กี่วินาที เปิด server ของตัวเอง
ที่พอร์ต 5055):
    java TestRunner

สร้างไฟล์ทดสอบ (ประมาณ 500 MB ใช้ได้ทุกระบบปฏิบัติการ):
    java MakeTestFile shared/test.bin 500

Terminal 1 - เปิดเซิร์ฟเวอร์ (ค่าเริ่มต้น พอร์ต 5000 โฟลเดอร์ "shared"):
    java Server
    (หรือ: java Server 5000 shared)

Terminal 2 - ใช้งาน client:
    java Client localhost 5000 list
    java Client localhost 5000 info test.bin
    java Client localhost 5000 download test.bin out.bin            (10 workers, io)
    java Client localhost 5000 download test.bin out.bin 10 nio     (10 workers, nio)
    java Client localhost 5000 download test.bin out.bin 1 io       (1 worker,  io)
    java Client localhost 5000 benchmark test.bin 3                 (ทุกกรณี 3 รอบ)

รูปแบบ download:  <ชื่อไฟล์> <ไฟล์ปลายทาง> [จำนวน worker=10] [io|nio]
ค่า io|nio ใช้ทั้งสองฝั่ง: client บอกให้ server ใช้วิธีนี้ส่งข้อมูล
และ client เองก็ใช้วิธีเดียวกันรับข้อมูล


4. PROTOCOL
---------------------------------------------------------------------
ทุก request เป็นข้อความ 1 บรรทัด ลงท้ายด้วย "\n"
ชื่อไฟล์ห้ามมีช่องว่าง คำตอบเป็นข้อความ 1 บรรทัด
ยกเว้น GET ที่ตามด้วยข้อมูลดิบ (raw bytes)

  Request                                  Response
  ---------------------------------------  -----------------------------
  LIST                                     FILE <ชื่อ> <ขนาด>  (หลายบรรทัด)
                                           END
  INFO <filename>                          SIZE <bytes>
  HASH <filename>                          SHA256 <hex>
  GET <filename> <offset> <length> [mode]  OK <length>\n  ตามด้วย
                                           ข้อมูลดิบ <length> byte พอดี
  (เมื่อมีปัญหา)                            ERROR <code> <message>

  mode = io (ค่าเริ่มต้น) หรือ nio
  รหัส error:  400 คำสั่งผิด/ไม่รู้จักคำสั่ง
               404 ไม่พบไฟล์
               416 offset/length อยู่นอกขอบเขตของไฟล์

connection เดียวส่งได้หลายคำสั่ง แต่ worker ของ client จะเปิด connection
ส่ง GET หนึ่งครั้ง รับข้อมูล แล้วปิด

HASH เป็นคำสั่งที่เพิ่มเอง (ไม่ได้บังคับ) ใช้ให้ client เทียบ SHA-256
ของไฟล์ที่โหลดมากับไฟล์ต้นฉบับ ใช้ได้แม้ client กับ server อยู่คนละเครื่อง


5. การตัดสินใจในการออกแบบ และเหตุผล
---------------------------------------------------------------------
ก) Server รองรับหลาย connection = Thread Pool
   (Executors.newCachedThreadPool)
   Loop หลักทำหน้าที่ accept อย่างเดียว แล้วส่งแต่ละ connection ให้
   thread ใน pool การอ่านไฟล์และเขียน socket เป็น blocking operation
   ดังนั้นโมเดล "1 connection ต่อ 1 thread" จึงง่ายและถูกต้องที่สุด
   (ถ้าใช้ Java 21 เปลี่ยนเป็น Virtual Thread ได้แก้บรรทัดเดียว
   ดูคอมเมนต์ใน Server.java)

ข) Client เขียนลงไฟล์ปลายทาง "ไฟล์เดียว" ตามตำแหน่ง
   (ไม่ใช้ part files และไม่ต้อง merge)
   - Client เรียก setLength(size) ก่อน ไฟล์จึงมีขนาดสุดท้ายตั้งแต่แรก
   - worker แต่ละตัวเปิด RandomAccessFile / FileChannel "ของตัวเอง"
     และเขียนเฉพาะในช่วง [offset, offset+length) ของตัวเอง
     ช่วงไม่ซ้อนกัน และไม่มีใครใช้ file position ร่วมกัน
     จึงไม่เกิด race condition
   - ง่ายกว่าการ merge และไม่ต้องใช้พื้นที่ดิสก์เพิ่ม
     หรืออ่านข้อมูลซ้ำอีกรอบ

ค) การคำนวณช่วงข้อมูล
   chunk = size / workers, worker ลำดับที่ i เริ่มที่ i * chunk
   worker ตัวสุดท้ายรับ "size - offset" คือส่วนที่เหลือทั้งหมด
   ช่วงทั้งหมดจึงครอบคลุมทั้งไฟล์เสมอ
   (ทดสอบกับไฟล์ 21 byte และ 10 workers แล้ว)

ง) อ่านบรรทัด header ทีละ byte
   BufferedReader อาจอ่านล่วงหน้าและกินข้อมูลไบนารีที่ตามหลัง header
   ไปด้วย ทำให้ข้อมูลเสีย การอ่านทีละ byte หลีกเลี่ยงบั๊กนี้ได้
   header สั้นมาก จึงไม่เสียเวลาเท่าไร

จ) ต้องวน loop เสมอเพราะการส่งอาจไม่ครบในครั้งเดียว
   read(), write(), transferTo() และ transferFrom() มีสิทธิ์ส่งน้อยกว่า
   ที่ขอ ทุกการส่งจึงอยู่ใน "while (remaining > 0)"

ฉ) ใช้ protocol เดียวกันทั้งสองวิธี I/O
   ต่างกันแค่ขั้นตอน "คัดลอก byte" ส่วนอื่น (socket, thread, protocol,
   รูปแบบไฟล์) เหมือนกันทุกอย่าง จึงเปรียบเทียบได้อย่างยุติธรรม

ช) การป้องกันฝั่ง Server
   - ปฏิเสธชื่อไฟล์ที่มี "/", "\" หรือ ".." (client อ่านไฟล์นอก
     โฟลเดอร์ที่แชร์ไม่ได้)
   - ตรวจ offset/length (ค่าติดลบ, เกินท้ายไฟล์, integer overflow)
     ตอบ ERROR 416


6. วิธี I/O ทั้งสองแบบ
---------------------------------------------------------------------
Traditional I/O (mode io)
  Server: RandomAccessFile.seek + read(byte[]) -> OutputStream.write
  Client: InputStream.read(byte[]) -> RandomAccessFile.seek + write
  ข้อมูลถูกคัดลอกระหว่างหน่วยความจำของ kernel กับ byte[] ของ Java
  (user space) ไปมา

NIO native transfer (mode nio)
  Server: FileChannel.transferTo(position, count, socketChannel)
  Client: FileChannel.transferFrom(socketChannel, position, count)
  transferTo ให้ระบบปฏิบัติการส่งข้อมูลจากไฟล์ไปที่ socket โดยตรง
  (sendfile บน Linux, TransmitFile บน Windows) ข้อมูลไม่ต้องผ่าน array
  ของ Java เรียกว่า "zero copy"

  ข้อควรรู้เกี่ยวกับฝั่ง CLIENT: ใน JDK การ transferFrom() จาก SOCKET
  ไม่ใช่ zero-copy จริง ภายในมันอ่านจาก socket ใส่ buffer ชั่วคราวเล็ก ๆ
  (ประมาณ 8 KB) แล้วเขียนลงไฟล์ ดังนั้นข้อดีของ NIO จะเห็นชัดที่ฝั่ง
  SERVER (transferTo) เป็นหลัก เป็นประเด็นที่ควรเขียนในรายงาน


7. วิธีทำการทดลอง (ตามที่โจทย์กำหนด)
---------------------------------------------------------------------
1. ใช้ไฟล์ใหญ่ไฟล์เดียว (เช่น 500 MB - 1 GB) และสภาพเครื่องเดิมทุกครั้ง
   ปิดโปรแกรมหนัก ๆ ระหว่างวัดผล
2. เปิด server ครั้งเดียว แล้วรัน:
       java Client localhost 5000 benchmark test.bin 3
   โปรแกรมจะรอบอุ่นเครื่อง (warm-up) 1 รอบ (ไม่นับ) จากนั้นรัน
   io/1, io/10, nio/1, nio/10 อย่างละ 3 รอบ ทุกรอบตรวจ size + SHA-256
   (คอลัมน์ "verified" ต้องเป็น OK)
3. คัดลอกตารางและค่าเฉลี่ยไปใส่รายงาน ถ้าต้องการรันมากกว่า 3 รอบ
   เปลี่ยนตัวเลขท้ายสุด (เช่น 5)
   เวลาที่วัด = ตั้งแต่เริ่ม worker จนทุก worker เสร็จ
   (ไม่รวมเวลาตรวจ hash)

ทำไม 10 workers อาจไม่เร็วขึ้น 10 เท่า:
  - บน localhost "เครือข่าย" คือการคัดลอกหน่วยความจำภายใน OS (loopback)
    ไม่มีสายเครือข่ายจริงที่ต้องใช้ให้เต็ม
  - Client กับ Server ใช้ CPU, ดิสก์ และ memory bandwidth ร่วมกัน
    10 thread จึงแย่งทรัพยากรกันเอง
  - การสร้าง thread, การเปิด 10 connection (TCP handshake) และ
    context switch เป็นต้นทุนเพิ่ม ถ้าไฟล์ไม่ใหญ่/ส่งเร็ว
    ต้นทุนนี้อาจมากกว่าที่ได้
  - การโหลดแบบขนานได้ผลดีเมื่อคอขวดคือ latency หรือความเร็วต่อ
    connection บนเครือข่ายจริง แต่ที่นี่คอขวดคือ CPU / memory / ดิสก์
    ซึ่งใช้ร่วมกัน

ทำไม NIO อาจไม่ชนะทุกครั้ง:
  - Zero-copy ช่วยลดการใช้ CPU และการคัดลอกหน่วยความจำ แต่ถ้าคอขวด
    อยู่ที่อื่น (ดิสก์, loopback, ฝั่ง client) เวลารวมแทบไม่ต่าง
  - ฝั่ง client การ transferFrom จาก socket ไม่ใช่ zero-copy (ดูข้อ 6)
    และ buffer ภายในที่เล็กอาจช้ากว่า loop แบบ byte[] 64 KB ของเรา
  - ผลแต่ละรอบไม่เท่ากัน (OS scheduling, cache) จึงรันซ้ำ 3 รอบ
    และเทียบค่าเฉลี่ย ไม่ใช้รอบเดียว

ข้อจำกัดเมื่อทดสอบบน localhost (ควรเขียนในรายงาน):
  - Page cache: หลังอ่านไฟล์ครั้งแรก ไฟล์จะอยู่ใน RAM รอบถัด ๆ ไป
    แทบไม่แตะดิสก์ รอบ warm-up ทำให้ทุกกรณีเท่ากัน แต่ก็แปลว่าเรากำลัง
    วัดความเร็วหน่วยความจำ ไม่ใช่ความเร็วดิสก์
  - Loopback network: ไม่มีการ์ดเครือข่ายจริง ไม่มี packet loss
    latency ต่ำมาก ผลจึงสูงกว่าเครือข่ายจริง
  - Storage cache: write cache ของ SSD/OS อาจซ่อนต้นทุนการเขียนจริง
  - ถ้าต้องการผลที่สมจริงกว่า ให้รัน server คนละเครื่อง (ใช้ IP แทน
    "localhost") และ/หรือใช้ไฟล์ที่ใหญ่กว่า RAM


8. สิ่งที่เพิ่มเข้ามา (นอกเหนือจากขั้นต่ำ)
---------------------------------------------------------------------
  - คำสั่ง HASH และการตรวจ SHA-256 อัตโนมัติหลังดาวน์โหลดทุกครั้ง
  - คำว่า [io|nio] ท้ายคำสั่ง GET เพื่อให้ server ตัวเดียวทดสอบได้
    ทั้งสองโหมดโดยไม่ต้อง restart
  - คำสั่ง benchmark ในตัว พร้อมรอบ warm-up และค่าเฉลี่ย
  - แสดงความคืบหน้าแต่ละ worker (offset, length, เวลา) ตอน download
  - ป้องกัน path traversal และตรวจช่วงข้อมูลที่ฝั่ง server
  ตั้งใจเขียนโค้ดให้เรียบง่าย (ไฟล์เล็ก ๆ แต่ละไฟล์ทำหน้าที่เดียว
  ไม่ใช้ library ภายนอก)


9. สิ่งที่ทดสอบแล้ว
---------------------------------------------------------------------
  - LIST / INFO / HASH / GET และ error ทุกแบบ (400, 404, 416)
  - ไฟล์ 64 MB ดาวน์โหลดด้วย 1 และ 10 workers ทั้งโหมด io และ nio:
    SHA-256 ตรงกับไฟล์ต้นฉบับทุกครั้ง
  - ไฟล์ 21 byte กับ 10 workers (ไฟล์เล็กกว่าจำนวนช่วง)
  - Client 4 ตัว (= 40 connection) ดาวน์โหลดพร้อมกัน
  รันซ้ำเองได้ทั้งหมดด้วย java TestRunner (72 ข้อ)
  ดูคู่มือทีละขั้นใน HOW_TO_RUN.txt


10. ข้อจำกัดที่ทราบ
---------------------------------------------------------------------
  - ไม่รองรับชื่อไฟล์ที่มีช่องว่าง (protocol แยกคำด้วยช่องว่าง)
  - ไม่มีการยืนยันตัวตนหรือเข้ารหัส (โจทย์ไม่ได้บังคับ)
  - ไม่มี retry หรือ resume: ถ้า worker ใดล้มเหลว โปรแกรมจะรายงาน
    error และหยุด
  - แชร์เฉพาะไฟล์ในโฟลเดอร์ชั้นบนสุดของ shared
