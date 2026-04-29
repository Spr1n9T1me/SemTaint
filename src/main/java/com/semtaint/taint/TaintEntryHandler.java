package com.semtaint.taint;

import com.semtaint.config.ConfManager;
import com.semtaint.frame.detector.FrameworkDetector;
import com.semtaint.frame.detector.DetectingHolder;
import com.semtaint.frame.lifecycle.Bean;
import com.semtaint.frame.lifecycle.BeanManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.*;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.plugin.taint.*;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Sets;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Set;

public class TaintEntryHandler extends OnFlyHandler {

    private static final Logger logger = LogManager.getLogger(TaintEntryHandler.class);

    public DetectingHolder detectingHolder;
    private HeapModel heapModel;
//    private MultiMap<String,EntryPoint> entryPointsMap;
    private Set<String> taintObjs;
    private ContextSelector contextSelector;
    private BufferedWriter bufferedWriter;
    public static JMethod currentMethod;

    public record EntryTaintObjModel(CSMethod entry , TaintedParamProvider paramProvider) { }

    public TaintEntryHandler(HandlerContext context) {
        super(context);
        this.detectingHolder = World.get().getResult(FrameworkDetector.ID);
    }

    @Override
    public void onStart() {
        this.heapModel = solver.getHeapModel();
        this.contextSelector = solver.getContextSelector();
        this.taintObjs = Sets.newSet();
        try {
            String entryFile = ConfManager.v().getString("path.entry-output-file", "");
            this.bufferedWriter = new BufferedWriter(new FileWriter(entryFile, true));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        JMethod method = csMethod.getMethod();
        if (TaintUtils.isFrameworkEntryMethod(detectingHolder, method)) {
            currentMethod = method;
            int count = method.getParamCount();
            if (count != 0) {
                createKScaleParamObj(csMethod, ConfManager.v().getInt("pta.k-level", 2));
            }
        }
    }


    public void createEmptyParamObj(CSMethod csMethod) {
        JMethod method = csMethod.getMethod();
        IR ir = method.getIR(); // TODO 直接给web入口方法的param创建污点对象是否合适？
        for (int i = 0; i < ir.getParams().size(); i++) {
            Var param_i = ir.getParam(i);
            IndexRef paramIndexRef = new IndexRef(IndexRef.Kind.VAR, i, null);
            ParamSourcePoint paramSourcePoint = new ParamSourcePoint( // source point是必须要有的，否则无法识别。
                    method,
                    paramIndexRef,
                    new ParamSource(method,paramIndexRef, param_i.getType()));
            //这里使用container加以区分不同方法名的source，以防止通用的taintObj标记(使用desc、alloc、type)出现混淆。
            //method似乎用了也没用，因为它不影响equals，修改desc还是有效的
//          Obj taintObj = heapModel.getMockObj(() -> "springTaintObj_"+ method, paramSourcePoint, param_i.getType());
            Obj taintObj = manager.makeTaint(paramSourcePoint, paramSourcePoint.source().type()); //TODO 过污染 与 欠污染 的问题
            solver.addVarPointsTo(csMethod.getContext(),param_i, taintObj);
        }
    }

    public void createKScaleParamObj(CSMethod csMethod, int k) {
        TaintedParamProvider declaredParamProvider = new TaintedParamProvider(
            csMethod.getMethod(), heapModel, k, solver, manager);
        EntryTaintObjModel entryTaintObjModel = new EntryTaintObjModel(csMethod,declaredParamProvider);
        addTaintEntry(entryTaintObjModel);
    }
    public void addTaintEntry(EntryTaintObjModel entryTaintObjModel){
        Context entryCtx = contextSelector.getEmptyContext();
        CSMethod csMethod = entryTaintObjModel.entry();
//        csManager.getCSMethod()
        JMethod entryMethod = entryTaintObjModel.entry().getMethod();
        TaintedParamProvider paramProvider = entryTaintObjModel.paramProvider();
        IR ir = entryMethod.getIR();
        Set<Obj> createdTaintObjs = Sets.newSet();
        // pass this objects
        if (!entryMethod.isStatic()) {
            BeanManager beanManager = detectingHolder.getAnnotationsHolder().getBeanManager();
            Bean bean = beanManager.getByType(entryMethod.getDeclaringClass());
            if (bean != null && bean.getMockObj() != null) {
                solver.addVarPointsTo(entryCtx, ir.getThis(), entryCtx, bean.getMockObj());
            } else {
                for (Obj thisObj : paramProvider.getThisObjs()) {
                    solver.addVarPointsTo(entryCtx, ir.getThis(), entryCtx, thisObj);
                }
            }
        }

        // pass parameter objects
        for (int i = 0; i < entryMethod.getParamCount(); ++i) {
            Var param = ir.getParam(i);
                for (Obj paramObj : paramProvider.getParamObjs(i)) {
                    if (manager.isTaint(paramObj)) {
                        createdTaintObjs.add(paramObj);
                    }
//TODO        reserved context = {paramObjs}, store reserved ele here.  *** Can reserved ele be a summary as context? ***
                    solver.addVarPointsTo(csMethod.getContext(), param, entryCtx, paramObj);
            }
        }
        // pass field objects
        paramProvider.getFieldObjs().forEach((base, field, obj) -> {
            if (manager.isTaint(obj)) {
                createdTaintObjs.add(obj);
            }
            CSObj csBase = csManager.getCSObj(entryCtx, base);
            InstanceField iField = csManager.getInstanceField(csBase, field);
            solver.addPointsTo(iField, entryCtx, obj);
        });
        // pass array objects
        paramProvider.getArrayObjs().forEach((array, elem) -> {
            if (manager.isTaint(elem)) {
                createdTaintObjs.add(elem);
            }
            CSObj csArray = csManager.getCSObj(entryCtx, array);
            ArrayIndex arrayIndex = csManager.getArrayIndex(csArray);
            solver.addPointsTo(arrayIndex, entryCtx, elem);
        });
//        logger.info("[TaintEntry] method={} createdTaintObjCount={}",
//                entryMethod.getSignature(), createdTaintObjs.size());
    }


    public void wirteTaintObjs(Set<String> taintObjs,BufferedWriter bufferedWriter) throws Exception{
        for (String taintObj : taintObjs) {
            bufferedWriter.write(taintObj);
            bufferedWriter.newLine();
        }
    }

    @Override
    public void onFinish() {
        manager.getTaintObjs().stream()
                .map(Obj::toString)
                .forEach(taintObjs::add);
        try {
            bufferedWriter.write("[*]自动创建入口污点对象：\n");
            wirteTaintObjs(taintObjs,bufferedWriter);
            bufferedWriter.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
