# XML 配置解析框架重构文档

## 概述

本次重构完全按照 SemTaint 论文 4.2 节（Framework Modeling）的思路，将 XML 配置解析能力模块化，构建了一个清晰、可扩展的框架配置模型。

## 架构设计

### 核心组件

```
com.semtaint.frame.config/
├── XmlConfigHolder.java       # 配置数据缓存模型
├── XmlConfigDetector.java     # XML 解析检测器
└── XmlConfigUsageExample.java # 使用示例

com.semtaint.frame.detector/
├── FrameworkDetector.java     # 统一框架检测入口
├── XMLHolder.java             # 向后兼容的旧版持有者（已标记 @Deprecated）
└── processor/                 # 注解处理器（策略模式）
    ├── AnnotationProcessor.java
    ├── SpringBeanProcessor.java
    ├── SpringWebProcessor.java
    ├── AspectJProcessor.java
    ├── InjectionProcessor.java
    ├── MyBatisMapperProcessor.java
    ├── LifecycleProcessor.java
    └── ServletProcessor.java

com.semtaint.config/
└── GlobalState.java           # 全局状态管理
```

## 核心特性

### 1. XmlConfigHolder - 配置数据模型

负责缓存从 XML 解析的所有框架配置信息：

```java
// Bean 定义
Map<String, String> beanDefinitions;  // id -> class

// 依赖注入关系
Map<String, Map<String, String>> beanPropertyRefs;  // beanId -> (property -> ref)
Map<String, List<String>> beanConstructorRefs;      // beanId -> [refs]

// AOP 配置
List<XmlAopModel> aopConfigs;

// 组件扫描
Set<String> componentScanPackages;

// MyBatis Mapper
Map<String, String> myBatisMappers;           // namespace -> path
Map<String, List<String>> mapperMethods;      // namespace -> [methodIds]

// 依赖追踪
Set<String> injectedBeanIds;
```

**对应论文要点**：
- 恢复隐式数据流（Implicit Data Flow）：通过 `beanPropertyRefs` 知道哪个 Bean 被注入到哪个字段
- 支持 AOP 切点匹配：存储切面配置供后续 pointcut 解析使用
- MyBatis 映射：建立 Java 接口与 XML SQL 实现的对应关系

### 2. XmlConfigDetector - 文件遍历与解析

核心功能：
1. 递归遍历项目目录，查找所有 `.xml` 文件
2. 使用 Java 原生 DOM 解析器（无外部依赖）
3. 智能识别配置类型（Spring beans / MyBatis mapper）
4. 提取关键特征并存储到 `XmlConfigHolder`

**鲁棒性设计**：
```java
// 禁用 DTD 验证，避免联网和文件缺失问题
dbFactory.setFeature(
    "http://apache.org/xml/features/nonvalidating/load-external-dtd", 
    false
);
```

**支持的配置类型**：

#### Spring 配置
- `<bean id="..." class="...">` - Bean 定义
- `<property name="..." ref="...">` - Setter 注入
- `<constructor-arg ref="...">` - 构造器注入
- `<context:component-scan base-package="...">` - 组件扫描
- `<aop:config>` - AOP 配置
  - `<aop:aspect ref="...">`
  - `<aop:pointcut id="..." expression="...">`
  - `<aop:before/after/around>`

#### MyBatis 配置
- `<mapper namespace="...">` - Mapper 命名空间
- `<select/insert/update/delete id="...">` - SQL 方法

### 3. FrameworkDetector - 统一入口

协调注解分析和 XML 配置解析：

```java
FrameworkDetector detector = new FrameworkDetector(projectRoot);
detector.detect();

// XML 配置自动保存到全局状态
XmlConfigHolder xmlConfig = GlobalState.getXmlConfigHolder();
```

**集成流程**：
1. 执行 XML 配置检测
2. 将结果合并到全局状态（`GlobalState`）
3. 将 XML 定义的 Bean 注册到 `AnnotationsHolder`（统一处理）

## 使用指南

### 基本用法

```java
// 方式 1: 直接使用检测器
XmlConfigDetector detector = new XmlConfigDetector("/path/to/project");
XmlConfigHolder holder = detector.detect();

// 方式 2: 通过 FrameworkDetector（推荐）
FrameworkDetector frameworkDetector = new FrameworkDetector(projectPath);
frameworkDetector.detect();
XmlConfigHolder xmlConfig = GlobalState.getXmlConfigHolder();
```

### 查询配置信息

