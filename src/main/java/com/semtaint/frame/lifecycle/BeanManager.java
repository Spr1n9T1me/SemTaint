package com.semtaint.frame.lifecycle;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.flowgraph.FlowKind;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Sets;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

public class BeanManager {

    private static final Logger logger = LogManager.getLogger(BeanManager.class);

    private final Map<String, Bean> name2Bean = new HashMap<>();
    private final MultiMap<JClass, Bean> class2Beans = Maps.newMultiMap();
    private final Map<Pointer, Bean> pointer2Bean = new HashMap<>();

    private final List<PendingBean> pendingBeans = new ArrayList<>();
    private final List<PendingBeanMethod> pendingBeanMethods = new ArrayList<>();

    private Solver runtimeSolver;

    public void attachSolver(Solver solver) {
        this.runtimeSolver = solver;
    }

    /**
     * Clears solver-specific runtime state that should not be reused
     * across multiple analysis runs (e.g., CI pre-analysis -> precise analysis).
     */
    public void resetRuntimeState() {
        pointer2Bean.clear();
        for (Bean bean : getAllBeans()) {
            bean.resetPointer();
            pointer2Bean.put(bean.getPointer(), bean);
        }
    }

    public void registerPending(JClass beanClass, String beanName) {
        if (beanClass == null || beanName == null || beanName.isEmpty()) {
            return;
        }
        if (pendingBeans.stream().noneMatch(p -> p.beanClass().equals(beanClass) && p.beanName().equals(beanName))) {
            pendingBeans.add(new PendingBean(beanClass, beanName));
        }
    }

    public void registerPendingBeanMethod(JClass returnClass, List<String> beanNames, JMethod method) {
        if (returnClass == null || beanNames == null || beanNames.isEmpty() || method == null) {
            return;
        }
        pendingBeanMethods.add(new PendingBeanMethod(returnClass, List.copyOf(beanNames), method));
    }

    public void materializeAll(Solver solver) {
        for (PendingBean pendingBean : pendingBeans) {
            createIfAbsent(pendingBean.beanClass(), pendingBean.beanName(), solver);
        }

        for (PendingBeanMethod pendingBeanMethod : pendingBeanMethods) {
            JClass returnClass = pendingBeanMethod.returnClass();
            List<String> beanNames = pendingBeanMethod.beanNames();
            Bean bean = name2Bean.get(beanNames.get(0));
            if (bean == null) {
                bean = new Bean(returnClass, beanNames.get(0), null, null);
                registerBean(beanNames.get(0), bean);
            }
            pendingBeanMethod.method().getIR().getReturnVars().forEach(bean::addInEdge);
            for (String alias : beanNames) {
                name2Bean.putIfAbsent(alias, bean);
            }
        }
    }

    public Bean createIfAbsent(JClass beanClass, String beanName, Solver solver) {
        if (beanClass == null || beanName == null || beanName.isEmpty()) {
            return null;
        }
        beanClass = resolveConcreteImpl(beanClass);
        Bean existingByName = name2Bean.get(beanName);
        if (existingByName != null) {
            return existingByName;
        }
        Set<Bean> existingByClass = class2Beans.get(beanClass);
        if (!existingByClass.isEmpty()) {
            return existingByClass.iterator().next();
        }
        JMethod ctor = pickConstructor(beanClass);
        Obj mockObj = solver.getHeapModel()
                .getMockObj(() -> "SpringBeanObj", beanName, beanClass.getType());
        Bean bean = new Bean(beanClass, beanName, ctor, mockObj);
        registerBean(beanName, bean);
        return bean;
    }

    public Bean createWithMockObj(JClass beanClass, String beanName,
                                  @Nullable JMethod constructor, Obj mockObj) {
        if (beanClass == null || beanName == null || beanName.isEmpty()) {
            return null;
        }
        Bean existingByName = name2Bean.get(beanName);
        if (existingByName != null) {
            return existingByName;
        }
        Set<Bean> existingByClass = class2Beans.get(beanClass);
        if (!existingByClass.isEmpty()) {
            return existingByClass.iterator().next();
        }
        Bean bean = new Bean(beanClass, beanName, constructor, mockObj);
        registerBean(beanName, bean);
        return bean;
    }

    public void addClassHierarchyIndex(JClass clz, Bean bean) {
        if (clz != null && bean != null) {
            addClassHierarchyTree(clz, bean);
        }
    }

    private void registerBean(String beanName, Bean bean) {
        name2Bean.put(beanName, bean);
        pointer2Bean.put(bean.getPointer(), bean);
        addClassHierarchyTree(bean.getJClass(), bean);
    }

    @Nullable
    private JMethod pickConstructor(JClass beanClass) {
        List<JMethod> ctors = beanClass.getDeclaredMethods().stream()
                .filter(JMethod::isConstructor)
                .filter(m -> !m.isAbstract())
                .toList();
        if (ctors.isEmpty()) {
            return null;
        }
        return ctors.stream()
                .filter(m -> m.hasAnnotation("org.springframework.beans.factory.annotation.Autowired")
                        || m.hasAnnotation("javax.inject.Inject"))
                .findFirst()
                .orElseGet(() -> ctors.stream().filter(m -> m.getParamCount() == 0).findFirst().orElse(ctors.get(0)));
    }

    @Nullable
    public Bean getByName(String name) {
        return name2Bean.get(name);
    }

