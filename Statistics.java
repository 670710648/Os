import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * รวบรวมและคำนวณค่าที่ใช้วัดผลของการรันหนึ่งครั้ง
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ข้อกำหนดจากโจทย์ที่เกี่ยวกับคลาสนี้ (หัวข้อ 8):
 *   - Waiting Time, Turnaround Time, Throughput, Resource Wait Time
 *   - ต้องถูกอัปเดตจากหลาย Worker พร้อมกันได้อย่างปลอดภัย
 *   - ผลต้องสอดคล้องกับสมการตรวจสอบ:
 *       Turnaround = Waiting + workMs + Resource Wait + resourceMs
 *     ใช้สมการนี้ตรวจงานทีละชิ้นได้ว่าค่าไหนคำนวณผิด
 *
 * ข้อควรระวัง: ค่าเฉลี่ยของ Resource Wait ให้คิดเฉพาะงานที่ใช้ resource
 * ส่วนงานที่ resource = NONE ให้ถือว่า Resource Wait เป็น 0
 *
 * ===== การออกแบบที่เลือกใช้ =====
 * ตัวเลขที่ Worker หลายตัวแก้พร้อมกันจริง ๆ มีแค่ "จำนวนงานที่เสร็จแล้ว"
 * (เก็บด้วย AtomicInteger ซึ่งรับประกัน atomic increment โดยไม่ต้องใช้
 * synchronized) ส่วนค่า Waiting/Turnaround/Resource Wait ของแต่ละ Job นั้น
 * เก็บอยู่ที่ตัว Job เอง ผ่าน synchronized getter/setter ที่ Job.java
 * ประกาศไว้ (ดูเหตุผลเรื่อง synchronized แทน volatile ที่ Job.java)
 * จึงไม่ต้องคัดลอกมาเก็บซ้ำที่นี่ — printSummary() คำนวณค่าเฉลี่ยจาก
 * allJobs ที่ผู้เรียก (Main) ส่งเข้ามาโดยตรงตอนสรุปผลครั้งเดียวตอนจบ
 * โดยเรียกผ่าน getter ของ Job ทุกครั้ง (เช่น job.getResourceWaitMs())
 * เพื่อให้ได้ mutual exclusion เดียวกับตอนที่ Worker เขียนค่าเข้าไป
 */
public class Statistics {

    // ตัวนับเดียวที่หลาย Worker เขียนพร้อมกันได้จริง ต้องเป็น atomic
    private final AtomicInteger completed = new AtomicInteger(0);

    /** บันทึกว่างานชิ้นหนึ่งเสร็จแล้ว เรียกโดย Worker หลายตัวพร้อมกันได้ */
    public void recordCompletion(Job job) {
        completed.incrementAndGet();
    }

    /** จำนวนงานที่เสร็จแล้ว ใช้โดย Monitor และใช้ตรวจว่างานครบหรือยัง */
    public int completedCount() {
        return completed.get();
    }

    /**
     * พิมพ์ตารางสรุปผลตอนจบโปรแกรม
     * อย่างน้อยต้องมี avg Waiting Time, avg Turnaround Time,
     * Throughput และ avg Resource Wait Time
     *
     * ตามหัวข้อ 14 ให้รายงานเวลาเป็นจำนวนเต็มหน่วย ms
     * และ Throughput อย่างน้อย 2 ตำแหน่งทศนิยม
     */
    public void printSummary(List<Job> allJobs, long makespanMs) {
        int jobCount = allJobs.size();

        long totalWaiting = 0;
        long totalTurnaround = 0;
        long totalResourceWait = 0;
        int resourceJobCount = 0;

        for (Job job : allJobs) {
            totalWaiting += job.waitingTimeMs();
            totalTurnaround += job.turnaroundTimeMs();
            if (job.resource != ResourceType.NONE) {
                totalResourceWait += job.getResourceWaitMs();
                resourceJobCount++;
            }
        }

        double avgWaitingMs = jobCount == 0 ? 0.0 : (double) totalWaiting / jobCount;
        double avgTurnaroundMs = jobCount == 0 ? 0.0 : (double) totalTurnaround / jobCount;
        double avgResourceWaitMs = resourceJobCount == 0 ? 0.0 : (double) totalResourceWait / resourceJobCount;

        // Throughput = จำนวน Job ที่เสร็จ / เวลารวมของ simulation (jobs/second)
        // makespanMs เป็นหน่วย ms จึงหารด้วย 1000.0 เพื่อแปลงเป็นวินาที
        double throughput = makespanMs <= 0 ? 0.0
                : completed.get() / (makespanMs / 1000.0);

        System.out.println();
        System.out.println("===== Summary =====");
        System.out.printf("Jobs completed     : %d/%d%n", completed.get(), jobCount);
        System.out.printf("Makespan           : %d ms%n", makespanMs);
        System.out.printf("Avg Waiting Time   : %d ms%n", Math.round(avgWaitingMs));
        System.out.printf("Avg Turnaround Time: %d ms%n", Math.round(avgTurnaroundMs));
        System.out.printf("Throughput         : %.2f jobs/sec%n", throughput);
        System.out.printf("Avg Resource Wait  : %d ms (over %d resource jobs)%n",
                Math.round(avgResourceWaitMs), resourceJobCount);
    }
}