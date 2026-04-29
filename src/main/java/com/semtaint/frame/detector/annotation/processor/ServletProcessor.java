package com.semtaint.frame.detector.annotation.processor;

import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import pascal.taie.language.classes.JClass;

/**
 * Servlet
 */
public class ServletProcessor implements AnnotationProcessor {

    private static final String WEB_SERVLET_ANNOTATION = "javax.servlet.annotation.WebServlet";

    @Override
    public void processClass(JClass jClass, AnnotationsHolder holder) {
        boolean isWebServlet = jClass.getAnnotations().stream()
                .anyMatch(a -> a.getType().equals(WEB_SERVLET_ANNOTATION));

        if (isWebServlet) {
            holder.addWebServletClass(jClass);
        }
    }
}
