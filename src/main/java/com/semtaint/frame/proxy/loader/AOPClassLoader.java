package com.semtaint.frame.proxy.loader;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * @description:
 * @author: springtime
 **/
public class AOPClassLoader extends URLClassLoader {

    // 这些包应该由父加载器优先加载以避免冲突
    private static final String[] PARENT_FIRST_PACKAGES = {
        "org.apache.logging",
        "org.springframework.boot.logging",
        "com.fasterxml.jackson",
        "net.logstash",
        "java.",
        // "javax.",
        "sun.",
        "com.sun.",
        "org.w3c.",
        "org.xml.",
        // "org.slf4j",
        // "ch.qos.logback",
        // "org.yaml"
    };

    public AOPClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }


//     @Override
//     protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
//         Class<?> loadedClass = findLoadedClass(name);
//         if (loadedClass == null) {
//             try {
//                 //先用urls加载
//                 loadedClass = findClass(name);
//                 return loadedClass;
//             } catch (Exception e) {
//                 // 如果找不到类，继续尝试使用父加载器加载
//                loadedClass =  super.getParent().loadClass(name);
//             }
//         }
//         return loadedClass;
//     }

     @Override
     protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> loadedClass = findLoadedClass(name);
        if (loadedClass != null) {
            return loadedClass;
        }
         // 对特定包使用父加载器优先策略
         for (String prefix : PARENT_FIRST_PACKAGES) {
             if (name.startsWith(prefix)) {
                 try {
                    // 对这些包使用父加载器
                    loadedClass = getParent().loadClass(name);
                    if (resolve) {
                        resolveClass(loadedClass);
                    }
                    return loadedClass;
                 } catch (Exception e) {
                     // 父加载器找不到，继续尝试
                     break;
                 }
             }
         }

        // 尝试URL加载
        try {
            loadedClass = findClass(name);
        } catch (ClassNotFoundException e) {
            // 找不到类，尝试父加载器
            loadedClass = getParent().loadClass(name);
        }

        if (resolve) {
            resolveClass(loadedClass);
        }

        return loadedClass;
    }

}
