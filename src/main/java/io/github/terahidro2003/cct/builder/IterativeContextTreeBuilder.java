package io.github.terahidro2003.cct.builder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.terahidro2003.cct.SamplerResultsProcessor;
import io.github.terahidro2003.cct.TreeUtils;
import io.github.terahidro2003.cct.result.StackTraceTreeNode;
import io.github.terahidro2003.cct.result.VmMeasurement;

public class IterativeContextTreeBuilder extends StackTraceTreeBuilder {

    private static final Logger log = LoggerFactory.getLogger(IterativeContextTreeBuilder.class);
   
    public StackTraceTreeNode buildTree(List<File> jfrs, String commit, String testcase, boolean filterJvmNativeNodes,
                                        boolean parallelProcessing, int maxThreads) throws IOException {
        log.info("Building tree for testcase method: {}", testcase);
        if (jfrs.isEmpty()) {
            throw new RuntimeException("JFR files cannot be empty");
        }

        // Gather only JFR files containing a commit hash in the filename
        jfrs = jfrs.stream().filter(jfr -> jfr.getName().contains(commit) && jfr.getName().endsWith(".jfr"))
                .collect(Collectors.toCollection(ArrayList::new));
        log.info("Filtered JFRs for tree generation: {}", jfrs);

        SamplerResultsProcessor processor = new SamplerResultsProcessor();
        final List<StackTraceTreeNode> vmTrees = new ArrayList<>();
        StackTraceTreeNode mergedTree = null;

        final Map<List<String>, List<VmMeasurement>> measurementsMap = new HashMap<>();
        for (int i = 0; i<jfrs.size(); i++) {
            log.info("Building local tree for index: {} from JFR file: {}", i, jfrs.get(i).getName());
            StackTraceTreeNode vmTree = buildVmTree(jfrs.get(i), processor, testcase);
            mergedTree = processPartialTree(filterJvmNativeNodes, measurementsMap, vmTree, mergedTree, commit, testcase, vmTrees);
        }
        addLocalMeasurements(mergedTree, measurementsMap, commit, false);

        String folderPath = jfrs.get(0).getParentFile().getAbsolutePath();
        File output =
                new File(folderPath + File.separator + testcase + "-" + commit + "-" + UUID.randomUUID() + ".json");
        log.info("Writing merged tree to a file at location {}", output.getAbsolutePath());
        TreeUtils.writeCCTtoFile(mergedTree, output);
        return mergedTree;
    }

    public static int extractVmNumber(String filename) {
        Pattern pattern = Pattern.compile("_vm_(\\d+)_");
        Matcher matcher = pattern.matcher(filename);
        matcher.find();
        return Integer.parseInt(matcher.group(1));
    }

    private StackTraceTreeNode processPartialTree(boolean filterJvmNativeNodes, Map<List<String>, List<VmMeasurement>> measurementsMap, StackTraceTreeNode vmTree, StackTraceTreeNode mergedTree, String commit, String testcase, final List<StackTraceTreeNode> vmTrees) {
        // filters out common JVM and native method call nodes from all retrieved subtrees
        if(filterJvmNativeNodes) {
            vmTree = TreeUtils.filterJvmNodes(vmTree);
        }

        createMeasurementsMap(measurementsMap, List.of(vmTree), testcase, false);

        vmTrees.add(mergedTree);
        vmTrees.add(vmTree);
        mergedTree = TreeUtils.mergeTrees(vmTrees);
        vmTrees.clear();
        return mergedTree;
    }

    private synchronized StackTraceTreeNode buildVmTree(File jfr, SamplerResultsProcessor processor, String testcase) {
        StackTraceTreeNode bat = processor.getTreeFromJfr(List.of(jfr));
        String filename = jfr.getName();
        int vm = extractVmNumber(filename);

        // retrieves subtrees that share the same parent node
        log.info("Filtering and retrieving diverted testcase method subtrees");
        List<StackTraceTreeNode> filteredSubtrees = TreeUtils.filterMultiple(testcase, bat, false);
        var mergedTree = TreeUtils.mergeTrees(filteredSubtrees);
        mergedTree.getPayload().setVm(vm);
        return mergedTree;
    }

    private synchronized void addLocalMeasurements(StackTraceTreeNode bat, Map<List<String>, List<VmMeasurement>> measurementsMap, String identifier, boolean flag) {
        if (bat == null || measurementsMap == null) {
            throw new IllegalArgumentException("BAT and measurements map cannot be null");
        }

        for (Map.Entry<List<String>, List<VmMeasurement>> entry : measurementsMap.entrySet()) {
            List<String> signatures = entry.getKey();
            List<VmMeasurement> weights = entry.getValue();

            var result = TreeUtils.search(signatures, bat);
            if (result != null) {
                for (VmMeasurement weight : weights) {
                    result.addMeasurement(identifier, weight);
                }
            }
        }
    }

    private void createMeasurementsMap(Map<List<String>, List<VmMeasurement>> measurementsMap, List<StackTraceTreeNode> localTrees,
                                                                        String testcaseSignature, boolean flag) {
        for (StackTraceTreeNode localTree : localTrees) {

            Stack<StackTraceTreeNode> stack = new Stack<>();
            stack.push(localTree);

            while (!stack.isEmpty()) {
                StackTraceTreeNode currentNode = stack.pop();
                if(currentNode == null) continue;

                List<String> parentSignatures = currentNode.getParentMethodNames();
                List<String> signaturesToRemove = new ArrayList<>();
                for (int i = 0; i < parentSignatures.size(); i++) {
                    if(parentSignatures.get(i).contains(testcaseSignature)) {
                        break;
                    } else {
                        signaturesToRemove.add(parentSignatures.get(i));
                    }
                }
                parentSignatures.removeAll(signaturesToRemove);

                if (!measurementsMap.containsKey(parentSignatures)) {
                    measurementsMap.put(parentSignatures, new ArrayList<VmMeasurement>());
                    currentNode.setMeasurements(new HashMap<>());
                }

                VmMeasurement vmMeasurement = measurementsMap.get(parentSignatures).stream()
                        .filter(vmm -> vmm.getVm() == localTree.getPayload().getVm()).findFirst().orElse(null);
                if(vmMeasurement != null) {
                    vmMeasurement.addMeasurement(currentNode.getInitialWeight());
                } else {
                    measurementsMap.get(parentSignatures)
                            .add(new VmMeasurement(new ArrayList<Double>(List.of(currentNode.getInitialWeight())), localTree.getPayload().getVm()));
                }

                for (StackTraceTreeNode child : currentNode.getChildren()) {
                    if (child != null) {
                        stack.push(child);
                    }
                }
            }
        }
    }
}
