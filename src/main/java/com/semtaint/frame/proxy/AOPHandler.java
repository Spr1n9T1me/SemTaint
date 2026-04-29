package com.semtaint.frame.proxy;

import com.semtaint.frame.detector.DetectingHolder;
import com.semtaint.frame.detector.FrameworkDetector;
import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import com.semtaint.frame.lifecycle.Bean;
import com.semtaint.frame.lifecycle.BeanManager;
import com.semtaint.utils.enhance.CodeEnhancer;
import com.semtaint.utils.enhance.StatementGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.OtherEdge;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.exp.DoubleLiteral;
import pascal.taie.ir.exp.FloatLiteral;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.LongLiteral;
import pascal.taie.ir.exp.NewArray;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeSystem;
import pascal.taie.language.type.VoidType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AOP Handler using CodeEnhancer for proxy generation
 */
public class AOPHandler implements Plugin {

    private static final Logger logger = LogManager.getLogger(AOPHandler.class);

    private Solver solver;
    private CSManager csManager;
    private AnnotationsHolder annotationsHolder;
    private final CodeEnhancer enhancer;

    public boolean isSkip = false;
    private boolean isCalled = false;

    private final Map<JClass, ProxyArtifacts> proxyArtifactsCache = new HashMap<>();
    private final Map<String, JMethod> proxyMethodCache = new HashMap<>();
    private final Map<JMethod, List<JMethod>> proxyWrapperMethods = new HashMap<>();

    private JClass joinPointImplClass;
    private JField joinPointArgsField;
    private JMethod joinPointSetArgsMethod;
    private AroundProceedReplacer proceedReplacer;

