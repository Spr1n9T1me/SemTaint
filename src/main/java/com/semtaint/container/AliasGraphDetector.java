package com.semtaint.container;

import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.graph.flowgraph.ObjectFlowGraph;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.util.SolverHolder;
import pascal.taie.ir.exp.Literal;
import pascal.taie.util.graph.Reachability;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * @program: Tai-e
 * @description:
 * @author: springtime
 **/
public class AliasGraphDetector extends SolverHolder {
    public AliasGraphDetector(Solver solver) {
        super(solver);
    }

    /**
     * Query the may-alias pointers of a given pointer.
     * @param pointer the key can only be CSVar.
     * TODO 如何优化查询的效率，比如按需查询
     */
    public Set<Literal> queryMayAliasPointers(CSVar pointer){
        if (pointer == null) {
            return Set.of();
        }
        ObjectFlowGraph ofg = solver.getResult().getObjectFlowGraph();
        Node node = ofg.toNode(pointer);
        if (node == null) {
            return Set.of();
        }
        Reachability<Node> nodeReachability = new Reachability<>(ofg);
        Set<Node> nodes = nodeReachability.nodesCanReach(node);
        Set<Literal> constants = getConst(nodes);
        return constants.isEmpty() ? Set.of() : constants;
    }

    private Set<Literal> getConst(Set<Node> nodes){
        return nodes.stream().filter(node ->
                        node instanceof VarNode varNode && varNode.getVar().isConst())
                .map(node -> ((VarNode) node).getVar().getConstValue())
                .collect(Collectors.toSet());
    }
}
