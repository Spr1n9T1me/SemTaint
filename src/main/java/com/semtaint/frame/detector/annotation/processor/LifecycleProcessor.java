package com.semtaint.frame.detector.annotation.processor;

import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import pascal.taie.language.classes.JMethod;

/**
 *@PostConstruct, @PreDestroy
 */
public class LifecycleProcessor implements AnnotationProcessor {

    private static final String POST_CONSTRUCT_ANNOTATION = "jakarta.annotation.PostConstruct";
    private static final String PRE_DESTROY_ANNOTATION = "jakarta.annotation.PreDestroy";

    @Override
    public void processMethod(JMethod jMethod, AnnotationsHolder holder) {
        jMethod.getAnnotations().forEach(annotation -> {
            switch (annotation.getType()) {
                case POST_CONSTRUCT_ANNOTATION:
                    holder.addPostConstructMethod(jMethod);
                    break;
                case PRE_DESTROY_ANNOTATION:
                    holder.addPreDestroyMethod(jMethod);
                    break;
            }
        });
    }
}
