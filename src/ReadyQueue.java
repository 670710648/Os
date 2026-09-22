import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * คิวงานที่พร้อมถูกหยิบไปทำ
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * สิ่งที่คลาสนี้ต้องทำได้:
 *   - เก็บงานที่รอ Worker อยู่
 *   - หยิบงานถัดไปตามนโยบายที่เลือก (FCFS หรือ Priority)
 *   - ถูกเรียกจากหลาย Thread พร้อมกันได้อย่างปลอดภัย
 *
 * ข้อกำหนดจากโจทย์ที่เกี่ยวกับคลาสนี้:
 *   - หัวข้อ 4: priority = 1 สูงสุด เมื่อเท่ากันต้องมีกติกาตัดสินลำดับ (tie-break)
 *     ที่ตัดสินจากข้อมูลของ Job ไม่ขึ้นกับว่า Thread ใดเข้าถึงคิวก่อน
 *   - หัวข้อ 7: ห้ามวนลูปเช็กแบบกิน CPU (busy waiting) — Worker ที่ไม่มีงานทำ
 *     ต้องถูกพักไว้ ไม่ใช่วนถามซ้ำ ๆ
 *
 * จะออกแบบเป็นคลาสเดียวที่รับนโยบายเข้ามา หรือแยกเป็นสองคลาส
 * หรือใช้โครงสร้างข้อมูลสำเร็จรูปของ Java ก็ได้ ขอให้อธิบายเหตุผลได้ใน Demo
 *
 * ===== การออกแบบที่เลือกใช้ =====
 * ใช้ BlockingQueue<Job> ของ java.util.concurrent เป็นแกนหลัก เพราะ take()
 * ของมัน "block" (พัก Thread) เองอยู่แล้วเมื่อคิวว่าง โดยไม่ต้องเขียน wait/notify
 * หรือวนลูปเช็กเอง จึงไม่มี busy waiting (ตอบข้อกำหนดหัวข้อ 7) และปลอดภัยเมื่อ
 * หลาย Thread เรียก add()/take() พร้อมกันโดยไม่ต้องใส่ synchronized เพิ่มเอง
 *
 * เลือกโครงสร้างข้อมูลตามนโยบาย:
 *   - FCFS     -> LinkedBlockingQueue คืนงานตามลำดับที่ add() เข้ามา (FIFO)
 *   - PRIORITY -> PriorityBlockingQueue พร้อม Comparator ที่เรียงตาม
 *                 priority ก่อน (เลขน้อยสำคัญกว่า) แล้วตัดสินเสมอด้วย
 *                 sequence (ลำดับที่ปรากฏในไฟล์ workload) เมื่อ priority เท่ากัน
 *                 sequence มาจากข้อมูลของ Job เอง ไม่ขึ้นกับว่า Thread ใด
 *                 เข้าถึงคิวก่อน จึงตอบข้อกำหนดเรื่อง tie-break ในหัวข้อ 4 ได้
 *
 * การหยุดระบบ (poison pill): เมื่อไม่มีงานเหลือแล้ว ผู้เรียก (Scheduler) ใส่
 * POISON_PILL ลงคิวหนึ่งตัวต่อ Worker หนึ่งตัว Worker ที่ได้ POISON_PILL จาก
 * take() ต้องหยุดทำงานแทนที่จะเรียก processJob() ให้เช็คด้วย isPoisonPill(job)
 * POISON_PILL ถูกกำหนด priority และ sequence เป็นค่ามากที่สุดเท่าที่เป็นไปได้
 * เพื่อรับประกันว่าใน PriorityBlockingQueue มันจะถูกหยิบทีหลังงานจริงเสมอ
 * แม้ Scheduler จะใส่มันเข้าคิวไปพร้อมกับงานที่ยังไม่ถูกหยิบไปทำก็ตาม
 */
public class ReadyQueue {

    /**
     * สัญญาณบอก Worker ว่า "ไม่มีงานให้ทำอีกแล้ว ให้หยุด"
     * ใช้ ResourceType.NONE และ priority/sequence เป็นค่ามากสุด
     * เพื่อไม่ให้แซงหน้างานจริงใน Priority Queue
     */
    public static final Job POISON_PILL =
            new Job("POISON_PILL", 0, Integer.MAX_VALUE, 0,
                    ResourceType.NONE, 0, Integer.MAX_VALUE);

