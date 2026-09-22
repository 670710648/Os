import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * ปล่อยงานเข้าสู่ระบบตามเวลา arrivalMs ของแต่ละ Job
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * หน้าที่ (หัวข้อ 3 ของโจทย์):
 *   - รอจนถึงเวลา arrivalMs ของแต่ละงาน แล้วส่งงานต่อไปยัง Scheduler
 *   - บันทึกเวลาที่งานเข้าสู่ระบบ "จริง" ลงใน Job
 *     (อาจไม่ตรงกับ arrivalMs เป๊ะ เพราะ Thread ถูกปลุกช้าได้)
 *   - เรียก logger.jobArrived(job) ทุกครั้งที่ปล่อยงาน
 *
 * ข้อควรคิด:
 *   - รายการงานที่ได้จาก WorkloadLoader เรียงตามลำดับในไฟล์ ไม่ได้เรียงตามเวลา
 *   - เมื่อปล่อยงานครบทุกชิ้นแล้ว ต้องมีวิธีบอกระบบว่า "จะไม่มีงานเข้ามาอีก"
 *     ดู TODO เรื่องการปิดระบบใน Main
 *
 * ===== การออกแบบที่เลือกใช้ =====
 * ช่องทางส่งงานไปยัง Scheduler คือ BlockingQueue<Job> ("arrivalQueue")
 * ที่ Main สร้างขึ้นแล้วส่งเข้ามาทั้ง JobGenerator (ฝั่งใส่) และ Scheduler
 * (ฝั่งหยิบ) — JobGenerator ไม่แตะ ReadyQueue โดยตรงเลย ตรงตามข้อบังคับ
 * หัวข้อ 2
 *
 * รายการงานที่ได้มาจาก WorkloadLoader เรียงตามลำดับในไฟล์ ไม่ใช่ตามเวลา
 * จึง sort สำเนาของ list ตาม arrivalMs ก่อนเริ่มปล่อย (ใช้ List.sort ซึ่งเป็น
 * stable sort ทำให้งานที่ arrivalMs เท่ากันยังคงเรียงตามลำดับเดิมในไฟล์ —
 * สอดคล้องกับ sequence ที่ WorkloadLoader กำหนดไว้ให้)
 *
 * สัญญาณ "จะไม่มีงานเข้ามาอีก": ใช้ตัวเดียวกับที่ ReadyQueue ใช้บอก Worker
 * (ReadyQueue.POISON_PILL) ใส่ลง arrivalQueue เพียงหนึ่งตัวหลังปล่อยงานจริง
 * ครบทุกชิ้น เพราะมี Scheduler เป็นผู้บริโภค arrivalQueue เพียง Thread เดียว
 * (ไม่ต้องส่งหลายตัวเหมือนตอน Scheduler ส่งเข้า ReadyQueue ที่มี Worker
 * หลายตัวรอรับ) เมื่อ Scheduler เจอ pill นี้ จึงไปสร้าง pill แยกให้ Worker
 * แต่ละตัวเข้า ReadyQueue เอง (ดู Scheduler.java)
 */
public class JobGenerator extends Thread {

    private final List<Job> jobsByArrival;
    private final BlockingQueue<Job> arrivalQueue;
    private final ProjectLogger logger;

    public JobGenerator(List<Job> jobs, BlockingQueue<Job> arrivalQueue, ProjectLogger logger) {
        super("generator");
        // ทำสำเนาแล้ว sort ตาม arrivalMs โดยไม่แก้ไข list เดิมที่ Main ถืออยู่
        // (list เดิมต้องคงลำดับตามไฟล์ไว้ให้ Statistics/summary ใช้ทีหลัง)
        this.jobsByArrival = new ArrayList<>(jobs);
        this.jobsByArrival.sort(Comparator.comparingLong(job -> job.arrivalMs));
        this.arrivalQueue = arrivalQueue;
        this.logger = logger;
    }

    @Override
    public void run() {
        try {
            for (Job job : jobsByArrival) {
                long waitMs = job.arrivalMs - logger.now();
                if (waitMs > 0) {
                    Thread.sleep(waitMs);
                }

                // บันทึกเวลาที่เข้าสู่ระบบ "จริง" ซึ่งอาจต่างจาก arrivalMs
                // เล็กน้อยเพราะ Thread ถูกปลุกช้าได้ (ห้ามใช้ arrivalMs ตรง ๆ)
                job.setActualArrivalMs(logger.now());
                logger.jobArrived(job);

                arrivalQueue.put(job);
            }

            // ปล่อยงานครบทุกชิ้นแล้ว ส่งสัญญาณให้ Scheduler รู้ว่าจะไม่มี
            // งานใหม่เข้ามาอีก
            arrivalQueue.put(ReadyQueue.POISON_PILL);
        } catch (InterruptedException e) {
            // ถูกสั่งหยุดกลางทาง (เช่นตอน error shutdown) ให้รักษาสถานะ
            // interrupt ไว้แล้วจบ run() โดยไม่ปล่อยงานที่เหลือต่อ
            Thread.currentThread().interrupt();
        }
    }
}