```java
// 查询 Bean 定义
xmlConfig.getBeanDefinitions().forEach((id, className) -> {
    System.out.println("Bean: " + id + " -> " + className);
});

// 查询依赖注入关系
xmlConfig.getBeanPropertyRefs().forEach((beanId, properties) -> {
    properties.forEach((prop, ref) -> {
        System.out.println(beanId + "." + prop + " -> " + ref);
    });
});

// 查询 AOP 配置
xmlConfig.getAopConfigs().forEach(aop -> {
    System.out.println("Aspect: " + aop.aspectBeanRef);
    System.out.println("  Pointcut: " + aop.pointcutExpr);
    System.out.println("  Advice: " + aop.adviceType + " -> " + aop.method);
});

// 查询 MyBatis Mapper
xmlConfig.getMyBatisMappers().forEach((namespace, path) -> {
    List<String> methods = xmlConfig.getMapperMethods()
            .getOrDefault(namespace, Collections.emptyList());
    System.out.println("Mapper: " + namespace + " [" + methods.size() + " methods]");
});
```

### 在污点分析中使用

```java
// 示例：识别通过 XML 配置注入的 DataSource
xmlConfig.getBeanPropertyRefs().forEach((beanId, properties) -> {
    properties.forEach((propName, refBeanId) -> {
        String refClass = xmlConfig.getBeanDefinitions().get(refBeanId);
        
        if (refClass != null && refClass.contains("DataSource")) {
            // 将此 property 标记为潜在的污点源
            markAsTaintSource(beanId, propName, refClass);
        }
    });
});
```

## 对比原实现

### 改进点

| 方面 | 原实现 | 新实现 |
|------|--------|--------|
| **架构** | 逻辑分散在 `SpringXmlParser` 中 | 清晰分层：Holder + Detector |
| **数据结构** | 简单的 Map 存储 | 结构化的配置模型 |
| **依赖注入** | 仅记录被注入的 Bean ID | 完整记录注入关系（bean -> property -> ref） |
| **AOP** | 需要额外处理 | 直接提供结构化的 `XmlAopModel` |
| **MyBatis** | 仅 namespace 映射 | 支持方法级别的映射 |
| **扩展性** | 硬编码逻辑 | 易于添加新的配置类型 |
| **鲁棒性** | 依赖外部 XML 库 | 原生 DOM，禁用 DTD 验证 |

### 向后兼容

旧的 `XMLHolder` 类被标记为 `@Deprecated`，但保留了接口：

```java
@Deprecated
public class XMLHolder {
    // 保留原有字段和方法
    
    // 新增转换方法
    public void fillFromXmlConfigHolder(XmlConfigHolder configHolder) {
        // 将新模型数据填充到旧结构
    }
}
```

## 对应论文的设计点

### SemTaint 论文 4.2 节关键点

| 论文要求 | 实现位置 |
|---------|---------|
| **文件遍历** | `XmlConfigDetector.detect()` 使用 `Files.walk` |
| **DI 关系提取** | `parseBeanDefinition()` 提取 `<property>` 和 `<constructor-arg>` |
| **AOP 元数据收集** | `parseAopConfig()` 解析切面和切点 |
| **持久化框架支持** | `parseMyBatisMapper()` 建立 namespace 到方法的映射 |
| **隐式数据流恢复** | `beanPropertyRefs` 存储 Bean 间的引用关系 |
| **DTD 问题处理** | `setFeature(..., false)` 禁用外部 DTD 加载 |

### 核心优势

1. **无需网络连接**：禁用 DTD 验证，可在离线环境运行
2. **容错性强**：单个 XML 解析失败不影响整体
3. **统计可见**：提供详细的解析统计信息
4. **查询友好**：所有数据都提供 `Collections.unmodifiable*` 的只读视图
5. **易于测试**：每个组件职责单一，便于单元测试

## 性能考虑

- **惰性加载**：只在需要时执行检测
- **流式遍历**：使用 `Files.walk` 避免一次性加载所有文件
- **统计输出**：提供性能分析数据
  ```
  [SemTaint] XML detection completed in 1523 ms
    - Total XML files found: 45
    - Successfully parsed: 43
  ```

## 扩展指南

### 添加新的配置类型支持

1. 在 `parseXmlFile()` 中添加根元素识别：
```java
else if ("custom-config".equals(rootName)) {
    parseCustomConfig(root, xmlPath.toString());
}
```

2. 在 `XmlConfigHolder` 中添加相应的数据结构
3. 实现解析方法

### 添加更多 XML 特征

例如，解析 Spring 的 `<import>` 标签实现配置文件递归：

```java
else if ("import".equals(tagName)) {
    String resource = element.getAttribute("resource");
    Path importPath = resolvePath(filePath, resource);
    parseXmlFile(importPath);
}
```

## 测试建议

参考 `XmlConfigUsageExample.java` 中的示例：

1. 准备测试 XML 文件
2. 运行检测器
3. 验证解析结果的正确性
4. 检查统计数据的准确性

## 总结

本次重构实现了：
- ✅ 符合 SemTaint 论文 4.2 节的设计思想
- ✅ 清晰的架构分层（Holder + Detector）
- ✅ 完整的 Spring 和 MyBatis 配置支持
- ✅ 鲁棒的错误处理和统计信息
- ✅ 易于扩展的设计模式
- ✅ 向后兼容旧代码
- ✅ 丰富的使用示例和文档

这为后续的污点分析和指针分析提供了坚实的框架配置基础。
