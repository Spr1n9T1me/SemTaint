package com.semtaint.frame.detector;

import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import com.semtaint.frame.detector.config.XmlConfigHolder;

/**
 * @author springtime
 * @see FrameworkDetector
 * @see AnnotationsHolder
 * @see XmlConfigHolder
 */
public class DetectingHolder {

    private final AnnotationsHolder annotationsHolder;
    private final XmlConfigHolder xmlConfigHolder;

    public DetectingHolder() {
        this.annotationsHolder = new AnnotationsHolder();
        this.xmlConfigHolder = new XmlConfigHolder();
    }

    /**
     * 获取注解分析结果
     * @return AnnotationsHolder 包含所有注解分析的结果
     */
    public AnnotationsHolder getAnnotationsHolder() {
        return annotationsHolder;
    }

    /**
     * 获取 XML 配置解析结果
     * @return XmlConfigHolder 包含所有 XML 配置解析的结果
     */
    public XmlConfigHolder getXmlConfigHolder() {
        return xmlConfigHolder;
    }

    /**
     * 打印完整的统计信息
     * 包括注解分析统计、XML 配置统计和汇总统计
     */
    public void printStatistics() {
        System.out.println("\n========== Framework Detection Statistics ==========");

        // 注解分析统计
        System.out.println("\n[Annotation Analysis]");
        System.out.println("  - App Classes: " + annotationsHolder.getAppClasses().size());
        System.out.println("  - Bean Classes: " + annotationsHolder.getBeanClasses().size());
        System.out.println("  - Entry Methods: " + annotationsHolder.getEntryMethods().size());
        System.out.println("  - Injected Fields: " + annotationsHolder.getInjectedField().size());
        System.out.println("  - Value Injected Fields: " + annotationsHolder.getValueInjectedField().size());
        System.out.println("  - AOP Class Models: " + annotationsHolder.getAopClassModels().size());
        System.out.println("  - Mapper Classes: " + annotationsHolder.getMapperClasses().size());
        System.out.println("  - PostConstruct Methods: " + annotationsHolder.getPostConstructMethods().size());
        System.out.println("  - PreDestroy Methods: " + annotationsHolder.getPreDestroyMethods().size());
        System.out.println("  - WebServlet Classes: " + annotationsHolder.getWebServletClasses().size());

        // XML 配置统计
        System.out.println("\n[XML Configuration]");
        System.out.println("  - Bean Definitions: " + xmlConfigHolder.getBeanDefinitions().size());
        System.out.println("  - Property Injections: " + xmlConfigHolder.getBeanPropertyRefs().size());
        System.out.println("  - Constructor Injections: " + xmlConfigHolder.getBeanConstructorRefs().size());
        System.out.println("  - AOP Configurations: " + xmlConfigHolder.getAopConfigs().size());
        System.out.println("  - Component Scan Packages: " + xmlConfigHolder.getComponentScanPackages().size());
        System.out.println("  - MyBatis Mappers: " + xmlConfigHolder.getMyBatisMappers().size());

        System.out.println("\n====================================================\n");
    }
}
