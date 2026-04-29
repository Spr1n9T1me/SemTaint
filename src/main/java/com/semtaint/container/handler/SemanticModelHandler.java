package com.semtaint.container.handler;

import java.util.*;

import javax.annotation.Nullable;

import com.semtaint.container.ConPointer;
import com.semtaint.container.CorrelationSet;
import com.semtaint.container.AliasGraphDetector;
import com.semtaint.container.ap.AccessPatternManager;
import com.semtaint.container.ap.FixedPattern;
import com.semtaint.container.ap.LiteralPattern;
import com.semtaint.container.ap.ReferenceLocation;
import com.semtaint.container.ap.VariablePattern;
import com.semtaint.container.ap.WildCardPattern;
import com.semtaint.container.mod.IContainerCSManager;
import com.semtaint.taint.TaintContextProvider;

import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.solver.Identity;
import pascal.taie.analysis.pta.core.solver.PointerFlowEdge;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.core.solver.Transfer;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.taint.TaintManager;
import pascal.taie.analysis.pta.plugin.util.SolverHolder;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.Literal;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeSystem;

/**
 * Semantic model handler for secondary solving after PTA phase
 */
public class SemanticModelHandler extends SolverHolder implements Plugin {

    private final ContainerScanHandler containerScanHandler;
    private final CorrelationSet correlationSet;
    private final IContainerCSManager containerCSManager;
    private final TypeSystem typeSystem;
    private final Type listClass;
    private final AccessPatternManager apManager;
    private final AliasGraphDetector aliasGraphDetector;
    private final Map<CSObj, Set<CSObj>> iteratorToContainer = new HashMap<>();
    // 防止 secondarySolve 被多次执行
    private boolean secondarySolveCompleted = false;

    @Nullable
    private final TaintContextProvider taintContextProvider;

    public SemanticModelHandler(Solver solver,
                                ContainerScanHandler containerScanHandler,
                                CorrelationSet correlationSet,
                                AccessPatternManager apManager,
                                @Nullable TaintContextProvider taintContextProvider)
    {
        super(solver);
        this.containerScanHandler = containerScanHandler;
        this.correlationSet = correlationSet;
        this.taintContextProvider = taintContextProvider;
        this.containerCSManager = initContainerCSManager(solver);
        this.apManager = apManager;
        this.aliasGraphDetector = new AliasGraphDetector(solver);
        this.typeSystem = solver.getTypeSystem();
        // 获取 java.util.List 的类型，用于子类型检查
        this.listClass = typeSystem.getType("java.util.List");
    }

    private static IContainerCSManager initContainerCSManager(Solver solver) {
        if (solver.getCSManager() instanceof IContainerCSManager manager) {
            return manager;
        }
        throw new IllegalStateException("ContainerCSManager is required for ConPointer indexing.");
    }

    @Override
    public void onPhaseFinish() {
        if (!secondarySolveCompleted) {
            secondarySolveCompleted = true;
            // 执行二次求解：Put 时更新状态，Get 时直接传播
            secondarySolve();
        }
    }

    @Override
    public void onFinish() {
        correlationSet.logPreciseStatistics();
    }

    /**
     * Perform secondary solving for all collected container operations
     */
    private void secondarySolve() {
        List<ContainerOperation> operations = containerScanHandler.getCollectedOperations();

        for (ContainerOperation operation : operations) {
            // TODO Alias-aware or not?
            // if (ConfManager.v().getBoolean("container-precise")) {
            //     ContainerOperation refined = tryRefineAccessPattern(operation);
            //     applyOperation(refined);
            // }else{
            //     applyOperation(operation);
            // }
            applyOperation(operation);
        }
        // for (ContainerOperation operation : operations) {
        //     if (!isReadOperation(operation.kind())) {
        //         applyOperation(operation);
        //     }
        // }

        // for (ContainerOperation operation : operations) {
        //     if (isReadOperation(operation.kind())) {
        //         applyOperation(operation);
        //     }
//        }
    }

