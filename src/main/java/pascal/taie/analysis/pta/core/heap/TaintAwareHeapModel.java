package pascal.taie.analysis.pta.core.heap;

import pascal.taie.ir.exp.ReferenceLiteral;
import pascal.taie.ir.stmt.New;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;

import java.util.Collection;
import java.util.Set;

/**
 * Heap model for layered taint abstraction.
 */
public class TaintAwareHeapModel implements HeapModel {

    private static final Descriptor IRRELEVANT_DESC =
            () -> "TaintIrrelevantMergedObj";

    private final HeapModel delegate;

    private final Set<JMethod> taintRelevantMethods;

    public TaintAwareHeapModel(HeapModel delegate, Set<JMethod> taintRelevantMethods) {
        this.delegate = delegate;
        this.taintRelevantMethods = taintRelevantMethods;
    }

    @Override
    public Obj getObj(New allocSite) {
        JMethod container = allocSite.getContainer();
        if (taintRelevantMethods.contains(container)) {
            return delegate.getObj(allocSite);
        }
        Type type = allocSite.getRValue().getType();
        return delegate.getMockObj(IRRELEVANT_DESC,
                "<Merged " + type + ">", type);
    }

    @Override
    public boolean isMergedObj(Obj obj) {
        if (obj instanceof NewObj newObj) {
            JMethod container = newObj.getAllocation().getContainer();
            if (!taintRelevantMethods.contains(container)) {
                return true;
            }
        }
        return delegate.isMergedObj(obj);
    }

    @Override
    public Obj getConstantObj(ReferenceLiteral value) {
        return delegate.getConstantObj(value);
    }

    @Override
    public boolean isStringConstant(Obj obj) {
        return delegate.isStringConstant(obj);
    }

    @Override
    public Obj getMockObj(Descriptor desc, Object alloc, Type type,
                          JMethod container, boolean isFunctional) {
        return delegate.getMockObj(desc, alloc, type, container, isFunctional);
    }

    @Override
    public int getIndex(Obj obj) {
        return delegate.getIndex(obj);
    }

    @Override
    public Obj getObject(int index) {
        return delegate.getObject(index);
    }

    @Override
    public Collection<Obj> getObjects() {
        return delegate.getObjects();
    }
}
