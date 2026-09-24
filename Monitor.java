/**
 * Thread ที่รายงานสถานะระบบเป็นระยะ
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ข้อกำหนดจากโจทย์ (หัวข้อ 10):
 *   - รายงานประมาณทุก 1,000 ms ไม่ต้องแม่นตรงทุกครั้ง
 *   - อย่างน้อยต้องมี ready, running, completed และสถานะการใช้ resource
 *   - ข้อมูลที่อ่านต้องเป็น snapshot ที่ปลอดภัย
 *     โดยเฉพาะตัวนับ running ซึ่ง Worker หลายตัวเพิ่ม/ลดพร้อมกัน
 *   - ห้ามอ่าน collection หรือตัวนับที่กำลังถูกแก้ไขโดยไม่มีการป้องกัน
 *
 * ให้พิมพ์ผ่าน logger.monitor(ready, running, completed, resources.status())
 * เพื่อให้รูปแบบตรงกับกลุ่มอื่น
 *
 * ข้อควรคิด: ตัวนับ running ควรอยู่ที่ไหน ใครเป็นคนเพิ่มและลด
 * และจะอ่านพร้อมกับ ready กับ completed ให้เป็นภาพเดียวกันได้อย่างไร
 *
 * ===== การออกแบบที่เลือกใช้ =====
 * ตัวนับ running ตัดสินใจไว้ที่ Worker.java แล้ว: เป็น static AtomicInteger
 * ของคลาส Worker เอง (เพราะ Worker ทุกตัวใช้ class เดียวกัน จึงแชร์ตัวนับ
 * ได้โดยไม่ต้องส่ง object กลางเพิ่ม) อ่านผ่าน Worker.runningCount() ซึ่งเป็น
 * static method — ดังนั้น constructor ของ Monitor "ไม่ต้องเพิ่ม parameter"
 * สำหรับตัวนับ running เลย ตรงข้ามกับที่หมายเหตุเดิมแนะนำไว้ (ที่แนะนำไว้
 * เพราะตอนนั้นยังไม่รู้ว่าจะเก็บที่ไหน) — ยังคง constructor เดิมของอาจารย์
 * ไว้ครบทุก parameter
 *
 * ความปลอดภัยของ snapshot: readyQueue.size(), Worker.runningCount(),
 * statistics.completedCount() ต่างก็เป็นการอ่าน AtomicInteger/โครงสร้าง
 * thread-safe คนละตัว การอ่านทั้งสามค่าจึงไม่ได้ถูกป้องกันให้เป็น "ภาพเดียวกัน"
 * แบบ atomic ข้ามตัวแปร (เช่น ready อาจลดลงหนึ่งพร้อมกับ running เพิ่มขึ้น
 * หนึ่งระหว่างที่ Monitor อ่านสองค่านี้คนละจังหวะกันเป๊ะ ๆ) แต่โจทย์หัวข้อ 10
 * ต้องการแค่ "snapshot ที่ปลอดภัย" หมายถึงห้ามอ่านค่าที่กำลังถูกแก้แบบไม่มี
 * การป้องกันเลย (เช่น อ่าน int ธรรมดาที่ไม่ atomic) ไม่ได้บังคับว่าต้อง
 * sync ทั้งสามค่าให้ตรงกันเป๊ะทุกมิลลิวินาที ซึ่งเป็นเรื่องปกติของระบบรายงาน
 * สถานะแบบ non-blocking (การจะทำให้ตรงกันเป๊ะต้อง lock ทั้งระบบพร้อมกัน
 * ซึ่งจะกระทบ Worker ตัวอื่นโดยไม่จำเป็น)
 *
 * การหยุด: ไม่ใช้ poison pill (Monitor ไม่ได้อ่านจากคิวงาน) แต่ให้ Main
 * เรียก monitor.interrupt() ตอน shutdown ซึ่งจะไปสะดุดที่ Thread.sleep(1000)
 * ใน run() แล้วโยน InterruptedException ออกมาให้ลูปจบทันที
 */
public class Monitor extends Thread {

    private static final long REPORT_INTERVAL_MS = 1000L;

    private final ReadyQueue readyQueue;
    private final ResourceManager resources;
    private final Statistics statistics;
    private final ProjectLogger logger;

    public Monitor(ReadyQueue readyQueue, ResourceManager resources,
                   Statistics statistics, ProjectLogger logger) {
        super("monitor");
        this.readyQueue = readyQueue;
        this.resources = resources;
        this.statistics = statistics;
        this.logger = logger;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(REPORT_INTERVAL_MS);

                int ready = readyQueue.size();
                int running = Worker.runningCount();
                int completed = statistics.completedCount();
                String resourceStatus = resources.status();

                logger.monitor(ready, running, completed, resourceStatus);
            }
        } catch (InterruptedException e) {
            // ถูกสั่งหยุดจาก Main ตอน shutdown (ดูคำอธิบายด้านบน)
            // รักษาสถานะ interrupt ไว้แล้วจบ run() โดยไม่ต้องรายงานซ้ำ
            Thread.currentThread().interrupt();
        }
    }
}