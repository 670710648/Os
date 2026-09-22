import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * จุดเริ่มต้นของโปรแกรม
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ส่วนที่เขียนไว้ให้แล้วคือการรับค่า การโหลด workload และการแสดง error
 * ซึ่งไม่ใช่สิ่งที่โครงงานนี้วัด ส่วนที่เหลือเป็น TODO ทั้งหมด
 *
 * วิธีรัน:
 *   java Main jobs_standard.csv priority 3 1 2
 */
public class Main {

    public static void main(String[] args) {
        // ---------- 1. รับค่าจาก command line ----------
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("ผิดพลาด: " + e.getMessage());
            System.err.println();
            System.err.println(Config.USAGE);
            System.exit(1);
            return;
        }

        // ---------- 2. เริ่มจับเวลาและโหลด workload ----------
        ProjectLogger logger = new ProjectLogger();
        List<Job> jobs;
        try {
            jobs = WorkloadLoader.load(config.workloadPath);
        } catch (WorkloadFormatException e) {
            System.err.println("ไฟล์ workload ผิดรูปแบบ — " + e.getMessage());
            System.exit(1);
            return;
        } catch (java.io.IOException e) {
            System.err.println("เปิดไฟล์ \"" + config.workloadPath + "\" ไม่ได้");
            System.err.println("ตรวจว่าไฟล์มีอยู่จริงและ path ถูกต้อง (สั่ง java จากโฟลเดอร์ใด)");
            System.exit(1);
            return;
        }
        logger.systemStart(config);
        logger.systemEvent("โหลดงานได้ " + jobs.size() + " ชิ้น");

        // ---------- 3. สร้างส่วนประกอบของระบบ ----------
        // TODO: สร้าง ResourceManager จากจำนวน permit ใน config
        // TODO: สร้าง ReadyQueue ตามนโยบายใน config
        // TODO: สร้าง Statistics

        ResourceManager resources = new ResourceManager(config.printerPermits, config.databasePermits);
        ReadyQueue readyQueue = new ReadyQueue(config.policy);
        Statistics statistics = new Statistics();
 
        // ช่องทางระหว่าง JobGenerator -> Scheduler (ดูเหตุผลใน JobGenerator.java)
        BlockingQueue<Job> arrivalQueue = new LinkedBlockingQueue<>();
 
        // นับจำนวน Job จริงที่ต้องเสร็จทั้งหมด แยกจากเรื่อง poison pill โดยเจตนา
        // (ดูเหตุผลใน Worker.java: pill บอกแค่ "จะไม่มีงานใหม่" ไม่ได้บอกว่า
        // "งานที่มีอยู่ตอนนี้เสร็จหมดแล้ว")
        CountDownLatch allJobsDone = new CountDownLatch(jobs.size());

        // ---------- 4. สร้างและเริ่ม Thread ----------
        // TODO: สร้าง Worker จำนวน config.workers ตัว แล้ว start
        // TODO: สร้างและ start Scheduler
        // TODO: สร้างและ start Monitor
        // TODO: สร้างและ start JobGenerator
        //
        // ลำดับการ start มีผลหรือไม่ ให้คิดและอธิบายได้ใน Demo

        Worker[] workers = new Worker[config.workers];
        for (int i = 0; i < config.workers; i++) {
            workers[i] = new Worker("worker-" + (i + 1), readyQueue, resources,
                    statistics, logger, allJobsDone);
        }
 
        Scheduler scheduler = new Scheduler(arrivalQueue, readyQueue, logger, config.workers);
        Monitor monitor = new Monitor(readyQueue, resources, statistics, logger);
        JobGenerator generator = new JobGenerator(jobs, arrivalQueue, logger);
 
        // ลำดับการ start ไม่มีผลต่อความถูกต้อง: ทุกช่องทางเป็น BlockingQueue
        // ซึ่ง take()/put() รอกันเองได้โดยไม่สนว่าฝั่งตรงข้าม start ไปแล้วหรือยัง
        // (Worker ที่ start ก่อนแล้วยังไม่มีงานก็แค่ block อยู่ใน readyQueue.take()
        // ไม่กิน CPU เหมือนกับที่ ReadyQueue.java อธิบายไว้)
        for (Worker w : workers) {
            w.start();
        }
        scheduler.start();
        monitor.start();
        generator.start();

