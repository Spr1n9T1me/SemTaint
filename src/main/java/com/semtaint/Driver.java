package com.semtaint;

import com.semtaint.config.ConfManager;
import com.semtaint.config.GlobalState;
import com.semtaint.utils.IOUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.Main;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


public class Driver {
    private static final Logger logger = LogManager.getLogger(Driver.class);
    protected static String jarFilesString;

    /**
     * Main entry point for SemTaint analysis
     * @param args command line arguments, expects config file path
     * @throws IOException if file operations fail
     */
    public static void main(String[] args) throws IOException {
        // Load configuration
        ConfManager config = ConfManager.v();
        String configPath = args.length != 0 ? args[0] : "src/main/resources/semtaint-default.yml";
        config.loadConfig(configPath);
        logger.info("Loaded configuration from: {}", configPath);

        // Process JAR files if in JAR mode
        if (config.getBoolean("app.jar-mode")) {
            logger.info("Running in JAR mode, scanning directory: {}", config.getString("path.jar-path"));
            scanDir(config.getString("path.jar-path"));
        } else {
            logger.info("Running in class path mode");
            configureClassPaths(config);
        }

        // Build and execute Tai-e command
        String[] cmdArgs = buildTaieCommand(config);
        commandRun(cmdArgs);

        // Log memory usage
        logPeakMemoryUsage();
    }

    /**
     * Configure class paths and library paths based on configuration
     */
    private static void configureClassPaths(ConfManager config) {
        List<String> appClassPaths = config.getList("path.app-class-path");
        config.set("path.app-class-path", String.join(File.pathSeparator, appClassPaths));
        config.set("path.app-lib-path", config.getString("path.app-lib-path"));
        logger.debug("Configured app-class-path: {}", config.getString("path.app-class-path"));
        logger.debug("Configured app-app-lib-path: {}", config.getString("path.app-lib-path"));
    }

    /**
     * Build Tai-e command line arguments based on configuration
     * @param config configuration manager
     * @return command line arguments array
     * @throws IOException if collecting JAR files fails
     */
    private static String[] buildTaieCommand(ConfManager config) throws IOException {
        ArrayList<String> command = new ArrayList<>();

        // Basic options
        command.add("-scope");
        command.add("APP");
        if (config.getBoolean("app.prepend-jvm", false)){
            command.add("--prepend-JVM");
        }else {
            command.add("-java");
            command.add("8");
        }
        // Cache mode
        if (config.getBoolean("app.cache-mode")) {
            command.add("-wc");
            logger.info("Cache mode enabled");
        }

        command.add("-ap");

        // Collect JAR files from lib path
        List<String> jarFiles = IOUtil.collectJarFiles(config.getString("path.app-lib-path", ""));
        jarFilesString = jarFiles.stream().collect(Collectors.joining(File.pathSeparator));
        logger.info("Collected {} JAR files from library path", jarFiles.size());

        // Application class path
        command.add("-acp");
        String appClassPath = config.getString("path.app-class-path", "") + File.pathSeparator + jarFilesString;
        command.add(appClassPath);
        logger.debug("Application class path: {}", appClassPath);

        // Output directory
        command.add("--output-dir");
        String outputDir = config.getString("path.app-lib-path", "") + "/../../output";
        command.add(outputDir);
        logger.info("Output directory: {}", outputDir);

        // Analysis options
        addAnalysisOptions(command, config);

        return command.toArray(new String[0]);
    }

    /**
     * Add analysis-specific options to command
     */
    private static void addAnalysisOptions(ArrayList<String> command, ConfManager config) {
        // Framework detection (Spring annotation + XML config analysis)
        command.add("-a");
        command.add("framework-detector");

        // Pointer analysis configuration
        command.add("-a");
        String ptaOption = buildPtaOption(config);
        command.add(ptaOption);
        logger.info("PTA option: {}", ptaOption);
    }

