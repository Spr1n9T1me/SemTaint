package com.semtaint.frame.lifecycle;

import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.collection.Sets;

import javax.annotation.Nullable;
import java.util.Set;

public class Bean {

    private final JClass jClass;
    private final String beanName;
    private final Obj mockObj;
    private BeanPointer pointer;
    private final JMethod constructor;

    private final Set<Var> ins = Sets.newSet();
    private final Set<Var> outs = Sets.newSet();
    private final Set<JField> outFields = Sets.newSet();

    public Bean(JClass jClass, String beanName,
                @Nullable JMethod constructor, @Nullable Obj mockObj) {
        this.jClass = jClass;
        this.beanName = beanName;
        this.constructor = constructor;
        this.pointer = new BeanPointer(beanName + "@" + jClass.getName(), jClass.getType());
        this.mockObj = mockObj;
        if (constructor != null && !constructor.isStatic() && !constructor.isAbstract()) {
            Var thisVar = constructor.getIR().getThis();
            if (thisVar != null) {
                outs.add(thisVar);
            }
        }
    }

    public JClass getJClass() {
        return jClass;
    }

    public String getBeanName() {
        return beanName;
    }

    @Nullable
    public Obj getMockObj() {
        return mockObj;
    }

    public BeanPointer getPointer() {
        return pointer;
    }

    /**
     * Rebuilds runtime pointer to avoid carrying points-to state
     * across different solver runs.
     */
    public void resetPointer() {
        this.pointer = new BeanPointer(beanName + "@" + jClass.getName(), jClass.getType());
    }

    @Nullable
    public JMethod getConstructor() {
        return constructor;
    }

    public Set<Var> getIns() {
        return ins;
    }

    public Set<Var> getOuts() {
        return outs;
    }

    public Set<JField> getOutFields() {
        return outFields;
    }

    public void addInEdge(Var in) {
        if (in != null) {
            ins.add(in);
        }
    }

    public void addOutEdge(Var out) {
        if (out != null) {
            outs.add(out);
        }
    }

    public void addOutEdge(JField field) {
        if (field != null) {
            outFields.add(field);
        }
    }
}
