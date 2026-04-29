package pascal.taie.analysis.graph.flowgraph;

import com.semtaint.container.ap.AccessPattern;
import pascal.taie.analysis.pta.core.heap.Obj;

/**
 * Node for container pointer in object flow graph.
 */
public class ConPointerNode extends InstanceNode {

    private final AccessPattern accessPattern;

    ConPointerNode(Obj base, AccessPattern accessPattern, int index) {
        super(base, index);
        this.accessPattern = accessPattern;
    }

    public AccessPattern getAccessPattern() {
        return accessPattern;
    }

    @Override
    public String toString() {
        return "ConPointerNode{" + base + ", " + accessPattern + "}";
    }
}
