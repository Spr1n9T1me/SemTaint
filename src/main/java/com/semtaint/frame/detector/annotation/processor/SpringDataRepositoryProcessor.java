package com.semtaint.frame.detector.annotation.processor;

import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

public class SpringDataRepositoryProcessor implements AnnotationProcessor {

    private static final String REPOSITORY_ANNOTATION = "org.springframework.stereotype.Repository";
    private static final String QUERY_ANNOTATION = "org.springframework.data.jpa.repository.Query";
    private static final String MODIFYING_ANNOTATION = "org.springframework.data.jpa.repository.Modifying";

    // Spring Data Repository base interfaces
    private static final String REPOSITORY_INTERFACE = "org.springframework.data.repository.Repository";
    private static final String CRUD_REPOSITORY = "org.springframework.data.repository.CrudRepository";
    private static final String JPA_REPOSITORY = "org.springframework.data.jpa.repository.JpaRepository";
    private static final String MONGO_REPOSITORY = "org.springframework.data.mongodb.repository.MongoRepository";

    @Override
    public void processClass(JClass jClass, AnnotationsHolder holder) {
        boolean isRepository = false;

        if (jClass.hasAnnotation(REPOSITORY_ANNOTATION)) {
            isRepository = true;
        }

        if (jClass.isInterface() && extendsRepositoryInterface(jClass)) {
            isRepository = true;
        }

        if (jClass.isInterface() && (jClass.getName().endsWith("Repository") ||jClass.getName().endsWith("Dao"))) {
            isRepository = true;
        }

        if (isRepository) {
            holder.addMapperClass(jClass);
            holder.addBean(jClass);  // Spring Data repositories are always beans

            processRepositoryMethods(jClass, holder);
        }
    }

    /**
     * Spring Data Repository Interface
     */
    private boolean extendsRepositoryInterface(JClass jClass) {
        for (JClass iface : jClass.getInterfaces()) {
            String name = iface.getName();
            if (name.equals(REPOSITORY_INTERFACE) ||
                name.equals(CRUD_REPOSITORY) ||
                name.equals(JPA_REPOSITORY) ||
                name.equals(MONGO_REPOSITORY)) {
                return true;
            }

            // 递归检查父接口
            if (extendsRepositoryInterface(iface)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Repository
     */
    private void processRepositoryMethods(JClass repositoryClass, AnnotationsHolder holder) {
        for (JMethod method : repositoryClass.getDeclaredMethods()) {
            if (method.hasAnnotation(QUERY_ANNOTATION)) {
                if (method.hasAnnotation(MODIFYING_ANNOTATION)) {
                    holder.addUpdateMethod(method);
                } else {
                    holder.addSelectMethod(method);
                }
            } else {
                processMethodByNamingConvention(method, holder);
            }
        }
    }

    /**
     * Spring Data
     */
    private void processMethodByNamingConvention(JMethod method, AnnotationsHolder holder) {
        String methodName = method.getName();

        if (methodName.startsWith("find") ||
            methodName.startsWith("get") ||
            methodName.startsWith("read") ||
            methodName.startsWith("query") ||
            methodName.startsWith("search") ||
            methodName.startsWith("stream") ||
            methodName.startsWith("count") ||
            methodName.startsWith("exists")) {
            holder.addSelectMethod(method);
        }
        else if (methodName.startsWith("save") ||
                 methodName.startsWith("insert") ||
                 methodName.equals("saveAll") ||
                 methodName.equals("saveAndFlush")) {
            holder.addInsertMethod(method);
        }
        else if (methodName.startsWith("delete") ||
                 methodName.startsWith("remove") ||
                 methodName.equals("deleteAll") ||
                 methodName.equals("deleteAllInBatch")) {
            holder.addDeleteMethod(method);
        }
        else if (methodName.startsWith("update") ||
                 methodName.startsWith("modify")) {
            holder.addUpdateMethod(method);
        }
    }
}