    /** เช็คว่า Job ที่ได้จาก take() เป็นสัญญาณให้หยุดหรือไม่ */
    public static boolean isPoisonPill(Job job) {
        return job == POISON_PILL;
    }

    // เก็บนโยบายไว้เผื่อ debug/log แต่โครงสร้างข้อมูลจริงที่ใช้เก็บงาน
    // คือ queue ด้านล่าง ซึ่งเลือกชนิดตามนโยบายตอน constructor เพียงครั้งเดียว
    private final Config.Policy policy;
    private final BlockingQueue<Job> queue;

    public ReadyQueue(Config.Policy policy) {
        this.policy = policy;
        if (policy == Config.Policy.PRIORITY) {
            // priority น้อย = สำคัญมาก (1 คือสูงสุด) เมื่อเท่ากัน ให้ sequence
            // น้อยกว่า (มาก่อนในไฟล์ workload) ชนะ — กติกานี้คงที่เสมอ
            // ไม่ขึ้นกับว่า Thread ใดเข้าถึงคิวก่อน
            Comparator<Job> byPriorityThenSequence =
                    Comparator.<Job>comparingInt(job -> job.priority)
                            .thenComparingInt(job -> job.sequence);
            // ต้องระบุ initialCapacity ตอนสร้าง (constructor นี้ของ
            // PriorityBlockingQueue ต้องการ) ใช้ 11 ซึ่งเป็นค่าเริ่มต้นของ Java
            // เอง — คิวขยายอัตโนมัติได้ ไม่จำกัดจำนวนงานจริง
            this.queue = new PriorityBlockingQueue<>(11, byPriorityThenSequence);
        } else {
            this.queue = new LinkedBlockingQueue<>();
        }
    }

    /** ใส่งานเข้าคิว เรียกโดย Scheduler Thread */
    public void add(Job job) {
        // ทั้งสองชนิดของคิวนี้ไม่มีขอบเขตจำกัด (unbounded) การ add() จึง
        // ไม่มีวันบล็อก แต่ยังใช้ put() แทน offer() เพื่อความชัดเจนของเจตนา
        // และเผื่อมีการเปลี่ยนไปใช้คิวแบบมีขอบเขตในอนาคต
        try {
            queue.put(job);
        } catch (InterruptedException e) {
            // คิวชนิดนี้ไม่บล็อกจริงตอน put() จึงไม่ควรเกิดขึ้น
            // แต่ถ้าเกิดต้องรักษาสถานะ interrupt ไว้ ไม่ใช่กลืนทิ้งเงียบ ๆ
            Thread.currentThread().interrupt();
        }
    }

    /**
     * หยิบงานถัดไปตามนโยบาย เรียกโดย Worker Thread
     *
     * ถ้ายังไม่มีงาน ต้องรอโดยไม่กิน CPU
     * ต้องคิดด้วยว่าจะบอก Worker อย่างไรเมื่อไม่มีงานเหลือแล้วและควรหยุดทำงาน
     *
     * take() ของ BlockingQueue จะพัก Thread ที่เรียกไว้เอง (ไม่กิน CPU)
     * จนกว่าจะมีงานเข้ามา — Worker ที่ต้องหยุดจะได้รับ POISON_PILL แทน
     * การถูกปลุกด้วยงานจริง ให้ผู้เรียกเช็ค isPoisonPill(result) เอง
     */
    public Job take() throws InterruptedException {
        return queue.take();
    }

    /** จำนวนงานที่รออยู่ตอนนี้ ใช้โดย Monitor — ต้องอ่านได้อย่างปลอดภัย */
    public int size() {
        // BlockingQueue.size() เป็น thread-safe อยู่แล้ว (อ่านค่าปัจจุบันของ
        // ตัวนับภายในที่ป้องกันด้วย lock ของคิวเอง) ปลอดภัยสำหรับ Monitor
        // ที่จะเรียกพร้อมกับ Worker ที่กำลัง add()/take() อยู่ก็ได้
        return queue.size();
    }
}