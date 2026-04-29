package com.semtaint.frame.detector;

import com.semtaint.config.ConfManager;
import com.semtaint.config.GlobalState;
import com.semtaint.frame.detector.annotation.AnnotationAnalysis;
import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import com.semtaint.frame.detector.config.XmlConfigAnalysis;
import com.semtaint.frame.detector.config.XmlConfigHolder;
import pascal.taie.World;
import pascal.taie.analysis.ProgramAnalysis;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.language.classes.JClass;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 框架检测器 - 作为 ProgramAnalysis 的统一入口
 * 负责协调注解分析和 XML 配置解析，构建完整的框架模型
 *
 * 对应 SemTaint 论文 4.2 节的 Framework Modeling 阶段
 *
 * <p>架构设计：</p>
 * <ul>
 *   <li>FrameworkDetector：唯一的 ProgramAnalysis，负责整体协调</li>
 *   <li>AnnotationAnalysis：注解分析组件，处理 Spring/MyBatis 注解</li>
 *   <li>XmlConfigAnalysis：XML 配置检测组件，解析 Spring/MyBatis XML</li>
 *   <li>DetectingHolder：统一的结果持有者，合并两个组件的结果</li>
 * </ul>
 *
 * @author springtime
 */
public class FrameworkDetector extends ProgramAnalysis<DetectingHolder> {

    public static final String ID = "framework-detector";

    // 两个组件
    private final AnnotationAnalysis annotationAnalysis;
    private final XmlConfigAnalysis xmlConfigAnalysis;

    public FrameworkDetector(AnalysisConfig config) {
        super(config);

        // 构建 projectRoots：从 app-class-path（List）和 app-app-lib-path（String）收集路径，
        // 若路径包含 BOOT-INF 或 WEB-INF，则截取其之前的部分；否则直接使用原路径，最终去重合并。
        Set<String> rootSet = new LinkedHashSet<>();
//        path.app-class-path 已经在Driver被连接为String了
        String[] classPaths = ConfManager.v().getString("path.app-class-path").split(";");
        for (String p : classPaths) {
            rootSet.add(extractProjectRoot(p));
        }

        String libPath = ConfManager.v().getString("path.app-lib-path", "");
        if (!libPath.isEmpty()) {
            rootSet.add(extractProjectRoot(libPath));
        }

        List<String> projectRoots = new ArrayList<>(rootSet);

        // 初始化两个组件
        this.annotationAnalysis = new AnnotationAnalysis();
        this.xmlConfigAnalysis = new XmlConfigAnalysis(projectRoots);
    }

    /**
     * 根据 app.package-name（如 com.myapp）对应的路径片段（如 /com/myapp/）提取项目根路径。
     */
    private static String extractProjectRoot(String path) {
        String normalizedPath = path.replace('\\', '/');
        String packageName = ConfManager.v().getString("app.package-name", "").trim();

        if (packageName.isEmpty()) {
            return path;
        }

        String packagePath = packageName.replace('.', '/');
        String packageMarker = "/" + packagePath + "/";

        int idx = normalizedPath.indexOf(packageMarker);
        if (idx >= 0) {
            return idx == 0 ? "." : path.substring(0, idx);
        }

        // 兼容路径以包路径开头，或路径正好等于包路径
        if (normalizedPath.startsWith(packagePath + "/") || normalizedPath.equals(packagePath)) {
            return ".";
        }

        // 兼容路径以 /com/myapp 结尾（无尾随 /）
        String endingMarker = "/" + packagePath;
        if (normalizedPath.endsWith(endingMarker)) {
            int endIdx = normalizedPath.length() - endingMarker.length();
            return endIdx == 0 ? "." : path.substring(0, endIdx);
        }

        return path;
    }

    /**
     * 执行完整的框架检测流程（ProgramAnalysis 接口方法）
     *
     * <p>流程：</p>
     * <ol>
     *   <li>执行注解分析（AnnotationAnalysis）</li>
     *   <li>执行 XML 配置检测（XmlConfigAnalysis）</li>
     *   <li>将两种来源的配置合并到 DetectingHolder</li>
     *   <li>保存结果到全局状态</li>
     * </ol>
     *
     * @return DetectingHolder 包含完整的框架检测结果
     */
    @Override
    public DetectingHolder analyze() {
        System.out.println("\n========== Framework Detection Start ==========");
        System.out.println("[FrameworkDetector] Starting framework detection...");

        // 创建统一的结果持有者
        DetectingHolder holder = new DetectingHolder();

        // 第一步：执行注解分析
        System.out.println("\n[Phase 1] Annotation Analysis...");
        annotationAnalysis.analyze(holder.getAnnotationsHolder());
        System.out.println("[Phase 1] Annotation analysis completed");

        // 第二步：执行 XML 配置检测
        System.out.println("\n[Phase 2] XML Configuration Detection...");
        XmlConfigHolder xmlResult = xmlConfigAnalysis.detect();
        // 将 XML 结果合并到 holder
        mergeXmlConfigToHolder(holder, xmlResult);
        System.out.println("[Phase 2] XML configuration detection completed");

        // 第三步：合并两种来源的配置
        mergeConfigurations(holder);

        // 第四步：将结果保存到全局状态
        saveToGlobalState(holder);

        // 打印统计信息
        holder.printStatistics();

        System.out.println("========== Framework Detection Complete ==========\n");

        return holder;
    }

