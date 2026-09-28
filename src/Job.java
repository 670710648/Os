/**
 * ข้อมูลของงานหนึ่งชิ้น
 *
 * ฟิลด์ทั้งหมดในไฟล์นี้มาจากไฟล์ workload CSV โดยตรง และถูกกำหนดครั้งเดียว
 * ตอนโหลด จึงประกาศเป็น final และปลอดภัยเมื่อหลาย Thread อ่านพร้อมกัน
 *
 * ไฟล์นี้เป็นโค้ดตั้งต้นที่อาจารย์แจก แต่ต่างจากไฟล์อื่นตรงที่
 * นักศึกษา "ต้องแก้" โดยเพิ่มฟิลด์ของตัวเองในส่วน TODO ด้านล่าง
 */
public class Job {

    /** รหัสงาน เช่น J01 — ไม่ซ้ำกันภายในหนึ่งไฟล์ workload */
    public final String id;

    /** เวลาที่งานควรเข้าสู่ระบบ นับจากวินาทีที่โปรแกรมเริ่ม (มิลลิวินาที) */
    public final long arrivalMs;

    /** ระดับความสำคัญ โดย 1 คือสูงสุด ตัวเลขยิ่งมากยิ่งสำคัญน้อย */
    public final int priority;

    /** ระยะเวลาของงานหลัก ก่อนขอใช้ทรัพยากรร่วม (มิลลิวินาที) */
    public final long workMs;

    /** ทรัพยากรร่วมที่ต้องใช้ หรือ NONE ถ้าไม่ต้องใช้ */
    public final ResourceType resource;

    /** ระยะเวลาที่ถือครองทรัพยากร (มิลลิวินาที) เป็น 0 เสมอเมื่อ resource เป็น NONE */
    public final long resourceMs;

    /**
     * ลำดับที่งานนี้ปรากฏในไฟล์ workload เริ่มจาก 0
     * เตรียมไว้ให้เผื่อกลุ่มต้องการใช้ประกอบการตัดสินลำดับเมื่อ priority เท่ากัน
     * จะใช้หรือไม่ใช้ก็ได้ กติกาตัดสินลำดับเป็นสิ่งที่กลุ่มต้องออกแบบเอง
     */
    public final int sequence;

    public Job(String id, long arrivalMs, int priority, long workMs,
               ResourceType resource, long resourceMs, int sequence) {
        this.id = id;
        this.arrivalMs = arrivalMs;
        this.priority = priority;
        this.workMs = workMs;
        this.resource = resource;
        this.resourceMs = resourceMs;
        this.sequence = sequence;
    }

    // =====================================================================
    // TODO (นักศึกษา): เพิ่มฟิลด์สำหรับเก็บค่าที่ใช้วัดผลของงานชิ้นนี้เอง
    //
    // ค่าที่โครงงานต้องการ (ดูหัวข้อ 8 ของเอกสารโจทย์):
    //   - เวลาที่เข้าสู่ระบบจริง
    //   - เวลาที่เริ่มถูกทำโดย Worker
    //   - เวลาที่ทำเสร็จ
    //   - เวลาที่เริ่มรอ resource และเวลารอ resource รวม
    //
    // สามคำถามที่ต้องตอบให้ได้ก่อนเขียน และจะถูกถามใน Demo:
    //   1. ใช้เวลาจากนาฬิกาตัวไหน (ดู ProjectLogger.now() ซึ่งให้เวลาฐานเดียว
    //      กับที่ปรากฏใน log ทำให้ค่าที่วัดกับ log ตรวจสอบกันได้)
    //   2. ฟิลด์ใดถูกเขียนโดย Thread หนึ่งแล้วอ่านโดยอีก Thread หนึ่ง
    //      และต้องป้องกันอย่างไร
    //   3. ผลที่ได้ต้องสอดคล้องกับสมการตรวจสอบในหัวข้อ 8:
    //      Turnaround = Waiting + workMs + Resource Wait + resourceMs
    // =====================================================================

