package io.github.terahidro2003.cct.jfr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.terahidro2003.cct.TreeUtils;
import io.github.terahidro2003.config.Constants;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutionSample {
   
    private static final Logger log = LoggerFactory.getLogger(ExecutionSample.class);
   
    private String timestamp;
    private String sampledThread;
    private String threadState;
    private List<Method> stackTrace;

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getSampledThread() {
        return sampledThread;
    }

    public void setSampledThread(String sampledThread) {
        this.sampledThread = sampledThread;
    }

    public String getThreadState() {
        return threadState;
    }

    public void setThreadState(String threadState) {
        this.threadState = threadState;
    }

    public List<Method> getStackTrace() {
        return stackTrace;
    }

    public void setStackTrace(List<Method> stackTrace) {
        this.stackTrace = stackTrace;
    }

    public static List<ExecutionSample> parseJson(String filePath) throws IOException {
        log.info("Deserializing {}", filePath);
        ObjectMapper mapper = Constants.OBJECT_MAPPER;
        var serializedMap = mapper.readValue(new File(filePath), new TypeReference<HashMap<String, Object>>() {});
        List<ExecutionSample> samples = new ArrayList<ExecutionSample>();

        if(serializedMap.get("recording") != null && serializedMap.get("recording") instanceof Map
                && ((Map<?, ?>) serializedMap.get("recording")).get("events") != null
                && ((Map<?, ?>) serializedMap.get("recording")).get("events") instanceof List
        ) {
            List<Map> events = (List<Map>) ((Map<String, List>) serializedMap.get("recording")).get("events");

            // filter out the events that do not contain samples (such as system and JVM properties)
            events.stream().filter(event -> event.containsValue("jdk.ExecutionSample")).toList()
                    .forEach(event -> {
                        var eventValue = event.get("values");
                        if(eventValue instanceof Map) {
                            var eventMap = (Map<String, Object>) eventValue;
                            ExecutionSample sample = new ExecutionSample();

                            if(eventMap.containsKey("startTime")) {
                                sample.setTimestamp(eventMap.get("startTime").toString());
                            } else {
                                log.error("Couldn't find start time in JFR recording sample");
                            }

                            // TODO: thread name and state parsing does not work
                            if(eventMap.containsKey("sampledThread")) {
                                if(eventMap.get("sampledThread") instanceof Map) {
                                    var threadMap = (Map<String, Map>) eventMap.get("sampledThread"); // fails here
                                    String threadName = (threadMap != null && threadMap.get("sampledThread") != null)
                                            ? (String) threadMap.get("sampledThread").get("osName") : null;
                                    if(threadName != null) {
                                        sample.setSampledThread(threadName);
                                    } else {
                                        log.debug("Couldn't find sampled thread name in JFR recording sample");
                                        sample.setSampledThread("[NOT FOUND]");
                                    }
                                }
                            } else {
                                log.error("Couldn't find sampled thread in JFR recording sample");
                            }

                            if(eventMap.containsKey("stackTrace") && eventMap.get("stackTrace") instanceof Map) {
                                List<Method> methods = new ArrayList<>();
                                var stacktraceMap = (Map<String, Object>) eventMap.get("stackTrace");
                                var frames = stacktraceMap.get("frames");
                                if(frames instanceof List) {
                                    var traces = (List) frames;
                                    traces.forEach(trace -> {
                                        if (trace instanceof Map) {
                                            Method method = new Method();
                                            var frameMap = (Map<String, Object>) trace;
                                            if(frameMap.containsKey("method") && frameMap.get("method") instanceof Map) {
                                                var methodMap = (Map<String, Object>) frameMap.get("method");
                                                if(methodMap.containsKey("type")) {
                                                    var methodType = methodMap.get("type");
                                                    if(methodType != null && methodType instanceof Map) {
                                                        method.setClassLoader(propertyFromMapAsString((Map<?, ?>) methodType, "classLoader"));
                                                        method.setClassSignature(propertyFromMapAsString((Map<?, ?>) methodType, "name"));
                                                        method.setPackageName(propertyFromMapAsString((Map<?, ?>) methodType, "package"));
                                                        method.setClassModifier(propertyFromMapAsString((Map<?, ?>) methodType, "modifiers"));
                                                    }

                                                    method.setMethodName(propertyFromMapAsString((Map<?, ?>) methodMap, "name"));
                                                    method.setMethodDescriptor(propertyFromMapAsString((Map<?, ?>) methodMap, "descriptor"));
                                                    method.setMethodModifier(propertyFromMapAsString((Map<?, ?>) methodMap, "modifiers"));
                                                    method.setHidden(Boolean.parseBoolean(propertyFromMapAsString((Map<?, ?>) methodMap, "hidden")));
                                                }
                                            }

                                            method.setLineNumber(Integer.parseInt(propertyFromMapAsString((Map<?, ?>) frameMap, "lineNumber")));
                                            method.setByteCodeIndex(Integer.parseInt(propertyFromMapAsString((Map<?, ?>) frameMap, "bytecodeIndex")));
                                            method.setType(propertyFromMapAsString((Map<?, ?>) frameMap, "type"));

                                            if (method.getType() != null
                                                    &&
                                                    (method.getType().contains("Native") ||
                                                    method.getType().contains("native") || method.getType().contains("C++")
                                                    || method.getType().contains("c++")
                                            )) {
//                                                log.info("Skipping native method " + method);
                                            } else {
                                                methods.add(method);
                                            }
                                        }
                                    });
                                }
                                sample.setStackTrace(methods);
                            }
                            samples.add(sample);
                            // TODO: add parsing logic into try-catch (just in case)
                        }
                    });
        }
        return samples;
    }

    private static String propertyFromMapAsString(Map<?, ?> map, String key) {
        if(map.containsKey(key) && map.get(key) != null) {
            return map.get(key).toString();
        } else {
            log.debug("Couldn't find property {} in JFR recording sample", key);
            return "[NOT FOUND]";
        }
    }

    public List<String> getMethodSignatures() {
        List<String> methods = new ArrayList<>();
        stackTrace.forEach(trace -> {
            methods.add(trace.getFullMethodSignature());
        });
        return methods;
    }

    @Override
    public String toString() {
        return "ExecutionSample{" +
                "timestamp='" + timestamp + '\'' +
                ", sampledThread=" + sampledThread +
                ", threadState='" + threadState + '\'' +
                ", stackTrace=" + stackTrace +
                '}';
    }
}
