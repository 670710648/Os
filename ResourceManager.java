import java.util.concurrent.Semaphore;

/**
 * ควบคุมสิทธิ์การใช้ทรัพยากรร่วมของทั้งระบบ
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ข้อกำหนดจากโจทย์ที่เกี่ยวกับคลาสนี้:
 *   - หัวข้อ 5: ใช้ Semaphore ควบคุม PRINTER และ DATABASE
 *     จำนวน permit มาจาก command line (Config)
 *     ในส่วนบังคับให้สร้าง Semaphore แบบ fair = true
 *   - Worker ทุกตัวต้องใช้ ResourceManager object เดียวกัน
 *   - หัวข้อ 7: permit ต้องไม่สูญหายหรือค้าง แม้เกิด exception
 *     หรือถูก interrupt ระหว่างถือ resource
 *
 * คำถามที่จะถูกถามใน Demo:
 *   - ทำไมต้อง fair = true และถ้าเปลี่ยนเป็น false จะเกิดอะไรขึ้น
 *   - ถ้า Thread ถูก interrupt หลัง acquire สำเร็จแต่ก่อน release
 *     โค้ดของกลุ่มยังคืน permit ได้หรือไม่
 */
public class ResourceManager {

    // เก็บ Semaphore ของ PRINTER และ DATABASE แยกกัน คนละตัว คนละจำนวน permit
    // fair = true ตามข้อบังคับหัวข้อ 5 (ตอบคำถาม Demo ข้อแรก: ทำไมต้อง fair)
    //
    // เหตุผลที่ fair = true: ถ้า fair = false, JVM ไม่รับประกันลำดับการปลุก Thread
    // ที่กำลังรออยู่ (อาจเลือกตัวที่เพิ่งมาก่อนตัวที่รอมานาน) ทำให้บาง Job
    // อดได้ใช้ resource ไปเรื่อย ๆ (starvation) และผลการรันของแต่ละกลุ่มจะเทียบกัน
    // ไม่ได้ เพราะลำดับการได้ resource ไม่ได้ขึ้นกับลำดับการมาถึงอย่างเดียวอีกต่อไป
    private final Semaphore printerSemaphore;
    private final Semaphore databaseSemaphore;

    // เก็บจำนวน permit รวมไว้ด้วย เพื่อคำนวณ "กำลังใช้อยู่กี่สิทธิ์" ใน status()
    // (availablePermits() ให้แค่ "เหลือเท่าไร" ไม่ได้บอก "ใช้ไปเท่าไร" โดยตรง)
    private final int printerPermits;
    private final int databasePermits;

    public ResourceManager(int printerPermits, int databasePermits) {
        this.printerPermits = printerPermits;
        this.databasePermits = databasePermits;
        this.printerSemaphore = new Semaphore(printerPermits, true);
        this.databaseSemaphore = new Semaphore(databasePermits, true);
    }

    /** ขอสิทธิ์ใช้ทรัพยากร จะรอจนกว่าจะได้ */
    public void acquire(ResourceType type) throws InterruptedException {
        semaphoreFor(type).acquire();
    }

    /**
     * คืนสิทธิ์ใช้ทรัพยากร
     *
     * ผู้เรียกต้องเรียก release() นี้ใน finally เสมอ (หัวข้อ 7) เพื่อรับประกันว่า
     * permit จะไม่ค้าง แม้เกิด exception หรือถูก interrupt ระหว่างถือ resource อยู่
     * ตัว release() เองไม่โยน checked exception และไม่ throw จาก interrupt
     * จึงเรียกได้อย่างปลอดภัยแม้อยู่ใน finally ของ catch (InterruptedException e)
     */
    public void release(ResourceType type) {
        semaphoreFor(type).release();
    }

    /**
     * ข้อความสั้น ๆ บอกสถานะการใช้ทรัพยากร สำหรับส่งให้ ProjectLogger.monitor()
     * เช่น "printer=1/1 database=0/2"
     */
    public String status() {
        int printerInUse = printerPermits - printerSemaphore.availablePermits();
        int databaseInUse = databasePermits - databaseSemaphore.availablePermits();
        return String.format("printer=%d/%d database=%d/%d",
                printerInUse, printerPermits, databaseInUse, databasePermits);
    }

    // ---------- ภายใน ----------

    private Semaphore semaphoreFor(ResourceType type) {
        switch (type) {
            case PRINTER:
                return printerSemaphore;
            case DATABASE:
                return databaseSemaphore;
            default:
                // NONE ไม่ควรถูกส่งเข้ามาเลย ผู้เรียก (Worker) ต้องเช็ค
                // job.resource != ResourceType.NONE ก่อนเรียก acquire/release เสมอ
                throw new IllegalArgumentException(
                        "ResourceManager ไม่รองรับ resource type: " + type);
        }
    }
}