package com.semtaint.frame.detector.annotation.processor;

import com.semtaint.config.GlobalState;
import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import pascal.taie.language.annotation.ArrayElement;
import pascal.taie.language.annotation.Element;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.Objects;

/**
 * MyBatis Mapper (@Select, @Insert, @Update, @Delete)
 */
public class MyBatisMapperProcessor implements AnnotationProcessor {

    private static final String MAPPER_SCAN_ANNOTATION = "org.mybatis.spring.annotation.MapperScan";
    private static final String MAPPER_ANNOTATION = "org.apache.ibatis.annotations.Mapper";

    // MyBatis SQL operation annotations
    private static final String SELECT_ANNOTATION = "org.apache.ibatis.annotations.Select";
    private static final String INSERT_ANNOTATION = "org.apache.ibatis.annotations.Insert";
    private static final String UPDATE_ANNOTATION = "org.apache.ibatis.annotations.Update";
    private static final String DELETE_ANNOTATION = "org.apache.ibatis.annotations.Delete";

    @Override
    public void processClass(JClass jClass, AnnotationsHolder holder) {
        if (jClass.hasAnnotation(MAPPER_SCAN_ANNOTATION)) {
             ArrayElement loc = (ArrayElement) Objects.requireNonNull(jClass.getAnnotation(MAPPER_SCAN_ANNOTATION))
                     .getElement("value");
            if (loc!=null)
                loc.elements().stream().map(Element::toString).forEach(GlobalState.MAPPER_PACKAGES::add);
        }

        boolean isMapper = false;

        if (jClass.hasAnnotation(MAPPER_ANNOTATION)) {
            isMapper = true;
        } else if (jClass.isInterface() && jClass.getName().endsWith("Mapper")) {
            isMapper = true;
        }

        if (isMapper) {
            holder.addMapperClass(jClass);
            //TODO  really need?
//            holder.addBean(jClass);
//            if (isInMapperPackage(jClass)) {
//                holder.addBean(jClass);
//            }

            processMapperMethods(jClass, holder);
        }
    }

    /**
     */
    private void processMapperMethods(JClass mapperClass, AnnotationsHolder holder) {
        for (JMethod method : mapperClass.getDeclaredMethods()) {
            if (method.hasAnnotation(SELECT_ANNOTATION)) {
                holder.addSelectMethod(method);
            }

            if (method.hasAnnotation(INSERT_ANNOTATION)) {
                holder.addInsertMethod(method);
            }

            if (method.hasAnnotation(UPDATE_ANNOTATION)) {
                holder.addUpdateMethod(method);
            }

            if (method.hasAnnotation(DELETE_ANNOTATION)) {
                holder.addDeleteMethod(method);
            }
        }
    }

    private boolean isInMapperPackage(JClass jClass) {
        String name = jClass.getName();
        int lastIndex = name.lastIndexOf('.');

        if (lastIndex == -1) {
            return false;
        }

        String packageName = name.substring(0, lastIndex);

        for (String pattern : GlobalState.MAPPER_PACKAGES) {
            String regex = pattern.replace(".", "\\.")
                    .replace("**", "*")
                    .replace("\"", "");
            if (packageName.matches(regex)) {
                return true;
            }
        }

        return false;
    }
}
