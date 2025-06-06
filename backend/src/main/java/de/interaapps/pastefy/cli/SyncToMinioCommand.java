package de.interaapps.pastefy.cli;

import de.interaapps.pastefy.model.database.Paste;
import de.interaapps.pastefy.model.elastic.ElasticPaste;
import de.interaapps.pastefy.model.minio.MinioPaste;
import org.javawebstack.orm.Repo;
import picocli.CommandLine;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@CommandLine.Command(
        name = "syncminio",
        mixinStandardHelpOptions = true,
        description = "Sync data to minio"
)
public class SyncToMinioCommand implements Callable<Integer> {
    @CommandLine.Option(names = {"-s", "--size"}, description = "Batch size")
    private int batchSize = 100;

    @CommandLine.Option(names = {"-i", "--iterations"}, description = "Iterations")
    private int iterations = 500;

    @CommandLine.Option(names = {"-t", "--threads"}, description = "Number of threads")
    private int threadCount = 32;



    @Override
    public Integer call() throws Exception {
        System.out.println("syncing");
        final int size = this.batchSize;
        final int iterations = this.iterations;
        final int threadCount = 16;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        AtomicInteger syncedCount = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            int finalI = i;
            executor.submit(() -> {
                System.out.println("iteration " + (finalI + 1) + "/" + iterations);
                Repo.get(Paste.class).query()
                        .where("storageType", Paste.StorageType.DATABASE)
                        .orWhereNull("storageType")
                        .limit(size)
                        .offset(finalI * size)
                        .all()
                        .forEach(p -> {
                            MinioPaste.store(p);
                        });
                for (int i1 = 0; i1 < size; i1++) {
                    syncedCount.incrementAndGet();
                }
                System.out.println("iteration " + (finalI + 1) + "/" + iterations + " done ("+syncedCount.get()+"/"+ (iterations * size) + ")");
            });
        }



        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
        System.out.println("done");

        return 0;
    }
}