    private ContainerOperation tryRefineAccessPattern(ContainerOperation op) {
        if (!(op.accessPattern() instanceof VariablePattern variablePattern)) {
            return op;
        }

        if (!(variablePattern.getPattern() instanceof Var var)) {
            return op;
        }

        pascal.taie.analysis.pta.core.cs.context.Context context = null;
        if (op.baseVar() != null) {
            context = op.baseVar().getContext();
        } else if (op.valueVar() != null) {
            context = op.valueVar().getContext();
        }

        if (context == null) {
            return op;
        }

        CSVar csVar = csManager.getCSVar(context, var);
        Set<Literal> consts = aliasGraphDetector.queryMayAliasPointers(csVar);
        if (consts.size() != 1) {
            return op;
        }

        Literal lit = consts.iterator().next();
        com.semtaint.container.ap.AccessPattern refined = apManager.getLiteralPattern(lit);
        return new ContainerOperation(op.baseVar(), refined, op.valueVar(), op.kind());
    }

    private void applyOperation(ContainerOperation operation) {
        switch (operation.kind()) {
            case CONTAINER_PUT -> handleContainerPut(operation);
            case CONTAINER_GET -> handleContainerGet(operation);
            case CONTAINER_REMOVE -> handleContainerRemove(operation);
            case SORTED_CONTAINER_PUT -> handleSortedContainerPut(operation);
            case SORTED_CONTAINER_REMOVE -> handleSortedContainerRemove(operation);
            case ITERATOR_LINK -> handleIteratorLink(operation);
            case ITERATOR_GET -> handleIteratorGet(operation);
            case CONTAINER_TRANSFER -> handleContainerTransfer(operation);
        }
    }

    private boolean isReadOperation(ContainerOperation.OperationKind kind) {
        return kind == ContainerOperation.OperationKind.CONTAINER_GET
                || kind == ContainerOperation.OperationKind.ITERATOR_GET;
    }

    /**
     * 建立对象关联：dst -> src
     */
    private void linkObjects(CSObj dst, CSObj src) {
        iteratorToContainer.computeIfAbsent(dst, k -> new HashSet<>()).add(src);
    }

    /**
     * 从起点对象出发，解析可达的源容器对象（支持多跳）。
     */
    private Set<CSObj> resolveLinkedContainers(CSObj startObj) {
        Set<CSObj> resolved = new HashSet<>();
        Deque<CSObj> worklist = new ArrayDeque<>();
        worklist.add(startObj);

        while (!worklist.isEmpty()) {
            CSObj current = worklist.poll();
            if (!resolved.add(current)) {
                continue;
            }
            Set<CSObj> next = iteratorToContainer.get(current);
            if (next != null) {
                worklist.addAll(next);
            }
        }

        resolved.remove(startObj);
        return resolved;
    }

