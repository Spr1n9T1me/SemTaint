package com.semtaint.frame.detector.config;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * XML 配置检测组件
 *
 * <p>职责：</p>
 * <ul>
 *   <li>递归遍历项目目录查找 XML 文件</li>
 *   <li>解析 Spring 配置（Bean、AOP、组件扫描）</li>
 *   <li>解析 MyBatis Mapper 配置</li>
 *   <li>提取框架配置特征并存储到 XmlConfigHolder</li>
 * </ul>
 *
 * <p>架构定位：</p>
 * <ul>
 *   <li>此类是 FrameworkDetector 的内部组件，不是独立的分析</li>
 *   <li>由 FrameworkDetector 调用并协调</li>
 *   <li>结果通过 XmlConfigHolder 传递给调用者</li>
 * </ul>
 *
 * <p>对应 SemTaint 论文 4.2 节中的 Configuration Model 构建阶段</p>
 *
 * @see XmlConfigHolder
 */
public class XmlConfigAnalysis {

    private final XmlConfigHolder holder = new XmlConfigHolder();
    private final List<String> projectRoots;
    private int totalXmlFiles = 0;
    private int successfullyParsed = 0;
    private int anonymousBeanCounter = 0;

    public XmlConfigAnalysis(List<String> projectRoots) {
        this.projectRoots = projectRoots;
    }

    /**
     * 执行检测的主入口
     * @return 包含所有解析结果的 XmlConfigHolder
     */
    public XmlConfigHolder detect() {
        System.out.println("[SemTaint] Start detecting XML configurations in: " + projectRoots);
        long startTime = System.currentTimeMillis();

        for (String projectRoot : projectRoots) {
            try (Stream<Path> paths = Files.walk(Paths.get(projectRoot))) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.toString().endsWith(".xml"))
                     .forEach(this::parseXmlFile);
            } catch (Exception e) {
                System.err.println("[Error] Failed to walk directory: " + projectRoot);
                e.printStackTrace();
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        System.out.println(String.format("[SemTaint] XML detection completed in %d ms", duration));
        System.out.println(String.format("  - Total XML files found: %d", totalXmlFiles));
        System.out.println(String.format("  - Successfully parsed: %d", successfullyParsed));

        holder.printStatistics();

        return holder;
    }

