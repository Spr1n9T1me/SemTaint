package com.semtaint.frame.detector;

import com.semtaint.frame.persistence.MapperModel;
import com.semtaint.frame.proxy.AOPClassModel;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Sets;

import java.util.*;

/**
 *
 * @see com.semtaint.frame.detector.config.XmlConfigHolder
 * @see com.semtaint.frame.detector.annotation.AnnotationsHolder
 */
public class FrameworkConfigHolder {

    // ================================================================
    // 一、IOC — XML Bean 定义
    // ================================================================

    /** Spring Bean 定义 (beanId -> className)，来自 &lt;bean id="..." class="..."/&gt; */
    private final Map<String, String> beanDefinitions = new HashMap<>();

    /** 属性引用注入 (beanId -> (propName -> refBeanId))，来自 &lt;property name="..." ref="..."/&gt; */
    private final Map<String, Map<String, String>> beanPropertyRefs = new HashMap<>();

    /** 构造器引用注入 (beanId -> [refBeanId...])，来自 &lt;constructor-arg ref="..."/&gt; */
    private final Map<String, List<String>> beanConstructorRefs = new HashMap<>();

    /** 属性字面量注入 (beanId -> (propName -> value))，来自 &lt;property name="..." value="..."/&gt; */
    private final Map<String, Map<String, String>> beanPropertyValues = new HashMap<>();

    /** 构造器字面量注入 (beanId -> [value...])，来自 &lt;constructor-arg value="..."/&gt; */
    private final Map<String, List<String>> beanConstructorValues = new HashMap<>();

    /** factory-method 记录 (beanId -> factoryMethodName) */
    private final Map<String, String> beanFactoryMethods = new HashMap<>();

    /** 被其他 Bean 引用过的 beanId 集合（快速查询"谁被依赖"） */
    private final Set<String> injectedBeanIds = new HashSet<>();

    /** &lt;context:component-scan base-package="..."/&gt; 声明的扫描包路径 */
    private final Set<String> componentScanPackages = new HashSet<>();

    // ================================================================
    // 二、IOC — 注解驱动 Bean
    // ================================================================

    /** 应用程序所有类（入口类的超集） */
    public Set<JClass> appClasses = Sets.newSet();

    /** 原始注解映射（保留为通用扩展用途） */
    @SuppressWarnings("rawtypes")
    public Map annotations = Maps.newMap();

    /** @Component / @Service / @Repository / @Controller 类 */
    public Set<JClass> beanClasses = Sets.newSet();

    /** 被识别为框架入口的类 */
    public Set<JClass> entryClasses = Sets.newSet();

    /** 被识别为框架入口的方法 */
    public Set<JMethod> entryMethods = Sets.newSet();

    /** @Autowired / @Inject / @Resource 注入字段 */
    public Set<JField> injectedField = Sets.newSet();

    /** @Value 注入字段 */
    public Set<JField> valueInjectedField = Sets.newSet();

    /** 含注入参数的方法（构造器注入 / Setter 注入） */
    public Set<JMethod> injectedParamMethod = Sets.newSet();

    // ================================================================
    // 三、AOP — XML 配置
    // ================================================================

    /** &lt;aop:aspect&gt; 内的 advice 模型列表 */
    private final List<XmlAopModel> aopConfigs = new ArrayList<>();

    /** &lt;aop:advisor advice-ref="..." pointcut-ref="..."/&gt; 模型列表（声明式事务） */
    private final List<XmlAdvisorModel> advisorConfigs = new ArrayList<>();

    // ================================================================
    // 四、AOP — 注解驱动
    // ================================================================

    /** @Aspect 类的完整 AOP 模型 */
    public Set<AOPClassModel> aopClassModels = Sets.newSet();

    /** @Pointcut 方法的原始表达式 (declaring class -> expression) */
    public Map<JClass, String> pointCutsRawValue = Maps.newMap();

    // ================================================================
    // 五、Persistence — XML MyBatis 配置
    // ================================================================

    /** MyBatis Mapper XML 文件映射 (namespace -> filePath) */
    private final Map<String, String> myBatisMappers = new HashMap<>();

    /** Mapper 方法列表 (namespace -> [methodId...]) */
    private final Map<String, List<String>> mapperMethods = new HashMap<>();

    /** Mapper 方法操作类型 (namespace -> methodId -> select|insert|update|delete) */
    private final Map<String, Map<String, String>> mapperMethodTypes = new HashMap<>();

    // ================================================================
    // 六、Persistence — 注解驱动
    // ================================================================

    /** @Mapper 类 */
    public Set<JClass> mapperClasses = Sets.newSet();

    /** 注解驱动的 Mapper 模型集合 */
    public Set<MapperModel> mapperModels = Sets.newSet();

