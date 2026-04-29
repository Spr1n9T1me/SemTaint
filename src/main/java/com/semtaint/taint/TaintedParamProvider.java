package com.semtaint.taint;

import com.semtaint.utils.BaseClasses;
import lombok.Getter;
import pascal.taie.World;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.DeclaredParamProvider;
import pascal.taie.analysis.pta.core.solver.EntryPoint;
import pascal.taie.analysis.pta.core.solver.ParamProvider;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.core.solver.SpecifiedParamProvider;
import pascal.taie.analysis.pta.plugin.taint.FieldSourcePoint;
import pascal.taie.analysis.pta.plugin.taint.ParamSourcePoint;
import pascal.taie.analysis.pta.plugin.taint.SourcePoint;
import pascal.taie.analysis.pta.plugin.taint.TaintManager;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.generics.ClassTypeGSignature;
import pascal.taie.language.generics.ReferenceTypeGSignature;
import pascal.taie.language.generics.TypeArgument;
import pascal.taie.language.type.*;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.TwoKeyMultiMap;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * This {@link TaintedParamProvider} creates parameter objects of the declared types,
 * and the objects pointed to by fields of parameter objects,
 * as well as elements of array objects. This class ignores
 * non-instantiable types, i.e., primitive types and abstract classes.
 */
public class TaintedParamProvider implements ParamProvider {

    /**
     * Special index representing "this" variable.
     */
    private static final int THIS_INDEX = -1;

    /**
     * Represents combination of a method and a parameter index.
     *
     * @param method the entry method
     * @param index  the index of the parameter
     */
    private record MethodParam(JMethod method, int index) {

        @Override
        public String toString() {
            return "MethodParam{" + method + '/' +
                    (index == THIS_INDEX ? "this" : index) + '}';
        }
    }

    private record QueueNode(Obj obj, int level, int maxDepth) {
    }

    private static final String HTTP_SERVLET_REQUEST = "jakarta.servlet.http.HttpServletRequest";

    private static final String SERVLET_REQUEST = "jakarta.servlet.ServletRequest";

        private static final Set<String> COLLECTION_TYPES = Set.of(
            "java.util.List", "java.util.ArrayList", "java.util.LinkedList",
            "java.util.Set", "java.util.HashSet", "java.util.TreeSet",
            "java.util.Collection"
        );

        private static final Set<String> MAP_TYPES = Set.of(
            "java.util.Map", "java.util.HashMap", "java.util.TreeMap",
            "java.util.LinkedHashMap", "java.util.concurrent.ConcurrentHashMap"
        );

    @Nullable
    private Obj thisObj;

    @Getter
    private Obj[] paramObjs;

    private TwoKeyMultiMap<Obj, JField, Obj> fieldObjs;

    private MultiMap<Obj, Obj> arrayObjs;

    private final Solver solver;

    private TaintManager manager;

    /**
     * @param method    the entry method.
     * @param heapModel the model for generating mock objects.
     */
    public TaintedParamProvider(JMethod method, HeapModel heapModel,
                                Solver solver, TaintManager manager) {
        this(method, heapModel, 0, solver, manager);
    }

    /**
     * @param method    the entry method.
     * @param heapModel the model for generating mock objects.
     * @param k         level of field/array accesses. If this is not 0,
     *                  the provider generates objects recursively along
     *                  k field/array accesses.
     */
    public TaintedParamProvider(JMethod method, HeapModel heapModel,
                                int k, Solver solver, TaintManager manager) {
        this.solver = solver;
        this.manager = manager;
        generateObjs(method, heapModel, k);
    }