    private boolean isListType(CSObj obj) {
        try {
            return typeSystem.isSubtype(listClass, obj.getObject().getType());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private PointsToSet mergeAllMappings(CSObj containerObj) {
        Map<com.semtaint.container.ap.AccessPattern, PointsToSet> mappings =
                correlationSet.getContainerMappings(containerObj);
        if (mappings == null || mappings.isEmpty()) {
            return null;
        }

        PointsToSet merged = solver.makePointsToSet();
        for (PointsToSet pts : mappings.values()) {
            if (pts != null && !pts.isEmpty()) {
                merged.addAll(pts);
            }
        }
        return merged.isEmpty() ? null : merged;
    }

    private boolean containsTaint(PointsToSet pointsToSet) {
        if (pointsToSet == null || pointsToSet.isEmpty()) {
            return false;
        }
        for (CSObj obj : pointsToSet) {
            if (isTaint(obj)) {
                return true;
            }
        }
        return false;
    }

    private void propagateTaintToContainer(CSVar containerVar, PointsToSet valuePts) {
        if (containerVar == null || valuePts == null || valuePts.isEmpty()) {
            return;
        }
        PointsToSet taintOnly = solver.makePointsToSet();
        for (CSObj obj : valuePts) {
            if (isTaint(obj)) {
                taintOnly.addObject(obj);
            }
        }
        if (!taintOnly.isEmpty()) {
            solver.addPointsTo(containerVar, taintOnly);
        }
    }

    /**
     * Handle container put operation: base[accessPattern] = value
     */
    private void handleContainerPut(ContainerOperation op) {
        for (CSObj baseObj : solver.getPointsToSetOf(op.baseVar())) {
            PointsToSet valuePts = op.valueVar().getPointsToSet();
            if (valuePts != null && !valuePts.isEmpty()) {
                ConPointer to = containerCSManager.getConPointer(baseObj, op.accessPattern());
                correlationSet.addMapping(to, valuePts);

                // U6: 当写入值包含 taint 时，让容器变量也携带 taint 信息，支持 toString 传播链。
//                if (containsTaint(valuePts)) {
//                    propagateTaintToContainer(op.baseVar(), valuePts);
//                }
            }
        }
    }

    /**
     * Handle container get operation: target = base[accessPattern]
     */
    private void handleContainerGet(ContainerOperation op) {
        for (CSObj baseObj : solver.getPointsToSetOf(op.baseVar())) {
            com.semtaint.container.ap.AccessPattern queryAp = op.accessPattern();
            com.semtaint.container.ap.AccessPattern effectiveAp = queryAp;

            boolean isList = isListType(baseObj);
            boolean isLiteralIndex = queryAp instanceof LiteralPattern;

            if (isList && isLiteralIndex) {
                // 1. 提取常量索引 i
                Object literal = queryAp.getPattern();
                if (literal instanceof IntLiteral intLit) {
                    int index = intLit.getValue();

                    // 2. 从 CorrelationSet 获取当前逻辑大小
                    int currentSize = correlationSet.deriveContainerSize(baseObj);

                    // 3. 坐标转换：Index -> TAIL(delta)
                    if (currentSize > index && index >= 0) {
                        // 公式: delta = index - Size + 1
                        // 例: Size=2, get(0) -> 0 - 2 + 1 = -1 -> TAIL(-1)
                        // 例: Size=2, get(1) -> 1 - 2 + 1 = 0 -> TAIL(0)
                        int delta = index - currentSize + 1;
                        effectiveAp = apManager.getFixedPattern(ReferenceLocation.Anchor.TAIL, delta);
                    }
                }
            }
            // 对于 Stack/Queue 的 pop/poll，effectiveAp 保持为 FixedPattern(HEAD/TAIL, 0) 不变

            PointsToSet sourcePts = correlationSet.getMapping(baseObj, effectiveAp);

            // 仅对有序容器的固定位置查询进行回退，避免 map/list 精确键查询被过度放大。
            if (effectiveAp instanceof FixedPattern && correlationSet.hasContainer(baseObj)) {
                PointsToSet fallback = mergeAllMappings(baseObj);
                if (sourcePts != null && fallback != null) {
                    PointsToSet merged = solver.makePointsToSet();
                    merged.addAll(sourcePts);
                    merged.addAll(fallback);
                    sourcePts = merged;
                } else if (fallback != null) {
                    sourcePts = fallback;
                }
            }

            if (sourcePts != null && !sourcePts.isEmpty() && op.valueVar() != null) {
                // 不创建 PFG 边，而是直接添加指向关系
                solver.addPointsTo(op.valueVar(), sourcePts);
            }
        }
    }

    /**
     * Handle container remove operation: remove base[accessPattern]
     */
    private void handleContainerRemove(ContainerOperation op) {
        for (CSObj baseObj : solver.getPointsToSetOf(op.baseVar())) {
            correlationSet.removeMapping(baseObj, op.accessPattern());
        }
    }

    /**
     * Handle sorted container put with shift semantics
     */
    private void handleSortedContainerPut(ContainerOperation op) {
        for (CSObj baseObj : solver.getPointsToSetOf(op.baseVar())) {
            correlationSet.shift(baseObj, op.accessPattern());
            // 注意：这里不再添加 PFG 边，延迟到 get 时再决定数据流向
            PointsToSet valuePts = op.valueVar().getPointsToSet();
            if (valuePts != null && !valuePts.isEmpty()) {
                ConPointer cp = containerCSManager.getConPointer(baseObj, op.accessPattern());
                correlationSet.addMapping(cp, valuePts);
            }
        }
    }

    /**
     * Handle sorted container remove with unshift semantics
     */
    private void handleSortedContainerRemove(ContainerOperation op) {
        for (CSObj baseObj : solver.getPointsToSetOf(op.baseVar())) {
            // Remove mapping at specified position
            correlationSet.removeMapping(baseObj, op.accessPattern());

            // Apply unshift to affected access patterns
            correlationSet.unshift(baseObj, op.accessPattern());
        }
    }

    private void handleIteratorLink(ContainerOperation op) {
        if (op.baseVar() == null || op.valueVar() == null) {
            return;
        }
        for (CSObj linkedObj : solver.getPointsToSetOf(op.valueVar())) {
            for (CSObj containerObj : solver.getPointsToSetOf(op.baseVar())) {
                linkObjects(linkedObj, containerObj);
            }
        }
    }

    private void handleIteratorGet(ContainerOperation op) {
        if (op.baseVar() == null || op.valueVar() == null) {
            return;
        }

        for (CSObj iterObj : solver.getPointsToSetOf(op.baseVar())) {
            Set<CSObj> candidateContainers = resolveLinkedContainers(iterObj);
            if (candidateContainers.isEmpty()) {
                continue;
            }

            PointsToSet merged = solver.makePointsToSet();
            for (CSObj containerObj : candidateContainers) {
                PointsToSet pts = mergeAllMappings(containerObj);
                if (pts != null && !pts.isEmpty()) {
                    merged.addAll(pts);
                }
            }

            if (!merged.isEmpty()) {
                solver.addPointsTo(op.valueVar(), merged);
            }
        }
    }

    private void handleContainerTransfer(ContainerOperation op) {
        if (op.baseVar() == null || op.valueVar() == null) {
            return;
        }

        for (CSObj srcObj : solver.getPointsToSetOf(op.baseVar())) {
            PointsToSet srcPts = mergeAllMappings(srcObj);
            if (srcPts == null || srcPts.isEmpty()) {
                continue;
            }

            for (CSObj dstObj : solver.getPointsToSetOf(op.valueVar())) {
                ConPointer cp = containerCSManager.getConPointer(dstObj, WildCardPattern.getInstance());
                correlationSet.addMapping(cp, srcPts);
            }
        }
    }

    /**
     * 判断对象是否为污点对象
     *
     * @param obj 要检查的对象
     * @return 如果是污点对象返回 true，否则返回 false
     */
    public boolean isTaint(CSObj obj) {
        if (taintContextProvider == null) {
            return false;  // 污点分析未启用
        }

        TaintManager manager = taintContextProvider.getTaintManager();
        return manager != null && manager.isTaint(obj.getObject());
    }

    /**
     * Adds a pointer flow edge with the specified transfer function to the solver.
     */
    public void addPFGEdge(PointerFlowEdge edge) {
        Transfer transfer = Identity.get();
        if (edge != null && edge.addTransfer(transfer)) {
            PointsToSet targetSet = transfer.apply(
                    edge, solver.getPointsToSetOf(edge.source()));
            if (!targetSet.isEmpty()) {
                solver.addPointsTo(edge.target(), targetSet);
            }
        }
    }

}
