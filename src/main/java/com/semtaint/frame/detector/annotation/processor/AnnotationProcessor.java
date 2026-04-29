package com.semtaint.frame.detector.annotation.processor;

import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;


public interface AnnotationProcessor {


    default void processClass(JClass jClass, AnnotationsHolder holder) {}


    default void processMethod(JMethod jMethod, AnnotationsHolder holder) {}

    default void processField(JField jField, AnnotationsHolder holder) {}
}
