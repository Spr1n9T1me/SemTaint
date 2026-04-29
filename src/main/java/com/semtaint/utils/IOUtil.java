package com.semtaint.utils;

import com.semtaint.config.ConfManager;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IOUtil {
    private static boolean pathsSet = false;

    public static List<String> collectJarFiles(String directory) throws IOException {
        Path project = Paths.get(System.getProperty("user.dir"));
        List<String> jarFiles = new ArrayList<>();
        Files.walkFileTree(Paths.get(directory), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".jar")) {
                    jarFiles.add(project.relativize(file.toAbsolutePath()).toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return jarFiles;
    }
    public static List<Path> findFiles(String rootDirectory, String fileExtension, String keyword) throws IOException {
        try (Stream<Path> pathStream = Files.walk(Paths.get(rootDirectory))) {
            return pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith("." + fileExtension) && path.getFileName().toString().contains(keyword))
                    .collect(Collectors.toList());
        }
    }
    public static List<Path> findMapper(String path) throws IOException {
        return findFiles(path, "xml", "Mapper").stream().toList();
    }
    public static void writeSet2File(Set<String> collection, String file)  {
        try {
            Files.write(Paths.get(file), collection);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public static void writeString2File(String content, String file) {
        try {
            Files.write(Paths.get(file), content.getBytes());
        } catch (IOException e) {

        }
    }
    public static void clearFile(String filePath) {
        try (FileWriter fileWriter = new FileWriter(filePath)) {
            // Opening the file in write mode clears its contents
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Extract archive file with customizable file processing
     * @param file source archive file (.jar, .war, etc.)
     * @param destDir destination directory
     * @param fileFilter predicate to filter files (return true to process/extract)
     * @param fileProcessor callback to process each file (entryName, inputStream)
     * @throws IOException if extraction fails
     */
    public static void extractFileWithProcessor(File file, String destDir,
                                                Predicate<String> fileFilter,
                                                BiConsumer<String, InputStream> fileProcessor) throws IOException {
        if (!file.exists() || !file.canRead()) {
            throw new IOException("File does not exist or cannot be read: " + file.getAbsolutePath());
        }

        File destDirFile = new File(destDir);
        boolean alreadyExtracted = destDirFile.exists() && destDirFile.isDirectory()
                && destDirFile.list() != null && destDirFile.list().length > 0;

        if (alreadyExtracted) {
            System.out.println("Archive already extracted to: " + destDir + ". Processing existing files.");

            // Check and set paths if needed
            if (!pathsSet) {
                configurePaths(destDir);
            }

            // Process existing files if fileProcessor is provided
            if (fileProcessor != null) {
                processExtractedFiles(destDir, fileFilter, fileProcessor);
            }
            return;
        }

        // Extract and process files
        try (ZipFile zipFile = new ZipFile(file)) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();

            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String entryName = entry.getName();
                File destFile = new File(destDir, entryName);

                if (entry.isDirectory()) {
                    destFile.mkdirs();
                } else {
                    destFile.getParentFile().mkdirs();

                    // Get input stream for processing
                    try (InputStream inputStream = zipFile.getInputStream(entry)) {
                        // Always extract the file
                        Files.copy(inputStream, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }

                    // Process file if filter matches and processor is provided
                    if (fileProcessor != null && fileFilter != null && fileFilter.test(entryName)) {
                        try (InputStream inputStream = zipFile.getInputStream(entry)) {
                            fileProcessor.accept(entryName, inputStream);
                        }
                    }
                }

                // Check and set paths
                if (!pathsSet && (entryName.equals("BOOT-INF/") || entryName.equals("WEB-INF/"))) {
                    configurePaths(destDir);
                }
            }
        }
    }

    /**
     * Extract archive file without custom processing (backward compatibility)
     */
    public static void extractFile(File file, String destDir) throws IOException {
        extractFileWithProcessor(file, destDir, null, null);
    }

    /**
     * Configure application class path and lib path based on directory structure
     */
    private static void configurePaths(String destDir) {
        ConfManager config = ConfManager.v();
        File bootInfDir = new File(destDir, "BOOT-INF");
        File webInfDir = new File(destDir, "WEB-INF");

        if (bootInfDir.exists() && bootInfDir.isDirectory()) {
            config.set("path.app-class-path", bootInfDir.getAbsolutePath() + File.separator + "classes");
            config.set("path.app-lib-path", bootInfDir.getAbsolutePath() + File.separator + "lib");
            pathsSet = true;
        } else if (webInfDir.exists() && webInfDir.isDirectory()) {
            config.set("path.app-class-path", webInfDir.getAbsolutePath() + File.separator + "classes");
            config.set("path.app-lib-path", webInfDir.getAbsolutePath() + File.separator + "lib");
            pathsSet = true;
        }
    }

    /**
     * Process files in already extracted directory
     */
    private static void processExtractedFiles(String directory,
                                             Predicate<String> fileFilter,
                                             BiConsumer<String, InputStream> fileProcessor) throws IOException {
        if (fileFilter == null || fileProcessor == null) {
            return;
        }

        Files.walk(Paths.get(directory))
            .filter(Files::isRegularFile)
            .filter(path -> fileFilter.test(path.toString()))
            .forEach(path -> {
                try (InputStream inputStream = Files.newInputStream(path)) {
                    fileProcessor.accept(path.toString(), inputStream);
                } catch (IOException e) {
                    System.err.println("Error processing file: " + path + ", " + e.getMessage());
                }
            });
    }

    /**
     * Collect all XML files from a directory recursively
     */
    public static List<Path> collectXmlFiles(String directory) throws IOException {
        List<Path> xmlFiles = new ArrayList<>();
        Files.walkFileTree(Paths.get(directory), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".xml")) {
                    xmlFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return xmlFiles;
    }

    /**
     * Collect all class files from a directory recursively
     */
    public static List<Path> collectClassFiles(String directory) throws IOException {
        List<Path> classFiles = new ArrayList<>();
        Files.walkFileTree(Paths.get(directory), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".class")) {
                    classFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return classFiles;
    }
}
