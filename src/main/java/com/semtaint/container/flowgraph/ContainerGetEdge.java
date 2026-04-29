package com.semtaint.container.flowedge;

import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.solver.OtherEdge;

public class ContainerGetEdge extends OtherEdge {
    public ContainerGetEdge(Pointer source, Pointer target) {
        super(source, target);
    }

}
