package com.semtaint.container.flowedge;

import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.solver.OtherEdge;

public class ContainerPutEdge extends OtherEdge {
    public ContainerPutEdge(Pointer source, Pointer target) {
        super(source, target);
    }

}
