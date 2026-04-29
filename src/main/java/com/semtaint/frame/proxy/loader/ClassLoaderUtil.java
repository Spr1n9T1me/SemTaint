package com.semtaint.frame.proxy.loader;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ClassLoaderUtil {
    public static Set<String> unresolved = new HashSet<>();
    public static String rootDir = "";
    private static ClassLoader currentLoader;

    public static URLClassLoader addURLToClasspath(List<String> paths) throws Exception {
        ClassLoader classLoader = ClassLoader.getSystemClassLoader();

        // 添加健壮性检查
        if (paths == null || paths.size() < 2) {
            throw new IllegalArgumentException("Both jar and class paths must be provided");
        }

        List<String> jars = new ArrayList<>();
        // 安全地获取JAR文件
        File libDir = new File(paths.get(0));
        if (libDir.exists() && libDir.isDirectory()) {
            jars.addAll(getJarFilePathsFromDirectory(libDir));
        }

        // 分割并添加类路径
        if (paths.get(1) != null) {
            String[] classPaths = paths.get(1).split(File.pathSeparator);
            for (String path : classPaths) {
                if (path != null && !path.trim().isEmpty()) {
                    jars.add(path);
                }
            }
        }

        // 创建URL数组
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            try {
                File file = new File(jars.get(i));
                urls[i] = file.toURI().toURL();
            } catch (Exception e) {
                System.err.println("Error creating URL for " + jars.get(i) + ": " + e.getMessage());
                // 使用空URL防止数组中出现null
                urls[i] = new URL("file:/");
            }
        }

        // 创建自定义类加载器
        AOPClassLoader aopClassLoader = new AOPClassLoader(urls, classLoader);
        return aopClassLoader;
//        try {
//            Class<?> clazz = Class.forName("org.aspectj.weaver.tools.PointcutParser");
//            Method setClassLoader = clazz.getDeclaredMethod("setClassLoader", ClassLoader.class);
//            setClassLoader.setAccessible(true);
//            setClassLoader.invoke(parser, aopClassLoader);
//            currentLoader = aopClassLoader;
//            return aopClassLoader;
//        } catch (Exception e) {
//            System.err.println("Failed to set custom ClassLoader: " + e.getMessage());
//            throw e;
//        }
    }

    public static Set<Method> loadMethodsFromURLClassLoader(URLClassLoader urlClassLoader) throws Exception {
        Set<Method> methods = new HashSet<>();

        // 获取URLClassLoader中加载的所有URL
        URL[] urls = urlClassLoader.getURLs();

        for (URL url : urls) {
            File file = new File(url.toURI());

            // 递归处理目录或JAR文件
            if (file.isDirectory()) {
                processDirectoryRecursively(file, urlClassLoader, methods, file.getPath());
            } else if (file.getName().endsWith(".jar")) {
                processJarFile(file, urlClassLoader, methods);
            }
        }

        return methods;
    }

    // 递归处理目录，寻找.class文件和.jar文件
    private static void processDirectoryRecursively(File directory, URLClassLoader classLoader, Set<Method> methods, String classPathRoot) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                // 递归处理子目录
                processDirectoryRecursively(file, classLoader, methods, classPathRoot);
            } else if (file.getName().endsWith(".class")) {
                // 处理 .class 文件
                List<String> classNames = getClassNamesFromDirectory(directory, classPathRoot);
                methods.addAll(loadClassMethods(classNames, classLoader));
            } else if (file.getName().endsWith(".jar")) {
                // 处理 .jar 文件
                processJarFile(file, classLoader, methods);
            }
        }
    }

    // 处理JAR文件中的所有类和方法
    private static void processJarFile(File jarFile, URLClassLoader classLoader, Set<Method> methods) throws IOException {
        List<String> classNames = getClassNamesFromJar(jarFile);
        methods.addAll(loadClassMethods(classNames, classLoader));
    }

    // 获取目录下的所有类的名称
    private static List<String> getClassNamesFromDirectory(File directory, String rootDirectory) {
        List<String> classNames = new ArrayList<>();
        String rootPath = new File(rootDirectory).getAbsolutePath();

        // 修复：确保根路径和文件路径使用相同的分隔符标准化
        rootPath = rootPath.replace('\\', '/');

        for (File file : directory.listFiles()) {
            if (file.isDirectory()) {
                classNames.addAll(getClassNamesFromDirectory(file, rootDirectory));
            } else if (file.getName().endsWith(".class")) {
                // 修复：安全处理绝对路径
                String absolutePath = file.getAbsolutePath().replace('\\', '/');

                // 检查路径是否有效
                if (!absolutePath.startsWith(rootPath)) {
                    // 路径不匹配时的备选方案
                    String packageName = "";
                    try {
                        // 尝试获取相对路径
                        String relativePath = absolutePath.substring(absolutePath.lastIndexOf(directory.getName()));
                        packageName = relativePath.replace('/', '.').replace(".class", "");
                    } catch (Exception e) {
                        // 如果提取失败，至少提供类名
                        packageName = file.getName().replace(".class", "");
                    }
                    classNames.add(packageName);
                } else {
                    // 正常情况：从根目录开始提取
                    try {
                        String className = absolutePath.substring(rootPath.length() + 1)
                                .replace('/', '.').replace(".class", "");
                        classNames.add(className);
                    } catch (StringIndexOutOfBoundsException e) {
                        // 发生错误时提供日志并使用简单的类名
                        System.err.println("Error extracting class name from " + absolutePath +
                                         " (root: " + rootPath + ")");
                        classNames.add(file.getName().replace(".class", ""));
                    }
                }
            }
        }
        return classNames;
    }

    // 获取JAR文件中的所有类的名称
    private static List<String> getClassNamesFromJar(File jarFile) throws IOException {
        List<String> classNames = new ArrayList<>();
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // 只处理 .class 文件，忽略 META-INF 等目录
                if (entryName.endsWith(".class") && !entryName.startsWith("META-INF/")) {
                    String className = entryName.replace("/", ".").replace(".class", "");
                    classNames.add(className);
                }
            }
        }
        return classNames;
    }

    // 加载类并返回所有方法
    private static Set<Method> loadClassMethods(List<String> classNames, ClassLoader classLoader) {
        Set<Method> methods = new HashSet<>();
        for (String className : classNames) {
            // 跳过META-INF目录
            if (className.startsWith("META-INF") ) {
                continue;
            }

            try {
                Class<?> clazz = classLoader.loadClass(className);  //父加载器未加载，使用URLClassLoader加载类
//                System.out.println("Loaded class: " + clazz.getName());

                // 获取类的所有声明的方法
                Method[] classMethods = clazz.getDeclaredMethods();
                for (Method method : classMethods) {
                    methods.add(method);
                }
            } catch (ClassNotFoundException e) {
//                System.err.println("无法加载类: " + className + "error: " + e.getMessage());
                unresolved.add(className);
            } catch (NoClassDefFoundError e) {
//                System.err.println("NoClassDefFoundError: " + className + "error: " + e.getMessage());
                unresolved.add(className);
            }catch (IllegalAccessError e){
//                System.err.println("IllegalAccessError: " + className + "error: " + e.getMessage());
                unresolved.add(className);
            }catch (IncompatibleClassChangeError e){
//                System.err.println("IncompatibleClassChangeError: " + className + "error: " + e.getMessage());
                unresolved.add(className);
            }catch (TypeNotPresentException e){
//                System.err.println("TypeNotPresentException: " + className + "error: " + e.getMessage());
                unresolved.add(className);
            }catch (LinkageError e){
                System.err.println("LinkageError: " + className + "error: " + e.getMessage());
                unresolved.add(className);
            }
        }
        return methods;
    }

    // 递归查找目录中的所有 JAR 文件
    public static List<String> getJarFilePathsFromDirectory(File directory) {
        List<String> jarFilePaths = new ArrayList<>();
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        // 递归处理子目录
                        jarFilePaths.addAll(getJarFilePathsFromDirectory(file));
                    } else if (file.getName().endsWith(".jar")) {
                        // 添加 JAR 文件路径
                        jarFilePaths.add(file.getAbsolutePath());
                    }
                }
            }
        }
        return jarFilePaths;
    }

}
