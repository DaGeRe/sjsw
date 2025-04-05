package io.github.terahidro2003.cct;

import io.github.terahidro2003.cct.builder.StackTraceModelTreeBuilder;
import io.github.terahidro2003.cct.result.StackTraceTreeNode;
import io.github.terahidro2003.config.Config;
import io.github.terahidro2003.measurement.executor.asprof.AsyncProfilerHelper;
import io.github.terahidro2003.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemFilter;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.flightrecorder.CouldNotLoadRecordingException;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkFilters;
import org.openjdk.jmc.flightrecorder.stacktrace.FrameSeparator;
import org.openjdk.jmc.flightrecorder.stacktrace.tree.StacktraceTreeModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SamplerResultsProcessor {
   
   private static final Logger log = LoggerFactory.getLogger(SamplerResultsProcessor.class);

    public StacktraceTreeModel jfrToStacktraceGraph(List<File> jfrs, String... rootMethod) {
        try {
            IItemCollection items = JfrLoaderToolkit.loadEvents(jfrs);
            IItemCollection filteredItems = items.apply(JdkFilters.EXECUTION_SAMPLE);
            if(rootMethod.length > 0) {
                IItemFilter rootMethodFilter = ItemFilters.contains(JdkAttributes.STACK_TRACE_STRING, rootMethod[0]);
                filteredItems = items.apply(rootMethodFilter);
            }
            FrameSeparator frameSeparator = new FrameSeparator(FrameSeparator.FrameCategorization.METHOD, false);
            return new StacktraceTreeModel(filteredItems, frameSeparator);
        } catch (IOException | CouldNotLoadRecordingException e) {
            log.error("Failed to load JFR", e);
            throw new RuntimeException(e);
        }
    }

    public StackTraceTreeNode getTreeFromJfr(List<File> jfrs, String... rootMethod) {
        StacktraceTreeModel model = jfrToStacktraceGraph(jfrs, rootMethod);
        StackTraceTreeNode tree = StackTraceModelTreeBuilder.buildFromStacktraceTreeModel(model);
        return tree;
    }

    public List<File> listJfrMeasurementFiles(Path directory, List<String> containables) {
        return List.of(Objects.requireNonNull(directory.toFile().listFiles((dir, name) -> isMeasurementJfr(name, containables))));
    }

    public List<File> extractSamplesFromMultipleJFRs(Path directory, Config config, String commit) throws IOException {
        List<File> samples = new ArrayList<>();
        List<File> jfrs = listJfrMeasurementFiles(directory, List.of(commit));

        for (File file : jfrs) {
            log.info("Detected JFR measurement file: {}", file);
        }

        for (File file : jfrs) {
            if (file.getName().endsWith(".jfr")) {
                samples.add(extractSamplesFromJFR(file, "extracted_jfr_samples_" + file.getName() + ".json", config));
            }
        }
        return samples;
    }

    private boolean isMeasurementJfr(String filename, List<String> containables) {
        if (!filename.endsWith(".jfr")) {
            return false;
        }

        for (String containable : containables) {
            if (!filename.contains(containable)) {
                return false;
            }
        }

        return true;
    }


    public StackTraceTreeNode filterTestcaseSubtree(String testcase, StackTraceTreeNode bat) {
        return TreeUtils.search(testcase, bat);
    }

    public File extractSamplesFromJFR(File file, String jsonFileName, Config config) throws IOException {
        log.info("Extracting samples from jfr file {}", file.getName());

        // retrieve samples from JFR file
        List<String> command = new ArrayList<>();

        command.add("./jfr");
        command.add("print");
        command.add("--json");
        command.add("--categories");
        command.add("JVM");
        command.add("--events");
        command.add("Profiling");
        command.add(file.getAbsolutePath());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File("/Users/juozas/Library/Java/JavaVirtualMachines/openjdk-22.0.1/Contents/Home/bin"));
        Process process = processBuilder.start();
        InputStream processInputStream = process.getInputStream();
        String jfr_json = AsyncProfilerHelper.getInstance(config).rawProfilerOutput(jsonFileName).getAbsolutePath();
        FileUtils.inputStreamToFile(processInputStream, jfr_json);
        log.info("Extracted samples from jfr file {}", jfr_json);
        return new File(jfr_json);
    }

    public void processIterationMeasurementFiles(File resultPath, int vm, String commit) {
        if (!resultPath.exists()) {
            throw new IllegalArgumentException("Result path " + resultPath + " does not exist");
        }

        List<File> unprocessedJfrs = listJfrMeasurementFiles(resultPath.toPath(), List.of("unprocessed"));
        if(unprocessedJfrs.isEmpty()) {
           log.error("No unprocessed iterative measurements identified.");
           return;
        }

        for (int i = 0; i < unprocessedJfrs.size(); i++) {
            String newName = "checked_sjsw_partial_vm_" + vm + "_iteration_" + i + "_commit_" + commit + ".jfr";
            if(unprocessedJfrs.get(i) == null) {
                continue;
            }
            try {
                FileUtils.renameFile(unprocessedJfrs.get(i), newName);
            } catch (IOException e) {
                log.error("Something went wrong while renaming partial result JFR file");
            }
        }
    }
}
