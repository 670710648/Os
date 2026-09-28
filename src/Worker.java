import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread ที่ดึงงานจาก Ready Queue ไปทำจนเสร็จ
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ลำดับการทำงานของ Job หนึ่งชิ้น บังคับตามหัวข้อ 6 ของโจทย์:
 *   1. รับงานจาก Ready Queue แล้วบันทึกเวลาเริ่ม
 *   2. จำลองงานหลักด้วย Thread.sleep(job.workMs)
 *   3. ถ้า job.resource != NONE ให้บันทึกเวลาเริ่มรอ แล้ว acquire
 *   4. จำลองการถือครองด้วย Thread.sleep(job.resourceMs)
 *   5. release แล้วบันทึกเวลาจบ
 *
 * ห้ามสลับขั้นที่ 2 กับ 3 เพราะจะทำให้ผลของทุกกลุ่มเทียบกันไม่ได้
 *
 * จุดที่มักพลาด:
 *   - ถ้า exception หรือ interrupt เกิดขึ้นหลัง acquire แต่ก่อน release
 *     permit จะค้างถาวรและระบบจะแขวน ต้องออกแบบให้คืนได้เสมอ
 *   - Worker ต้องหยุดเองได้เมื่อไม่มีงานเหลือแล้ว ไม่ใช่วนรอตลอดไป
 *
 * ===== การออกแบบที่เลือกใช้ =====
 * รับสัญญาณ "ไม่มีงานเหลือแล้ว" ผ่าน ReadyQueue.POISON_PILL: run() วนเรียก
 * readyQueue.take() (ไม่กิน CPU) ถ้าได้ pill กลับมาก็หยุดลูปทันทีแทนที่จะ
 * เรียก processJob() — Scheduler เป็นผู้ส่ง pill ให้ครบหนึ่งตัวต่อ Worker
 * หนึ่งตัวอยู่แล้ว (ดู Scheduler.java) จึงมั่นใจได้ว่า Worker ทุกตัวจะได้
 * รับสัญญาณให้หยุดแน่นอน ไม่มีตัวไหนค้างรอตลอดไป
 *
 * ต้องรู้ว่า "งานทั้งหมดเสร็จจริงหรือยัง" แยกจากเรื่อง pill: ใช้
 * CountDownLatch (allJobsDone) ที่ Main สร้างด้วยขนาด = jobs.size() ทุกครั้ง
 * ที่ Worker ทำ Job จริงเสร็จ (ไม่ใช่ pill) ให้เรียก countDown() หนึ่งครั้ง
 * Main รอที่ latch.await() เพื่อรู้ว่า "งานชิ้นสุดท้ายเสร็จแล้วจริง ๆ" ก่อน
 * จะสั่งหยุดระบบ — แยกจาก pill เพราะ pill บอกแค่ "จะไม่มีงานใหม่เข้ามา"
 * ไม่ได้บอกว่า "งานที่มีอยู่ตอนนี้ทำเสร็จหมดแล้ว" สองเรื่องนี้เกิดคนละเวลากัน
 * ถ้าใช้อย่างใดอย่างหนึ่งเพียงอย่างเดียวจะเกิดอาการ #2 ที่ Main.java ห้าม
 * เกิด (Worker หยุดก่อนงานชิ้นสุดท้ายเสร็จ)
 *
 * runningJobs (หัวข้อ 10 ที่ Monitor ต้องอ่าน) เก็บเป็น static AtomicInteger
 * ของคลาสนี้ เพราะ Worker ทุกตัวใช้ class เดียวกัน จึงแชร์ตัวนับเดียวกันได้
 * โดยไม่ต้องส่งผ่าน object กลางเพิ่ม เพิ่มค่าตอนเริ่ม Job จริง (ไม่นับ pill)
 * และลดค่าตอน Job เสร็จ — Monitor อ่านผ่าน Worker.runningCount() ซึ่งเป็น
 * atomic read จึงเป็น snapshot ที่ปลอดภัยแม้ Worker หลายตัวกำลังแก้พร้อมกัน
 */
public class Worker extends Thread {

    private static final AtomicInteger RUNNING = new AtomicInteger(0);