    /**
     * 解析单个 XML 文件
     */
    private void parseXmlFile(Path xmlPath) {
        totalXmlFiles++;

        try {
            File xmlFile = xmlPath.toFile();
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();

            // 关键：禁用 DTD 验证以避免联网和文件不存在问题
            // 这在静态分析环境中非常重要
            dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbFactory.setFeature("http://xml.org/sax/features/namespaces", false);
            dbFactory.setFeature("http://xml.org/sax/features/validation", false);

            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xmlFile);
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();
            String rootName = root.getNodeName();
            String rootLocal = localName(rootName);

            // rootName 可能格式: beans / beans:beans，统一通过 localName 去前缀
            if ("beans".equals(rootLocal)) {
                parseSpringConfig(root, xmlPath.toString());
            } else if ("mapper".equals(rootLocal)) {
                parseMyBatisMapper(root, xmlPath.toString());
            }
            // 可扩展：添加其他框架的配置文件类型

            successfullyParsed++;

        } catch (Exception e) {
            System.err.println("[Warning] Failed to parse XML: " + xmlPath + " Reason: " + e.getMessage());
        }
    }

    /**
     * 解析 Spring 配置文件 (beans, aop, context)
     * <p>
     * 支持以下 XML 命名空间形式：
     * <ul>
     *   <li>无前缀: &lt;bean&gt;, &lt;property&gt;, &lt;import&gt;</li>
     *   <li>带前缀: &lt;beans:bean&gt;, &lt;beans:property&gt;, &lt;beans:import&gt;</li>
     *   <li>嵌套: &lt;beans:beans profile="default"&gt;...&lt;/beans:beans&gt; (profile 块递归解析)</li>
     * </ul>
     *
     * @param root 根元素 (beans 或 beans:beans)
     * @param filePath XML 文件路径（保留用于日志或调试）
     */
    @SuppressWarnings("unused")
    private void parseSpringConfig(Element root, String filePath) {
        NodeList children = root.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            Element element = (Element) node;
            String local = localName(element.getTagName());

            switch (local) {
                // 1. 解析 Bean 定义: <bean> / <beans:bean>
                case "bean":
                    parseBeanDefinition(element);
                    break;

                // 2. 嵌套 beans 块: <beans:beans profile="..."> — 递归解析
                case "beans":
                    parseSpringConfig(element, filePath);
                    break;

                // 3. 解析组件扫描: <context:component-scan> / <component-scan>
                case "component-scan":
                    parseComponentScan(element);
                    break;

                // 4. 解析注解驱动标记: <context:annotation-config>
                case "annotation-config":
                    // 标记 annotation-config 已启用，暂无额外操作
                    break;

                // 5. AOP 配置: <aop:config>
                case "config":
                    if (element.getTagName().contains("aop")) {
                        parseAopConfig(element);
                    }
                    break;

                // 6. TX Advice: <tx:advice id="..." transaction-manager="...">
                case "advice":
                    if (element.getTagName().contains("tx")) {
                        parseTxAdvice(element);
                    }
                    break;

                // 7. util:map / util:list: <util:map id="...">
                case "map":
                    parseUtilMap(element);
                    break;
                case "list":
                    parseUtilList(element);
                    break;

                // 8. import 标签: <import> / <beans:import>
                case "import":
                    // 可以在这里递归解析引入的其他配置文件
                    // String resource = element.getAttribute("resource");
                    break;

                default:
                    break;
            }
        }
    }

    /**
     * 解析 &lt;bean&gt; / &lt;beans:bean&gt; 标签及其内部的依赖注入
     * <p>
     * 支持的依赖注入形式：
     * <ul>
     *   <li>属性注入 (Setter): &lt;property name="x" ref="y"/&gt;</li>
     *   <li>属性注入 (嵌套 ref): &lt;property name="x"&gt;&lt;ref bean="y"/&gt;&lt;/property&gt;</li>
     *   <li>属性注入 (value): &lt;property name="x" value="v"/&gt;</li>
     *   <li>属性注入 (嵌套 value): &lt;property name="x"&gt;&lt;value&gt;v&lt;/value&gt;&lt;/property&gt;</li>
     *   <li>构造器注入 (ref): &lt;constructor-arg ref="y"/&gt;</li>
     *   <li>构造器注入 (value): &lt;constructor-arg value="v"/&gt;</li>
     *   <li>工厂方法: factory-method="getInstance", factory-bean="fbId"</li>
     *   <li>匿名 Bean（无 id/name）: 生成合成标识符 anonymous#类名#序号</li>
     * </ul>
     */
    private void parseBeanDefinition(Element beanElem) {
        String id = beanElem.getAttribute("id");
        String name = beanElem.getAttribute("name");
        String className = beanElem.getAttribute("class");
        String factoryBean = beanElem.getAttribute("factory-bean");
        String factoryMethod = beanElem.getAttribute("factory-method");

        boolean hasClass = className != null && !className.isEmpty();
        boolean hasFactoryBean = factoryBean != null && !factoryBean.isEmpty();

        // 至少需要 class 或 factory-bean 之一才能注册 Bean
        if (!hasClass && !hasFactoryBean) return;

        // 使用 id 或 name 作为 Bean 标识符；匿名 Bean 生成合成标识符
        String beanId = (id != null && !id.isEmpty()) ? id
                : (name != null && !name.isEmpty()) ? name
                : null;
        if (beanId == null || beanId.isEmpty()) {
            String baseName = hasClass ? className : factoryBean;
            beanId = "anonymous#" + baseName + "#" + (anonymousBeanCounter++);
        }

        String effectiveClass = hasClass ? className : "#factory-of:" + factoryBean;
        holder.addBeanDefinition(beanId, effectiveClass);

        // 记录 factory-bean 为依赖引用（等同于一个 ref）
        if (hasFactoryBean) {
            holder.addPropertyRef(beanId, "#factory-bean", factoryBean);
        }

        // 记录 factory-method
        if (factoryMethod != null && !factoryMethod.isEmpty()) {
            holder.addFactoryMethod(beanId, factoryMethod);
        }

        // 遍历子元素解析依赖注入
        NodeList children = beanElem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;

            Element childElem = (Element) child;
            String childLocal = localName(childElem.getTagName());

            if ("property".equals(childLocal)) {
                parsePropertyElement(beanId, childElem);
            } else if ("constructor-arg".equals(childLocal)) {
                parseConstructorArgElement(beanId, childElem);
            }
        }
    }

    /**
     * 解析 &lt;property&gt; / &lt;beans:property&gt; 元素
     * 支持 ref/value 属性 和 嵌套的 &lt;ref&gt;/&lt;value&gt;/&lt;list&gt; 子元素
     */
    private void parsePropertyElement(String beanId, Element propElem) {
        String propName = propElem.getAttribute("name");
        if (propName == null || propName.isEmpty()) return;

        // 优先检查属性形式: <property name="x" ref="y"/> 或 <property name="x" value="v"/>
        String ref = propElem.getAttribute("ref");
        String value = propElem.getAttribute("value");

        if (ref != null && !ref.isEmpty()) {
            holder.addPropertyRef(beanId, propName, ref);
            return;
        }
        if (value != null && !value.isEmpty()) {
            holder.addPropertyValue(beanId, propName, value);
            return;
        }

        // 否则扫描子元素: <ref bean="..."/>, <value>...</value>, <list>, <bean>
        NodeList propChildren = propElem.getChildNodes();
        for (int j = 0; j < propChildren.getLength(); j++) {
            Node pChild = propChildren.item(j);
            if (pChild.getNodeType() != Node.ELEMENT_NODE) continue;

            Element pChildElem = (Element) pChild;
            String pChildLocal = localName(pChildElem.getTagName());

            if ("ref".equals(pChildLocal)) {
                // <ref bean="..."/> 或 <beans:ref bean="..."/>
                String refBean = pChildElem.getAttribute("bean");
                if (refBean != null && !refBean.isEmpty()) {
                    holder.addPropertyRef(beanId, propName, refBean);
                }
            } else if ("value".equals(pChildLocal)) {
                // <value>literal</value>
                String textContent = pChildElem.getTextContent();
                if (textContent != null && !textContent.trim().isEmpty()) {
                    holder.addPropertyValue(beanId, propName, textContent.trim());
                }
            } else if ("bean".equals(pChildLocal)) {
                // 内联匿名 Bean 定义
                parseBeanDefinition(pChildElem);
            } else if ("list".equals(pChildLocal)) {
                // <list> 内嵌 <ref>/<bean>/<value> 元素
                parseInlineList(beanId, propName, pChildElem);
            } else if ("props".equals(pChildLocal)) {
                // <props><prop key="k">v</prop></props>
                parsePropsElement(beanId, propName, pChildElem);
            }
        }
    }

    /**
     * 解析 &lt;constructor-arg&gt; / &lt;beans:constructor-arg&gt; 元素
     * 支持 ref/value 属性 和 嵌套 &lt;ref&gt;/&lt;value&gt; 子元素
     */
    private void parseConstructorArgElement(String beanId, Element argElem) {
        String ref = argElem.getAttribute("ref");
        String value = argElem.getAttribute("value");

        if (ref != null && !ref.isEmpty()) {
            holder.addConstructorRef(beanId, ref);
            return;
        }
        if (value != null && !value.isEmpty()) {
            holder.addConstructorValue(beanId, value);
            return;
        }

        // 扫描子元素
        NodeList argChildren = argElem.getChildNodes();
        for (int j = 0; j < argChildren.getLength(); j++) {
            Node aChild = argChildren.item(j);
            if (aChild.getNodeType() != Node.ELEMENT_NODE) continue;

            Element aChildElem = (Element) aChild;
            String aChildLocal = localName(aChildElem.getTagName());

            if ("ref".equals(aChildLocal)) {
                String refBean = aChildElem.getAttribute("bean");
                if (refBean != null && !refBean.isEmpty()) {
                    holder.addConstructorRef(beanId, refBean);
                }
            } else if ("value".equals(aChildLocal)) {
                String textContent = aChildElem.getTextContent();
                if (textContent != null && !textContent.trim().isEmpty()) {
                    holder.addConstructorValue(beanId, textContent.trim());
                }
            } else if ("bean".equals(aChildLocal)) {
                parseBeanDefinition(aChildElem);
            } else if ("list".equals(aChildLocal) || "set".equals(aChildLocal)) {
                // <constructor-arg><list><ref/><value/><bean/></list></constructor-arg>
                NodeList listItems = aChildElem.getChildNodes();
                for (int k = 0; k < listItems.getLength(); k++) {
                    Node li = listItems.item(k);
                    if (li.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element liElem = (Element) li;
                    String liLocal = localName(liElem.getTagName());
                    if ("ref".equals(liLocal)) {
                        String rb = liElem.getAttribute("bean");
                        if (rb != null && !rb.isEmpty()) holder.addConstructorRef(beanId, rb);
                    } else if ("value".equals(liLocal)) {
                        String tc = liElem.getTextContent();
                        if (tc != null && !tc.trim().isEmpty()) holder.addConstructorValue(beanId, tc.trim());
                    } else if ("bean".equals(liLocal)) {
                        parseBeanDefinition(liElem);
                    }
                }
            }
        }
    }

    /**
     * 解析 &lt;list&gt; 子元素中的 &lt;ref&gt;、&lt;bean&gt;、&lt;value&gt;
     */
    private void parseInlineList(String parentBeanId, String propName, Element listElem) {
        NodeList items = listElem.getChildNodes();
        for (int i = 0; i < items.getLength(); i++) {
            Node item = items.item(i);
            if (item.getNodeType() != Node.ELEMENT_NODE) continue;

            Element itemElem = (Element) item;
            String itemLocal = localName(itemElem.getTagName());

            if ("ref".equals(itemLocal)) {
                String refBean = itemElem.getAttribute("bean");
                if (refBean != null && !refBean.isEmpty()) {
                    holder.addPropertyRef(parentBeanId, propName, refBean);
                }
            } else if ("bean".equals(itemLocal)) {
                parseBeanDefinition(itemElem);
            } else if ("value".equals(itemLocal)) {
                // <list><value>literal</value></list>
                String textContent = itemElem.getTextContent();
                if (textContent != null && !textContent.trim().isEmpty()) {
                    holder.addPropertyValue(parentBeanId, propName, textContent.trim());
                }
            }
        }
    }

    /**
     * 解析 &lt;props&gt; / &lt;beans:props&gt; 元素中的 &lt;prop key="..."&gt; 子元素
     */
    private void parsePropsElement(String beanId, String propName, Element propsElem) {
        NodeList items = propsElem.getChildNodes();
        for (int i = 0; i < items.getLength(); i++) {
            Node item = items.item(i);
            if (item.getNodeType() != Node.ELEMENT_NODE) continue;
            Element itemElem = (Element) item;
            if (!"prop".equals(localName(itemElem.getTagName()))) continue;

            String key = itemElem.getAttribute("key");
            String val = itemElem.getTextContent();
            if (key != null && !key.isEmpty()) {
                holder.addPropertyValue(beanId, propName + "." + key,
                        val != null ? val.trim() : "");
            }
        }
    }

    /**
     * 解析 <context:component-scan> 标签
     */
    private void parseComponentScan(Element element) {
        String basePkg = element.getAttribute("base-package");
        if (basePkg != null && !basePkg.isEmpty()) {
            // 处理多个包（逗号或分号分隔）
            String[] packages = basePkg.split("[,;\\s]+");
            for (String pkg : packages) {
                if (!pkg.isEmpty()) {
                    holder.addComponentScan(pkg.trim());
                }
            }
        }
    }

    /**
     * 解析 &lt;aop:config&gt; 及其子标签
     * <p>支持解析：</p>
     * <ul>
     *   <li>&lt;aop:pointcut id="..." expression="..."/&gt;</li>
     *   <li>&lt;aop:aspect ref="..."&gt; 内的 before/after/around advice</li>
     *   <li>&lt;aop:advisor advice-ref="..." pointcut-ref="..."/&gt; (声明式事务等)</li>
     * </ul>
     */
    private void parseAopConfig(Element aopConfigElem) {
        // 1. 收集 pointcut 定义 (pointcut-id -> expression)
        Map<String, String> pointcutMap = new HashMap<>();
        NodeList allChildren = aopConfigElem.getChildNodes();
        for (int i = 0; i < allChildren.getLength(); i++) {
            Node node = allChildren.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            if ("pointcut".equals(localName(elem.getTagName()))) {
                String id = elem.getAttribute("id");
                String expression = elem.getAttribute("expression");
                if (id != null && !id.isEmpty() && expression != null && !expression.isEmpty()) {
                    pointcutMap.put(id, expression);
                }
            }
        }

        // 2. 解析 aspect 和 advisor
        for (int i = 0; i < allChildren.getLength(); i++) {
            Node node = allChildren.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String local = localName(elem.getTagName());

            if ("aspect".equals(local)) {
                parseAopAspect(elem, pointcutMap);
            } else if ("advisor".equals(local)) {
                parseAopAdvisor(elem, pointcutMap);
            }
        }
    }

    /**
     * 解析 &lt;aop:aspect ref="..."&gt; 内的 advice 子标签
     */
    private void parseAopAspect(Element aspectElem, Map<String, String> pointcutMap) {
        String refBean = aspectElem.getAttribute("ref");

        NodeList children = aspectElem.getChildNodes();
        for (int j = 0; j < children.getLength(); j++) {
            Node node = children.item(j);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;

            Element advice = (Element) node;
            String type = localName(advice.getTagName());

            if (isAdviceTag(type)) {
                String method = advice.getAttribute("method");
                String pointcut = advice.getAttribute("pointcut");
                String pointcutRef = advice.getAttribute("pointcut-ref");

                String finalExpr = (pointcut != null && !pointcut.isEmpty())
                        ? pointcut
                        : pointcutMap.get(pointcutRef);

                if (method != null && !method.isEmpty() && finalExpr != null && !finalExpr.isEmpty()) {
                    XmlConfigHolder.XmlAopModel model = new XmlConfigHolder.XmlAopModel(
                            refBean, finalExpr, type, method
                    );
                    holder.addAopConfig(model);
                }
            }
        }
    }

    /**
     * 解析 &lt;aop:advisor advice-ref="..." pointcut-ref="..."/&gt;
     * 通常与 &lt;tx:advice&gt; 配合实现声明式事务
     */
    private void parseAopAdvisor(Element advisorElem, Map<String, String> pointcutMap) {
        String adviceRef = advisorElem.getAttribute("advice-ref");
        String pointcutRef = advisorElem.getAttribute("pointcut-ref");
        String pointcutExpr = pointcutMap.getOrDefault(pointcutRef, "");

        if (adviceRef != null && !adviceRef.isEmpty()) {
            XmlConfigHolder.XmlAdvisorModel model = new XmlConfigHolder.XmlAdvisorModel(
                    adviceRef, pointcutRef, pointcutExpr
            );
            holder.addAdvisorConfig(model);
        }
    }

    /**
     * 判断是否为 Advice 标签（已去掉命名空间前缀后的 localName）
     */
    private boolean isAdviceTag(String tag) {
        return Set.of("before", "after", "after-returning", "after-throwing", "around").contains(tag);
    }

    // ==================== TX / Util 标签解析 ====================

    /**
     * 解析 &lt;tx:advice id="..." transaction-manager="..."&gt;
     * <p>
     * 将 tx:advice 注册为一个伪 Bean，并记录其内部 &lt;tx:method&gt; 声明的事务方法模式。
     * 与 &lt;aop:advisor&gt; 配合可实现声明式事务切面的完整建模。
     */
    private void parseTxAdvice(Element txAdviceElem) {
        String id = txAdviceElem.getAttribute("id");
        String txManager = txAdviceElem.getAttribute("transaction-manager");

        if (id == null || id.isEmpty()) return;

        // 将 tx:advice 注册为伪 Bean 以记录 advice-ref 引用目标
        holder.addBeanDefinition(id, "org.springframework.transaction.interceptor.TransactionInterceptor");

        if (txManager != null && !txManager.isEmpty()) {
            holder.addPropertyRef(id, "transactionManager", txManager);
        }

        // 解析 <tx:attributes> -> <tx:method> 子元素
        NodeList attrsList = txAdviceElem.getChildNodes();
        for (int i = 0; i < attrsList.getLength(); i++) {
            Node attrNode = attrsList.item(i);
            if (attrNode.getNodeType() != Node.ELEMENT_NODE) continue;

            Element attrsElem = (Element) attrNode;
            if ("attributes".equals(localName(attrsElem.getTagName()))) {
                NodeList methods = attrsElem.getChildNodes();
                for (int j = 0; j < methods.getLength(); j++) {
                    Node mNode = methods.item(j);
                    if (mNode.getNodeType() != Node.ELEMENT_NODE) continue;

                    Element mElem = (Element) mNode;
                    if ("method".equals(localName(mElem.getTagName()))) {
                        String methodName = mElem.getAttribute("name");
                        String readOnly = mElem.getAttribute("read-only");
                        // 以 name=pattern 的形式记录到 propertyValues 中，
                        // key 格式 "tx:method:<pattern>"，值为 read-only 属性
                        if (methodName != null && !methodName.isEmpty()) {
                            holder.addPropertyValue(id, "tx:method:" + methodName,
                                    readOnly != null && !readOnly.isEmpty() ? readOnly : "false");
                        }
                    }
                }
            }
        }
    }

    /**
     * 解析 &lt;util:map id="..."&gt; 及其 &lt;entry key="..." value-ref="..."/&gt; 子元素
     * <p>
     * util:map 本身是一个顶层 Bean（类型为 java.util.HashMap），
     * 其 entry 的 value-ref 构成从 map-bean 到各 value-bean 的依赖注入关系。
     */
    private void parseUtilMap(Element mapElem) {
        String mapId = mapElem.getAttribute("id");
        if (mapId == null || mapId.isEmpty()) return;

        // map 本身是一个 Bean
        String mapClass = mapElem.getAttribute("map-class");
        if (mapClass == null || mapClass.isEmpty()) {
            mapClass = "java.util.HashMap";
        }
        holder.addBeanDefinition(mapId, mapClass);

        // 遍历 <entry> 子元素
        NodeList entries = mapElem.getChildNodes();
        for (int i = 0; i < entries.getLength(); i++) {
            Node eNode = entries.item(i);
            if (eNode.getNodeType() != Node.ELEMENT_NODE) continue;

            Element entry = (Element) eNode;
            if (!"entry".equals(localName(entry.getTagName()))) continue;

            String key = entry.getAttribute("key");
            String valueRef = entry.getAttribute("value-ref");

            if (key != null && !key.isEmpty() && valueRef != null && !valueRef.isEmpty()) {
                holder.addPropertyRef(mapId, key, valueRef);
            }
        }
    }

    /**
     * 解析 &lt;util:list id="..."&gt; 及其 &lt;ref bean="..."/&gt; 子元素
     * <p>
     * util:list 本身是一个顶层 Bean（类型为 java.util.ArrayList），
     * 其子 ref 元素构成从 list-bean 到各 value-bean 的依赖引用关系。
     */
    private void parseUtilList(Element listElem) {
        String listId = listElem.getAttribute("id");
        if (listId == null || listId.isEmpty()) return;

        String valueType = listElem.getAttribute("value-type");
        holder.addBeanDefinition(listId, valueType != null && !valueType.isEmpty()
                ? valueType : "java.util.ArrayList");

        NodeList items = listElem.getChildNodes();
        for (int i = 0; i < items.getLength(); i++) {
            Node iNode = items.item(i);
            if (iNode.getNodeType() != Node.ELEMENT_NODE) continue;

            Element item = (Element) iNode;
            String itemLocal = localName(item.getTagName());

            if ("ref".equals(itemLocal)) {
                String refBean = item.getAttribute("bean");
                if (refBean != null && !refBean.isEmpty()) {
                    holder.addPropertyRef(listId, "item:" + i, refBean);
                }
            } else if ("bean".equals(itemLocal)) {
                parseBeanDefinition(item);
            }
        }
    }

    // ==================== MyBatis Mapper 解析 ====================

    /**
     * 解析 MyBatis Mapper XML
     * 支持解析：
     * - namespace 属性
     * - <select>, <insert>, <update>, <delete> 标签的 id
     */
    private void parseMyBatisMapper(Element root, String filePath) {
        String namespace = root.getAttribute("namespace");
        if (namespace != null && !namespace.isEmpty()) {
            holder.addMapper(namespace, filePath);

            // 提取 SQL 方法 ID（同时记录操作类型 select/insert/update/delete）
            String[] sqlTags = {"select", "insert", "update", "delete"};
            for (String tag : sqlTags) {
                NodeList nodes = root.getElementsByTagName(tag);
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element sqlElem = (Element) nodes.item(i);
                    String methodId = sqlElem.getAttribute("id");
                    if (methodId != null && !methodId.isEmpty()) {
                        holder.addMapperMethodWithType(namespace, methodId, tag);
                    }
                }
            }
        }
    }

    /**
     * 获取解析结果
     */
    public XmlConfigHolder getHolder() {
        return holder;
    }

    // ==================== 工具方法 ====================

    /**
     * 去掉 XML 命名空间前缀，返回本地名称。
     * 例如: "beans:bean" -> "bean", "aop:config" -> "config", "bean" -> "bean"
     */
    private static String localName(String tagName) {
        int colonIdx = tagName.indexOf(':');
        return colonIdx >= 0 ? tagName.substring(colonIdx + 1) : tagName;
    }
}