    /**
     * 将 XML 检测结果合并到 DetectingHolder
     *
     * @param holder 目标持有者
     * @param xmlResult XML 检测结果
     */
    private void mergeXmlConfigToHolder(DetectingHolder holder, XmlConfigHolder xmlResult) {
        XmlConfigHolder targetHolder = holder.getXmlConfigHolder();

        // 合并 Bean 定义
        xmlResult.getBeanDefinitions().forEach(targetHolder::addBeanDefinition);

        // 合并属性注入
        xmlResult.getBeanPropertyRefs().forEach((beanId, props) ->
            props.forEach((prop, ref) -> targetHolder.addPropertyRef(beanId, prop, ref)));

        // 合并构造器注入
        xmlResult.getBeanConstructorRefs().forEach((beanId, refs) ->
            refs.forEach(ref -> targetHolder.addConstructorRef(beanId, ref)));

        // 合并 AOP 配置
        xmlResult.getAopConfigs().forEach(targetHolder::addAopConfig);

        // 合并组件扫描路径
        xmlResult.getComponentScanPackages().forEach(targetHolder::addComponentScan);

        // 合并属性值注入
        xmlResult.getBeanPropertyValues().forEach((beanId, props) ->
            props.forEach((prop, value) -> targetHolder.addPropertyValue(beanId, prop, value)));

        // 合并构造器值注入
        xmlResult.getBeanConstructorValues().forEach((beanId, values) ->
            values.forEach(value -> targetHolder.addConstructorValue(beanId, value)));

        // 合并工厂方法
        xmlResult.getBeanFactoryMethods().forEach(targetHolder::addFactoryMethod);

        // 合并 AOP Advisor 配置
        xmlResult.getAdvisorConfigs().forEach(targetHolder::addAdvisorConfig);

        // 合并 MyBatis Mapper
        xmlResult.getMyBatisMappers().forEach(targetHolder::addMapper);
        xmlResult.getMapperMethods().forEach((namespace, methods) ->
            methods.forEach(method -> targetHolder.addMapperMethod(namespace, method)));
    }

    /**
     * 合并 XML 和注解配置
     *
     * <p>功能：</p>
     * <ol>
     *   <li>将 XML 定义的 Bean 注册到 AnnotationsHolder</li>
     *   <li>将组件扫描路径添加到全局配置</li>
     * </ol>
     *
     * @param holder 检测结果持有者
     */
    private void mergeConfigurations(DetectingHolder holder) {
        AnnotationsHolder annotationsHolder = holder.getAnnotationsHolder();
        XmlConfigHolder xmlConfigHolder = holder.getXmlConfigHolder();

        int mergedBeans = 0;
        int mergedScans = 0;

        // 1. 将 XML 定义的 Bean 注册到 AnnotationsHolder
        for (String className : xmlConfigHolder.getBeanDefinitions().values()) {
            try {
                JClass jClass = World.get().getClassHierarchy().getClass(className);
                if (jClass != null) {
                    annotationsHolder.addBean(jClass);
                    mergedBeans++;
                }
            } catch (Exception e) {
                System.err.println("[Warning] Failed to load XML-defined bean class: " + className);
            }
        }

        // 2. 将组件扫描路径添加到全局配置
        for (String scanPackage : xmlConfigHolder.getComponentScanPackages()) {
            GlobalState.MAPPER_PACKAGES.add(scanPackage);
            mergedScans++;
        }

        System.out.println(String.format("  - Merged %d XML-defined beans", mergedBeans));
        System.out.println(String.format("  - Merged %d component scan packages", mergedScans));
    }

    /**
     * 将检测结果保存到全局状态
     *
     * @param holder 检测结果持有者
     */
    private void saveToGlobalState(DetectingHolder holder) {
        // 保存 XML 配置到全局状态（供其他分析使用）
        GlobalState.setXmlConfigHolder(holder.getXmlConfigHolder());
        System.out.println("[FrameworkDetector] Results saved to GlobalState");
    }
}

