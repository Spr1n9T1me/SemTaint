package com.semtaint.frame.detector.config;

import java.util.*;

/**
 * - Spring Bean 定义和依赖注入关系
 * - AOP 配置（切面、切点、通知）
 * - 组件扫描包路径
 * - MyBatis Mapper 映射
 */
public class XmlConfigHolder {

    // 1. Spring Bean 定义缓存 (BeanName -> BeanClass)
    // 用于处理 <bean id="..." class="...">
    private final Map<String, String> beanDefinitions = new HashMap<>();

    // 2. 依赖注入关系缓存 (BeanName -> Map<FieldName, RefBeanName>)
    // 用于处理 <property name="..." ref="...">
    // 这是恢复隐式数据流（Implicit Data Flow）的关键
    private final Map<String, Map<String, String>> beanPropertyRefs = new HashMap<>();

    // 2.1 构造器注入缓存 (BeanName -> List<RefBeanName>)
    // 用于处理 <constructor-arg ref="...">
    private final Map<String, List<String>> beanConstructorRefs = new HashMap<>();

    // 3. AOP 配置缓存
    // 存储 <aop:config> 解析出的切面信息
    private final List<XmlAopModel> aopConfigs = new ArrayList<>();

    // 4. 组件扫描包路径
    // 对应 <context:component-scan base-package="...">
    private final Set<String> componentScanPackages = new HashSet<>();

    // 5. MyBatis Mapper 映射 (Namespace -> XmlFilePath)
    // 对应 <mapper namespace="...">
    private final Map<String, String> myBatisMappers = new HashMap<>();

    // 5.1 MyBatis Mapper 方法映射 (Namespace -> List<MethodId>)
    // 存储 Mapper 中定义的 SQL 方法
    private final Map<String, List<String>> mapperMethods = new HashMap<>();

    // 5.2 MyBatis Mapper 方法操作类型 (Namespace -> MethodId -> opType)
    // opType 为 XML 标签名：select | insert | update | delete
    private final Map<String, Map<String, String>> mapperMethodTypes = new HashMap<>();

    // 6. 被注入的 Bean ID 集合（用于快速查询哪些 Bean 被其他 Bean 依赖）
    private final Set<String> injectedBeanIds = new HashSet<>();

    // 7. 属性字面量值注入 (BeanName -> Map<PropName, Value>)
    // 用于处理 <property name="..." value="..."> 和 <property name="..."><value>...</value></property>
    private final Map<String, Map<String, String>> beanPropertyValues = new HashMap<>();

    // 7.1 构造器字面量值注入 (BeanName -> List<Value>)
    // 用于处理 <constructor-arg value="..."> 或 <constructor-arg><value>...</value></constructor-arg>
    private final Map<String, List<String>> beanConstructorValues = new HashMap<>();

    // 8. factory-method / factory-bean 属性 (BeanName -> factory-method)
    // 用于处理 factory-method="getInstance" 等工厂模式（CmsImageFileManagerImpl 等单例工厂）
    private final Map<String, String> beanFactoryMethods = new HashMap<>();

    // 9. AOP Advisor 配置（<aop:advisor advice-ref="..." pointcut-ref="..."/>）
    // 通常与 <tx:advice> 配合使用，实现声明式事务切面
    private final List<XmlAdvisorModel> advisorConfigs = new ArrayList<>();

    // ==================== Bean Definition Methods ====================

    public void addBeanDefinition(String id, String className) {
        if (id != null && !id.isEmpty() && className != null && !className.isEmpty()) {
            beanDefinitions.put(id, className);
        }
    }

    public void addPropertyRef(String beanId, String property, String ref) {
        if (beanId != null && property != null && ref != null) {
            beanPropertyRefs.computeIfAbsent(beanId, k -> new HashMap<>()).put(property, ref);
            injectedBeanIds.add(ref);
        }
    }

    public void addConstructorRef(String beanId, String ref) {
        if (beanId != null && ref != null) {
            beanConstructorRefs.computeIfAbsent(beanId, k -> new ArrayList<>()).add(ref);
            injectedBeanIds.add(ref);
        }
    }

    public void addPropertyValue(String beanId, String property, String value) {
        if (beanId != null && property != null && value != null) {
            beanPropertyValues.computeIfAbsent(beanId, k -> new HashMap<>()).put(property, value);
        }
    }

    public void addConstructorValue(String beanId, String value) {
        if (beanId != null && value != null) {
            beanConstructorValues.computeIfAbsent(beanId, k -> new ArrayList<>()).add(value);
        }
    }

    public void addFactoryMethod(String beanId, String factoryMethod) {
        if (beanId != null && factoryMethod != null && !factoryMethod.isEmpty()) {
            beanFactoryMethods.put(beanId, factoryMethod);
        }
    }

    // ==================== AOP Configuration Methods ====================

    public void addAopConfig(XmlAopModel model) {
        if (model != null) {
            aopConfigs.add(model);
        }
    }

    public void addAdvisorConfig(XmlAdvisorModel model) {
        if (model != null) {
            advisorConfigs.add(model);
        }
    }

    // ==================== Component Scan Methods ====================

    public void addComponentScan(String basePackage) {
        if (basePackage != null && !basePackage.isEmpty()) {
            componentScanPackages.add(basePackage);
        }
    }

    // ==================== MyBatis Mapper Methods ====================

