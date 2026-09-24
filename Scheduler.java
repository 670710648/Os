import java.util.concurrent.BlockingQueue;

/**
 * รับงานจาก JobGenerator แล้วจัดเข้า Ready Queue
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ข้อกำหนดจากโจทย์ (หัวข้อ 2 และ 4):
 *   - Scheduler เป็น Thread บังคับ ห้ามให้ JobGenerator ใส่งานลง Ready Queue โดยตรง
 *   - รับผิดชอบการจัดลำดับตามนโยบาย FCFS หรือ Priority
 *
 * ข้อควรคิด:
 *   - Scheduler รับงานจาก JobGenerator ผ่านอะไร และรอโดยไม่กิน CPU อย่างไร
 *   - เมื่อ JobGenerator ปล่อยงานครบแล้ว Scheduler รู้ได้อย่างไรว่าควรหยุด
 *
 * ===== การออกแบบที่เลือกใช้ =====
 * ช่องทางรับงานจาก JobGenerator คือ BlockingQueue<Job> ("arrivalQueue") ตัวเดียว
 * กับที่ JobGenerator.java ใช้ (Main สร้างครั้งเดียวแล้วส่งเข้าทั้งสองฝั่ง)
 * arrivalQueue.take() block เองอยู่แล้วเมื่อยังไม่มีงาน จึงไม่มี busy waiting
 *
 * นโยบาย FCFS/Priority ไม่ได้ตัดสินใจที่นี่ — ReadyQueue เลือกโครงสร้างข้อมูล
 * (LinkedBlockingQueue หรือ PriorityBlockingQueue) ให้เองตาม policy ที่ส่งเข้า
 * constructor ตอนสร้างแล้ว (ดู ReadyQueue.java) Scheduler แค่เรียก readyQueue.add(job)
 * โดยไม่ต้องรู้ว่าอยู่เบื้องหลังเป็นนโยบายใด
 *
 * การหยุด: เหมือนที่ JobGenerator.java อธิบายไว้ — arrivalQueue จะได้รับ
 * ReadyQueue.POISON_PILL เพียงหนึ่งตัวหลัง JobGenerator ปล่อยงานจริงครบทุกชิ้น
 * เมื่อ Scheduler เจอ pill นี้ใน arrivalQueue แปลว่า "จะไม่มีงานใหม่เข้ามาอีก"
 * จึงต้องสร้าง POISON_PILL แยกให้ครบหนึ่งตัวต่อ Worker หนึ่งตัว (workerCount)
 * ใส่ลง readyQueue เอง เพราะ Worker แต่ละตัวต้องได้รับสัญญาณให้หยุดคนละตัว
 * (ผู้บริโภค readyQueue มีหลาย Thread ต่างจาก arrivalQueue ที่มี Scheduler
 * เป็นผู้บริโภคเพียงตัวเดียว) จากนั้น Scheduler จบ run() ของตัวเอง
 */
public class Scheduler extends Thread {

    private final BlockingQueue<Job> arrivalQueue;
    private final ReadyQueue readyQueue;
    private final ProjectLogger logger;
    private final int workerCount;

    /**
     * @param arrivalQueue ช่องทางรับงานจาก JobGenerator (ตัวเดียวกับที่ JobGenerator ใช้)
     * @param readyQueue   ปลายทางที่จะจัดงานเข้า ตามนโยบาย FCFS/Priority
     * @param logger       สำหรับบันทึก log (เผื่อใช้ในอนาคต แม้ ProjectLogger
     *                     ปัจจุบันไม่มี event เฉพาะของ Scheduler ก็เก็บไว้ตามที่
     *                     constructor เดิมของอาจารย์กำหนด)
     * @param workerCount  จำนวน Worker ทั้งหมดในระบบ ใช้ตอนส่ง POISON_PILL
     *                     ให้ครบคนละหนึ่งตัวตอนปิดระบบ
     */
    public Scheduler(BlockingQueue<Job> arrivalQueue, ReadyQueue readyQueue,
                      ProjectLogger logger, int workerCount) {
        super("scheduler");
        this.arrivalQueue = arrivalQueue;
        this.readyQueue = readyQueue;
        this.logger = logger;
        this.workerCount = workerCount;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Job job = arrivalQueue.take();

                if (ReadyQueue.isPoisonPill(job)) {
                    // JobGenerator ปล่อยงานครบทุกชิ้นแล้ว ไม่มีงานใหม่เข้ามาอีก
                    // ส่งสัญญาณให้ Worker ทุกตัวหยุด คนละหนึ่ง pill
                    for (int i = 0; i < workerCount; i++) {
                        readyQueue.add(ReadyQueue.POISON_PILL);
                    }
                    break;
                }

                readyQueue.add(job);
            }
        } catch (InterruptedException e) {
            // ถูกสั่งหยุดกลางทาง (เช่นตอน error shutdown) รักษาสถานะ
            // interrupt ไว้แล้วจบ run() โดยไม่จัดงานที่เหลือต่อ
            Thread.currentThread().interrupt();
        }
    }
}