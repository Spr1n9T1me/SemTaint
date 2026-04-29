package com.semtaint.frame.detector.annotation.processor;

import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import pascal.taie.World;
import pascal.taie.frontend.newfrontend.java.LambdaManager;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

/**
 * Spring Web
 * <p></p>
 * <ol>
 *   <li>@Controller / @RestController / @RequestMapping /li>
 *   <li>@ControllerAdvice / @Configuration /li>
 *   <li>javax.servlet.Filter </li>
 *   <li>InitializingBean  afterPropertiesSet()</li>
 *   <li>@Scheduled p</li>
 * </ol>
 *
 */
public class SpringWebProcessor implements AnnotationProcessor {

    private static final String CONTROLLER           = "org.springframework.stereotype.Controller";
    private static final String REST_CONTROLLER      = "org.springframework.web.bind.annotation.RestController";
    private static final String REQUEST_MAPPING      = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String CONTROLLER_ADVICE    = "org.springframework.web.bind.annotation.ControllerAdvice";
    private static final String CONFIGURATION        = "org.springframework.context.annotation.Configuration";

    private static final String SCHEDULED            = "org.springframework.scheduling.annotation.Scheduled";
    private static final String GET_MAPPING          = "org.springframework.web.bind.annotation.GetMapping";
    private static final String POST_MAPPING         = "org.springframework.web.bind.annotation.PostMapping";
    private static final String PUT_MAPPING          = "org.springframework.web.bind.annotation.PutMapping";
    private static final String DELETE_MAPPING       = "org.springframework.web.bind.annotation.DeleteMapping";
    private static final String PATCH_MAPPING        = "org.springframework.web.bind.annotation.PatchMapping";

    private static final String SERVLET_FILTER       = "javax.servlet.Filter";
    private static final String INITIALIZING_BEAN    = "org.springframework.beans.factory.InitializingBean";
    private static final String AFTER_PROPERTIES_SET = "afterPropertiesSet";

    @Override
    public void processClass(JClass jClass, AnnotationsHolder holder) {
        if (jClass.isAbstract() || jClass.isInterface()) {
            return;
        }

        // 1. @Controller / @RestController / @RequestMapping
        if (jClass.hasAnnotation(CONTROLLER)
                || jClass.hasAnnotation(REST_CONTROLLER)
                || jClass.hasAnnotation(REQUEST_MAPPING)
                || jClass.getSimpleName().toLowerCase().endsWith("controller")) {
            holder.addEntryClasses(jClass);
            holder.addBean(jClass);
            addAllDeclaredMethods(jClass, holder);
        }

        // 2. @ControllerAdvice / @Configuration
        if (jClass.hasAnnotation(CONTROLLER_ADVICE) || jClass.hasAnnotation(CONFIGURATION)) {
            addAllDeclaredMethods(jClass, holder);
        }

        // 3. javax.servlet.Filter
        if (implementsType(jClass, SERVLET_FILTER)) {
            addAllDeclaredMethods(jClass, holder);
        }

        // 4. InitializingBean abstract afterPropertiesSet()
        if (implementsType(jClass, INITIALIZING_BEAN)) {
            jClass.getDeclaredMethods().stream()
                    .filter(m -> !m.isAbstract() && m.getName().equals(AFTER_PROPERTIES_SET))
                    .forEach(holder::addEntryMethod);
        }
    }


    @Override
    public void processMethod(JMethod jMethod, AnnotationsHolder holder) {
        JClass declaringClass = jMethod.getDeclaringClass();

        if (jMethod.isAbstract() || declaringClass.isInterface()) {
            return;
        }

        // @Scheduled
        if (jMethod.hasAnnotation(SCHEDULED)) {
            holder.addScheduledMethod(declaringClass, jMethod);
        }

        if (hasMappingAnnotation(jMethod)) {
            holder.addEntryMethod(jMethod);
        }
    }

    private boolean hasMappingAnnotation(JMethod jMethod) {
        return jMethod.hasAnnotation(REQUEST_MAPPING)
            || jMethod.hasAnnotation(GET_MAPPING)
            || jMethod.hasAnnotation(POST_MAPPING)
            || jMethod.hasAnnotation(PUT_MAPPING)
            || jMethod.hasAnnotation(DELETE_MAPPING)
            || jMethod.hasAnnotation(PATCH_MAPPING);
    }


    private void addAllDeclaredMethods(JClass jClass, AnnotationsHolder holder) {
        jClass.getDeclaredMethods().stream()
                .filter(m -> !m.isAbstract() && !m.isConstructor())
                .filter(m -> !m.getName().contains("lambda$")) // no lambda
                .forEach(holder::addEntryMethod);
    }

    private boolean implementsType(JClass jClass, String typeName) {
        JClass target = World.get().getClassHierarchy().getClass(typeName);
        if (target == null) {
            return false;
        }
        return World.get().getClassHierarchy().isSubclass(target, jClass);
    }
}
