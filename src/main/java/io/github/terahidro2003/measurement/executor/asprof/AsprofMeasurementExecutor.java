package io.github.terahidro2003.measurement.executor.asprof;

import static io.github.terahidro2003.utils.FileUtils.configureResultsFolder;
import static io.github.terahidro2003.utils.FileUtils.retrieveAsyncProfiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.terahidro2003.cct.result.StackTraceData;
import io.github.terahidro2003.config.Config;
import io.github.terahidro2003.measurement.executor.SjswMeasurementExecutor;
import io.github.terahidro2003.utils.CommandStarter;
import io.github.terahidro2003.utils.FileUtils;

public class AsprofMeasurementExecutor implements SjswMeasurementExecutor {
    private static final Logger log = LoggerFactory.getLogger(AsprofMeasurementExecutor.class);
   
    private volatile boolean benchmarkException = false;

    @Override
    public void execute(Config config, long pid, Duration duration) throws InterruptedException, IOException {
        configureResultsFolder(config);
        config = retrieveAsyncProfiler(config);
        String[] command = {config.profilerPath(), "-d", String.valueOf(duration.getSeconds()), String.valueOf(pid)};

        ProcessBuilder processBuilder = new ProcessBuilder(command);

        Process process = processBuilder.start();
        InputStream samplingOutputAsIs = process.getInputStream();
        File output = FileUtils.inputStreamToFile(samplingOutputAsIs, config.outputPath() + UUID.randomUUID() + ".json");
        writeResults(config, output);

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.error("Failed to execute async profiler. Async profiler returned exit code 0");
        }
    }

    @Override
    public void execute(Config config, Duration samplingDuration) throws InterruptedException, IOException {
        execute(config, samplingDuration, "00000");
    }

    @Override
    public void execute(Config config, Duration duration, String identifier) throws InterruptedException, IOException {
        configureResultsFolder(config);
        config = retrieveAsyncProfiler(config);
        log.info("Executing async-profiler sampler with the following configuration: classPath: {}, mainClass: {}, profilerPath: {}, outputPath: {}", config.executable(), config.mainClass(), config.profilerPath(), config.outputPath());
        duration = chooseDuration(duration);

        File output = AsyncProfilerHelper.getInstance(config).retrieveRawOutputFile(0, identifier);
        Thread javaBenchmarkThread = getBenchmarkThread(config, duration, output);
        execute(javaBenchmarkThread, duration);
        writeResults(config, output);
    }

    private void writeResults(Config config, File output) throws IOException, InterruptedException {
        if(config.JfrEnabled()) {
            // retrieve samples from JFR file
            List<String> command = new ArrayList<>();
            command.add("jfr");
            command.add("print");
            command.add("--json");
            command.add("--categories");
            command.add("JVM");
            command.add("--events");
            command.add("Profiling");
            command.add(output.getAbsolutePath());

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();
            InputStream processInputStream = process.getInputStream();
            String jfr_json = AsyncProfilerHelper.getInstance(config).rawProfilerOutput("parsed_jfr_samples" + System.currentTimeMillis() + ".json").getAbsolutePath();
            FileUtils.inputStreamToFile(processInputStream, jfr_json);

//            SamplerResultsProcessor samplerResultsProcessor = new SamplerResultsProcessor();
//            List<ExecutionSample> jfrSamples = samplerResultsProcessor.readJfrExecutionSamplesJsonFile(new File(jfr_json));
//            ExecutionSampleTreeBuilder stackTraceTreeBuilder = new ExecutionSampleTreeBuilder();
//            var tree = stackTraceTreeBuilder.buildFromExecutionSamples(jfrSamples);
//            tree.printTree();

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("Failed to execute async profiler. Async profiler returned exit code 0");
            }
        }
    }

    private static List<StackTraceData> parseProfile(InputStream asyncProfilerOutput) throws IOException {
        log.info("Parsing async-profiler output");
        List<StackTraceData> samples = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(asyncProfilerOutput))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("---") && !line.contains("Execution") && !line.contains("profile")) {
                    String[] firstSplittedLine = line.split("[\\s(),%]+");
                    long timeNs = Long.parseLong(firstSplittedLine[1]);
                    double percent = Double.parseDouble(firstSplittedLine[3]);
                    int sampleCount = Integer.parseInt(firstSplittedLine[4]);

                    List<String> methods = new ArrayList<>();

                    while ((line = br.readLine()) != null && line.trim().startsWith("[")) {
                        var sanitizedLine = line.trim().substring(5);
                        methods.add(sanitizedLine);
                    }

                    samples.add(new StackTraceData(methods, timeNs, percent, sampleCount));
                }
            }
        }
        return samples;
    }

    private void execute(Thread workload, Duration duration) {
        CountDownLatch latch = new CountDownLatch(1);

        final Duration finalDuration = duration;
        Thread waitingThread = new Thread(() -> {
            try {
                Thread.sleep(finalDuration.getSeconds() * 1000 + 1000);
                workload.interrupt();
                latch.countDown();
                log.debug("Benchmark thread interrupted");
            } catch (InterruptedException e) {
                log.warn("Benchmark process interrupted", e);
            }
        });

        workload.start();
        waitingThread.start();

        try {
            latch.await();
        } catch (InterruptedException e) {
            log.warn("Benchmark thread interrupted", e);
        }


        if(benchmarkException) {
            throw new RuntimeException("Benchmark exception");
        }
    }

    private Duration chooseDuration(Duration duration) {
        if(duration == null || duration.getSeconds() <= 0) {
            log.warn("No duration was specified. Setting default sampling duration of 10 seconds.");
            duration = Duration.ofSeconds(10);
        } else {
            log.info("Setting sampling duration of {} seconds.", duration);
        }
        return duration;
    }

    private Thread getBenchmarkThread(Config config, Duration duration, File output) {
        log.info("Sampling for {} seconds", duration.getSeconds());
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add(AsyncProfilerHelper.getInstance(config, output)
                .retrieveJavaAgent(duration, 0, "unspecified_commit").javaAgent());
        command.add("-Dfile.encoding=UTF-8");

        if(config.executable().contains(".jar")) {
            command.add("-jar");
        } else {
            command.add("-cp");
        }
        command.add(config.executable());

        if(config.mainClass().contains(" ")) {
            var mains = config.mainClass().split(" ");
            command.addAll(Arrays.asList(mains));
        } else {
            command.add(config.mainClass());
        }

        return new Thread(() -> {
            try {
                CommandStarter.start(command.toArray(new String[0]));
            } catch (Exception e) {
                this.benchmarkException = true;
                throw new RuntimeException("Benchmark process failed to start", e);
            }
        });
    }
}
