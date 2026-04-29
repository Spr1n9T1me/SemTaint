package com.semtaint.frame.persistence;

import com.semtaint.frame.detector.DetectingHolder;
import com.semtaint.frame.detector.FrameworkDetector;
import com.semtaint.frame.detector.annotation.AnnotationsHolder;
import com.semtaint.frame.detector.config.XmlConfigHolder;
import com.semtaint.frame.lifecycle.Bean;
import com.semtaint.frame.lifecycle.BeanManager;
import com.semtaint.solver.SemTaintSolver;
import com.semtaint.utils.enhance.CodeEnhancer;
import com.semtaint.utils.enhance.EnhancementUtils;
import com.semtaint.utils.enhance.StatementGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.exp.DoubleLiteral;
import pascal.taie.ir.exp.FloatLiteral;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.Literal;
import pascal.taie.ir.exp.LongLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeSystem;
import pascal.taie.language.type.VoidType;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.Sets;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PersistenceHandler implements Plugin {

    private static final Logger logger = LogManager.getLogger(PersistenceHandler.class);

    public static final Descriptor PERSISTENCE_DESC = () -> "PersistenceMockObj";

    private static final Descriptor DB_ENTITY_DESC = () -> "DatabaseEntity";
    private static final Descriptor DB_COLLECTION_DESC = () -> "DatabaseEntityList";
    private static final Descriptor DB_ARRAY_DESC = () -> "DatabaseEntityListData";
    private static final Descriptor DB_OPTIONAL_DESC = () -> "DatabaseOptional";
    private static final Descriptor DB_PAGE_DESC = () -> "DatabasePage";
    private static final Descriptor DB_FIELD_DESC = () -> "DatabaseEntityField";

    private SemTaintSolver solver;
    private ClassHierarchy hierarchy;
    private CSManager csManager;
    private Context emptyCtx;
    private HeapModel heapModel;
    private TypeSystem typeSystem;

    private AnnotationsHolder annotationsHolder;
    private XmlConfigHolder xmlConfigHolder;
    private BeanManager beanManager;

    private boolean isCalled;
    private boolean isSkip;

    private final Map<JClass, Bean> mapperBeans = Maps.newMap();
    private final Set<JClass> syntheticImplClasses = Sets.newSet();
    private final Map<JClass, JClass> syntheticToMapper = Maps.newMap();

    @Override
    public void setSolver(Solver solver) {
        this.solver = (SemTaintSolver) solver;
        this.hierarchy = World.get().getClassHierarchy();
        this.csManager = solver.getCSManager();
        this.emptyCtx = solver.getContextSelector().getEmptyContext();
        this.heapModel = solver.getHeapModel();
        this.typeSystem = solver.getTypeSystem();

        DetectingHolder detectingHolder = World.get().getResult(FrameworkDetector.ID);
        this.annotationsHolder = detectingHolder.getAnnotationsHolder();
        this.xmlConfigHolder = detectingHolder.getXmlConfigHolder();
        this.beanManager = annotationsHolder.getBeanManager();

    }

    @Override
    public void onStart() {
        boolean hasAnnotationMappers = !annotationsHolder.mapperClasses.isEmpty();
        boolean hasXmlMappers = !xmlConfigHolder.getMyBatisMappers().isEmpty();
        if (!hasAnnotationMappers && !hasXmlMappers) {
            logger.info("[-] NO Mapper/Repository found (annotation or XML), skip persistence analysis.");
            this.isSkip = true;
        }
        collectXmlMapperMetadata();
    }

    @Override
    public void onPhaseFinish() {
        if (isSkip || isCalled) {
            return;
        }
        isCalled = true;
        logger.info("[ -----Persistence Framework analysis start----- ]");
        registerPersistenceBeans();
        rewriteSyntheticMethodIRs();

        logger.info("[ -----Persistence Framework analysis end----- ]");
        logger.info("[*] Registered {} persistence beans", mapperBeans.size());
    }

    @Override
    public void onUnresolvedCall(CSObj recv, Context context, Invoke invoke) {
        if (isSkip) {
            return;
        }
        handleUnresolvedPersistenceCall(recv, context, invoke);
    }

    private void collectXmlMapperMetadata() {
        Map<String, String> xmlMappers = xmlConfigHolder.getMyBatisMappers();
        Map<String, Map<String, String>> mapperMethodTypes = xmlConfigHolder.getMapperMethodTypes();
        if (xmlMappers.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> mapperEntry : xmlMappers.entrySet()) {
            String namespace = mapperEntry.getKey();
            JClass mapperInterface = hierarchy.getClass(namespace);
            if (mapperInterface == null || !mapperInterface.isInterface()) {
                continue;
            }
            annotationsHolder.addMapperClass(mapperInterface);

            Map<String, String> methodType = mapperMethodTypes.getOrDefault(namespace, Map.of());
            for (JMethod method : mapperInterface.getDeclaredMethods()) {
                String op = methodType.get(method.getName());
                if (op == null) {
                    continue;
                }
                switch (op) {
                    case "insert" -> annotationsHolder.addInsertMethod(method);
                    case "update" -> annotationsHolder.addUpdateMethod(method);
                    case "delete" -> annotationsHolder.addDeleteMethod(method);
                    default -> annotationsHolder.addSelectMethod(method);
                }
            }
        }
    }

    private void registerPersistenceBeans() {
        Set<JClass> mapperClasses = annotationsHolder.mapperClasses;
        logger.info("[Persistence] Registering {} mapper/repo interfaces", mapperClasses.size());

        for (JClass mapperInterface : mapperClasses) {
            if (!mapperInterface.isInterface()) {
                continue;
            }
            if (mapperBeans.containsKey(mapperInterface)) {
                continue;
            }

            Bean existing = beanManager.getByType(mapperInterface);
            if (existing != null && !existing.getJClass().getName().contains("$$MockedImpl")) {
                mapperBeans.put(mapperInterface, existing);
                continue;
            }

            JClass syntheticImpl = EnhancementUtils.createInterfaceImpl(mapperInterface);
            syntheticImplClasses.add(syntheticImpl);
            syntheticToMapper.put(syntheticImpl, mapperInterface);

            String beanName = toLowerCamelCase(mapperInterface.getSimpleName());
            Obj mockObj = heapModel.getMockObj(PERSISTENCE_DESC, beanName, syntheticImpl.getType());

            Bean bean = beanManager.createWithMockObj(syntheticImpl, beanName, null, mockObj);
            beanManager.addClassHierarchyIndex(mapperInterface, bean);
            mapperBeans.put(mapperInterface, bean);

            logger.info("[Persistence] Registered {} -> {}", mapperInterface.getName(), syntheticImpl.getName());
        }
    }

    private void rewriteSyntheticMethodIRs() {
        int rewritten = 0;
        int total = 0;

        for (JClass syntheticImpl : syntheticImplClasses) {
            JClass mapperInterface = findMapperInterface(syntheticImpl);
            MapperModel mapperModel = mapperInterface == null ? null : findMapperModel(mapperInterface);

            for (JMethod implMethod : syntheticImpl.getDeclaredMethods()) {
                if (implMethod.isConstructor() || implMethod.isStatic() || implMethod.isNative()) {
                    continue;
                }
                total++;

                Type returnType = implMethod.getReturnType();
                if (returnType instanceof VoidType) {
                    continue;
                }

                List<Stmt> newBody = generateMethodBody(implMethod, returnType, mapperModel);
                if (newBody == null || newBody.isEmpty()) {
                    continue;
                }

                implMethod.setIR(new CodeEnhancer().createMethodIR(implMethod, newBody));
                rewritten++;
            }
        }

        int registeredInterfaceMethods = registerMapperInterfaceMethods();

        logger.info("[Persistence] Rewrote {}/{} synthetic method IRs", rewritten, total);
        logger.info("[Persistence] Registered {} mapper/repo interface methods to call graph", registeredInterfaceMethods);
    }

    private int registerMapperInterfaceMethods() {
        int registered = 0;

        for (JClass syntheticImpl : syntheticImplClasses) {
            JClass mapperInterface = findMapperInterface(syntheticImpl);
            if (mapperInterface == null) {
                continue;
            }

            // Bean bean = beanManager.getByType(mapperInterface);
            // Obj beanObj = bean != null ? bean.getMockObj() : null;

            for (JMethod interfaceMethod : mapperInterface.getDeclaredMethods()) {
                if (interfaceMethod.isStatic()) {
                    continue;
                }

                CSMethod csMethod = csManager.getCSMethod(emptyCtx, interfaceMethod);
                solver.addCSMethodWithoutProcess(csMethod);
                registered++;

                // if (beanObj == null) {
                //     continue;
                // }

                // if (interfaceMethod.getIR() == null) {
                //     continue;
                // }
                // Var thisVar = interfaceMethod.getIR().getThis();
                // if (thisVar != null) {
                //     solver.addPointsTo(
                //             csManager.getCSVar(emptyCtx, thisVar),
                //             csManager.getCSObj(emptyCtx, beanObj));
                // }
            }
        }
        return registered;
    }

    @Nullable
    private List<Stmt> generateMethodBody(JMethod method,
                                          Type returnType,
                                          @Nullable MapperModel mapperModel) {
        StatementGenerator gen = new StatementGenerator(method);
        List<Stmt> body = new ArrayList<>();

        if (returnType instanceof PrimitiveType primitiveType) {
            Var ret = gen.newLocal("ret", returnType);
            body.add(gen.generateAssignLiteral(ret, getDefaultLiteral(primitiveType)));
            body.add(gen.generateReturn(ret));
            return body;
        }

        if (!(returnType instanceof ClassType classType)) {
            return null;
        }

        Var ret = gen.newLocal("ret", returnType);
        String returnName = classType.getName();

        if (isCollectionType(returnName)) {
            generateCollectionReturn(gen, body, ret, method, mapperModel);
        } else if ("java.util.Optional".equals(returnName)) {
            generateOptionalReturn(gen, body, ret, method, mapperModel);
        } else if (isPageType(returnName)) {
            generatePageReturn(gen, body, ret, method, mapperModel);
        } else {
            generateEntityReturn(gen, body, ret, method, classType, mapperModel);
        }

        body.add(gen.generateReturn(ret));
        return body;
    }

    private void generateEntityReturn(StatementGenerator gen,
                                      List<Stmt> body,
                                      Var ret,
                                      JMethod method,
                                      ClassType returnType,
                                      @Nullable MapperModel mapperModel) {
        JClass entityClass = returnType.getJClass();
        if (entityClass.isAbstract() || entityClass.isInterface()) {
            JClass impl = resolveConcreteType(entityClass);
            if (impl == null) {
                body.add(gen.generateAssignLiteral(ret, gen.nullLiteral()));
                return;
            }
            entityClass = impl;
        }

        body.add(gen.generateNew(ret, entityClass.getType()));
        initializeEntityFields(gen, body, ret, entityClass, method, mapperModel);
    }

    private void initializeEntityFields(StatementGenerator gen,
                                        List<Stmt> body,
                                        Var entityVar,
                                        JClass entityClass,
                                        JMethod method,
                                        @Nullable MapperModel mapperModel) {
        Set<String> mappedFields = resolveMappedFields(method, mapperModel);

        for (JClass clz = entityClass;
             clz != null && !"java.lang.Object".equals(clz.getName());
             clz = clz.getSuperClass()) {
            for (JField field : clz.getDeclaredFields()) {
                if (field.isStatic()) {
                    continue;
                }
                if (mappedFields != null && !mappedFields.contains(field.getName())) {
                    continue;
                }

                Type fieldType = field.getType();
                if ("java.lang.String".equals(fieldType.getName())) {
                    Var fieldVar = gen.newLocal("f_" + field.getName(), fieldType);
                    String value = "db_" + field.getName();
                    body.add(gen.generateAssignLiteral(fieldVar, gen.stringLiteral(value)));
                    body.add(gen.generateStoreField(entityVar, field, fieldVar));
                } else if (fieldType instanceof ClassType fieldCT
                        && !fieldCT.getJClass().isAbstract()
                        && !fieldCT.getJClass().isInterface()
                        && !isBasicType(fieldType)) {
                    Var fieldVar = gen.newLocal("f_" + field.getName(), fieldType);
                    body.add(gen.generateNew(fieldVar, fieldCT));
                    body.add(gen.generateStoreField(entityVar, field, fieldVar));
                }
            }
        }
    }

    @Nullable
    private Set<String> resolveMappedFields(JMethod method, @Nullable MapperModel mapperModel) {
        if (mapperModel == null) {
            return null;
        }

        MapperModel.ResultTypeInfo info = mapperModel.getResultTypeMappings().get(method.getSignature());
        if (info == null) {
            for (Map.Entry<String, MapperModel.ResultTypeInfo> entry : mapperModel.getResultTypeMappings().entrySet()) {
                String signature = entry.getKey();
                if (signature != null
                        && signature.contains(method.getName() + "(")
                        && signature.contains(method.getDeclaringClass().getName())) {
                    info = entry.getValue();
                    break;
                }
            }
        }

        if (info == null || info.getFieldMappings() == null || info.getFieldMappings().isEmpty()) {
            return null;
        }

        return new HashSet<>(info.getFieldMappings().values());
    }

    private void generateCollectionReturn(StatementGenerator gen,
                                          List<Stmt> body,
                                          Var ret,
                                          JMethod method,
                                          @Nullable MapperModel mapperModel) {
        JClass arrayListClz = hierarchy.getClass("java.util.ArrayList");
        if (arrayListClz == null) {
            body.add(gen.generateAssignLiteral(ret, gen.nullLiteral()));
            return;
        }
        body.add(gen.generateNew(ret, arrayListClz.getType()));

        String elementTypeName = extractReturnGenericType(method);
        if (elementTypeName == null) {
            return;
        }
        JClass elementClass = hierarchy.getClass(elementTypeName);
        if (elementClass == null) {
            return;
        }
        if (elementClass.isAbstract() || elementClass.isInterface()) {
            JClass impl = resolveConcreteType(elementClass);
            if (impl == null) {
                return;
            }
            elementClass = impl;
        }

        Var entityVar = gen.newLocal("entity", elementClass.getType());
        body.add(gen.generateNew(entityVar, elementClass.getType()));
        initializeEntityFields(gen, body, entityVar, elementClass, method, mapperModel);

        JMethod addMethod = arrayListClz.getDeclaredMethods().stream()
            .filter(m -> "add".equals(m.getName()) && m.getParamCount() == 1)
            .findFirst()
            .orElse(null);
        if (addMethod != null) {
            body.add(gen.generateInvokeVirtual(null, ret, addMethod, List.of(entityVar)));
        }
    }

    private void generateOptionalReturn(StatementGenerator gen,
                                        List<Stmt> body,
                                        Var ret,
                                        JMethod method,
                                        @Nullable MapperModel mapperModel) {
        JClass optionalClz = hierarchy.getClass("java.util.Optional");
        if (optionalClz == null) {
            body.add(gen.generateAssignLiteral(ret, gen.nullLiteral()));
            return;
        }

        String elementTypeName = extractReturnGenericType(method);
        if (elementTypeName == null) {
            body.add(gen.generateAssignLiteral(ret, gen.nullLiteral()));
            return;
        }
        JClass elementClass = hierarchy.getClass(elementTypeName);
        if (elementClass == null) {
            body.add(gen.generateAssignLiteral(ret, gen.nullLiteral()));
            return;
        }
        if (elementClass.isAbstract() || elementClass.isInterface()) {
            JClass impl = resolveConcreteType(elementClass);
            if (impl == null) {
                body.add(gen.generateAssignLiteral(ret, gen.nullLiteral()));
                return;
            }
            elementClass = impl;
        }

        Var entityVar = gen.newLocal("entity", elementClass.getType());
        body.add(gen.generateNew(entityVar, elementClass.getType()));
        initializeEntityFields(gen, body, entityVar, elementClass, method, mapperModel);

        JMethod ofMethod = optionalClz.getDeclaredMethod(
                Subsignature.get("of",
                        List.of(typeSystem.getType("java.lang.Object")),
                        optionalClz.getType()));
        if (ofMethod != null) {
            body.add(gen.generateInvokeStatic(ret, ofMethod, List.of(entityVar)));
        } else {
            body.add(gen.generateAssignLiteral(ret, gen.nullLiteral()));
        }
    }

    private void generatePageReturn(StatementGenerator gen,
                                    List<Stmt> body,
                                    Var ret,
                                    JMethod method,
                                    @Nullable MapperModel mapperModel) {
        JClass pageClz = resolvePageClass();
        if (pageClz == null) {
            body.add(gen.generateAssignLiteral(ret, gen.nullLiteral()));
            return;
        }
        body.add(gen.generateNew(ret, pageClz.getType()));

        String elementTypeName = extractReturnGenericType(method);
        if (elementTypeName == null) {
            return;
        }
        JClass elementClass = hierarchy.getClass(elementTypeName);
        if (elementClass == null) {
            return;
        }
        if (elementClass.isAbstract() || elementClass.isInterface()) {
            JClass impl = resolveConcreteType(elementClass);
            if (impl == null) {
                return;
            }
            elementClass = impl;
        }

        JClass arrayListClz = hierarchy.getClass("java.util.ArrayList");
        if (arrayListClz == null) {
            return;
        }

        Var listVar = gen.newLocal("records", arrayListClz.getType());
        body.add(gen.generateNew(listVar, arrayListClz.getType()));

        Var entityVar = gen.newLocal("entity", elementClass.getType());
        body.add(gen.generateNew(entityVar, elementClass.getType()));
        initializeEntityFields(gen, body, entityVar, elementClass, method, mapperModel);

        JMethod addMethod = arrayListClz.getDeclaredMethods().stream()
            .filter(m -> "add".equals(m.getName()) && m.getParamCount() == 1)
            .findFirst()
            .orElse(null);
        if (addMethod != null) {
            body.add(gen.generateInvokeVirtual(null, listVar, addMethod, List.of(entityVar)));
        }

        JField recordsField = findFieldInHierarchy(pageClz, "records");
        if (recordsField == null) {
            recordsField = findFieldInHierarchy(pageClz, "content");
        }
        if (recordsField != null) {
            body.add(gen.generateStoreField(ret, recordsField, listVar));
        }
    }

    private void handleUnresolvedPersistenceCall(CSObj recv, Context context, Invoke invoke) {
        Obj recvObj = recv.getObject();
        if (!(recvObj instanceof MockObj mockObj)) {
            return;
        }
        if (!isPersistenceMockObj(mockObj)) {
            return;
        }

        Var resultVar = invoke.getResult();
        if (resultVar == null) {
            return;
        }

        JMethod callee = invoke.getMethodRef().resolveNullable();
        if (callee == null) {
            return;
        }

        Type returnType = callee.getReturnType();
        if (returnType instanceof PrimitiveType || returnType instanceof VoidType) {
            return;
        }
        if (!(returnType instanceof ClassType returnCT)) {
            return;
        }

        CSVar resultCSVar = csManager.getCSVar(context, resultVar);
        String returnName = returnCT.getName();
        if (isCollectionType(returnName)) {
            fallbackCollectionReturn(callee, mockObj, resultCSVar);
        } else if ("java.util.Optional".equals(returnName)) {
            fallbackOptionalReturn(callee, mockObj, resultCSVar);
        } else if (isPageType(returnName)) {
            fallbackPageReturn(callee, mockObj, resultCSVar);
        } else {
            fallbackEntityReturn(returnCT, mockObj, resultCSVar);
        }

//        logger.info("[Persistence-Fallback] Handled unresolved: {}", invoke.getMethodRef());
    }

    private boolean isPersistenceMockObj(MockObj mockObj) {
        if (mockObj.getDescriptor() == PERSISTENCE_DESC) {
            return true;
        }
        if (mockObj.getType() instanceof ClassType ct) {
            return syntheticImplClasses.contains(ct.getJClass());
        }
        return false;
    }

    private void fallbackEntityReturn(ClassType returnType, Obj mapperObj,
                                      CSVar resultCSVar) {
        JClass returnClass = returnType.getJClass();
        if (returnClass.isAbstract() || returnClass.isInterface()) {
            JClass impl = resolveConcreteType(returnClass);
            if (impl == null) {
                return;
            }
            returnType = impl.getType();
        }

        Obj entityObj = createEntityMockObj(mapperObj, returnType);
        solver.addPointsTo(resultCSVar, csManager.getCSObj(emptyCtx, entityObj));
    }

    private void fallbackCollectionReturn(JMethod callee, Obj mapperObj,
                                          CSVar resultCSVar) {
        String elementTypeName = extractReturnGenericType(callee);
        if (elementTypeName == null) {
            return;
        }

        JClass elementClass = hierarchy.getClass(elementTypeName);
        if (elementClass == null) {
            return;
        }
        ClassType elementType = elementClass.getType();
        if (elementClass.isInterface() || elementClass.isAbstract()) {
            JClass impl = resolveConcreteType(elementClass);
            if (impl == null) {
                return;
            }
            elementType = impl.getType();
        }

        Obj entityObj = createEntityMockObj(mapperObj, elementType);

        JClass arrayListClz = hierarchy.getClass("java.util.ArrayList");
        if (arrayListClz == null) {
            return;
        }

        Obj listObj = heapModel.getMockObj(DB_COLLECTION_DESC, mapperObj, arrayListClz.getType());
        CSObj listCSObj = csManager.getCSObj(emptyCtx, listObj);

        JField elementDataField = arrayListClz.getDeclaredField("elementData");
        if (elementDataField == null) {
            solver.addPointsTo(resultCSVar, listCSObj);
            return;
        }

        ArrayType arrayType = typeSystem.getArrayType(typeSystem.getType("java.lang.Object"), 1);
        Obj arrayObj = heapModel.getMockObj(DB_ARRAY_DESC, listObj, arrayType);
        CSObj arrayCSObj = csManager.getCSObj(emptyCtx, arrayObj);

        solver.addPointsTo(csManager.getArrayIndex(arrayCSObj), csManager.getCSObj(emptyCtx, entityObj));
        solver.addPointsTo(csManager.getInstanceField(listCSObj, elementDataField), arrayCSObj);
        solver.addPointsTo(resultCSVar, listCSObj);
    }

    private void fallbackOptionalReturn(JMethod callee, Obj mapperObj,
                                        CSVar resultCSVar) {
        String elementTypeName = extractReturnGenericType(callee);
        if (elementTypeName == null) {
            return;
        }

        JClass elementClass = hierarchy.getClass(elementTypeName);
        if (elementClass == null) {
            return;
        }
        if (elementClass.isInterface() || elementClass.isAbstract()) {
            JClass impl = resolveConcreteType(elementClass);
            if (impl == null) {
                return;
            }
            elementClass = impl;
        }

        Obj entityObj = createEntityMockObj(mapperObj, elementClass.getType());

        JClass optionalClz = hierarchy.getClass("java.util.Optional");
        if (optionalClz == null) {
            return;
        }
        Obj optionalObj = heapModel.getMockObj(DB_OPTIONAL_DESC, mapperObj, optionalClz.getType());
        CSObj optionalCSObj = csManager.getCSObj(emptyCtx, optionalObj);

        JField valueField = optionalClz.getDeclaredField("value");
        if (valueField != null) {
            solver.addPointsTo(csManager.getInstanceField(optionalCSObj, valueField),
                    csManager.getCSObj(emptyCtx, entityObj));
        }
        solver.addPointsTo(resultCSVar, optionalCSObj);
    }

    private void fallbackPageReturn(JMethod callee, Obj mapperObj,
                                    CSVar resultCSVar) {
        String elementTypeName = extractReturnGenericType(callee);
        if (elementTypeName == null) {
            return;
        }

        JClass elementClass = hierarchy.getClass(elementTypeName);
        if (elementClass == null) {
            return;
        }
        if (elementClass.isInterface() || elementClass.isAbstract()) {
            JClass impl = resolveConcreteType(elementClass);
            if (impl == null) {
                return;
            }
            elementClass = impl;
        }

        Obj entityObj = createEntityMockObj(mapperObj, elementClass.getType());

        JClass pageClz = resolvePageClass();
        if (pageClz == null) {
            return;
        }
        Obj pageObj = heapModel.getMockObj(DB_PAGE_DESC, mapperObj, pageClz.getType());
        CSObj pageCSObj = csManager.getCSObj(emptyCtx, pageObj);

        JField recordsField = findFieldInHierarchy(pageClz, "records");
        if (recordsField == null) {
            recordsField = findFieldInHierarchy(pageClz, "content");
        }
        if (recordsField != null) {
            JClass arrayListClz = hierarchy.getClass("java.util.ArrayList");
            if (arrayListClz != null) {
                Obj listObj = heapModel.getMockObj(DB_COLLECTION_DESC, pageObj, arrayListClz.getType());
                CSObj listCSObj = csManager.getCSObj(emptyCtx, listObj);

                JField elementDataField = arrayListClz.getDeclaredField("elementData");
                if (elementDataField != null) {
                    ArrayType arrayType = typeSystem.getArrayType(typeSystem.getType("java.lang.Object"), 1);
                    Obj arrayObj = heapModel.getMockObj(DB_ARRAY_DESC, listObj, arrayType);
                    CSObj arrayCSObj = csManager.getCSObj(emptyCtx, arrayObj);

                    solver.addPointsTo(csManager.getArrayIndex(arrayCSObj),
                            csManager.getCSObj(emptyCtx, entityObj));
                    solver.addPointsTo(csManager.getInstanceField(listCSObj, elementDataField), arrayCSObj);
                }

                solver.addPointsTo(csManager.getInstanceField(pageCSObj, recordsField), listCSObj);
            }
        }

        solver.addPointsTo(resultCSVar, pageCSObj);
    }

    private Obj createEntityMockObj(Obj mapperObj, ClassType entityType) {
        Obj entityObj = heapModel.getMockObj(DB_ENTITY_DESC, mapperObj, entityType);
        CSObj csEntity = csManager.getCSObj(emptyCtx, entityObj);

        for (JClass clz = entityType.getJClass();
             clz != null && !"java.lang.Object".equals(clz.getName());
             clz = clz.getSuperClass()) {
            for (JField field : clz.getDeclaredFields()) {
                if (field.isStatic()) {
                    continue;
                }
                Type fieldType = field.getType();
                InstanceField iField = csManager.getInstanceField(csEntity, field);

                if ("java.lang.String".equals(fieldType.getName())) {
                    Obj strObj = heapModel.getMockObj(DB_FIELD_DESC,
                            entityObj.getAllocation() + "." + field.getName(),
                            fieldType);
                    solver.addPointsTo(iField, csManager.getCSObj(emptyCtx, strObj));
                } else if (fieldType instanceof ClassType fieldCT
                        && !fieldCT.getJClass().isAbstract()
                        && !isBasicType(fieldType)) {
                    Obj fieldObj = heapModel.getMockObj(DB_FIELD_DESC,
                            entityObj.getAllocation() + "." + field.getName(),
                            fieldType);
                    solver.addPointsTo(iField, csManager.getCSObj(emptyCtx, fieldObj));
                }
            }
        }
        return entityObj;
    }

    @Nullable
    private String extractReturnGenericType(JMethod method) {
        var gSignature = method.getGSignature();
        if (gSignature != null) {
            String gsig = gSignature.toString();
            int lp = gsig.indexOf('(');
            if (lp > 0) {
                String resultSig = gsig.substring(0, lp).trim();
                if (resultSig.startsWith("<")) {
                    int gt = resultSig.indexOf('>');
                    if (gt > 0 && gt + 1 < resultSig.length()) {
                        resultSig = resultSig.substring(gt + 1).trim();
                    }
                }
                String extracted = extractFirstTypeArgument(resultSig);
                if (extracted != null) {
                    JClass candidate = hierarchy.getClass(extracted);
                    if (candidate != null) {
                        return extracted;
                    }
                }
            }
        }

        String typeName = method.getReturnType().getName();
        return extractFirstTypeArgument(typeName);
    }

    @Nullable
    private String extractFirstTypeArgument(String typeText) {
        if (typeText == null) {
            return null;
        }
        int start = typeText.indexOf('<');
        int end = typeText.lastIndexOf('>');
        if (start < 0 || end <= start) {
            return null;
        }
        String inner = typeText.substring(start + 1, end).trim();
        if (inner.isEmpty()) {
            return null;
        }

        int depth = 0;
        int split = -1;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (ch == ',' && depth == 0) {
                split = i;
                break;
            }
        }
        String candidate = (split >= 0 ? inner.substring(0, split) : inner).trim();
        candidate = candidate.replace("? extends ", "")
                .replace("? super ", "")
                .replace("?", "")
                .trim();
        int genericPos = candidate.indexOf('<');
        if (genericPos > 0) {
            candidate = candidate.substring(0, genericPos).trim();
        }
        return candidate.isEmpty() ? null : candidate;
    }

    @Nullable
    private JClass resolveConcreteType(JClass target) {
        List<JClass> impls = hierarchy.getAllSubclassesOf(target).stream()
                .filter(c -> !c.isAbstract() && !c.isInterface())
                .toList();
        List<JClass> appImpls = impls.stream().filter(JClass::isApplication).toList();
        if (!appImpls.isEmpty()) {
            return appImpls.get(0);
        }
        return impls.isEmpty() ? null : impls.get(0);
    }

    @Nullable
    private JClass resolvePageClass() {
        String[] candidates = {
                "com.baomidou.mybatisplus.extension.plugins.pagination.Page",
                "org.springframework.data.domain.PageImpl"
        };
        for (String name : candidates) {
            JClass clz = hierarchy.getClass(name);
            if (clz != null) {
                return clz;
            }
        }
        return null;
    }

    @Nullable
    private JField findFieldInHierarchy(JClass clz, String fieldName) {
        JClass current = clz;
        while (current != null) {
            JField field = current.getDeclaredField(fieldName);
            if (field != null) {
                return field;
            }
            current = current.getSuperClass();
        }
        return null;
    }

    private static boolean isCollectionType(String name) {
        return "java.util.List".equals(name)
                || "java.util.Collection".equals(name)
                || "java.util.Set".equals(name)
                || "java.util.ArrayList".equals(name)
                || "java.util.LinkedList".equals(name);
    }

    private static boolean isPageType(String name) {
        return "org.springframework.data.domain.Page".equals(name)
                || "com.baomidou.mybatisplus.core.metadata.IPage".equals(name)
                || "com.baomidou.mybatisplus.extension.plugins.pagination.Page".equals(name);
    }


    private Literal getDefaultLiteral(PrimitiveType type) {
        String name = type.getName();
        return switch (name) {
            case "long" -> LongLiteral.get(0L);
            case "float" -> FloatLiteral.get(0.0F);
            case "double" -> DoubleLiteral.get(0.0D);
            case "int", "short", "byte", "char", "boolean" -> IntLiteral.get(0);
            default -> IntLiteral.get(0);
        };
    }

    @Nullable
    private JClass findMapperInterface(JClass syntheticImpl) {
        JClass mapped = syntheticToMapper.get(syntheticImpl);
        if (mapped != null) {
            return mapped;
        }
        for (JClass iface : syntheticImpl.getInterfaces()) {
            return iface;
        }
        return null;
    }

    @Nullable
    private MapperModel findMapperModel(JClass mapperInterface) {
        return annotationsHolder.mapperModels.stream()
                .filter(m -> mapperInterface.equals(m.getMapperInterface()))
                .findFirst()
                .orElse(null);
    }

    private static boolean isBasicType(Type type) {
        if (type instanceof PrimitiveType) {
            return true;
        }
        if (!(type instanceof ClassType ct)) {
            return false;
        }
        String n = ct.getName();
        return "java.lang.String".equals(n)
                || "java.lang.Integer".equals(n)
                || "java.lang.Long".equals(n)
                || "java.lang.Boolean".equals(n)
                || "java.lang.Double".equals(n)
                || "java.lang.Float".equals(n)
                || "java.lang.Short".equals(n)
                || "java.lang.Byte".equals(n)
                || "java.lang.Character".equals(n);
    }

    private static String toLowerCamelCase(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return simpleName;
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

}
