package com.semtaint.container.flowedge;

import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.solver.OtherEdge;
@SuppressWarnings("nonused")
public class ContainerRemoveEdge extends OtherEdge {
    public ContainerRemoveEdge(Pointer source, Pointer target) {
        super(source, target);
    }

}
