package com.semtaint.frame.detector.annotation.processor;

import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import com.semtaint.frame.proxy.AOPClassModel;
import com.semtaint.frame.proxy.AdviceModel;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AspectJ AOP  @Aspect, @Pointcut, @Before, @After, @Around
 */
public class AspectJProcessor implements AnnotationProcessor {

    private static final String ASPECT_ANNOTATION = "org.aspectj.lang.annotation.Aspect";
    private static final String ORDER_ANNOTATION = "org.aspectj.lang.annotation.Order";
    private static final String POINTCUT_ANNOTATION = "org.aspectj.lang.annotation.Pointcut";
    private static final String BEFORE_ANNOTATION = "org.aspectj.lang.annotation.Before";
    private static final String AFTER_ANNOTATION = "org.aspectj.lang.annotation.After";
    private static final String AFTER_RETURNING_ANNOTATION = "org.aspectj.lang.annotation.AfterReturning";
    private static final String AFTER_THROWING_ANNOTATION = "org.aspectj.lang.annotation.AfterThrowing";
    private static final String AROUND_ANNOTATION = "org.aspectj.lang.annotation.Around";

    @Override
    public void processClass(JClass jClass, AnnotationsHolder holder) {
        boolean isAspect = jClass.getAnnotations().stream()
                .anyMatch(a -> a.getType().equals(ASPECT_ANNOTATION));

        if (isAspect) {
            AOPClassModel aopModel = parseAopModel(jClass);
            holder.addAOPClassModel(aopModel);
        }
    }

    private AOPClassModel parseAopModel(JClass jClass) {
        AOPClassModel aopModel = new AOPClassModel();
        aopModel.setProxyClass(jClass);
        List<AdviceModel> advices = aopModel.getAdvices();
        Map<JMethod, String> pointCutRawValue = aopModel.getPointCutRawValue();
        List<String> pointCutAlias = aopModel.getPointCutAlias();

        jClass.getAnnotations().stream()
                .filter(annotation -> annotation.getType().equals(ORDER_ANNOTATION))
                .forEach(annotation -> {
                    var orderElement = annotation.getElement("value");
                    if (orderElement != null) {
                        aopModel.setOrder(Integer.parseInt(orderElement.toString()));
                    }
                });

        jClass.getDeclaredMethods().forEach(method -> {
            method.getAnnotations().forEach(annotation -> {
                HashMap<String, String> annotationValueMap = new HashMap<>();
                annotation.getElementEntries().forEach(entry -> {
                    annotationValueMap.put(entry.name(), entry.element().toString().replace("\"", ""));
                });

                if (annotationValueMap.isEmpty()) {
                    return;
                }

                String value;
                if (annotationValueMap.get("pointcut") != null) {
                    value = annotationValueMap.get("pointcut");
                } else {
                    value = annotationValueMap.get("value");
                }

                switch (annotation.getType()) {
                    case POINTCUT_ANNOTATION:
                        pointCutRawValue.put(method, annotationValueMap.get("value"));
                        pointCutAlias.add(method.getName());
                        break;
                    case BEFORE_ANNOTATION:
                        advices.add(new AdviceModel(method, AdviceModel.AdviceEnum.BEFORE, value));
                        break;
                    case AFTER_ANNOTATION:
                        advices.add(new AdviceModel(method, AdviceModel.AdviceEnum.AFTER, value));
                        break;
                    case AFTER_RETURNING_ANNOTATION:
                        advices.add(new AdviceModel(method, AdviceModel.AdviceEnum.AFTER_RETURNING, value));
                        break;
                    case AFTER_THROWING_ANNOTATION:
                        advices.add(new AdviceModel(method, AdviceModel.AdviceEnum.AFTER_THROWING, value));
                        break;
                    case AROUND_ANNOTATION:
                        advices.add(new AdviceModel(method, AdviceModel.AdviceEnum.AROUND, value));
                        break;
                }
            });
        });

        aopModel.setAdvices(advices);
        return aopModel;
    }
}
