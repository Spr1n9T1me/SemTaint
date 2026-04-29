package com.semtaint.container.flowgraph;

import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.solver.OtherEdge;

/**
 * Flow edge for sorted container put operations with shift semantics
 */
public class SortedContainerPutEdge extends OtherEdge {
    public SortedContainerPutEdge(Pointer source, Pointer target) {
        super(source, target);
    }
}