    /** @Select 方法 */
    public Set<JMethod> selectMethods = Sets.newSet();

    /** @Insert 方法 */
    public Set<JMethod> insertMethods = Sets.newSet();

    /** @Update 方法 */
    public Set<JMethod> updateMethods = Sets.newSet();

    /** @Delete 方法 */
    public Set<JMethod> deleteMethods = Sets.newSet();

    // ================================================================
    // 七、Lifecycle & Web
    // ================================================================

    /** @PostConstruct 方法 */
    public Set<JMethod> postConstructMethods = Sets.newSet();

    /** @PreDestroy 方法 */
    public Set<JMethod> preDestroyMethods = Sets.newSet();

    /** @Scheduled 方法 (declaring class -> methods) */
    public Map<JClass, Set<JMethod>> scheduledMethods = Maps.newMap();

    /** Web Servlet 类 (@WebServlet 等) */
    public Set<JClass> webServletClasses = Sets.newSet();

    // ================================================================
    // 构造器
    // ================================================================

    public FrameworkConfigHolder() {
    }

    // ================================================================
    // IOC XML — Mutators
    // ================================================================

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

    public void addComponentScan(String basePackage) {
        if (basePackage != null && !basePackage.isEmpty()) {
            componentScanPackages.add(basePackage);
        }
    }

    // ================================================================
    // AOP XML — Mutators
    // ================================================================

    public void addAopConfig(XmlAopModel model) {
        if (model != null) aopConfigs.add(model);
    }

    public void addAdvisorConfig(XmlAdvisorModel model) {
        if (model != null) advisorConfigs.add(model);
    }

    // ================================================================
    // Persistence XML — Mutators
    // ================================================================

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

    // ================================================================
    // IOC 注解 — Mutators
    // ================================================================

    public void addBean(JClass jClass) {
        if (jClass != null) beanClasses.add(jClass);
    }

    public void addEntryClass(JClass jClass) {
        entryClasses.add(jClass);
    }

    public void addEntryMethod(JMethod jMethod) {
        entryMethods.add(jMethod);
    }

    public void addInjectedField(JField jField) {
        injectedField.add(jField);
    }

    public void addValueInjectedField(JField jField) {
        valueInjectedField.add(jField);
    }

    public void addInjectedParamMethod(JMethod jMethod) {
        injectedParamMethod.add(jMethod);
    }

    // ================================================================
    // AOP 注解 — Mutators
    // ================================================================

    public void addAOPClassModel(AOPClassModel aopClassModel) {
        aopClassModels.add(aopClassModel);
    }

    // ================================================================
    // Persistence 注解 — Mutators
    // ================================================================

    public void addMapperClass(JClass jClass) {
        mapperClasses.add(jClass);
    }

    public void addMapperModel(MapperModel model) {
        mapperModels.add(model);
    }

    public void addSelectMethod(JMethod jMethod) {
        selectMethods.add(jMethod);
    }

    public void addInsertMethod(JMethod jMethod) {
        insertMethods.add(jMethod);
    }

    public void addUpdateMethod(JMethod jMethod) {
        updateMethods.add(jMethod);
    }

    public void addDeleteMethod(JMethod jMethod) {
        deleteMethods.add(jMethod);
    }

    // ================================================================
    // Lifecycle & Web — Mutators
    // ================================================================

    public void addPostConstructMethod(JMethod jMethod) {
        postConstructMethods.add(jMethod);
    }

    public void addPreDestroyMethod(JMethod jMethod) {
        preDestroyMethods.add(jMethod);
    }

    public void addScheduledMethod(JClass declaringClass, JMethod jMethod) {
        scheduledMethods.computeIfAbsent(declaringClass, k -> Sets.newSet()).add(jMethod);
    }

    public void addWebServletClass(JClass jClass) {
        webServletClasses.add(jClass);
    }

    // ================================================================
    // Accessors（private final 字段的只读视图）
    // ================================================================

    public Map<String, String> getBeanDefinitions() {
        return Collections.unmodifiableMap(beanDefinitions);
    }

    public Map<String, Map<String, String>> getBeanPropertyRefs() {
        return Collections.unmodifiableMap(beanPropertyRefs);
    }

