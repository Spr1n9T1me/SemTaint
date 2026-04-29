package com.semtaint.frame.entry;

import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.EmptyParamProvider;
import pascal.taie.analysis.pta.core.solver.ParamProvider;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.TwoKeyMultiMap;

import java.util.Set;

/**
 * Provides receiver object only and ignores all parameter objects.
 */
public class ThisOnlyParamProvider implements ParamProvider {

    private final Set<Obj> thisObjs;

    public ThisOnlyParamProvider(JMethod method, HeapModel heapModel) {
        if (!method.isStatic() && !method.getDeclaringClass().isAbstract()) {
            Obj thisObj = heapModel.getMockObj(
                    Descriptor.ENTRY_DESC,
                    "this/" + method.getSignature(),
                    method.getDeclaringClass().getType(),
                    method);
            this.thisObjs = Set.of(thisObj);
        } else {
            this.thisObjs = Set.of();
        }
    }

    @Override
    public Set<Obj> getThisObjs() {
        return thisObjs;
    }

    @Override
    public Set<Obj> getParamObjs(int i) {
        return Set.of();
    }

    @Override
    public TwoKeyMultiMap<Obj, JField, Obj> getFieldObjs() {
        return EmptyParamProvider.INSTANCE.getFieldObjs();
    }

    @Override
    public MultiMap<Obj, Obj> getArrayObjs() {
        return EmptyParamProvider.INSTANCE.getArrayObjs();
    }
}