    private void generateObjs(JMethod method, HeapModel heapModel, int k) {
        Deque<Pair<Obj, Integer>> queueThis = new ArrayDeque<>();
        Deque<QueueNode> queueParam = new ArrayDeque<>();
        // generate this (receiver) object
        if (!method.isStatic() && !method.getDeclaringClass().isAbstract()) {
            thisObj = heapModel.getMockObj(Descriptor.ENTRY_DESC,
                    new MethodParam(method, THIS_INDEX),
                    method.getDeclaringClass().getType(), method);
            queueThis.add(new Pair<>(thisObj, 0)); //TODO thisObj添加进污点会产生误报，污点应该只由入参产生。or Not?
            registerConstructorEntryPoints(thisObj, solver);
        }
        // generate parameter objects
        paramObjs = new Obj[method.getParamCount()];
        for (int i = 0; i < method.getParamCount(); ++i) {
            Type paramType = method.getParamType(i);
            int maxDepth = getParamMaxDepth(paramType, k);
            if( paramType instanceof ClassType cType && (cType.getJClass().isAbstract() || cType.getJClass().isInterface())){
                // abstract/interface class , find the implement class
                ParamSourcePoint paramSourcePoint = TaintUtils.makeConcreteParamSource(method,cType,i);
                Obj taint = makeTaint(paramSourcePoint);
                paramObjs[i] = taint;
                if (isInstantiable(cType))
                    queueParam.add(new QueueNode(paramObjs[i], 0, maxDepth)); // make sure it must be instantiable
            }else {
                // concrete class
                ParamSourcePoint paramSourcePoint = TaintUtils.makeParamSource(method, i);
                Obj taint = makeTaint(paramSourcePoint);
                paramObjs[i] = taint;
                if (isBasic(paramType)) {
                    //Basic Class, do nothing and don't add to queue
                }else if (isInstantiable(paramType)){
                    queueParam.add(new QueueNode(paramObjs[i], 0, maxDepth));
                    //normal class , add to queue
                }
            }
        }
        // generate k-level field and array objects by a level-order traversal
        fieldObjs = Maps.newTwoKeyMultiMap();
        arrayObjs = Maps.newMultiMap();
        // for the sake of isInstantiable(), no Primitive type objs in queue.
        while (!queueParam.isEmpty()) {
            QueueNode node = queueParam.pop();
            Obj base = node.obj();
            int level = node.level();
            int maxDepth = node.maxDepth();
            if (level < maxDepth) {
                Type type = base.getType();
                if (type instanceof ClassType cType && !isBasic(cType)) {
                    //TODO cType must not be interface/abstract
                    for (JField field : cType.getJClass().getDeclaredFields()) {
                        Type fieldType = field.getType();
                        if (isCollectionType(fieldType)) {
                            boolean handled = handleCollectionField(
                                    method, base, field, level, maxDepth, heapModel, queueParam);
                            if (handled) {
                                continue;
                            }
                        }
                        if (isBasic(fieldType)) {
                            Set<FieldSourcePoint> sourcePoints = TaintUtils.makeFieldSource(field);
                            sourcePoints.forEach(sourcePoint -> {
                                Obj taint = makeTaint(sourcePoint);
                                fieldObjs.put(base, field, taint); //field of basic type, no add.
                            });
                        } else if (isInstantiable(fieldType)){
                            Set<FieldSourcePoint> sourcePoints = TaintUtils.makeFieldSource(field);
                            sourcePoints.forEach(sourcePoint -> { // make taint
                                Obj taint = makeTaint(sourcePoint);
                                fieldObjs.put(base, field, taint);
                                queueParam.add(new QueueNode(taint, level + 1, maxDepth)); // add to queue
                            });
                        }
                    }
                } else if (type instanceof ArrayType aType) {
                    Type elemType = aType.elementType();
                    if (isBasic(elemType)) {
                        // should be deleted , no exec here.
                    } else if (isInstantiable(elemType)) {
                        Obj elem = heapModel.getMockObj(() -> "TaintObj",
                                base.getAllocation() + "[*]",
                                elemType, method);
                        arrayObjs.put(base, elem);
                        registerConstructorEntryPoints(elem, solver);
                        queueParam.add(new QueueNode(elem, level + 1, maxDepth));
                    }
                }
            }
        }
        while (!queueThis.isEmpty()) { // queue for thisObj, no Taint.
            Pair<Obj, Integer> pair = queueThis.pop();
            Obj base = pair.first();
            int level = pair.second();
            if (level < k) {
                Type type = base.getType();
                if (type instanceof ClassType cType) {
                    for (JField field : cType.getJClass().getDeclaredFields()) {
                        Type fieldType = field.getType();
                        if (isInstantiable(fieldType)) {
                            Obj obj = heapModel.getMockObj(Descriptor.ENTRY_DESC,
                                    base.getAllocation() + "." + field.getName(),
                                    fieldType, method);
                            fieldObjs.put(base, field, obj);
                            registerConstructorEntryPoints(obj, solver);
                            queueThis.add(new Pair<>(obj, level + 1));
                        }
                    }
                } else if (type instanceof ArrayType aType) {
                    Type elemType = aType.elementType();
                    if (isInstantiable(elemType)) {
                        Obj elem = heapModel.getMockObj(Descriptor.ENTRY_DESC,
                                base.getAllocation() + "[*]",
                                elemType, method);
                        arrayObjs.put(base, elem);
                        registerConstructorEntryPoints(elem, solver);
                        queueThis.add(new Pair<>(elem, level + 1));
                    }
                }
            }
        }
    }