    @Nullable
    public Bean getByType(JClass clz) {
        Set<Bean> beans = class2Beans.get(clz);
        if (beans.size() == 1) {
            return beans.iterator().next();
        }
        if (!beans.isEmpty()) {
            Bean exact = beans.stream()
                    .filter(bean -> bean.getJClass().equals(clz))
                    .findFirst().orElse(null);
            if (exact != null) {
                return exact;
            }
            return beans.iterator().next();
        }

        Set<JClass> concreteSubclasses = World.get().getClassHierarchy().getAllSubclassesOf(clz).stream()
                .filter(c -> !c.isAbstract() && !c.isInterface())
                .collect(Collectors.toSet());
        if (!concreteSubclasses.isEmpty()) {
            logger.debug("[BeanManager] No registered bean for '{}'; fallback to {} concrete subtype(s)",
                    clz.getName(), concreteSubclasses.size());
            return getOrCreateFallbackBean(concreteSubclasses.iterator().next());
        }

        String concrete = resolveJdkCollectionFallback(clz.getName());
        if (concrete != null) {
            JClass jdkClass = World.get().getClassHierarchy().getJREClass(concrete);
            if (jdkClass == null) {
                jdkClass = World.get().getClassHierarchy().getClass(concrete);
            }
            if (jdkClass != null) {
                return getOrCreateFallbackBean(jdkClass);
            }
        }

        return null;
    }

    @Nullable
    public Bean getOrCreateFallbackBean(JClass clz) {
        clz = resolveConcreteImpl(clz);
        Set<Bean> existing = class2Beans.get(clz);
        if (!existing.isEmpty()) {
            return existing.iterator().next();
        }
        if (runtimeSolver == null) {
            return null;
        }
        String beanName = "__fallback_" + toLowerCamelCase(clz.getSimpleName());
        return createIfAbsent(clz, beanName, runtimeSolver);
    }

    @Nullable
    public Bean getByPointer(Pointer pointer) {
        return pointer2Bean.get(pointer);
    }

    public Collection<Bean> getAllBeans() {
        return Sets.newSet(name2Bean.values());
    }

    public List<PendingBeanMethod> getPendingBeanMethods() {
        return pendingBeanMethods;
    }

    public int size() {
        return getAllBeans().size();
    }

    public void connectPFGEdges(Solver solver, CSManager csManager,
                                Context emptyCtx) {
        for (Bean bean : getAllBeans()) {
            for (Var in : bean.getIns()) {
                CSVar csIn = csManager.getCSVar(emptyCtx, in);
                solver.addPFGEdge(csIn, bean.getPointer(), FlowKind.OTHER);
            }
            for (Var out : bean.getOuts()) {
                CSVar csOut = csManager.getCSVar(emptyCtx, out);
                solver.addPFGEdge(bean.getPointer(), csOut, FlowKind.OTHER);
            }
            for (JField outField : bean.getOutFields()) {
                JClass declaringClass = outField.getDeclaringClass();
                for (Bean containerBean : getAllBeans()) {
                    if (!declaringClass.equals(containerBean.getJClass())
                            && !class2Beans.get(declaringClass).contains(containerBean)) {
                        continue;
                    }
                    if (containerBean.getMockObj() == null) {
                        continue;
                    }
                    CSObj containerCSObj = csManager.getCSObj(emptyCtx, containerBean.getMockObj());
                    InstanceField instanceField = csManager.getInstanceField(containerCSObj, outField);
                    solver.addPFGEdge(bean.getPointer(), instanceField, FlowKind.OTHER);
                }
            }
        }
    }

    public void propagateBeanObjs(Solver solver, CSManager csManager,
                                  Context emptyCtx) {
        for (Bean bean : getAllBeans()) {
            if (bean.getMockObj() != null) {
                CSObj csObj = csManager.getCSObj(emptyCtx, bean.getMockObj());
                solver.addPointsTo(bean.getPointer(), csObj);
            }
        }
    }

    private void addClassHierarchyTree(JClass clz, Bean bean) {
        Queue<JClass> queue = new ArrayDeque<>();
        queue.offer(clz);
        while (!queue.isEmpty()) {
            JClass c = queue.poll();
            if (c == null || "java.lang.Object".equals(c.getName())) {
                continue;
            }
            class2Beans.put(c, bean);
            queue.offer(c.getSuperClass());
            queue.addAll(c.getInterfaces());
        }
    }

    @Nullable
    private static String resolveJdkCollectionFallback(String className) {
        return switch (className) {
            case "java.util.Map", "java.util.SortedMap",
                    "java.util.NavigableMap", "java.util.AbstractMap",
                    "java.util.concurrent.ConcurrentMap" -> "java.util.HashMap";
            case "java.util.List", "java.util.AbstractList",
                    "java.util.Collection", "java.lang.Iterable" -> "java.util.ArrayList";
            case "java.util.Set", "java.util.SortedSet",
                    "java.util.NavigableSet", "java.util.AbstractSet" -> "java.util.HashSet";
            case "java.util.Queue", "java.util.Deque",
                    "java.util.AbstractQueue" -> "java.util.ArrayDeque";
            default -> null;
        };
    }

    private static String toLowerCamelCase(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return simpleName;
        }
        if (simpleName.length() == 1) {
            return simpleName.toLowerCase();
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private JClass resolveConcreteImpl(JClass target) {
        if (target == null || (!target.isInterface() && !target.isAbstract())) {
            return target;
        }
        ClassHierarchy classHierarchy = World.get().getClassHierarchy();
        Set<JClass> allImpls = classHierarchy.getAllSubclassesOf(target).stream()
                .filter(c -> !c.isAbstract() && !c.isInterface())
                .collect(Collectors.toSet());
        if (allImpls.isEmpty()) {
            return target;
        }
        List<JClass> appImpls = allImpls.stream().filter(JClass::isApplication).toList();
        if (!appImpls.isEmpty()) {
            return appImpls.get(0);
        }
        return allImpls.iterator().next();
    }

    public record PendingBean(JClass beanClass, String beanName) {
    }

    public record PendingBeanMethod(JClass returnClass, List<String> beanNames, JMethod method) {
    }
}