    /**
     * Build pointer analysis option string
     */
    private static String buildPtaOption(ConfManager config) {
        String ptaOption;
        String app;
        if (config.getBoolean("pta.only-app")){
            app = "only-app:true;";
            logger.info("Only-app mode enabled for pointer analysis");
        }else {
            app = "only-app:false;";
        }
        if (config.getBoolean("pta.enable-taint")) {
            ptaOption = "semtaint=cs:ci;propagate-types:[reference,null];" +
                       app + "handle-invokedynamic:false;dump-ci:false;" +
                       "merge-string-objects:true;distinguish-string-constants:app;" +
                       "taint-config:" + config.getString("pta.taint-config");
            logger.info("Taint analysis enabled");
        } else {
            ptaOption = "semtaint=cs:ci;propagate-types:[reference,null,int,long];" +
                       app + "dump-ci:false;merge-string-objects:true;" +
                       "distinguish-string-constants:app";
        }

        // Replace context sensitivity configuration
        String ptaCSOption = ptaOption.replaceAll("cs:ci", config.getString("pta.cs"));
        return ptaCSOption;
    }



    /**
     * Execute Tai-e with the given command line arguments
     * @param args command line arguments for Tai-e
     */
    public static void commandRun(String[] args) {
        logger.info("------- Initializing Tai-e... -------");
        try {
            Main.main(args);
            logCallStatics();
        } catch (Exception e) {
            logger.error("Failed to execute Tai-e analysis", e);
            throw new RuntimeException("Tai-e execution failed", e);
        }
    }

    /**
     * Log statistics about reachable methods and call edges.
     * Outputs:
     *   - Summary counts (all methods, app methods, sem.virtual methods)
     *   - sem.virtual synthetic method breakdown by modeling mechanism
     *   - Call-edge breakdown by call kind and, for OTHER edges, by mechanism prefix
     *   - Per-mechanism file lists and a detailed stats file
     */
    private static void logCallStatics() {
        ConfManager config = ConfManager.v();
        String appPackageName = config.getString("app.package-name", "");

        if (appPackageName.isEmpty()) {
            logger.warn("app.package-name not configured, skipping call statistics");
            return;
        }

        // ── Collect declared app methods (denominator for reachability ratio) ──
        Set<JMethod> appMethods = World.get().getClassHierarchy()
                .applicationClasses()
                .filter(jc -> jc.getName().startsWith(appPackageName))
                .flatMap(jc -> jc.getDeclaredMethods().stream())
                .collect(Collectors.toSet());

        PointerAnalysisResult result = World.get().getResult(SemTaintAnalysis.ID);
        Set<JMethod> reachableMethods = result.getCallGraph()
                .allReachableMethods()
                .collect(Collectors.toSet());

        // ── sem.virtual synthetic methods (all reachable) ──
        Set<JMethod> semVirtualMethods = reachableMethods.stream()
                .filter(Driver::isVirtual)
                .collect(Collectors.toSet());

        // Group sem.virtual methods by the framework mechanism that created them
        Map<String, List<JMethod>> virtualByMechanism = semVirtualMethods.stream()
                .collect(Collectors.groupingBy(Driver::detectSyntheticMechanism));

        // ── Reachable app-package methods (sem.virtual tracked separately) ──
        Set<JMethod> reachableAppMethods = reachableMethods.stream()
                .filter(m -> m.getDeclaringClass().getName().startsWith(appPackageName))
                .collect(Collectors.toSet());

        Set<JMethod> unreachableAppMethods = new HashSet<>(appMethods);
        unreachableAppMethods.removeAll(reachableAppMethods);

        // ── Call edges where at least one endpoint is in the app package ──
        Set<Edge<Invoke, JMethod>> reachableAppCallEdges = result.getCallGraph().edges()
                .filter(e -> e.getCallSite().getContainer().getDeclaringClass().getName().startsWith(appPackageName)
                          || e.getCallee().getDeclaringClass().getName().startsWith(appPackageName))
                .collect(Collectors.toSet());

        // Edges that cross into sem.virtual (app synthetic)
        Set<Edge<Invoke, JMethod>> edgesIntoSemVirtual = result.getCallGraph().edges()
                .filter(e -> isVirtual(e.getCallee()))
                .collect(Collectors.toSet());

        // Group call edges by kind; for OTHER edges also extract the mechanism prefix
        // e.g. "PersistenceProxy-findAll" "OTHER/PersistenceProxy"
        Map<String, Long> edgesByKind = reachableAppCallEdges.stream()
                .collect(Collectors.groupingBy(e -> {
                    if (e.getKind() == CallKind.OTHER) {
                        String info = e.getInfo();
                        int dash = info.indexOf('-');
                        return "OTHER/" + (dash > 0 ? info.substring(0, dash) : info);
                    }
                    return e.getKind().name();
                }, Collectors.counting()));

        // ── Log summary ──
        double reachableRatio = appMethods.isEmpty() ? 0.0
                : (double) reachableAppMethods.size() / appMethods.size();

        logger.info("\n========== Call Graph Statistics ==========");
        logger.info("All Reachable Methods        : {}", reachableMethods.size());
        logger.info("App Methods (declared)       : {}", appMethods.size());
        logger.info("App Container Methods        : {}", GlobalState.appPackageMethodsCounted.size());
        logger.info("Reachable App Methods        : {}", reachableAppMethods.size());
        logger.info("Unreachable App Methods      : {}", unreachableAppMethods.size());
        logger.info("Reachable Ratio              : {}", String.format("%.2f%%", reachableRatio * 100));
        logger.info("sem.virtual Synthetic Methods: {}", semVirtualMethods.size());

        // sem.virtual breakdown by mechanism
        logger.info("\n--- sem.virtual Synthetic Methods by Mechanism ---");
        virtualByMechanism.entrySet().stream()
                .sorted(Map.Entry.<String, List<JMethod>>comparingByValue(
                        Comparator.comparingInt(List::size)).reversed())
                .forEach(e -> logger.info(String.format("  %-25s : %d", e.getKey(), e.getValue().size())));

        // Call-edge breakdown
        logger.info("\n--- Call Edge Breakdown ---");
        logger.info("Total App Call Edges         : {}", reachableAppCallEdges.size());
        logger.info("Edges into sem.virtual       : {}", edgesIntoSemVirtual.size());
        edgesByKind.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> logger.info(String.format("  %-35s : %d", e.getKey(), e.getValue())));