        // ---------- 5. รอจนงานเสร็จครบ ----------
        // TODO: รอจนกว่างานทั้ง jobs.size() ชิ้นจะเสร็จ
        //
        // *** นี่คือจุดที่ยากที่สุดของโครงงานนี้ ***
        // Worker ที่กำลังรออยู่ในคิวไม่มีทางรู้ได้เองว่าจะไม่มีงานเข้ามาอีกแล้ว
        // กลุ่มต้องออกแบบวิธีบอก โดยห้ามใช้การเดาเวลา เช่น sleep(10000)
        //
        // เทคนิคที่ไปหาอ่านต่อได้ (เลือกใช้อันใดอันหนึ่งหรือผสมกันก็ได้):
        //   - poison pill
        //   - CountDownLatch
        //   - ตัวนับงานค้างที่ป้องกันด้วย lock
        //
        // อาการผิดที่ต้องไม่เกิด:
        //   1. main จบแล้วแต่ JVM ไม่ปิด เพราะยังมี Thread ค้างอยู่
        //   2. Worker หยุดก่อนที่งานชิ้นสุดท้ายจะทำเสร็จ
        //   3. permit ค้างเพราะถูก interrupt ระหว่างถือ resource

        // รอจน Worker เรียก countDown() ครบ jobs.size() ครั้ง คือรู้แน่ชัดว่า
        // งานชิ้นสุดท้ายทำเสร็จจริงแล้ว (ไม่ใช่แค่ "ไม่มีงานใหม่เข้ามา")
        try {
            allJobsDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // ---------- 6. สั่งหยุดทุก Thread ----------
        // TODO: หยุด Worker ทุกตัว, Scheduler, Monitor และ JobGenerator
        // TODO: join ทุก Thread เพื่อยืนยันว่าหยุดจริงก่อนไปขั้นถัดไป

        // JobGenerator, Scheduler และ Worker ทุกตัวหยุดเองแล้วผ่านสาย poison
        // pill ที่ไล่มาตามลำดับ (JobGenerator -> arrivalQueue -> Scheduler ->
        // readyQueue -> Worker) ตอนนี้ถึง allJobsDone.await() ผ่านแล้ว
        // แปลว่างานจริงทุกชิ้นเสร็จ ดังนั้น Thread เหล่านี้ต้องกำลังจะจบหรือ
        // จบไปแล้วเช่นกัน เหลือแค่ Monitor ที่ไม่ได้อ่านจากคิวงาน
        // จึงต้องสั่ง interrupt เองเพื่อสะดุด Thread.sleep(1000) ใน run()
        monitor.interrupt();
 
        // join ทุก Thread เพื่อยืนยันว่าหยุดจริงก่อนพิมพ์สรุปผล
        // (กัน JVM ค้างเพราะมี Thread ไม่ใช่ daemon เหลืออยู่)
        try {
            generator.join();
            scheduler.join();
            for (Worker w : workers) {
                w.join();
            }
            monitor.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // ---------- 7. สรุปผล ----------
        // TODO: หา makespan = เวลาที่งานชิ้นสุดท้ายเสร็จ (ใช้ logger.now())
        // TODO: เรียก statistics.printSummary(jobs, makespanMs)
        // TODO: logger.systemStop(completed, jobs.size())

        // makespan วัดจากนาฬิกาเดียวกับ log เสมอ (ProjectLogger.now()) ตอนนี้
        // ทุก Thread หยุดแล้วและงานทุกชิ้นเสร็จแล้ว จึงเป็นเวลาสิ้นสุด simulation
        long makespanMs = logger.now();
        statistics.printSummary(jobs, makespanMs);
        logger.systemStop(statistics.completedCount(), jobs.size());
    }
}