    public Map<String, List<String>> getBeanConstructorRefs() {
        return Collections.unmodifiableMap(beanConstructorRefs);
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

    public Set<String> getInjectedBeanIds() {
        return Collections.unmodifiableSet(injectedBeanIds);
    }

    public Set<String> getComponentScanPackages() {
        return Collections.unmodifiableSet(componentScanPackages);
    }

    public List<XmlAopModel> getAopConfigs() {
        return Collections.unmodifiableList(aopConfigs);
    }

    public List<XmlAdvisorModel> getAdvisorConfigs() {
        return Collections.unmodifiableList(advisorConfigs);
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

    // ================================================================
    // 统计信息
    // ================================================================

    public void printStatistics() {
        System.out.println("\n========== FrameworkConfigHolder Statistics ==========");

        System.out.println("\n  [IOC — XML]");
        System.out.println("    Bean Definitions:              " + beanDefinitions.size());
        System.out.println("    Factory Methods:               " + beanFactoryMethods.size());
        System.out.println("    Property Injections (ref):     " + beanPropertyRefs.size());
        System.out.println("    Property Injections (value):   " + beanPropertyValues.size());
        System.out.println("    Constructor Injections (ref):  " + beanConstructorRefs.size());
        System.out.println("    Constructor Injections (value):" + beanConstructorValues.size());
        System.out.println("    Component Scan Packages:       " + componentScanPackages.size());

        System.out.println("\n  [IOC — Annotation]");
        System.out.println("    App Classes:                   " + appClasses.size());
        System.out.println("    Bean Classes:                  " + beanClasses.size());
        System.out.println("    Entry Classes:                 " + entryClasses.size());
        System.out.println("    Entry Methods:                 " + entryMethods.size());
        System.out.println("    Injected Fields:               " + injectedField.size());
        System.out.println("    Value-Injected Fields:         " + valueInjectedField.size());
        System.out.println("    Injected Param Methods:        " + injectedParamMethod.size());

        System.out.println("\n  [AOP — XML]");
        System.out.println("    AOP Configurations:            " + aopConfigs.size());
        System.out.println("    AOP Advisors:                  " + advisorConfigs.size());

        System.out.println("\n  [AOP — Annotation]");
        System.out.println("    AOP Class Models:              " + aopClassModels.size());
        System.out.println("    Pointcut Expressions:          " + pointCutsRawValue.size());

        System.out.println("\n  [Persistence — XML]");
        System.out.println("    MyBatis Mappers:               " + myBatisMappers.size());
        System.out.println("    Mapper Methods:                " + mapperMethods.values().stream().mapToInt(List::size).sum());

        System.out.println("\n  [Persistence — Annotation]");
        System.out.println("    Mapper Classes:                " + mapperClasses.size());
        System.out.println("    Mapper Models:                 " + mapperModels.size());
        System.out.println("    Select Methods:                " + selectMethods.size());
        System.out.println("    Insert Methods:                " + insertMethods.size());
        System.out.println("    Update Methods:                " + updateMethods.size());
        System.out.println("    Delete Methods:                " + deleteMethods.size());

        System.out.println("\n  [Lifecycle & Web]");
        System.out.println("    PostConstruct Methods:         " + postConstructMethods.size());
        System.out.println("    PreDestroy Methods:            " + preDestroyMethods.size());
        System.out.println("    Scheduled Methods (classes):   " + scheduledMethods.size());
        System.out.println("    Web Servlet Classes:           " + webServletClasses.size());
    }
    /**
     * AOP 切面模型，对应 &lt;aop:aspect&gt; 内的 advice 配置。
     */
    public static class XmlAopModel {
        public String aspectBeanRef; // 切面类的 Bean ID
        public String pointcutExpr;  // 切点表达式
        public String adviceType;    // before / after / around / after-returning / after-throwing
        public String method;        // 切面方法名
        public String pointcutId;    // 若引用了 pointcut-ref

        public XmlAopModel(String aspectBeanRef, String pointcutExpr, String adviceType, String method) {
            this.aspectBeanRef = aspectBeanRef;
            this.pointcutExpr  = pointcutExpr;
            this.adviceType    = adviceType;
            this.method        = method;
        }

        @Override
        public String toString() {
            return String.format("AOP[aspect=%s, type=%s, method=%s, pointcut=%s]",
                    aspectBeanRef, adviceType, method, pointcutExpr);
        }
    }

    /**
     * AOP Advisor 模型，对应 &lt;aop:advisor advice-ref="..." pointcut-ref="..."/&gt;。
     * 通常与 &lt;tx:advice&gt; 配合实现声明式事务管理。
     */
    public static class XmlAdvisorModel {
        public String adviceRef;    // 引用的 advice bean ID（如 txAdvice）
        public String pointcutRef;  // 引用的 pointcut bean ID
        public String pointcutExpr; // 内联 pointcut 表达式（若直接声明）

        public XmlAdvisorModel(String adviceRef, String pointcutRef, String pointcutExpr) {
            this.adviceRef    = adviceRef;
            this.pointcutRef  = pointcutRef;
            this.pointcutExpr = pointcutExpr;
        }

        @Override
        public String toString() {
            return String.format("Advisor[adviceRef=%s, pointcutRef=%s, pointcut=%s]",
                    adviceRef, pointcutRef, pointcutExpr);
        }
    }
}