        logger.info("==========================================\n");

        // ── Persist to files ──
        String outputDir = World.get().getOptions().getOutputDir().toString();

        // Existing outputs
        IOUtil.writeSet2File(
                unreachableAppMethods.stream().map(JMethod::toString).collect(Collectors.toSet()),
                outputDir + "/unreachableAppMethods.txt");
        IOUtil.writeSet2File(
                reachableAppMethods.stream().map(JMethod::toString).collect(Collectors.toSet()),
                outputDir + "/reachableAppMethods.txt");

        // All sem.virtual methods
        IOUtil.writeSet2File(
                semVirtualMethods.stream().map(JMethod::toString).collect(Collectors.toSet()),
                outputDir + "/semVirtualMethods.txt");

        // Per-mechanism sem.virtual method lists
        for (Map.Entry<String, List<JMethod>> entry : virtualByMechanism.entrySet()) {
            String safeKey = entry.getKey().replaceAll("[^a-zA-Z0-9_]", "_");
            IOUtil.writeSet2File(
                    entry.getValue().stream().map(JMethod::toString).collect(Collectors.toSet()),
                    outputDir + "/semVirtualMethods_" + safeKey + ".txt");
        }

        // Detailed stats summary file
        StringBuilder sb = new StringBuilder();
        sb.append("=== Call Graph Detailed Statistics ===\n");
        sb.append(String.format("All Reachable Methods        : %d%n", reachableMethods.size()));
        sb.append(String.format("App Methods (declared)       : %d%n", appMethods.size()));
        sb.append(String.format("Reachable App Methods        : %d%n", reachableAppMethods.size()));
        sb.append(String.format("Unreachable App Methods      : %d%n", unreachableAppMethods.size()));
        sb.append(String.format("Reachable Ratio              : %.2f%%%n", reachableRatio * 100));
        sb.append(String.format("sem.virtual Synthetic Methods: %d%n", semVirtualMethods.size()));
        sb.append("\n=== sem.virtual Methods by Mechanism ===\n");
        virtualByMechanism.entrySet().stream()
                .sorted(Map.Entry.<String, List<JMethod>>comparingByValue(
                        Comparator.comparingInt(List::size)).reversed())
                .forEach(e -> {
                    sb.append(String.format("  %-25s : %d%n", e.getKey(), e.getValue().size()));
                    e.getValue().stream().map(JMethod::toString).sorted()
                            .forEach(s -> sb.append("    ").append(s).append("\n"));
                });
        sb.append("\n=== Call Edge Breakdown by Kind / Mechanism ===\n");
        sb.append(String.format("Total App Call Edges         : %d%n", reachableAppCallEdges.size()));
        sb.append(String.format("Edges into sem.virtual       : %d%n", edgesIntoSemVirtual.size()));
        edgesByKind.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> sb.append(String.format("  %-35s : %d%n", e.getKey(), e.getValue())));
        IOUtil.writeString2File(sb.toString(), outputDir + "/callGraphDetailedStats.txt");

        // All edges crossing into sem.virtual (one per line)
        IOUtil.writeSet2File(
                edgesIntoSemVirtual.stream()
                        .map(e -> String.format("[%s | %s] %s -> %s",
                                e.getKind(), e.getInfo(),
                                e.getCallSite().getContainer(), e.getCallee()))
                        .collect(Collectors.toSet()),
                outputDir + "/edgesIntoSemVirtual.txt");

        logger.info("Call statistics saved to output directory: {}", outputDir);
    }

    /**
     * Detect the framework mechanism responsible for a synthetic {@code sem.virtual} method
     * based on the naming convention applied by each handler's {@code CodeEnhancer.createClass} call.
     */
    private static String detectSyntheticMechanism(JMethod method) {
        String className = method.getDeclaringClass().getName();
        if (className.contains("$$PersistenceImpl")) return "Persistence";
        if (className.contains("$AOPProxy"))         return "AOP";
        return "Other";
    }
    private static boolean isVirtual(JMethod method){
        String moduleName = method.getDeclaringClass().getModuleName();
        if (moduleName == null){
            return false;
        }else {
            return moduleName.startsWith("sem.virtual");
        }
    }

    /**
     * Log peak memory usage of the JVM
     */
    public static void logPeakMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long peakMemoryUsage = runtime.totalMemory() - runtime.freeMemory();
        double gb = peakMemoryUsage / (double) (1024 * 1024 * 1024);
        DecimalFormat df = new DecimalFormat("0.00");
        String formattedMemoryUsage = df.format(gb) + " GB";
        logger.info("Peak memory usage: {}", formattedMemoryUsage);
    }

    /**
     * Scan directory for JAR/WAR files and process them
     * @param path directory path to scan
     */
    public static void scanDir(String path){
        File file = new File(path);
        File[] jars = file.listFiles(pathname ->
            pathname.getName().endsWith(".jar") || pathname.getName().endsWith(".war")
        );

        if (jars == null || jars.length == 0) {
            logger.warn("No JAR/WAR files found in directory: " + path);
            return;
        }

        logger.info("Found {} archive file(s) to process", jars.length);
        for (File jar : jars) {
            try {
                runJarScan(jar);
            } catch (IOException e) {
                logger.error("Failed to process archive: " + jar.getAbsolutePath(), e);
            }
        }
    }

    /**
     * Extract and analyze JAR/WAR file with support for XML configuration detection
     * This method follows the design pattern of DirectoryTraverser
     *
     * @param jarFile the JAR/WAR file to scan
     * @throws IOException if file operations fail
     */
    public static void runJarScan(File jarFile) throws IOException {
        if (!jarFile.exists() || !jarFile.canRead()) {
            throw new IOException("File does not exist or cannot be read: " + jarFile.getAbsolutePath());
        }

        logger.info("Processing archive: {}", jarFile.getName());

        // Prepare extraction directory
        File parentDir = jarFile.getParentFile();
        if (parentDir == null) {
            throw new IOException("Parent directory is null for file: " + jarFile.getAbsolutePath());
        }

        File extractedDir = new File(parentDir, "extracted");
        extractedDir.mkdirs();

        // Create subdirectory named after the archive (without extension)
        String jarFileName = jarFile.getName();
        String subDirName = jarFileName.substring(0, jarFileName.lastIndexOf('.'));
        File subDir = new File(extractedDir, subDirName);
        subDir.mkdirs();

        String extractPath = subDir.getAbsolutePath();

        // Statistics counters
        AtomicInteger classCount = new AtomicInteger(0);
        AtomicInteger xmlCount = new AtomicInteger(0);

        // Define file processors
        // Process XML files - count and prepare for XmlConfigAnalysis
        java.util.function.BiConsumer<String, InputStream> xmlProcessor = (entryName, inputStream) -> {
            xmlCount.incrementAndGet();
            logger.debug("Found XML file: {}", entryName);
            // XML files are extracted and will be processed by XmlConfigAnalysis later
        };

        // Process class files - count for statistics
        java.util.function.BiConsumer<String, InputStream> classProcessor = (entryName, inputStream) -> {
            classCount.incrementAndGet();
            // Class files are extracted for later use by Tai-e
        };

        // Define file filter - accept both XML and class files
        java.util.function.Predicate<String> fileFilter = entryName ->
            entryName.endsWith(".xml") || entryName.endsWith(".class");

        // Combined processor
        java.util.function.BiConsumer<String, InputStream> combinedProcessor = (entryName, inputStream) -> {
            if (entryName.endsWith(".xml")) {
                xmlProcessor.accept(entryName, inputStream);
            } else if (entryName.endsWith(".class")) {
                classProcessor.accept(entryName, inputStream);
            }
        };

        // Extract and process files
        IOUtil.extractFileWithProcessor(jarFile, extractPath, fileFilter, combinedProcessor);

        // Log statistics
        logger.info("Archive extraction completed for: {}", jarFileName);
        logger.info("  - Class files: {}", classCount.get());
        logger.info("  - XML files: {}", xmlCount.get());
        logger.info("  - Extraction path: {}", extractPath);

        // Note: XML configuration detection can be performed later using:
        // XmlConfigAnalysis detector = new XmlConfigAnalysis(extractPath);
        // XmlConfigHolder holder = detector.detect();
    }

    /**
     * Collect and list all XML files from extracted archive
     * Useful for manual inspection or targeted XML processing
     *
     * @param extractedPath path to extracted archive content
     * @return list of XML file paths
     */
    public static List<Path> collectXmlFilesFromExtracted(String extractedPath) {
        try {
            List<Path> xmlFiles = IOUtil.collectXmlFiles(extractedPath);
            logger.info("Collected {} XML files from: {}", xmlFiles.size(), extractedPath);
            return xmlFiles;
        } catch (IOException e) {
            logger.error("Failed to collect XML files from: " + extractedPath, e);
            return List.of();
        }
    }

    /**
     * Collect and list all class files from extracted archive
     *
     * @param extractedPath path to extracted archive content
     * @return list of class file paths
     */
    public static List<Path> collectClassFilesFromExtracted(String extractedPath) {
        try {
            List<Path> classFiles = IOUtil.collectClassFiles(extractedPath);
            logger.info("Collected {} class files from: {}", classFiles.size(), extractedPath);
            return classFiles;
        } catch (IOException e) {
            logger.error("Failed to collect class files from: " + extractedPath, e);
            return List.of();
        }
    }
    }




