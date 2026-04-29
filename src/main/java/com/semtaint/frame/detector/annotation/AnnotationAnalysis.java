package com.semtaint.frame.detector.annotation;


import com.semtaint.config.ConfManager;
import com.semtaint.frame.detector.annotation.processor.*;
import com.semtaint.utils.TypeUtils;
import pascal.taie.World;
import pascal.taie.language.classes.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 注解分析组件 - 采用策略模式
 *
 * <p>职责：</p>
 * <ul>
 *   <li>遍历应用类并识别框架相关的注解</li>
 *   <li>将注解处理任务分发给各个专门的处理器</li>
 *   <li>收集并存储注解分析结果到 AnnotationsHolder</li>
 * </ul>
 *
 * <p>架构定位：</p>
 * <ul>
 *   <li>此类不是 ProgramAnalysis，而是 FrameworkDetector 的内部组件</li>
 *   <li>由 FrameworkDetector 调用并协调</li>
 *   <li>结果通过 AnnotationsHolder 传递给调用者</li>
 * </ul>
 *
 * @see AnnotationsHolder
 */
public class AnnotationAnalysis {

    private final ClassHierarchy hierarchy = World.get().getClassHierarchy();
    private final ConfManager config = ConfManager.v();

    // 注册所有注解处理器
    private final List<AnnotationProcessor> processors = List.of(
            new SpringBeanProcessor(),      // 处理 Spring Bean 注解
            new SpringWebProcessor(),       // 处理 Spring MVC 注解
            new AspectJProcessor(),         // 处理 AOP 注解
            new InjectionProcessor(),       // 处理依赖注入注解
            new MyBatisMapperProcessor(),   // 处理 MyBatis Mapper 注解
            new SpringDataRepositoryProcessor(), // 处理 Spring Data Repository 注解
            new LifecycleProcessor(),       // 处理生命周期注解
            new ServletProcessor()          // 处理 Servlet 注解
    );

    /**
     * 执行注解分析
     * @param holder 用于存储分析结果的持有者
     */
    public void analyze(AnnotationsHolder holder) {
        // 获取应用类
        Set<JClass> appClasses = World.get().getClassHierarchy().applicationClasses()
                .filter(jClass -> jClass.getName().contains(config.getString("app.package-name", "")))
                .collect(Collectors.toSet());

        // 直接赋值给 public 字段
        holder.appClasses = appClasses;

        // 统一遍历流程：分发给各个处理器
        for (JClass appClass : appClasses) {
            // 分发类级别注解处理
            processors.forEach(processor -> processor.processClass(appClass, holder));

            // 分发字段级别注解处理
            for (JField field : appClass.getDeclaredFields()) {
                processors.forEach(processor -> processor.processField(field, holder));
            }

            // 分发方法级别注解处理
            for (JMethod method : appClass.getDeclaredMethods()) {
                processors.forEach(processor -> processor.processMethod(method, holder));
            }
        }

        // 添加 Spring 内置注册的 Bean（非自定义）
//        addInheritedBean(holder);
    }

    /**
     * 添加 Spring 内置注册的 Bean（非自定义）
     */
    private void addInheritedBean(AnnotationsHolder holder) {
        TypeUtils.getImplsOrSubsOf(hierarchy.getClass(ClassNames.JPA_REPO)).forEach(holder::addBean);
        TypeUtils.getImplsOrSubsOf(hierarchy.getClass(ClassNames.HTTP_EXCHANGE_REPO)).forEach(holder::addBean);
        TypeUtils.getImplsOrSubsOf(hierarchy.getClass(ClassNames.APPLICATION_CONTEXT)).forEach(holder::addBean);
        TypeUtils.getImplsOrSubsOf(hierarchy.getClass(ClassNames.LIST)).forEach(holder::addBean);
        TypeUtils.getImplsOrSubsOf(hierarchy.getClass(ClassNames.MAP)).forEach(holder::addBean);
        holder.addBean(hierarchy.getClass(ClassNames.OBJECT_MAPPER));
        holder.addBean(hierarchy.getClass(ClassNames.DATA_SOURCE_PROPERTIES));
    }

    /**
     * 常用的类名常量
     */
    static class ClassNames {
        public static final String JPA_REPO = "org.springframework.data.jpa.repository.JpaRepository";
        public static final String HTTP_EXCHANGE_REPO = "org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository";
        public static final String APPLICATION_CONTEXT = "org.springframework.context.ApplicationContext";
        public static final String OBJECT_MAPPER = "com.fasterxml.jackson.databind.ObjectMapper";
        public static final String DATA_SOURCE_PROPERTIES = "org.springframework.boot.autoconfigure.jdbc.DataSourceProperties";
        public static final String LIST = "java.util.List";
        public static final String MAP = "java.util.Map";
    }
}