    // ---------------------------------------------------------------------
    // ฟิลด์ที่เพิ่มเข้ามา: เก็บ timestamp สำหรับคำนวณค่าที่ใช้วัดผล (หัวข้อ 8)
    //
    // ทั้งหมดใช้เวลาจาก ProjectLogger.now() (ตอบคำถามที่ 1 ด้านบน)
    //
    // ===== การออกแบบที่เลือกใช้: synchronized getter/setter แทน volatile =====
    //
    // ฟิลด์ด้านล่างเป็น private ธรรมดา (ไม่ volatile) เข้าถึงได้ผ่าน
    // getter/setter ที่เป็น synchronized เท่านั้น เหตุผล (ตอบคำถามที่ 2 ด้านบน):
    //
    //   1. volatile รับประกันแค่ visibility (เห็นค่าล่าสุด) ของ "ฟิลด์เดียว"
    //      ต่อการอ่าน/เขียนหนึ่งครั้ง แต่ที่นี่มีหลายฟิลด์ที่สัมพันธ์กัน
    //      (เช่น resourceWaitStartMs กับ resourceWaitMs ที่ Worker คำนวณคู่กัน)
    //      synchronized บน object เดียวกันรับประกัน mutual exclusion จริง ๆ
    //      ไม่ใช่แค่ visibility จึงตัดปัญหาการอ่านเห็น "ค่าผสม" ระหว่างที่ Worker
    //      กำลังเขียนหลายฟิลด์ไม่พร้อมกันได้ด้วย
    //   2. การ lock/unlock ของ synchronized (เข้า/ออก monitor ของ object)
    //      สร้าง happens-before edge เองอยู่แล้ว เหมือนกับที่ volatile ให้
    //      จึงไม่ต้องพึ่ง CountDownLatch หรือกลไกอื่นเพื่อความปลอดภัยของฟิลด์พวกนี้
    //      ผลคือปลอดภัยไม่ว่า Thread ใดจะอ่าน (Statistics, Monitor, Main)
    //      และไม่ว่าจะอ่านตอนไหน แม้ Worker ยังไม่เขียนเสร็จ ก็แค่ต้องรอ lock
    //      ไม่ใช่เห็นค่าเก่าแบบเงียบ ๆ เหมือนกรณี plain field ที่ไม่มีการ
    //      ประสานงานใด ๆ เลย
    //   3. ข้อเสียที่ต้องรู้: มี lock overhead เล็กน้อยทุกครั้งที่อ่าน/เขียน
    //      แต่ในระบบนี้ฟิลด์พวกนี้ถูกอ่าน/เขียนไม่บ่อย (ไม่กี่ครั้งต่อ Job
    //      หนึ่งชิ้น) เทียบกับเวลาที่ใช้ไปกับ Thread.sleep(workMs/resourceMs)
    //      จึงไม่กระทบ performance อย่างมีนัยสำคัญ
    //
    // สมการตรวจสอบในหัวข้อ 8 (Turnaround = Waiting + workMs + Resource Wait
    // + resourceMs) ยังคงถืออยู่เหมือนเดิม (ตอบคำถามที่ 3 ด้านบน) เพราะ
    // synchronized ไม่เปลี่ยนค่าที่คำนวณ เปลี่ยนแค่วิธีป้องกันการเข้าถึงพร้อมกัน

    private long actualArrivalMs;
    private long startMs;
    private long completionMs;
    private long resourceWaitStartMs;
    private long resourceWaitMs;

    /** เวลาที่งานเข้าสู่ระบบจริง เขียนโดย JobGenerator */
    public synchronized void setActualArrivalMs(long actualArrivalMs) {
        this.actualArrivalMs = actualArrivalMs;
    }

    public synchronized long getActualArrivalMs() {
        return actualArrivalMs;
    }

    /** เวลาที่ Worker เริ่มหยิบงานนี้ไปทำ เขียนโดย Worker */
    public synchronized void setStartMs(long startMs) {
        this.startMs = startMs;
    }

    public synchronized long getStartMs() {
        return startMs;
    }

    /** เวลาที่งานทำเสร็จสมบูรณ์ (หลัง release resource แล้ว) เขียนโดย Worker */
    public synchronized void setCompletionMs(long completionMs) {
        this.completionMs = completionMs;
    }

    public synchronized long getCompletionMs() {
        return completionMs;
    }

    /** เวลาที่เริ่มรอสิทธิ์ใช้ resource เป็น 0 ถ้า resource เป็น NONE เขียนโดย Worker */
    public synchronized void setResourceWaitStartMs(long resourceWaitStartMs) {
        this.resourceWaitStartMs = resourceWaitStartMs;
    }

    public synchronized long getResourceWaitStartMs() {
        return resourceWaitStartMs;
    }

    /**
     * เวลารอ resource รวม (จากเริ่มรอจนกว่า acquire สำเร็จ)
     * เป็น 0 เสมอเมื่อ resource เป็น NONE เขียนโดย Worker
     */
    public synchronized void setResourceWaitMs(long resourceWaitMs) {
        this.resourceWaitMs = resourceWaitMs;
    }

    public synchronized long getResourceWaitMs() {
        return resourceWaitMs;
    }

    /** Waiting Time = startMs - actualArrivalMs (หัวข้อ 8) */
    public synchronized long waitingTimeMs() {
        return startMs - actualArrivalMs;
    }

    /** Turnaround Time = completionMs - actualArrivalMs (หัวข้อ 8) */
    public synchronized long turnaroundTimeMs() {
        return completionMs - actualArrivalMs;
    }

    @Override
    public String toString() {
        return String.format("%s(priority=%d, work=%dms, %s)",
                id, priority, workMs,
                resource == ResourceType.NONE ? "no resource"
                        : resource + " " + resourceMs + "ms");
    }
}