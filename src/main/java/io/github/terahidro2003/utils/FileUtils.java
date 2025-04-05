package io.github.terahidro2003.utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.terahidro2003.config.Config;
import io.github.terahidro2003.config.Constants;

public class FileUtils {
    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);
   
    public static String readFileToString(String fileName) throws IOException {
        return new String(Files.readAllBytes(Paths.get(fileName)));
    }

    public static File inputStreamToFile(InputStream inputStream, String fileName) throws IOException {
        File targetFile = new File(fileName);

        Files.copy(
                inputStream,
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING);

        IOUtils.closeQuietly(inputStream);

        return targetFile;
    }

    public static String retrieveAsyncProfilerExecutable(Path folder) throws IOException {
        String os = determineOS();
        switch (os) {
            case "LINUX":
                downloadAsyncProfiler(folder.resolve("async-profiler-3.0-linux-x64.tar.gz"), Constants.LINUX_ASYNC_PROFILER_URL);
                unzip(folder.toAbsolutePath().resolve("").toAbsolutePath(), folder.resolve("async-profiler-3.0-linux-x64.tar.gz").toAbsolutePath().toString());
                return folder.resolve(Constants.LINUX_ASYNC_PROFILER_NAME).resolve("lib").resolve("libasyncProfiler.so").toString();
            case "MACOS":
                throw new RuntimeException("Not implemented yet");
        }
        return "";
    }

    public static String determineOS() {
        String os = System.getProperty("os.name");
        if (os.toLowerCase().contains("windows")) {
            throw new RuntimeException("SJSW does not support Windows.");
        } else if(os.toLowerCase().contains("linux")) {
            return "LINUX";
        } else if(os.toLowerCase().contains("mac")) {
            return "MACOS";
        }
        return "LINUX";
    }

    private static void downloadAsyncProfiler(Path tmp, String url) {
        try {
            InputStream in = new URL(url).openStream();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to download async-profiler executable");
        }
    }

    /**
     * Code of this method adapted from <a href="https://commons.apache.org/proper/commons-compress/examples.html">Apache Commons Compress User Guide</a>
     * @param target
     * @param name
     * @throws IOException
     */
    private static void unzip(Path target, String name) throws IOException {
        try (InputStream fi = Files.newInputStream(Path.of(name))) {
            try (InputStream bi = new BufferedInputStream(fi)) {
                try (InputStream gzi = new GzipCompressorInputStream(bi)) {
                    try (ArchiveInputStream i = new TarArchiveInputStream(gzi)) {
                        ArchiveEntry entry = null;
                        while ((entry = i.getNextEntry()) != null) {
                            if (!i.canReadEntryData(entry)) {
                                continue;
                            }
                            String n = String.valueOf(target.resolve(entry.getName()));
                            File f = new File(n);
                            if (entry.isDirectory()) {
                                if (!f.isDirectory() && !f.mkdirs()) {
                                    throw new IOException("failed to create directory " + f);
                                }
                            } else {
                                File parent = f.getParentFile();
                                if (!parent.isDirectory() && !parent.mkdirs()) {
                                    throw new IOException("failed to create directory " + parent);
                                }
                                try (OutputStream o = Files.newOutputStream(f.toPath())) {
                                    IOUtils.copy(i, o);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static void renameFile(File original, String newName) throws IOException {
        File newFile = new File(original.getParent(), newName);
        if (newFile.exists())
            throw new IOException("Target file already exists");

        boolean success = original.renameTo(newFile);
        if (!success) {
            throw new IOException("Failed to rename " + original.getName() + " to " + newName);
        }
    }

    /**
     * Ensures that specified output folder inside configuration exists. Creates one,
     * if it did not exist already.
     * @param config
     */
    public static void configureResultsFolder(Config config) {
        if (config.outputPath().isEmpty()) {
            throw new IllegalArgumentException("No output path specified");
        }

        File outputFolder = new File(config.outputPath());
        if (!outputFolder.exists()) {
            if (!outputFolder.mkdirs()) {
                throw new IllegalStateException("Failed to create output folder: " + outputFolder.getAbsolutePath());
            }
        }
    }

    public static Config retrieveAsyncProfiler(Config config) throws IOException {
        if (config.profilerPath() == null || config.profilerPath().isEmpty()) {
            File folder = new File(config.outputPath() + "/executables");
            if(!folder.exists()) {
                folder.mkdirs();
            }
            String profilerPath = io.github.terahidro2003.utils.FileUtils.retrieveAsyncProfilerExecutable(folder.toPath());
            log.warn("Downloaded profiler path: {}", profilerPath);
            return new Config(config.executable(), config.mainClass(), profilerPath, config.outputPath(), config.JfrEnabled(), config.interval(), false);
        }
        return config;
    }
}