    private boolean handleCollectionField(JMethod method, Obj base, JField field,
                                          int level, int maxDepth,
                                          HeapModel heapModel,
                                          Deque<QueueNode> queueParam) {
        String elementTypeName = extractGenericElementType(field);
        if (elementTypeName == null) {
            return false;
        }

        var hierarchy = World.get().getClassHierarchy();
        JClass elementClass = hierarchy.getClass(elementTypeName);
        if (elementClass == null) {
            return false;
        }

        String concreteCollectionName = resolveConcreteCollection(field.getType());
        JClass concreteCollectionClass = hierarchy.getClass(concreteCollectionName);
        if (concreteCollectionClass == null) {
            return false;
        }

        Obj collectionObj = heapModel.getMockObj(
                () -> "EntryTaintCollectionObj",
                base.getAllocation() + "." + field.getName(),
                concreteCollectionClass.getType(),
                method);
        fieldObjs.put(base, field, collectionObj);
        registerConstructorEntryPoints(collectionObj, solver);

        Set<FieldSourcePoint> sourcePoints = TaintUtils.makeFieldSource(field);
        if (sourcePoints.isEmpty()) {
            return true;
        }
        Obj firstElementTaint = null;
        for (FieldSourcePoint sourcePoint : sourcePoints) {
            Obj elementTaint = makeTaint(sourcePoint);
            if (firstElementTaint == null) {
                firstElementTaint = elementTaint;
            }

            JField elementDataField = concreteCollectionClass.getDeclaredField("elementData");
            if (elementDataField != null) {
                TypeSystem typeSystem = World.get().getTypeSystem();
                ArrayType elementDataArrayType = typeSystem.getArrayType(
                        typeSystem.getType("java.lang.Object"), 1);
                Obj elementDataArrayObj = heapModel.getMockObj(
                        () -> "EntryTaintCollectionData",
                        collectionObj.getAllocation() + ".elementData",
                        elementDataArrayType,
                        method);
                fieldObjs.put(collectionObj, elementDataField, elementDataArrayObj);
                arrayObjs.put(elementDataArrayObj, elementTaint);
            } else {
                arrayObjs.put(collectionObj, elementTaint);
            }
        }

        if (level + 1 < maxDepth
                && firstElementTaint != null
                && isInstantiable(elementClass.getType())
                && !isBasic(elementClass.getType())) {
            queueParam.add(new QueueNode(firstElementTaint, level + 1, maxDepth));
        }
        return true;
    }

    private static boolean isCollectionType(Type type) {
        if (!(type instanceof ClassType classType)) {
            return false;
        }
        String name = classType.getName();
        return COLLECTION_TYPES.contains(name) || MAP_TYPES.contains(name);
    }

