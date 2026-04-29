package com.semtaint.frame.detector.annotation.processor;

import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import com.semtaint.utils.TypeUtils;
import pascal.taie.World;
import pascal.taie.language.annotation.ArrayElement;
import pascal.taie.language.annotation.Element;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Spring Bean @Component, @Service, @Repository, @Configuration, @Bean
 */
public class SpringBeanProcessor implements AnnotationProcessor {

    private static final Set<String> BEAN_ANNOTATIONS = Set.of(
        "org.springframework.stereotype.Component",
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Repository",
        "org.springframework.context.annotation.Configuration",
            "org.springframework.boot.context.properties.ConfigurationProperties"
    );

    @Override
    public void processClass(JClass jClass, AnnotationsHolder holder) {
        boolean isBean = jClass.getAnnotations().stream()
                .anyMatch(a -> BEAN_ANNOTATIONS.contains(a.getType()));

        if (isBean) {
            if (jClass.isInterface() || jClass.isAbstract()) {
                TypeUtils.getImplsOrSubsOf(jClass).forEach(impl -> {
                    String name = toLowerCamelCase(impl.getSimpleName());
                    holder.addBean(impl);
                    holder.getBeanManager().registerPending(impl, name);
                });
            } else {
                holder.addBean(jClass);
                holder.getBeanManager().registerPending(jClass, toLowerCamelCase(jClass.getSimpleName()));
            }
        }
    }

    @Override
    public void processMethod(JMethod jMethod, AnnotationsHolder holder) {
        boolean hasBean = jMethod.getAnnotations().stream()
                .anyMatch(a -> a.getType().equals("org.springframework.context.annotation.Bean"));

        if (hasBean && jMethod.getDeclaringClass().hasAnnotation("org.springframework.context.annotation.Configuration")) {
            JClass returnTypeClass = World.get().getClassHierarchy()
                    .getClass(jMethod.getReturnType().getName());
            if (returnTypeClass != null) {
                holder.addBean(returnTypeClass);
                holder.getBeanManager().registerPendingBeanMethod(returnTypeClass, extractBeanNames(jMethod), jMethod);
            }
        }
    }

    private List<String> extractBeanNames(JMethod method) {
        var annotation = method.getAnnotation("org.springframework.context.annotation.Bean");
        if (annotation == null) {
            return List.of(method.getName());
        }
        Element name = annotation.getElement("name");
        Element value = annotation.getElement("value");
        List<String> names = new ArrayList<>();
        collectNames(names, name);
        collectNames(names, value);
        return names.isEmpty() ? List.of(method.getName()) : names;
    }

    private void collectNames(List<String> names, Element element) {
        if (element == null) {
            return;
        }
        if (element instanceof ArrayElement arrayElement) {
            arrayElement.elements().stream()
                    .map(Element::toString)
                    .map(this::normalizeString)
                    .filter(s -> !s.isEmpty())
                    .forEach(names::add);
            return;
        }
        String single = normalizeString(element.toString());
        if (!single.isEmpty()) {
            names.add(single);
        }
    }

    private String normalizeString(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\"", "").trim();
    }

    private String toLowerCamelCase(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return simpleName;
        }
        if (simpleName.length() == 1) {
            return simpleName.toLowerCase();
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
