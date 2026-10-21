package com.example.queue;

public class PurgeSweepTest {

    public int purgeExpiredBatch(String queue) throws Exception {
        PurgeSweep sweep = new PurgeSweep(100, 5000);
        int purged = sweep.purge(queue);
        if (purged < 0) {
            throw new AssertionError("negative purge count");
        }
        return purged;
    }
}
