package io.github.terahidro2003.measurement.executor.asprof;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.terahidro2003.config.Config;
import io.github.terahidro2003.measurement.data.MeasurementInformation;

public class AsyncProfilerHelper {
    private static final Logger log = LoggerFactory.getLogger(AsyncProfilerHelper.class);
   
    final Config config;

    private File output;

    public AsyncProfilerHelper(Config config) {
        this.config = config;
        this.output = null;
    }

    private AsyncProfilerHelper(Config config, File output) {
        this.config = config;
        this.output = output;
    }

    private static AsyncProfilerHelper instance;

    public static AsyncProfilerHelper getInstance(Config config) {
        return instance == null ? new AsyncProfilerHelper(config) : instance;
    }

    public static AsyncProfilerHelper getInstance(Config config, File output) {
        return instance == null ? new AsyncProfilerHelper(config, output) : instance;
    }

    public File retrieveRawOutputFile(int vmId, String commit) {
        if (output != null) {
            return this.output;
        }

        String outputFilePrefix = generateOutputFilePrefix(vmId, commit);

        File output = rawProfilerOutput(outputFilePrefix + ".json");
        if(config.JfrEnabled()) {
            output = rawProfilerOutput(outputFilePrefix + ".jfr");
        }
        this.output = output;
        return output;
    }

    private String generateOutputFilePrefix(int vms, String commit) {
        return "sjsw_asprof_results_" + System.currentTimeMillis() + "_" + vms + "-" + commit;
    }

    public MeasurementInformation retrieveJavaAgent(Duration duration, int vmId, String commit, String... includePattern) {
        File output = retrieveRawOutputFile(vmId, commit);

        final String asprofAgent;
        StringBuilder asprofAgentBuilder = new StringBuilder();
        asprofAgentBuilder.append("-agentpath:");
        asprofAgentBuilder.append(config.profilerPath());
        asprofAgentBuilder.append("=start,");
        asprofAgentBuilder.append("cstack=fp,event=cpu,alluser,");
        if(config.interval() == null || config.interval() == 0) {
            asprofAgentBuilder.append("interval=100ms,");
            if(!config.timeoutDisabled()) {
                asprofAgentBuilder.append("timeout=");
                asprofAgentBuilder.append(duration.getSeconds());
                asprofAgentBuilder.append(",");
            }
        } else {
            asprofAgentBuilder.append("interval=");
            asprofAgentBuilder.append(config.interval());
            asprofAgentBuilder.append("ms,");
            if(!config.timeoutDisabled()) {
                asprofAgentBuilder.append("timeout=");
                asprofAgentBuilder.append(duration.getSeconds());
                asprofAgentBuilder.append(",");
            }
        }
        if(includePattern != null && includePattern.length > 0) {
            asprofAgentBuilder.append("include=");
            asprofAgentBuilder.append(includePattern[0]);
            asprofAgentBuilder.append(",");
            asprofAgentBuilder.append("exclude=*jdk.internal.*,");
        }
        asprofAgentBuilder.append("file=");
        asprofAgentBuilder.append(output.getAbsolutePath());
        asprofAgent = asprofAgentBuilder.toString();
        System.out.println(asprofAgent);
        return new MeasurementInformation(output.getAbsolutePath(), asprofAgent);
    }

    public File rawProfilerOutput(String name) {
        try {
            File rawOutputFile = new File(Path.of(config.outputPath(), name).toAbsolutePath().toString());
            if(rawOutputFile.createNewFile()) {
                log.info("Created new file for raw asprof output: {}", rawOutputFile.getAbsolutePath());
            } else {
                log.info("File for asprof output probably already exists: {}", rawOutputFile.getAbsolutePath());
            }
            return rawOutputFile;
        } catch (IOException e) {
            log.error("Failed to create / find profiler output file: {}", e.getMessage());
        }
        return null;
    }
}
