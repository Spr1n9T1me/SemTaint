package com.semtaint.frame.detector.annotation.processor;

import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import pascal.taie.World;
import pascal.taie.language.annotation.Annotation;
import pascal.taie.language.annotation.Element;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Modifier;
import pascal.taie.language.type.Type;

import java.util.Set;

/**
  \@Autowired, @Resource, @Inject, @Value
 */
public class InjectionProcessor implements AnnotationProcessor {

    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
        "org.springframework.beans.factory.annotation.Autowired",
        "javax.annotation.Resource",
        "javax.inject.Inject",
        "javax.inject.Qualifier"
    );

    private static final String VALUE_ANNOTATION = "org.springframework.beans.factory.annotation.Value";
    private static final String JPA_REPO = "org.springframework.data.jpa.repository.JpaRepository";
    private static final String QUALIFIER_ANNOTATION = "org.springframework.beans.factory.annotation.Qualifier";
    private static final String RESOURCE_ANNOTATION = "org.springframework.beans.factory.annotation.Resource";

    @Override
    public void processField(JField jField, AnnotationsHolder holder) {
        boolean hasInjection = jField.getAnnotations().stream()
                .anyMatch(a -> INJECTION_ANNOTATIONS.contains(a.getType()));

        if (hasInjection) {
            holder.addInjectedField(jField);
            String qualifier = extractQualifierFromField(jField);
            if (qualifier == null || qualifier.isEmpty()) {
                qualifier = jField.getName();
            }
            holder.setFieldQualifier(jField, qualifier);
        }

        boolean hasValue = jField.getAnnotations().stream()
                .anyMatch(a -> a.getType().equals(VALUE_ANNOTATION));

        if (hasValue) {
            holder.addValueInjectedField(jField);
        }
    }

    @Override
    public void processMethod(JMethod jMethod, AnnotationsHolder holder) {
        boolean hasMethodInjection = jMethod.getAnnotations().stream()
                .anyMatch(a -> a.getType().equals("org.springframework.beans.factory.annotation.Autowired")
                        || a.getType().equals("javax.inject.Inject"));

        boolean isConstructorInjection = jMethod.isConstructor() && (
                jMethod.getDeclaringClass().hasAnnotation("org.springframework.context.annotation.Configuration")
                || holder.getEntryClasses().contains(jMethod.getDeclaringClass())
        );

        if (hasMethodInjection || isConstructorInjection) {
            processMethodInjection(jMethod, holder);
            extractParamQualifiers(jMethod, holder);
        }
    }


    private void processMethodInjection(JMethod method, AnnotationsHolder holder) {
        JClass declaringClass = method.getDeclaringClass();

        for (int i = 0; i < method.getParamCount(); i++) {
            if (method.getParamAnnotations(i).stream()
                    .anyMatch(a -> a.getType().equals(VALUE_ANNOTATION))) {
                continue;
            }

            Type paramType = method.getParamType(i);

            declaringClass.getDeclaredFields().stream()
                    .filter(field -> {
                        if (field.getType().equals(paramType)) {
                            if (field.getModifiers().contains(Modifier.FINAL)) {
                                return true;
                            }
                            JClass fieldTypeClass = World.get().getClassHierarchy()
                                    .getClass(field.getType().getName());
                            if (fieldTypeClass != null) {
                                JClass superClass = fieldTypeClass.getSuperClass();
                                if (superClass != null && superClass.getName().equals(JPA_REPO)) {
                                    return true;
                                }
                            }

                            return true;
                        }
                        return false;
                    })
                    .forEach(holder::addInjectedField);
        }
    }

    private String extractQualifierFromField(JField field) {
        Annotation qualifierAnno = field.getAnnotation(QUALIFIER_ANNOTATION);
        if (qualifierAnno != null) {
            String value = normalizeElement(qualifierAnno.getElement("value"));
            if (!value.isEmpty()) {
                return value;
            }
        }
        Annotation resourceAnno = field.getAnnotation(RESOURCE_ANNOTATION);
        if (resourceAnno != null) {
            String name = normalizeElement(resourceAnno.getElement("name"));
            if (!name.isEmpty()) {
                return name;
            }
        }
        return null;
    }

    private void extractParamQualifiers(JMethod method, AnnotationsHolder holder) {
        for (int i = 0; i < method.getParamCount(); i++) {
            String qualifier = null;
            for (Annotation annotation : method.getParamAnnotations(i)) {
                if (QUALIFIER_ANNOTATION.equals(annotation.getType())) {
                    qualifier = normalizeElement(annotation.getElement("value"));
                    break;
                }
                if (RESOURCE_ANNOTATION.equals(annotation.getType())) {
                    qualifier = normalizeElement(annotation.getElement("name"));
                    break;
                }
            }
            if (qualifier != null && !qualifier.isEmpty()) {
                holder.setMethodParamQualifier(method, i, qualifier);
            }
        }
    }

    private String normalizeElement(Element element) {
        if (element == null) {
            return "";
        }
        return element.toString().replace("\"", "").trim();
    }
}
