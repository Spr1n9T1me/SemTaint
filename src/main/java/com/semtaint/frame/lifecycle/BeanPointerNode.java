package com.semtaint.frame.lifecycle;

import pascal.taie.analysis.graph.flowgraph.Node;

public class BeanPointerNode extends Node {

    private final BeanPointer vp;

    public BeanPointerNode(BeanPointer vp, int index) {
        super(index);
        this.vp = vp;
    }

    @Override
    public String toString() {
        return "BeanPointerNode{" + vp + '}';
    }
}