    public AOPHandler() {
        this.enhancer = new CodeEnhancer();
    }

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.csManager = solver.getCSManager();
        DetectingHolder detectingHolder = World.get().getResult(FrameworkDetector.ID);
        this.annotationsHolder = detectingHolder.getAnnotationsHolder();
        this.proceedReplacer = new AroundProceedReplacer(this.enhancer);
    }

    @Override
    public void onStart() {
        Set<AOPClassModel> aopClassModels = annotationsHolder.aopClassModels;
        if (aopClassModels.isEmpty()) {
            logger.info("[-] NO AOP found, skip AOP analysis.");
            this.isSkip = true;
        }
    }

    @Override
    public void onPhaseFinish() {
        if (isSkip || isCalled) {
            return;
        }
        this.isCalled = true;
        logger.info("[ -----AOP analysis start----- ]");

        Processor processor = new Processor();
        Set<AOPClassModel> aopClassModels = annotationsHolder.aopClassModels;

        for (AOPClassModel aopClassModel : aopClassModels) {
            processor.process(aopClassModel);
            processAOPClass(aopClassModel);
        }

        processor.destroy();
        logger.info("[ -----AOP analysis end----- ]");
    }

    /**
     * A7: 按 target 聚合 advice，构建洋葱模型入口。
     */
    private void processAOPClass(AOPClassModel aopClassModel) {
        Map<JMethod, List<AdviceModel>> targetToAdvices = new LinkedHashMap<>();

        for (AdviceModel advice : aopClassModel.advices) {
            Set<JMethod> targets = resolveTargets(advice, aopClassModel);
            if (targets == null || targets.isEmpty()) {
                continue;
            }
            for (JMethod target : targets) {
                targetToAdvices.computeIfAbsent(target, ignored -> new ArrayList<>()).add(advice);
            }
        }

        for (Map.Entry<JMethod, List<AdviceModel>> entry : targetToAdvices.entrySet()) {
            JMethod target = entry.getKey();
            List<AdviceModel> advices = entry.getValue();
            advices.sort(adviceComparator(aopClassModel.order));
            createAggregatedProxy(target, advices);
        }
    }

    private Set<JMethod> resolveTargets(AdviceModel advice, AOPClassModel aopClassModel) {
        JMethod adviceMethod = advice.insertMethod();
        String adviceExpr = advice.pointcutExpression();

        List<String> pointCutAlias = aopClassModel.pointCutAlias;
        JClass originalProxyClass = aopClassModel.proxyClass;

        if (pointCutAlias.stream().anyMatch(adviceExpr::contains)) {
            String funcName = extractFunctionName(adviceExpr);
            if (funcName == null || originalProxyClass == null) {
                return Set.of();
            }
            Set<JMethod> candidates = originalProxyClass.getDeclaredMethods().stream()
                    .filter(m -> m.getName().equals(funcName))
                    .collect(Collectors.toSet());

            return candidates.stream()
                    .map(aopClassModel.targetMethods::get)
                    .filter(methods -> methods != null && !methods.isEmpty())
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
        }
        Set<JMethod> direct = aopClassModel.targetMethods.get(adviceMethod);
        return direct == null ? Set.of() : direct;
    }

    private Comparator<AdviceModel> adviceComparator(int aspectOrder) {
        return Comparator
                .comparingInt((AdviceModel ignored) -> aspectOrder)
                .thenComparingInt(advice -> adviceTypePriority(advice.adviceType()));
    }

    private int adviceTypePriority(AdviceModel.AdviceEnum adviceType) {
        return switch (adviceType) {
            case AROUND -> 0;
            case BEFORE -> 1;
            case AFTER -> 2;
            case AFTER_RETURNING -> 3;
            case AFTER_THROWING -> 4;
        };
    }

    private void createAggregatedProxy(JMethod target, List<AdviceModel> advices) {
        if (target == null || target.isAbstract()) {
            return;
        }
        try {
            ProxyArtifacts artifacts = getOrCreateProxyArtifacts(target.getDeclaringClass());
            JMethod proxyMethod = getOrCreateProxyMethod(artifacts.proxyClass(), target);

            List<JMethod> wrappers = generateAggregatedProxyIR(proxyMethod, target, artifacts.targetField(), advices);
            registerProxyInSolver(proxyMethod, target, advices, wrappers);
            registerProxyAsBean(artifacts.proxyClass(), target.getDeclaringClass(), artifacts.targetField());

//            logger.info("[AOP] Aggregated proxy {} -> {} with {} advices",
//                    proxyMethod.getName(), target.getSignature(), advices.size());
        } catch (Exception e) {
            logger.error("Failed to create aggregated AOP proxy for target: {}", target.getSignature(), e);
        }
    }

    /**
     * A1: 代理类继承目标类（接口场景实现接口）并添加 target 字段。
     */
    private ProxyArtifacts getOrCreateProxyArtifacts(JClass targetClass) {
        ProxyArtifacts cached = proxyArtifactsCache.get(targetClass);
        if (cached != null) {
            return cached;
        }

        JClass objectClass = getObjectClass();
        String proxyClassName = targetClass.getName() + "$AOPProxy";
        CodeEnhancer.EnhancedClass enhanced;

        if (targetClass.isInterface()) {
            enhanced = enhancer.createClass(proxyClassName, objectClass);
            enhanced.addInterface(targetClass);
        } else {
            enhanced = enhancer.createClass(proxyClassName, targetClass);
        }

        JClass proxyClass = enhanced.build();
        JField targetField = enhancer.addField(proxyClass, "target", targetClass.getType());

        ProxyArtifacts artifacts = new ProxyArtifacts(proxyClass, targetField);
        proxyArtifactsCache.put(targetClass, artifacts);
        return artifacts;
    }

    private JMethod getOrCreateProxyMethod(JClass proxyClass, JMethod target) {
        String key = proxyClass.getName() + "#" + target.getSubsignature();
        JMethod cached = proxyMethodCache.get(key);
        if (cached != null) {
            return cached;
        }

        String proxyMethodName = "proxy$" + target.getName();
        JMethod proxyMethod = enhancer.addMethod(
                proxyClass,
                proxyMethodName,
                target.getParamTypes(),
                target.getReturnType()
        );

        proxyMethodCache.put(key, proxyMethod);
        return proxyMethod;
    }

    private List<JMethod> generateAggregatedProxyIR(JMethod proxyMethod,
                                                    JMethod target,
                                                    JField targetField,
                                                    List<AdviceModel> advices) {
        StatementGenerator gen = new StatementGenerator(proxyMethod);
        List<Stmt> body = new ArrayList<>();
        List<Var> paramVars = createParamVars(gen, proxyMethod);
        List<JMethod> wrappers = new ArrayList<>();

        Var targetRef = loadTargetReference(gen, body, proxyMethod, target, targetField);
        Var joinPointVar = createJoinPoint(gen, body, paramVars, proxyMethod);

        List<AdviceModel> aroundAdvices = filterAdvices(advices, AdviceModel.AdviceEnum.AROUND);
        List<AdviceModel> beforeAdvices = filterAdvices(advices, AdviceModel.AdviceEnum.BEFORE);
        List<AdviceModel> afterAdvices = filterAdvices(advices, AdviceModel.AdviceEnum.AFTER);
        List<AdviceModel> afterReturningAdvices = filterAdvices(advices, AdviceModel.AdviceEnum.AFTER_RETURNING);
        List<AdviceModel> afterThrowingAdvices = filterAdvices(advices, AdviceModel.AdviceEnum.AFTER_THROWING);

        Var targetResult = null;
        boolean aroundExecuted = false;
        for (AdviceModel advice : aroundAdvices) {
            AroundCallResult aroundCallResult = generateAroundAdvice(
                    gen, body, proxyMethod, paramVars,
                    advice.insertMethod(), target,
                    targetField, joinPointVar, targetRef);
            aroundExecuted = true;
            if (aroundCallResult.wrapperMethod() != null) {
                wrappers.add(aroundCallResult.wrapperMethod());
            }
            if (aroundCallResult.result() != null) {
                targetResult = aroundCallResult.result();
            }
        }

        for (AdviceModel advice : beforeAdvices) {
            invokeAdvice(gen, body, advice.insertMethod(), paramVars, joinPointVar, null, false);
        }

        if (!aroundExecuted) {
            targetResult = generateTargetMethodCall(gen, body, paramVars, target, targetRef);
        }

        for (AdviceModel advice : afterAdvices) {
            invokeAdvice(gen, body, advice.insertMethod(), paramVars, joinPointVar, targetResult, false);
        }

        for (AdviceModel advice : afterReturningAdvices) {
            invokeAdvice(gen, body, advice.insertMethod(), paramVars, joinPointVar, targetResult, true);
        }

        for (AdviceModel advice : afterThrowingAdvices) {
            invokeAdvice(gen, body, advice.insertMethod(), paramVars, joinPointVar, null, false);
        }

        if (target.getReturnType().equals(VoidType.VOID)) {
            body.add(gen.generateVoidReturn());
        } else {
            if (targetResult == null) {
                targetResult = createDefaultVar(gen, body, target.getReturnType(), "targetResultFallback");
            }
            body.add(gen.generateReturn(targetResult));
        }

        proxyMethod.setIR(enhancer.createMethodIR(proxyMethod, body));
        proxyWrapperMethods.put(proxyMethod, wrappers);
        return wrappers;
    }

    private AroundCallResult generateAroundAdvice(
            StatementGenerator gen,
            List<Stmt> body,
            JMethod proxyMethod,
            List<Var> paramVars,
            JMethod adviceMethod,
            JMethod target,
            JField targetField,
            Var joinPointVar,
            Var targetRef) {

        Var resolvedTargetRef = targetRef;
        if (!target.isStatic() && resolvedTargetRef == null) {
            Var proxyThisVar = new Var(proxyMethod, "this", proxyMethod.getDeclaringClass().getType(), 0);
            if (targetField != null) {
                resolvedTargetRef = gen.newLocal("targetRef", target.getDeclaringClass().getType());
                body.add(gen.generateLoadField(resolvedTargetRef, proxyThisVar, targetField));
            }
        }

        if (proceedReplacer == null || adviceMethod.isStatic()) {
            Var fallback = generateLinearAroundFallback(gen, body, paramVars, adviceMethod, target,
                    joinPointVar, resolvedTargetRef);
            return new AroundCallResult(fallback, null);
        }

        JMethod wrapper = proceedReplacer.createWrapper(adviceMethod, target);
        if (wrapper == null) {
            Var fallback = generateLinearAroundFallback(gen, body, paramVars, adviceMethod, target,
                    joinPointVar, resolvedTargetRef);
            return new AroundCallResult(fallback, null);
        }

        Var adviceInstance = gen.newLocal("adviceInstance", adviceMethod.getDeclaringClass().getType());
        body.add(gen.generateNew(adviceInstance, adviceMethod.getDeclaringClass().getType()));

        List<Var> wrapperArgs = buildAroundWrapperArgs(
            gen, body, adviceMethod, target, paramVars, joinPointVar, resolvedTargetRef);

        Var aroundResult = null;
        if (!adviceMethod.getReturnType().equals(VoidType.VOID)) {
            aroundResult = gen.newLocal("aroundResult", adviceMethod.getReturnType());
            body.add(gen.generateInvokeVirtual(aroundResult, adviceInstance, wrapper, wrapperArgs));
        } else {
            body.add(gen.generateInvokeVirtual(null, adviceInstance, wrapper, wrapperArgs));
        }

        return new AroundCallResult(aroundResult, wrapper);
    }

    private List<Var> buildAroundWrapperArgs(StatementGenerator gen,
                                             List<Stmt> body,
                                             JMethod adviceMethod,
                                             JMethod targetMethod,
                                             List<Var> paramVars,
                                             Var joinPointVar,
                                             Var targetRef) {
        List<Var> wrapperArgs = new ArrayList<>();
        int consumedMethodArg = 0;

        for (int i = 0; i < adviceMethod.getParamCount(); i++) {
            Type pType = adviceMethod.getParamType(i);
            String typeName = pType.getName();
            if ((typeName.contains("JoinPoint") || typeName.contains("ProceedingJoinPoint")) && joinPointVar != null) {
                wrapperArgs.add(joinPointVar);
                continue;
            }

            if (consumedMethodArg < paramVars.size()) {
                wrapperArgs.add(paramVars.get(consumedMethodArg++));
            } else {
                wrapperArgs.add(createDefaultVar(gen, body, pType, "aroundArgDefault" + i));
            }
        }

        wrapperArgs.addAll(paramVars);
        if (targetRef != null) {
            wrapperArgs.add(targetRef);
        } else {
            Type targetRefType = targetMethod.getDeclaringClass().getType();
            wrapperArgs.add(createDefaultVar(gen, body, targetRefType, "aroundTargetRefDefault"));
        }
        return wrapperArgs;
    }

    private Var generateLinearAroundFallback(
            StatementGenerator gen,
            List<Stmt> body,
            List<Var> paramVars,
            JMethod adviceMethod,
            JMethod target,
            Var joinPointVar,
            Var targetRef) {
        List<Var> adviceArgs = new ArrayList<>();
        if (joinPointVar != null) {
            adviceArgs.add(joinPointVar);
        }

        if (adviceMethod.isStatic()) {
            if (!adviceMethod.getReturnType().equals(VoidType.VOID)) {
                Var r = gen.newLocal("advResult", adviceMethod.getReturnType());
                body.add(gen.generateInvokeStatic(r, adviceMethod, adviceArgs));
            } else {
                body.add(gen.generateInvokeStatic(null, adviceMethod, adviceArgs));
            }
        } else {
            Var adviceInstance = gen.newLocal("adviceInstance", adviceMethod.getDeclaringClass().getType());
            body.add(gen.generateNew(adviceInstance, adviceMethod.getDeclaringClass().getType()));
            if (!adviceMethod.getReturnType().equals(VoidType.VOID)) {
                Var r = gen.newLocal("advResult", adviceMethod.getReturnType());
                body.add(gen.generateInvokeVirtual(r, adviceInstance, adviceMethod, adviceArgs));
            } else {
                body.add(gen.generateInvokeVirtual(null, adviceInstance, adviceMethod, adviceArgs));
            }
        }

        return generateTargetMethodCall(gen, body, paramVars, target, targetRef);
    }

    private List<Var> createParamVars(StatementGenerator gen, JMethod method) {
        List<Var> paramVars = new ArrayList<>();
        for (int i = 0; i < method.getParamCount(); i++) {
            paramVars.add(gen.newLocal("param" + i, method.getParamType(i)));
        }
        return paramVars;
    }

    /**
     * A2: 通过 this.target 委托调用目标方法，而非 null 接收者。
     */
    private Var loadTargetReference(StatementGenerator gen,
                                    List<Stmt> body,
                                    JMethod proxyMethod,
                                    JMethod target,
                                    JField targetField) {
        if (target.isStatic() || targetField == null) {
            return null;
        }
        Var thisVar = new Var(proxyMethod, "this", proxyMethod.getDeclaringClass().getType(), 0);
        Var targetRef = gen.newLocal("targetRef", target.getDeclaringClass().getType());
        body.add(gen.generateLoadField(targetRef, thisVar, targetField));
        return targetRef;
    }

    /**
     * A4: 合成 JoinPointImpl，绑定 args。
     */
    private Var createJoinPoint(StatementGenerator gen,
                                List<Stmt> body,
                                List<Var> paramVars,
                                JMethod container) {
        ensureJoinPointImpl();
        if (joinPointImplClass == null || joinPointSetArgsMethod == null) {
            return null;
        }

        Var joinPointVar = gen.newLocal("joinPoint", joinPointImplClass.getType());
        body.add(gen.generateNew(joinPointVar, joinPointImplClass.getType()));

        TypeSystem typeSystem = World.get().getTypeSystem();
        ArrayType objArrayType = typeSystem.getArrayType(typeSystem.getType("java.lang.Object"), 1);
        Var argsLen = gen.newLocal("argsLen", typeSystem.getPrimitiveType("int"));
        body.add(gen.generateAssignLiteral(argsLen, IntLiteral.get(paramVars.size())));

        Var argsArray = gen.newLocal("argsArray", objArrayType);
        body.add(new New(container, argsArray, new NewArray(objArrayType, argsLen)));

        for (int i = 0; i < paramVars.size(); i++) {
            Var idxVar = gen.newLocal("argIndex" + i, typeSystem.getPrimitiveType("int"));
            body.add(gen.generateAssignLiteral(idxVar, IntLiteral.get(i)));
            body.add(gen.generateStoreArray(argsArray, idxVar, paramVars.get(i)));
        }

        body.add(gen.generateInvokeVirtual(null, joinPointVar, joinPointSetArgsMethod, List.of(argsArray)));
        return joinPointVar;
    }

    private void ensureJoinPointImpl() {
        if (joinPointImplClass != null) {
            return;
        }
        try {
            JClass objectClass = getObjectClass();
            CodeEnhancer.EnhancedClass enhanced = enhancer.createClass("sem.virtual.ProceedingJoinPointImpl", objectClass);

            JClass pjpInterface = World.get().getClassHierarchy().getClass("org.aspectj.lang.ProceedingJoinPoint");
            if (pjpInterface != null) {
                enhanced.addInterface(pjpInterface);
            }

            joinPointImplClass = enhanced.build();

            TypeSystem typeSystem = World.get().getTypeSystem();
            ArrayType objArrayType = typeSystem.getArrayType(typeSystem.getType("java.lang.Object"), 1);

            joinPointArgsField = enhancer.addField(joinPointImplClass, "args", objArrayType);
            joinPointSetArgsMethod = enhancer.addMethod(joinPointImplClass,
                    "setArgs_synthetic", List.of(objArrayType), VoidType.VOID);
            JMethod getArgsMethod = enhancer.addMethod(joinPointImplClass,
                    "getArgs", List.of(), objArrayType);

            StatementGenerator setGen = new StatementGenerator(joinPointSetArgsMethod);
            List<Stmt> setBody = new ArrayList<>();
            Var setThis = new Var(joinPointSetArgsMethod, "this", joinPointImplClass.getType(), 0);
            Var setParam = new Var(joinPointSetArgsMethod, "param0", objArrayType, 1);
            setBody.add(setGen.generateStoreField(setThis, joinPointArgsField, setParam));
            setBody.add(setGen.generateVoidReturn());
            joinPointSetArgsMethod.setIR(enhancer.createMethodIR(joinPointSetArgsMethod, setBody));

            StatementGenerator getGen = new StatementGenerator(getArgsMethod);
            List<Stmt> getBody = new ArrayList<>();
            Var getThis = new Var(getArgsMethod, "this", joinPointImplClass.getType(), 0);
            Var getRet = getGen.newLocal("ret", objArrayType);
            getBody.add(getGen.generateLoadField(getRet, getThis, joinPointArgsField));
            getBody.add(getGen.generateReturn(getRet));
            getArgsMethod.setIR(enhancer.createMethodIR(getArgsMethod, getBody));
        } catch (Exception e) {
            logger.warn("Failed to create synthetic JoinPoint impl", e);
            joinPointImplClass = null;
            joinPointArgsField = null;
            joinPointSetArgsMethod = null;
        }
    }

    /**
     * A3 + A8: advice 实例不再使用 null；AfterReturning 绑定 target 返回值。
     */
    private void invokeAdvice(StatementGenerator gen,
                              List<Stmt> body,
                              JMethod adviceMethod,
                              List<Var> paramVars,
                              Var joinPointVar,
                              Var targetResult,
                              boolean afterReturningMode) {
        List<Var> adviceArgs = buildAdviceArgs(gen, body, adviceMethod, paramVars, joinPointVar, targetResult, afterReturningMode);

        if (adviceMethod.isStatic()) {
            body.add(gen.generateInvokeStatic(null, adviceMethod, adviceArgs));
            return;
        }

        Var adviceInstance = gen.newLocal("adviceInstance", adviceMethod.getDeclaringClass().getType());
        body.add(gen.generateNew(adviceInstance, adviceMethod.getDeclaringClass().getType()));
        body.add(gen.generateInvokeVirtual(null, adviceInstance, adviceMethod, adviceArgs));
    }

    private List<Var> buildAdviceArgs(StatementGenerator gen,
                                      List<Stmt> body,
                                      JMethod adviceMethod,
                                      List<Var> paramVars,
                                      Var joinPointVar,
                                      Var targetResult,
                                      boolean afterReturningMode) {
        List<Var> args = new ArrayList<>();
        int nextParam = 0;
        boolean targetResultBound = false;

        for (int i = 0; i < adviceMethod.getParamCount(); i++) {
            Type pType = adviceMethod.getParamType(i);
            String typeName = pType.getName();

            if (typeName.contains("JoinPoint") && joinPointVar != null) {
                args.add(joinPointVar);
                continue;
            }

            if (afterReturningMode && !targetResultBound && targetResult != null) {
                args.add(targetResult);
                targetResultBound = true;
                continue;
            }

            if (nextParam < paramVars.size()) {
                args.add(paramVars.get(nextParam++));
            } else {
                args.add(createDefaultVar(gen, body, pType, "adviceArgDefault" + i));
            }
        }

        return args;
    }

    private Var createDefaultVar(StatementGenerator gen,
                                 List<Stmt> body,
                                 Type type,
                                 String baseName) {
        Var var = gen.newLocal(baseName, type);
        String name = type.getName();
        if (name.equals("long")) {
            body.add(gen.generateAssignLiteral(var, LongLiteral.get(0L)));
        } else if (name.equals("float")) {
            body.add(gen.generateAssignLiteral(var, FloatLiteral.get(0F)));
        } else if (name.equals("double")) {
            body.add(gen.generateAssignLiteral(var, DoubleLiteral.get(0D)));
        } else if (name.equals("byte") || name.equals("short") || name.equals("int")
                || name.equals("char") || name.equals("boolean")) {
            body.add(gen.generateAssignLiteral(var, IntLiteral.get(0)));
        } else {
            body.add(gen.generateAssignLiteral(var, gen.nullLiteral()));
        }
        return var;
    }

    private List<AdviceModel> filterAdvices(List<AdviceModel> advices, AdviceModel.AdviceEnum adviceType) {
        return advices.stream().filter(a -> a.adviceType() == adviceType).toList();
    }

    private Var generateTargetMethodCall(StatementGenerator gen,
                                         List<Stmt> body,
                                         List<Var> paramVars,
                                         JMethod target,
                                         Var targetRef) {
        if (target.isAbstract()) {
            logger.warn("Target method {} is abstract, skipping IR generation", target.getName());
            if (!target.getReturnType().equals(VoidType.VOID)) {
                return createDefaultVar(gen, body, target.getReturnType(), "result");
            }
            return null;
        }

        if (!target.getReturnType().equals(VoidType.VOID)) {
            Var result = gen.newLocal("result", target.getReturnType());
            if (!target.isStatic()) {
                Var recv = targetRef != null ? targetRef : createDefaultVar(gen, body,
                        target.getDeclaringClass().getType(), "targetInstanceFallback");
                body.add(gen.generateInvokeVirtual(result, recv, target, paramVars));
            } else {
                body.add(gen.generateInvokeStatic(result, target, paramVars));
            }
            return result;
        }

        if (!target.isStatic()) {
            Var recv = targetRef != null ? targetRef : createDefaultVar(gen, body,
                    target.getDeclaringClass().getType(), "targetInstanceFallback");
            body.add(gen.generateInvokeVirtual(null, recv, target, paramVars));
        } else {
            body.add(gen.generateInvokeStatic(null, target, paramVars));
        }
        return null;
    }

    private void registerProxyInSolver(JMethod proxyMethod,
                                       JMethod target,
                                       List<AdviceModel> advices,
                                       List<JMethod> wrappers) {
        Context emptyContext = solver.getContextSelector().getEmptyContext();

        CSMethod csProxyMethod = csManager.getCSMethod(emptyContext, proxyMethod);
        CSMethod csTarget = csManager.getCSMethod(emptyContext, target);

        solver.addCSMethod(csProxyMethod);
        solver.addCSMethod(csTarget);

        for (AdviceModel advice : advices) {
            JMethod adviceMethod = advice.insertMethod();
            CSMethod csAdvice = csManager.getCSMethod(emptyContext, adviceMethod);
            solver.addCSMethod(csAdvice);
        }

        registerMethodCallEdges(proxyMethod, emptyContext, "AOPProxy-");

        List<JMethod> effectiveWrappers = wrappers != null ? wrappers : proxyWrapperMethods.get(proxyMethod);
        if (effectiveWrappers != null) {
            for (JMethod wrapper : effectiveWrappers) {
                if (wrapper == null) {
                    continue;
                }
                CSMethod csWrapper = csManager.getCSMethod(emptyContext, wrapper);
                solver.addCSMethod(csWrapper);
                registerMethodCallEdges(wrapper, emptyContext, "AroundWrapper-");
            }
        }
    }

    private void registerMethodCallEdges(JMethod method, Context emptyContext, String infoPrefix) {
        if (method == null || method.isAbstract() || method.getIR() == null) {
            return;
        }
        method.getIR().getStmts().stream()
                .filter(stmt -> stmt instanceof Invoke)
                .map(stmt -> (Invoke) stmt)
                .forEach(invoke -> {
                    CSCallSite csCallSite = csManager.getCSCallSite(emptyContext, invoke);
                    JMethod callee = invoke.getMethodRef().resolveNullable();
                    if (callee != null && !callee.isAbstract()) {
                        CSMethod csCallee = csManager.getCSMethod(emptyContext, callee);
                        solver.addCallEdge(new OtherEdge<>(csCallSite, csCallee) {
                            @Override
                            public String getInfo() {
                                return infoPrefix + callee.getName();
                            }
                        });
                    }
                });
    }

    /**
     * A6: 代理 Bean 注册 + DI 重定向。
     */
    private void registerProxyAsBean(JClass proxyClass, JClass targetClass, JField targetField) {
        BeanManager beanManager = annotationsHolder.getBeanManager();
        Bean originalBean = beanManager.getByType(targetClass);
        if (originalBean == null) {
            return;
        }

        Obj proxyMockObj = solver.getHeapModel().getMockObj(
                () -> "AOPProxyObj",
                originalBean.getBeanName() + "$proxy",
                proxyClass.getType());

        Bean proxyBean = beanManager.createWithMockObj(
                proxyClass,
                originalBean.getBeanName() + "$proxy",
                null,
                proxyMockObj);

        beanManager.addClassHierarchyIndex(targetClass, proxyBean);

        if (targetField == null || originalBean.getMockObj() == null) {
            return;
        }

        Context emptyCtx = solver.getContextSelector().getEmptyContext();
        CSObj proxyCSObj = csManager.getCSObj(emptyCtx, proxyMockObj);
        CSObj originalCSObj = csManager.getCSObj(emptyCtx, originalBean.getMockObj());
        InstanceField iField = csManager.getInstanceField(proxyCSObj, targetField);
        solver.addPointsTo(iField, originalCSObj);
    }

    private JClass getObjectClass() {
        JClass objectClass = World.get().getClassHierarchy().getJREClass("java.lang.Object");
        if (objectClass == null) {
            objectClass = World.get().getClassHierarchy().getBootstrapClassLoader().loadClass("java.lang.Object");
        }
        return objectClass;
    }

    public String extractFunctionName(String functionDeclaration) {
        String regex = "\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(functionDeclaration);

        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private record ProxyArtifacts(JClass proxyClass, JField targetField) {
    }

    private record AroundCallResult(Var result, JMethod wrapperMethod) {
    }
}
