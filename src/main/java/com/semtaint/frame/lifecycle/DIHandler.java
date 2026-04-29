package com.semtaint.frame.lifecycle;

import com.semtaint.frame.detector.DetectingHolder;
import com.semtaint.frame.detector.FrameworkDetector;
import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import com.semtaint.frame.detector.config.XmlConfigHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DIHandler implements Plugin {

    private static final Logger logger = LogManager.getLogger(DIHandler.class);

    private Solver solver;
    private ClassHierarchy hierarchy;
    private AnnotationsHolder annotationsHolder;
    private XmlConfigHolder xmlConfigHolder;
    private boolean isCalled;

    public DIHandler() {
        this.isCalled = false;
    }

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.hierarchy = World.get().getClassHierarchy();
        DetectingHolder detectingHolder = World.get().getResult(FrameworkDetector.ID);
        this.annotationsHolder = detectingHolder.getAnnotationsHolder();
        this.xmlConfigHolder = detectingHolder.getXmlConfigHolder();
    }

    @Override
    public void onPhaseFinish() {
        if (isCalled) {
            return;
        }
        isCalled = true;
        logger.info("[ -----DI analysis start (PFG declarative mode)----- ]");

        BeanManager beanManager = annotationsHolder.getBeanManager();
        beanManager.attachSolver(solver);
        beanManager.resetRuntimeState();
        Context emptyCtx = solver.getContextSelector().getEmptyContext();
        CSManager csManager = solver.getCSManager();

        registerAnnotationBeans(beanManager);
        registerXmlBeans(beanManager);
        registerFrameworkManagedBeans(beanManager);

        beanManager.materializeAll(solver);
        initializeBeanMethods(beanManager, csManager, emptyCtx);

        modelFieldInjection(beanManager);
        modelConstructorParamInjection(beanManager);
        setupEntryPointsWithBeanThis(beanManager);
        setupPostConstruct(beanManager, csManager, emptyCtx);
        handleXmlDI(beanManager);

        beanManager.connectPFGEdges(solver, csManager, emptyCtx);
        beanManager.propagateBeanObjs(solver, csManager, emptyCtx);

        handleValueInjection(beanManager, csManager, emptyCtx);
        makeBeanReachable(beanManager, csManager, emptyCtx);
        postDiIntegrityCheck(beanManager);

        logger.info("[ -----DI analysis end----- ]");
    }

    private void registerAnnotationBeans(BeanManager beanManager) {
        for (JClass beanClass : annotationsHolder.getBeanClasses()) {
            if (annotationsHolder.getMapperClasses().contains(beanClass)) {
                continue;
            }
            String beanName = toLowerCamelCase(beanClass.getSimpleName());
            beanManager.registerPending(beanClass, beanName);
        }
    }

    private void registerXmlBeans(BeanManager beanManager) {
        for (Map.Entry<String, String> beanDef : xmlConfigHolder.getBeanDefinitions().entrySet()) {
            String beanId = beanDef.getKey();
            String className = beanDef.getValue();
            if (className == null || className.startsWith("#")) {
                continue;
            }
            JClass beanClass = hierarchy.getClass(className);
            if (beanClass != null && !beanClass.isPhantom()) {
                annotationsHolder.addBean(beanClass);
                beanManager.registerPending(beanClass, beanId);
            }
        }
    }

    private void registerFrameworkManagedBeans(BeanManager beanManager) {
        // Mapper 接口（MyBatisMapperProcessor 未调 addBean）
        for (JClass mapper : annotationsHolder.getMapperClasses()) {
            beanManager.registerPending(mapper,
                toLowerCamelCase(mapper.getSimpleName()));
        }
    }

    private void initializeBeanMethods(BeanManager beanManager, CSManager csManager, Context emptyCtx) {
        for (Bean bean : beanManager.getAllBeans()) {
            JClass beanClass = bean.getJClass();
            solver.initializeClass(beanClass);

            JMethod clinit = beanClass.getClinit();
            if (clinit != null) {
                solver.addCSMethod(csManager.getCSMethod(emptyCtx, clinit));
            }

            beanClass.getDeclaredMethods().stream()
                    .filter(m -> m.getName().equals("<init>") && !m.isAbstract())
                    .forEach(ctor -> solver.addCSMethod(csManager.getCSMethod(emptyCtx, ctor)));
        }
        for (BeanManager.PendingBeanMethod pendingBeanMethod : beanManager.getPendingBeanMethods()) {
            CSMethod csMethod = csManager.getCSMethod(emptyCtx, pendingBeanMethod.method());
            solver.addCSMethod(csMethod);
        }
    }

    private void modelFieldInjection(BeanManager beanManager) {
        Context emptyCtx = solver.getContextSelector().getEmptyContext();
        CSManager csManager = solver.getCSManager();

        for (JField field : annotationsHolder.getInjectedField()) {
            Bean refBean = resolveInjectionTarget(field, beanManager);
            if (refBean == null || refBean.getMockObj() == null) {
                continue;
            }

            Bean declaringBean = beanManager.getByType(field.getDeclaringClass());
            if (declaringBean == null || declaringBean.getMockObj() == null) {
                continue;
            }

            CSObj containerCSObj = csManager.getCSObj(emptyCtx, declaringBean.getMockObj());
            CSObj refCSObj = csManager.getCSObj(emptyCtx, refBean.getMockObj());
            InstanceField instanceField = csManager.getInstanceField(containerCSObj, field);
            solver.addPointsTo(instanceField, refCSObj);
        }
    }

    private void modelConstructorParamInjection(BeanManager beanManager) {
        for (Bean bean : beanManager.getAllBeans()) {
            JMethod constructor = bean.getConstructor();
            if (constructor == null) {
                continue;
            }
            IR ir = constructor.getIR();
            for (int index = 0; index < constructor.getParamCount(); index++) {
                Type paramType = constructor.getParamType(index);
                if (!(paramType instanceof ClassType classType)) {
                    continue;
                }
                String qualifier = annotationsHolder.getMethodParamQualifier(constructor, index);
                Bean paramBean = qualifier != null
                        ? beanManager.getByName(qualifier)
                        : beanManager.getByType(classType.getJClass());
                if (paramBean != null) {
                    paramBean.addOutEdge(ir.getParam(index));
                }
            }
        }
    }

    private void setupEntryPointsWithBeanThis(BeanManager beanManager) {
        for (JMethod entryMethod : annotationsHolder.getEntryMethods()) {
            Bean bean = beanManager.getByType(entryMethod.getDeclaringClass());
            if (bean == null) {
                continue;
            }
            Var thisVar = entryMethod.getIR().getThis();
            if (thisVar != null) {
                bean.addOutEdge(thisVar);
            }
        }
    }

    private void setupPostConstruct(BeanManager beanManager, CSManager csManager, Context emptyCtx) {
        for (JMethod postConstruct : annotationsHolder.getPostConstructMethods()) {
            Bean bean = beanManager.getByType(postConstruct.getDeclaringClass());
            if (bean == null) {
                continue;
            }
            CSMethod csMethod = csManager.getCSMethod(emptyCtx, postConstruct);
            solver.addCSMethod(csMethod);
            Var thisVar = postConstruct.getIR().getThis();
            if (thisVar != null) {
                bean.addOutEdge(thisVar);
            }
        }
    }

    private void handleXmlDI(BeanManager beanManager) {
        Map<String, JClass> beanIdToClass = xmlConfigHolder.getBeanDefinitions().entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().startsWith("#"))
                .map(e -> Map.entry(e.getKey(), hierarchy.getClass(e.getValue())))
                .filter(e -> e.getValue() != null && !e.getValue().isPhantom())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

        for (Map.Entry<String, Map<String, String>> beanEntry : xmlConfigHolder.getBeanPropertyRefs().entrySet()) {
            String beanId = beanEntry.getKey();
            Bean declaringBean = beanManager.getByName(beanId);
            JClass declaringClass = declaringBean != null
                    ? declaringBean.getJClass()
                    : beanIdToClass.get(beanId);
            if (declaringClass == null) continue;

            for (Map.Entry<String, String> propEntry : beanEntry.getValue().entrySet()) {
                String propName = propEntry.getKey();
                if (propName.startsWith("#")) {
                    continue;
                }
                String refBeanId = propEntry.getValue();
                Bean refBean = beanManager.getByName(refBeanId);
                if (refBean == null) {
                    JClass refClass = beanIdToClass.get(refBeanId);
                    if (refClass == null) {
                        logger.debug("[XML-DI] Cannot resolve ref bean '{}' for property '{}', skipping",
                                refBeanId, propName);
                        continue;
                    }
                    refBean = beanManager.getOrCreateFallbackBean(refClass);
                    if (refBean == null) {
                        continue;
                    }
                }
                JField field = findFieldInHierarchy(declaringClass, propName);
                if (field == null) {
                    logger.debug("[XML-DI] Field '{}' not found in hierarchy of '{}'",
                            propName, declaringClass.getName());
                    continue;
                }

                if (declaringBean != null && declaringBean.getMockObj() != null) {
                    refBean.addOutEdge(field);
                } else if (refBean.getMockObj() != null) {
                    Context emptyCtx = solver.getContextSelector().getEmptyContext();
                    CSManager csManager = solver.getCSManager();
                    Obj containerObj = solver.getHeapModel().getMockObj(
                            () -> "XmlBeanObj", beanId, declaringClass.getType());
                    CSObj containerCSObj = csManager.getCSObj(emptyCtx, containerObj);
                    CSObj refCSObj = csManager.getCSObj(emptyCtx, refBean.getMockObj());
                    InstanceField instanceField = csManager.getInstanceField(containerCSObj, field);
                    solver.addPointsTo(instanceField, refCSObj);
                }

                logger.info("[XML-DI] Injected {} -> {}.{}",
                        refBean.getJClass().getName(), declaringClass.getName(), propName);
            }
        }

        for (Map.Entry<String, List<String>> beanEntry : xmlConfigHolder.getBeanConstructorRefs().entrySet()) {
            Bean declaringBean = beanManager.getByName(beanEntry.getKey());
            if (declaringBean == null || declaringBean.getConstructor() == null) {
                continue;
            }
            JMethod ctor = declaringBean.getConstructor();
            IR ir = ctor.getIR();
            List<String> refs = beanEntry.getValue();
            for (int i = 0; i < refs.size() && i < ctor.getParamCount(); i++) {
                Bean refBean = beanManager.getByName(refs.get(i));
                if (refBean != null) {
                    refBean.addOutEdge(ir.getParam(i));
                }
            }
        }
    }

    private void handleValueInjection(BeanManager beanManager, CSManager csManager, Context emptyCtx) {
        for (JField field : annotationsHolder.getValueInjectedField()) {
            Type fieldType = field.getType();
            if (fieldType instanceof PrimitiveType) {
                continue;
            }

            Obj valueObj = solver.getHeapModel().getMockObj(
                    () -> "ValueInjectionObj", field, fieldType);
            Bean containerBean = beanManager.getByType(field.getDeclaringClass());

            if (containerBean != null && containerBean.getMockObj() != null) {
                CSObj containerObj = csManager.getCSObj(emptyCtx, containerBean.getMockObj());
                InstanceField instanceField = csManager.getInstanceField(containerObj, field);
                solver.addPointsTo(instanceField, csManager.getCSObj(emptyCtx, valueObj));
            } else {
                injectValueFieldFallback(field, fieldType, emptyCtx);
            }
        }
    }

    private void injectValueFieldFallback(JField field, Type type, Context emptyCtx) {
        JClass declaringClass = field.getDeclaringClass();
        Set<CSMethod> csMethodSet = solver.getCallGraph().reachableMethods()
                .filter(csm -> csm.getMethod().getDeclaringClass().equals(declaringClass))
                .collect(Collectors.toSet());

        Obj valueObj = solver.getHeapModel().getMockObj(
                () -> "ValueInjectionObj", field, type);

        for (CSMethod csMethod : csMethodSet) {
            csMethod.getMethod().getIR().getStmts().stream()
                    .filter(s -> s instanceof LoadField lf
                            && lf.getFieldAccess().getFieldRef().resolve() != null
                            && lf.getFieldAccess().getFieldRef().resolve().equals(field))
                    .map(s -> ((LoadField) s).getLValue())
                    .forEach(var -> {
                        CSVar csVar = solver.getCSManager().getCSVar(csMethod.getContext(), var);
                        solver.addPointsTo(csVar, valueObj);
                    });
        }
    }

    private Bean resolveInjectionTarget(JField field, BeanManager beanManager) {
        if (!(field.getType() instanceof ClassType classType)) {
            return null;
        }
        JClass fieldClass = classType.getJClass();

        String qualifier = annotationsHolder.getFieldQualifier(field);
        if (qualifier != null && !qualifier.isEmpty()) {
            Bean byName = beanManager.getByName(qualifier);
            if (byName != null && isFieldCompatibleWithBean(field, byName)) {
                return byName;
            }
        }
        Bean byType = beanManager.getByType(fieldClass);
        if (byType != null) return byType;

        // fallback：按接口简名查找（命中 PersistenceHandler 注册的 mocked impl Bean）
        return beanManager.getByName(toLowerCamelCase(fieldClass.getSimpleName()));

    }

    private void makeBeanReachable(BeanManager beanManager,
                                          CSManager csManager,
                                          Context emptyCtx) {
        for (Bean bean : beanManager.getAllBeans()) {
            JClass beanClass = bean.getJClass();
            if (beanClass.isInterface() || beanClass.isAbstract()) {
                continue;
            }
            solver.initializeClass(beanClass);
            for (JMethod method : beanClass.getDeclaredMethods()) {
                if (method.isAbstract() || method.isNative() || method.isPrivate()) {
                    continue;
                }
//                String methodName = method.getName();
//                if ("<clinit>".equals(methodName) || "<init>".equals(methodName)
//                        || methodName.startsWith("lambda$")) {
//                    continue;
//                }
                if (method.getIR() == null) {
                    continue;
                }

                CSMethod csMethod = csManager.getCSMethod(emptyCtx, method);
                solver.addCSMethod(csMethod);

                Var thisVar = method.getIR().getThis();
                if (thisVar != null && bean.getMockObj() != null) {
                    CSVar csThis = csManager.getCSVar(emptyCtx, thisVar);
                    CSObj csBeanObj = csManager.getCSObj(emptyCtx, bean.getMockObj());
                    solver.addPointsTo(csThis, csBeanObj);
                }
            }
        }
    }

    private boolean isFieldCompatibleWithBean(JField field, Bean bean) {
        if (!(field.getType() instanceof ClassType fieldType)) {
            return false;
        }
        return isTypeCompatible(fieldType.getJClass(), bean.getJClass());
    }

    private boolean isTypeCompatible(JClass requiredType, JClass candidateBeanType) {
        return requiredType.equals(candidateBeanType)
                || hierarchy.isSubclass(requiredType, candidateBeanType);
    }

    private JField findFieldInHierarchy(JClass jClass, String fieldName) {
        JClass current = jClass;
        while (current != null) {
            JField field = current.getDeclaredField(fieldName);
            if (field != null) {
                return field;
            }
            current = current.getSuperClass();
        }
        return null;
    }

    private String toLowerCamelCase(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return simpleName;
        }
        if (simpleName.length() == 1) {
            return simpleName.toLowerCase();
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private void postDiIntegrityCheck(BeanManager beanManager) {
        for (JClass entryClass : annotationsHolder.getBeanManager().getAllBeans().stream().map(Bean::getJClass).toList()) {
            entryClass.getDeclaredFields().stream()
                    .filter(f -> !f.isStatic() && !(f.getType() instanceof PrimitiveType))
                    .forEach(field -> {
                        Bean targetBean = resolveInjectionTarget(field, beanManager);
                        if (targetBean == null) {
                            logger.warn("[DI-Check] EntryClass '{}' field '{}' (type: {}) has no injection target in BeanManager",
                                    entryClass.getName(), field.getName(), field.getType());
                        }
                    });
        }
    }

    @Override
    public void onUnresolvedCall(CSObj recv, Context context, Invoke invoke) {
    }

    @Override
    public void onFinish() {
        BeanManager beanManager = annotationsHolder.getBeanManager();
        logger.info("[DI] Total beans: {}", beanManager.size());

        for (Bean bean : beanManager.getAllBeans()) {
            if (bean.getOuts().isEmpty() && bean.getOutFields().isEmpty()) {
                logger.debug("[DI] Bean '{}' ({}) has no injection targets",
                        bean.getBeanName(), bean.getJClass().getName());
            }
        }
    }
}
