package io.github.terahidro2003.cct.builder;

import io.github.terahidro2003.cct.result.StackTraceData;
import io.github.terahidro2003.cct.result.StackTraceTreeNode;
import io.github.terahidro2003.cct.result.StackTraceTreePayload;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
public class StackTraceDataTreeBuilder extends StackTraceTreeBuilder {
    private static final Logger log = LoggerFactory.getLogger(StackTraceDataTreeBuilder.class);
   
    public StackTraceDataTreeBuilder() {
        super();
    }

    public StackTraceTreeNode buildFromResultJson(@NonNull File resultFile) {
        if (!resultFile.exists()) {
            throw new RuntimeException("Result file doesn't exist");
        }

        try (InputStream in = new FileInputStream(resultFile)) {
            return build(parseProfile(in));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public StackTraceTreeNode build(List<StackTraceData> organizedSamples) {
        StackTraceTreeNode root = new StackTraceTreeNode(null, new ArrayList<>(),
                new StackTraceTreePayload("root"));
        // we need to reverse the asprof output method names
        // because the first element in asprof output is the last method call in the stack trace.
        organizedSamples.forEach(sampleBlock -> {
            Collections.reverse(sampleBlock.getMethods());
        });

        organizedSamples.forEach(sampleBlock -> addSampleBlock(root, sampleBlock));
        return root;
    }

    private void addSampleBlock(StackTraceTreeNode parent, StackTraceData sampleBlock) {
        // recursion exit check
        if(sampleBlock == null) {
            return;
        }

        // get measurement properties
        var methodNames = sampleBlock.getMethods();
        var timeTaken = sampleBlock.getTimeTaken();
        var amountOfSamples = sampleBlock.getTotalNumberOfSamples();
        var percentageOfSamples = sampleBlock.getPercentageOfTotalTimeTaken();

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
        sampleBlock.setMethods(sampleBlock.getMethods().subList(1, sampleBlock.getMethods().size()));

        // recursive call
        addSampleBlock(child, sampleBlock);
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
}