    /** จำนวน Job ที่ Worker ตัวใดตัวหนึ่งกำลังทำอยู่ตอนนี้ ใช้โดย Monitor */
    public static int runningCount() {
        return RUNNING.get();
    }

    private final ReadyQueue readyQueue;
    private final ResourceManager resources;
    private final Statistics statistics;
    private final ProjectLogger logger;
    private final CountDownLatch allJobsDone;

    public Worker(String name, ReadyQueue readyQueue, ResourceManager resources,
                  Statistics statistics, ProjectLogger logger, CountDownLatch allJobsDone) {
        super(name);
        this.readyQueue = readyQueue;
        this.resources = resources;
        this.statistics = statistics;
        this.logger = logger;
        this.allJobsDone = allJobsDone;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Job job = readyQueue.take();

                if (ReadyQueue.isPoisonPill(job)) {
                    // ไม่มีงานเหลือแล้ว หยุดตัวเอง ไม่ใช่วนรอตลอดไป
                    break;
                }

                processJob(job);
            }
        } catch (InterruptedException e) {
            // ถูกสั่งหยุดกลางทาง รักษาสถานะ interrupt ไว้แล้วจบ run()
            // (permit ของ resource ที่ถืออยู่ระหว่างทางถูกคืนแล้วเสมอ
            // เพราะ processJob() คืนผ่าน finally ไม่ใช่ผ่านจุดนี้)
            Thread.currentThread().interrupt();
        }
    }

    /** ทำงานหนึ่งชิ้นให้จบตามลำดับ 5 ขั้นด้านบน */
    private void processJob(Job job) throws InterruptedException {
        RUNNING.incrementAndGet();
        try {
            // ----- ขั้น 1: รับงานแล้วบันทึกเวลาเริ่ม -----
            job.setStartMs(logger.now());
            logger.jobStarted(job);

            // ----- ขั้น 2: จำลองงานหลัก (ห้ามสลับกับขั้น 3) -----
            Thread.sleep(job.workMs);
            logger.workFinished(job);

            // ----- ขั้น 3-4-5: ขอ/ถือ/คืน resource ถ้ามี -----
            if (job.resource != ResourceType.NONE) {
                job.setResourceWaitStartMs(logger.now());
                logger.resourceWaitStarted(job);

                resources.acquire(job.resource);
                // acquire สำเร็จ (ยังไม่ได้ throw) แปลว่าถือ permit อยู่แล้ว
                // ตั้งแต่บรรทัดนี้ ต้อง release ให้ได้เสมอไม่ว่าจะเกิดอะไรขึ้น
                // ต่อจากนี้ จึงห่อด้วย try/finally แยกออกมาโดยเฉพาะ
                long waitedMs = logger.now() - job.getResourceWaitStartMs();
                job.setResourceWaitMs(waitedMs);
                logger.resourceAcquired(job, waitedMs);

                try {
                    Thread.sleep(job.resourceMs);
                } finally {
                    // คืน permit เสมอ แม้ sleep ถูก interrupt หรือเกิด
                    // exception อื่นระหว่างถือครองอยู่ (จุดที่มักพลาดตามที่
                    // เอกสารเตือนไว้ด้านบน)
                    resources.release(job.resource);
                    logger.resourceReleased(job);
                }
            }

            // ----- บันทึกว่างานเสร็จสมบูรณ์ -----
            job.setCompletionMs(logger.now());
            logger.jobCompleted(job);
            statistics.recordCompletion(job);
        } finally {
            RUNNING.decrementAndGet();
            // countDown() ต้องอยู่นอก try ของงานนี้ (แต่ยังอยู่ใน finally
            // ของ processJob) เพื่อให้ Main รับรู้ว่างานชิ้นนี้ "จบการประมวลผล
            // แล้ว" ไม่ว่าจะจบแบบสำเร็จหรือถูก interrupt กลางทางก็ตาม
            // มิฉะนั้นถ้า Worker ถูก interrupt แล้ว exception หลุดออกจาก
            // processJob โดยไม่ countDown() จะทำให้ Main ค้างรอที่ await()
            // ตลอดไป (อาการ #1 ที่ Main.java ห้ามเกิด)
            allJobsDone.countDown();
        }
    }
}