    public void addMapper(String namespace, String path) {
        if (namespace != null && !namespace.isEmpty()) {
            myBatisMappers.put(namespace, path);
        }
    }

    public void addMapperMethod(String namespace, String methodId) {
        addMapperMethodWithType(namespace, methodId, null);
    }

    /**
     * 记录 Mapper 方法并附带操作类型（来自 XML 标签名）。
     *
     * @param opType XML 标签名：select | insert | update | delete，可为 null
     */
    public void addMapperMethodWithType(String namespace, String methodId, String opType) {
        if (namespace != null && methodId != null) {
            mapperMethods.computeIfAbsent(namespace, k -> new ArrayList<>()).add(methodId);
            if (opType != null) {
                mapperMethodTypes.computeIfAbsent(namespace, k -> new HashMap<>()).put(methodId, opType);
            }
        }
    }

    // ==================== Accessors for Analysis ====================

    public Map<String, String> getBeanDefinitions() {
        return Collections.unmodifiableMap(beanDefinitions);
    }

    public Map<String, Map<String, String>> getBeanPropertyRefs() {
        return Collections.unmodifiableMap(beanPropertyRefs);
    }

    public Map<String, List<String>> getBeanConstructorRefs() {
        return Collections.unmodifiableMap(beanConstructorRefs);
    }

    public List<XmlAopModel> getAopConfigs() {
        return Collections.unmodifiableList(aopConfigs);
    }

    public Set<String> getComponentScanPackages() {
        return Collections.unmodifiableSet(componentScanPackages);
    }

    public Map<String, String> getMyBatisMappers() {
        return Collections.unmodifiableMap(myBatisMappers);
    }

    public Map<String, List<String>> getMapperMethods() {
        return Collections.unmodifiableMap(mapperMethods);
    }

    /** 返回 namespace → (methodId → opType) 的只读视图。 */
    public Map<String, Map<String, String>> getMapperMethodTypes() {
        return Collections.unmodifiableMap(mapperMethodTypes);
    }

    public Set<String> getInjectedBeanIds() {
        return Collections.unmodifiableSet(injectedBeanIds);
    }

    public Map<String, Map<String, String>> getBeanPropertyValues() {
        return Collections.unmodifiableMap(beanPropertyValues);
    }

    public Map<String, List<String>> getBeanConstructorValues() {
        return Collections.unmodifiableMap(beanConstructorValues);
    }

    public Map<String, String> getBeanFactoryMethods() {
        return Collections.unmodifiableMap(beanFactoryMethods);
    }

    public List<XmlAdvisorModel> getAdvisorConfigs() {
        return Collections.unmodifiableList(advisorConfigs);
    }

    // ==================== Statistics Methods ====================

    public void printStatistics() {
        System.out.println("\n[XmlConfigHolder] Statistics:");
        System.out.println("  - Bean Definitions: " + beanDefinitions.size());
        System.out.println("  - Factory Methods: " + beanFactoryMethods.size());
        System.out.println("  - Property Injections (ref):   " + beanPropertyRefs.size());
        System.out.println("  - Property Injections (value): " + beanPropertyValues.size());
        System.out.println("  - Constructor Injections (ref):   " + beanConstructorRefs.size());
        System.out.println("  - Constructor Injections (value): " + beanConstructorValues.size());
        System.out.println("  - AOP Configurations: " + aopConfigs.size());
        System.out.println("  - AOP Advisors: " + advisorConfigs.size());
        System.out.println("  - Component Scan Packages: " + componentScanPackages.size());
        System.out.println("  - MyBatis Mappers: " + myBatisMappers.size());
    }

    /**
     * AOP 模型定义
     * 用于存储从 XML 中解析的 AOP 配置信息
     */
    public static class XmlAopModel {
        public String aspectBeanRef; // 切面类的 Bean ID
        public String pointcutExpr;  // 切点表达式
        public String adviceType;    // before, after, around, after-returning, after-throwing
        public String method;        // 切面方法名
        public String pointcutId;    // 如果引用了 pointcut-ref

        public XmlAopModel(String aspectBeanRef, String pointcutExpr, String adviceType, String method) {
            this.aspectBeanRef = aspectBeanRef;
            this.pointcutExpr = pointcutExpr;
            this.adviceType = adviceType;
            this.method = method;
        }

        @Override
        public String toString() {
            return String.format("AOP[aspect=%s, type=%s, method=%s, pointcut=%s]",
                    aspectBeanRef, adviceType, method, pointcutExpr);
        }
    }

    /**
     * AOP Advisor 模型定义
     * 对应 &lt;aop:advisor advice-ref="..." pointcut-ref="..."/&gt;
     * 通常与 &lt;tx:advice&gt; 配合，用于声明式事务管理
     */
    public static class XmlAdvisorModel {
        public String adviceRef;    // 引用的 advice bean ID（如 txAdvice）
        public String pointcutRef;  // 引用的 pointcut bean ID
        public String pointcutExpr; // 内联 pointcut 表达式（若直接声明）

        public XmlAdvisorModel(String adviceRef, String pointcutRef, String pointcutExpr) {
            this.adviceRef = adviceRef;
            this.pointcutRef = pointcutRef;
            this.pointcutExpr = pointcutExpr;
        }

        @Override
        public String toString() {
            return String.format("Advisor[adviceRef=%s, pointcutRef=%s, pointcut=%s]",
                    adviceRef, pointcutRef, pointcutExpr);
        }
    }
}