    @Nullable
    private static String extractGenericElementType(JField field) {
        try {
            ReferenceTypeGSignature signature = field.getGSignature();
            if (!(signature instanceof ClassTypeGSignature classTypeSignature)) {
                return null;
            }
            if (classTypeSignature.getSignatures().isEmpty()) {
                return null;
            }
            List<TypeArgument> typeArgs = classTypeSignature.getSignatures()
                    .get(classTypeSignature.getSignatures().size() - 1)
                    .typeArgs();
            if (typeArgs.isEmpty()) {
                return null;
            }

            String fieldTypeName = field.getType().getName();
            int targetIndex = MAP_TYPES.contains(fieldTypeName) ? 1 : 0;
            if (typeArgs.size() <= targetIndex) {
                return null;
            }

            ReferenceTypeGSignature targetSig = typeArgs.get(targetIndex).getGSignature();
            if (targetSig instanceof ClassTypeGSignature targetClassSig) {
                String packageName = targetClassSig.getPackageName();
                String simpleName = targetClassSig.getSignatures().isEmpty()
                        ? null
                        : targetClassSig.getSignatures()
                        .get(targetClassSig.getSignatures().size() - 1).className();
                if (simpleName == null) {
                    return null;
                }
                return packageName == null ? simpleName : packageName + "." + simpleName;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String resolveConcreteCollection(Type type) {
        if (!(type instanceof ClassType classType)) {
            return "java.util.ArrayList";
        }
        return switch (classType.getName()) {
            case "java.util.List", "java.util.Collection",
                 "java.lang.Iterable", "java.util.ArrayList" -> "java.util.ArrayList";
            case "java.util.Set", "java.util.HashSet" -> "java.util.HashSet";
            case "java.util.LinkedList" -> "java.util.LinkedList";
            case "java.util.Map", "java.util.HashMap" -> "java.util.HashMap";
            case "java.util.TreeMap" -> "java.util.TreeMap";
            default -> "java.util.ArrayList";
        };
    }

    public static void registerConstructorEntryPoints(Obj mockObj, Solver solver) {
        Type type = mockObj.getType();
        if (!(type instanceof ClassType classType)) {
            return;
        }
        JClass clz = classType.getJClass();
        if (clz.isAbstract() || clz.isInterface()) {
            return;
        }

        HeapModel heapModel = solver.getHeapModel();
        boolean isJdkClass = clz.getName().startsWith("java.")
                || clz.getName().startsWith("javax.")
                || clz.getName().startsWith("jakarta.");

        for (JMethod ctor : clz.getDeclaredMethods()) {
            if (!ctor.isConstructor()) {
                continue;
            }
            if (isJdkClass && ctor.getParamCount() != 0) {
                continue;
            }
            SpecifiedParamProvider paramProvider = new SpecifiedParamProvider.Builder(ctor)
                    .setDelegate(new DeclaredParamProvider(ctor, heapModel))
                    .addThisObj(mockObj)
                    .build();
            solver.addEntryPoint(new EntryPoint(ctor, paramProvider));
        }
    }

    private static boolean isInstantiable(Type type) {
        return (type instanceof ClassType cType && !cType.getJClass().isAbstract())
                || type instanceof ArrayType;
    }

    private static boolean isBasic(Type type){
        //judge if type in enum BaseClasses
        return type instanceof PrimitiveType ||
                (type instanceof ClassType classType && BaseClasses.get().contains(classType.getName()));
    }

    private static int getParamMaxDepth(Type paramType, int k) {
        if (k <= 0) {
            return 0;
        }
        return isHttpServletRequestRelated(paramType) ? 1 : k;
    }

    private static boolean isHttpServletRequestRelated(Type type) {
        if (!(type instanceof ClassType classType)) {
            return false;
        }
        try {
            TypeSystem typeSystem = World.get().getTypeSystem();
            Type httpServletRequestType = typeSystem.getType(HTTP_SERVLET_REQUEST);
            Type servletRequestType = typeSystem.getType(SERVLET_REQUEST);
            return typeSystem.isSubtype(httpServletRequestType, classType)
                    || typeSystem.isSubtype(servletRequestType, classType);
        } catch (RuntimeException e) {
            String typeName = classType.getName();
            return HTTP_SERVLET_REQUEST.equals(typeName)
                    || SERVLET_REQUEST.equals(typeName)
                    || typeName.contains("HttpServletRequest");
        }
    }

    @Override
    public Set<Obj> getThisObjs() {
        return thisObj != null ? Set.of(thisObj) : Set.of();
    }

    @Override
    public Set<Obj> getParamObjs(int i) {
        return paramObjs[i] != null ? Set.of(paramObjs[i]) : Set.of();
    }

    @Override
    public TwoKeyMultiMap<Obj, JField, Obj> getFieldObjs() {
        return Maps.unmodifiableTwoKeyMultiMap(fieldObjs);
    }

    @Override
    public MultiMap<Obj, Obj> getArrayObjs() {
        return Maps.unmodifiableMultiMap(arrayObjs);
    }

    private Obj makeTaint(SourcePoint sourcePoint) {
        return manager.makeTaint(sourcePoint, sourcePoint.source().type());
    }
}
