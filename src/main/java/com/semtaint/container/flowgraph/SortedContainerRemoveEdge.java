package com.semtaint.container.flowgraph;

import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.solver.OtherEdge;

/**
 * Flow edge for sorted container remove operations with unshift semantics
 */
public class SortedContainerRemoveEdge extends OtherEdge {
    public SortedContainerRemoveEdge(Pointer source, Pointer target) {
        super(source, target);
    }
}
