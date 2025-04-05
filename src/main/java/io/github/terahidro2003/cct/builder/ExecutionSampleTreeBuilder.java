package io.github.terahidro2003.cct.builder;

import io.github.terahidro2003.cct.SamplerResultsProcessor;
import io.github.terahidro2003.cct.jfr.ExecutionSample;
import io.github.terahidro2003.cct.result.StackTraceTreeNode;
import io.github.terahidro2003.cct.result.StackTraceTreePayload;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutionSampleTreeBuilder extends StackTraceTreeBuilder {
   
    private static final Logger log = LoggerFactory.getLogger(ExecutionSampleTreeBuilder.class);
   
    public ExecutionSampleTreeBuilder() {
        super();
    }

    public StackTraceTreeNode buildFromSerializedExecutionSamplesFile(@NonNull File file) {
        List<ExecutionSample> jfrSamples = readJfrExecutionSamplesJsonFile(file);
        return buildFromExecutionSamples(jfrSamples);
    }

    public StackTraceTreeNode buildFromExecutionSamples(List<ExecutionSample> samples) {
        StackTraceTreeNode root = new StackTraceTreeNode(null, new ArrayList<>(),
                new StackTraceTreePayload("root"));
        samples.forEach(sample -> {
            Collections.reverse(sample.getStackTrace());
        });

        samples.forEach(sample -> {
            addExecutionSample(root, sample);
        });
        return root;
    }

    private void addExecutionSample(StackTraceTreeNode parent, ExecutionSample sample) {
        // recursion exit check
        if(sample == null) {
            return;
        }

        // get measurement properties
        var methodNames =  sample.getMethodSignatures();

        // If sample has no methodNames, return.
        // Maybe consider throwing some exception at this point?
        if(methodNames == null || methodNames.isEmpty()) {
            return;
        }

        // We select the first child of current sample block
        String current = methodNames.get(0);
        StackTraceTreeNode child = null;

        // We're trying to find whether current parent has our current method as a child
        for(StackTraceTreeNode parentChild : parent.getChildren()) {
            if (parentChild.getPayload().getMethodName().equals(current)) {
                child = parentChild;
                break;
            }
        }

        // If our current parent node doesn't have our current method name as a child,
        // we assume that this is a new child of the current parent node.
        if(child == null) {
            child = new StackTraceTreeNode(parent, new ArrayList<>(), new StackTraceTreePayload(methodNames.get(0)));
            parent.getChildren().add(child);
        }

        // prepare for recursion
        // we remove the first element in method names list of a sample block (because we processed that node)

        sample.setStackTrace(sample.getStackTrace().subList(1, sample.getStackTrace().size()));

        // recursive call
        addExecutionSample(child, sample);
    }


    /**
     *
     * @param jfrFile - json file containing samples derived from JFR file
     * @return
     */
    private List<ExecutionSample> readJfrExecutionSamplesJsonFile(File jfrFile) {
        try {
            log.info("Reading serialized sample json file {}", jfrFile.getName());
            List<ExecutionSample> samples = ExecutionSample.parseJson(jfrFile.getAbsolutePath());
            log.info("Loaded {} samples", samples.size());
            return samples;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private List<ExecutionSample> getExecutionSamplesFromMultipleJsons(List<File> serializedSamples) {
        List<ExecutionSample> samples = new ArrayList<>();
        log.info("Reading serialized sample json files");
        for (File file : serializedSamples) {
            samples.addAll(readJfrExecutionSamplesJsonFile(file));
        }
        log.info("Loaded {} samples", samples.size());
        return samples;
    }
}
