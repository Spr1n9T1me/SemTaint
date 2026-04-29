package com.semtaint.taint;

import com.semtaint.utils.enhance.EnhancementUtils;
import com.semtaint.utils.TypeUtils;
import com.semtaint.frame.detector.DetectingHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.taint.*;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Sets;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class TaintUtils {
    private static final Logger logger = LogManager.getLogger(TaintedParamProvider.class);

    public static boolean isFrameworkEntryMethod(DetectingHolder detectingHolder, JMethod method) {
        return detectingHolder.getAnnotationsHolder().getEntryMethods().contains(method) ||
                (detectingHolder.getAnnotationsHolder().getWebServletClasses().contains(method.getDeclaringClass())
                        && method.getName().matches(".*do(Get|Post|Put|Delete|Options|Head|Trace).*"));
    }

    public static ParamSourcePoint makeParamSource(JMethod method, int paramIndex) {
        Type type = method.getIR().getParam(paramIndex).getType();
            IndexRef indexRef;
            if (method.getParamType(paramIndex) instanceof ArrayType) {
                indexRef = new IndexRef(IndexRef.Kind.ARRAY, paramIndex, null);
            } else {
                indexRef = new IndexRef(IndexRef.Kind.VAR, paramIndex, null);
            }
            return new ParamSourcePoint(method,
                    indexRef,
                    new ParamSource(method, indexRef, type));
        }

    /**
     * Make concrete param source points for interface/abstract class type and param index.
     * @param method
     * @param classType
     * @param paramIndex
     * @return
     */
    public static ParamSourcePoint makeConcreteParamSource(JMethod method, ClassType classType, int paramIndex) {
            JClass classByType = classType.getJClass();
            assert !classByType.isPhantom() : "[-] Phantom class found: " + classByType.getName();
            List<JClass> resolved = resolveParamClass(classByType.getType());
            List<JClass> collected = resolved.stream()
                    .filter(jClass -> !jClass.isAbstract() && !jClass.isInterface())
                    .collect(Collectors.toList());
            if (collected.isEmpty()) {
                logger.error("[MOCK] No concrete class found for abstract/interface class:{}", classByType.getName());
                JClass implType = EnhancementUtils.createInterfaceImpl(classType.getJClass());
                IndexRef indexRef = new IndexRef(IndexRef.Kind.VAR, paramIndex, null);
                return new ParamSourcePoint(method,
                        indexRef,
                        new ParamSource(method, indexRef, implType.getType()));
            }

            Type finalType;
            if (collected.size() == 1) {
                finalType = collected.get(0).getType();
            } else {
                finalType = resolveWellKnownInterface(classType.getName());
                if (finalType == null) {
                    List<JClass> appImpls = collected.stream()
                            .filter(JClass::isApplication)
                            .toList();
                    if (appImpls.size() == 1) {
                        finalType = appImpls.get(0).getType();
                    } else if (appImpls.size() > 1) {
                        logger.debug("[Entry] Multiple app impls for '{}': {}, using first",
                                classType.getName(),
                                appImpls.stream().map(JClass::getName).toList());
                        finalType = appImpls.get(0).getType();
                    } else {
                        finalType = collected.get(0).getType();
                    }
                }
            }
//            logger.info("final type for '{}' : {}", classType, finalType);
            IndexRef indexRef = new IndexRef(IndexRef.Kind.VAR, paramIndex, null);
            return new ParamSourcePoint(method,
                    indexRef,
                    new ParamSource(method, indexRef, finalType));
    }

    @Nullable
    private static Type resolveWellKnownInterface(String typeName) {
        ClassHierarchy hierarchy = World.get().getClassHierarchy();
        String concrete = switch (typeName) {
            case "java.util.List", "java.util.Collection", "java.lang.Iterable" -> "java.util.ArrayList";
            case "java.util.Set" -> "java.util.HashSet";
            case "java.util.Map" -> "java.util.HashMap";
            case "java.util.Queue" -> "java.util.LinkedList";
            case "java.util.Deque" -> "java.util.ArrayDeque";
            case "java.lang.Runnable" -> "java.lang.Thread";
            case "jakarta.servlet.http.HttpServletRequest" -> {
                JClass requestFacade = hierarchy.getClass("org.apache.catalina.connector.RequestFacade");
                JClass undertowImpl = hierarchy.getClass("io.undertow.servlet.spec.HttpServletRequestImpl");
                JClass jakartaWrapper = hierarchy.getClass("jakarta.servlet.http.HttpServletRequestWrapper");
                if (requestFacade != null) {
                    yield requestFacade.getName();
                }
                if (undertowImpl != null) {
                    yield undertowImpl.getName();
                }
                if (jakartaWrapper != null) {
                    yield jakartaWrapper.getName();
                }
                yield null;
            }
            default -> null;
        };
        if (concrete == null) {
            return null;
        }
        JClass clz = hierarchy.getClass(concrete);
        return clz != null ? clz.getType() : null;
    }
    /**
     * Make field source points for the given field.
     * @param field
     * @return
     */
    public static Set<FieldSourcePoint> makeFieldSource(JField field) {
        JClass fieldDeclaringClass = field.getDeclaringClass();
        Type type = field.getType();
        // find all loadField statements relevant to this field in each method of the class.
        Map<JMethod, Set<LoadField>> loadedFieldSources = Maps.newMap();
        Set<FieldSourcePoint> fspSet = Sets.newSet();
        for (JMethod method : fieldDeclaringClass.getDeclaredMethods()) {
            Set<LoadField> loads = Sets.newSet();
            method.getIR().forEach(stmt -> {
                if (stmt instanceof LoadField loadFieldStmt) {
                    if (loadFieldStmt.getFieldAccess().getFieldRef().resolve().equals(field)) {
                        loads.add(loadFieldStmt);
                    }
                }
            });
            loadedFieldSources.put(method, loads);
        }
        loadedFieldSources.entrySet().forEach(entry -> {
            JMethod method = entry.getKey();
            Set<LoadField> loads = entry.getValue();
            for (LoadField load : loads) {
                FieldSourcePoint sourcePoint = new FieldSourcePoint(method, load, new FieldSource(field, type));  // rawEntry只是会影响输出，不影响指针分析的过程。
                fspSet.add(sourcePoint);
            }
        });
        return fspSet;
    }

    /**
     * Add taint to the given source point and propagate it.
     * @param context
     * @param selector
     * @param sourcePoint
     */
    public static void addTaintAndPropagate(HandlerContext context, ContextSelector selector, SourcePoint sourcePoint) {
        Solver solver = context.solver();
        TaintManager manager = context.manager();
        Context emptyContext = selector.getEmptyContext();
        Obj taint = manager.makeTaint(sourcePoint, sourcePoint.source().type());
        if (sourcePoint instanceof ParamSourcePoint paramSourcePoint) {
            IndexRef indexRef = paramSourcePoint.indexRef();
            JMethod method = paramSourcePoint.sourceMethod();
            Var param = method.getIR().getParam(indexRef.index());
            solver.addVarPointsTo(emptyContext, param, taint);
        } else if (sourcePoint instanceof FieldSourcePoint fieldSourcePoint) {
            Var lhs = fieldSourcePoint.loadField().getLValue();
            solver.addVarPointsTo(emptyContext, lhs, taint);
        } else {
            throw new IllegalArgumentException("Unsupported source point type: " + sourcePoint.getClass());
        }

    }

    /**
     * Add taint to the given field source points and propagate it.
     */
    public static void addFieldTaintAndPropagate(HandlerContext context, ContextSelector selector, Set<FieldSourcePoint> sourcePoint) {
        sourcePoint.forEach(sp -> addTaintAndPropagate(context, selector, sp));
    }

    public static List<JClass> resolveParamClass(ClassType classType) {
        JClass jClass = classType.getJClass();
        if (jClass.isAbstract() || jClass.isInterface()) {
            return TypeUtils.getImplsOrSubsOf(jClass);
        }
        return List.of(jClass);
    }

}
