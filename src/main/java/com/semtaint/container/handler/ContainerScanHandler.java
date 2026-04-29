package com.semtaint.container.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import com.semtaint.config.ConfManager;
import com.semtaint.container.APDeriver;
import com.semtaint.container.ap.AccessPatternManager;
import com.semtaint.container.ap.ReferenceLocation;
import com.semtaint.config.GlobalState;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.util.SolverHolder;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.StoreArray;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.SignatureMatcher;


public class ContainerScanHandler extends SolverHolder implements Plugin {

    private final Map<JMethod, BiConsumer<Context, Invoke>> operationHandlers = new HashMap<>();
    private final Set<JMethod> methodsKeepPTA = new HashSet<>();
    private final Set<JMethod> appCustomizedContainerOp = new HashSet<>();
    private final APDeriver apDeriver;

    private final List<ContainerOperation> collectedOperations = new ArrayList<>();

    public ContainerScanHandler(Solver solver, AccessPatternManager apManager) {
        super(solver);
        this.apDeriver = new APDeriver(solver, apManager);
        collectAndRegisterMethods();
    }

    @Override
    public void onStart() {

    }


    public List<ContainerOperation> getCollectedOperations() {
        return collectedOperations;
    }

    private void collectAndRegisterMethods() {
        SignatureMatcher matcher = new SignatureMatcher(hierarchy);

        // =========================================================
        // 1. Map & Web Scope Operations (Key-Value)
        // =========================================================
        Set<JMethod> mapPuts = new HashSet<>();
        mapPuts.addAll(matcher.getMethods("<java.util.Map^: java.lang.Object put(java.lang.Object,java.lang.Object)>"));
        mapPuts.addAll(matcher.getMethods("<javax.servlet.http.HttpSession^: void setAttribute(java.lang.String,java.lang.Object)>"));
        mapPuts.addAll(matcher.getMethods("<javax.servlet.ServletContext^: void setAttribute(java.lang.String,java.lang.Object)>"));
        mapPuts.addAll(matcher.getMethods("<java.util.Set^: boolean add(java.lang.Object)>"));

        Set<JMethod> mapGets = new HashSet<>();
        mapGets.addAll(matcher.getMethods("<java.util.Map^: java.lang.Object get(java.lang.Object)>"));
        mapGets.addAll(matcher.getMethods("<javax.servlet.http.HttpSession^: java.lang.Object getAttribute(java.lang.String)>"));
        mapGets.addAll(matcher.getMethods("<javax.servlet.ServletContext^: java.lang.Object getAttribute(java.lang.String)>"));

        Set<JMethod> mapRemoves = matcher.getMethods("<java.util.Map^: java.lang.Object remove(java.lang.Object)>");

        registerHandlers(mapPuts, this::handleMapPut);
        registerHandlers(mapGets, this::handleMapGet);
        registerHandlers(mapRemoves, this::handleMapRemove);

        // =========================================================
        // 1.5 Iterator & view operations
        // =========================================================
        Set<JMethod> iteratorProducers = new HashSet<>();
        iteratorProducers.addAll(matcher.getMethods("<java.util.Collection^: java.util.Iterator iterator()>"));
        iteratorProducers.addAll(matcher.getMethods("<java.util.Map^: java.util.Set entrySet()>"));
        iteratorProducers.addAll(matcher.getMethods("<java.util.Map^: java.util.Set keySet()>"));
        iteratorProducers.addAll(matcher.getMethods("<java.util.Map^: java.util.Collection values()>"));
        /*
        whether to enable iterator modeling? this will lead to huge analyzing overhead.
         */
//        registerHandlersKeepPTA(iteratorProducers, this::handleIteratorSource);

        Set<JMethod> iteratorNexts = new HashSet<>();
        iteratorNexts.addAll(matcher.getMethods("<java.util.Iterator^: java.lang.Object next()>"));
//        registerHandlersKeepPTA(iteratorNexts, this::handleIteratorNext);

        // =========================================================
        // 2. Ordered Container Operations (List, Stack, Queue, Deque)
        // =========================================================

        // 2.1 Insertions (Put)
        // List.add(index, obj) -
        registerHandlers(matcher.getMethods("<java.util.List^: void add(int,java.lang.Object)>"),
                (ctx, inv) -> handleIndexedPut(ctx, inv, 0, 1));

        // List.add(obj), Queue.offer, Stack.push ->  TAIL
        Set<JMethod> tailInserts = new HashSet<>();
        tailInserts.addAll(matcher.getMethods("<java.util.List^: boolean add(java.lang.Object)>"));
        tailInserts.addAll(matcher.getMethods("<java.util.Queue^: boolean offer(java.lang.Object)>"));
        tailInserts.addAll(matcher.getMethods("<java.util.Stack^: java.lang.Object push(java.lang.Object)>"));
        tailInserts.addAll(matcher.getMethods("<java.util.Deque^: void addLast(java.lang.Object)>"));
        registerHandlers(tailInserts, (ctx, inv) -> handleFixedPut(ctx, inv, ReferenceLocation.Anchor.TAIL));

        // Deque.addFirst -> HEAD
        registerHandlers(matcher.getMethods("<java.util.Deque^: void addFirst(java.lang.Object)>"),
                (ctx, inv) -> handleFixedPut(ctx, inv, ReferenceLocation.Anchor.HEAD));

        // 2.2 Retrievals (Get)
        // List.get(index)
        registerHandlers(matcher.getMethods("<java.util.List^: java.lang.Object get(int)>"),
                (ctx, inv) -> handleIndexedGet(ctx, inv, 0));

        // Queue.poll, Deque.getFirst, pollFirst -> HEAD
        Set<JMethod> headGets = new HashSet<>();
        headGets.addAll(matcher.getMethods("<java.util.Queue^: java.lang.Object poll()>"));
        headGets.addAll(matcher.getMethods("<java.util.Deque^: java.lang.Object getFirst()>"));
        headGets.addAll(matcher.getMethods("<java.util.Deque^: java.lang.Object pollFirst()>"));
        registerHandlers(headGets, (ctx, inv) -> handleFixedGet(ctx, inv, ReferenceLocation.Anchor.HEAD));

        // Stack.pop, Deque.getLast, pollLast -> TAIL
        Set<JMethod> tailGets = new HashSet<>();
        tailGets.addAll(matcher.getMethods("<java.util.Stack^: java.lang.Object pop()>"));
        tailGets.addAll(matcher.getMethods("<java.util.Deque^: java.lang.Object getLast()>"));
        tailGets.addAll(matcher.getMethods("<java.util.Deque^: java.lang.Object pollLast()>"));
        registerHandlers(tailGets, (ctx, inv) -> handleFixedGet(ctx, inv, ReferenceLocation.Anchor.TAIL));

        // 2.3 Removals
        // List.remove(index)
        registerHandlers(matcher.getMethods("<java.util.List^: java.lang.Object remove(int)>"),
                (ctx, inv) -> handleIndexedRemove(ctx, inv, 0));

        // Deque removals
        registerHandlers(matcher.getMethods("<java.util.Deque^: java.lang.Object removeFirst()>"),
                (ctx, inv) -> handleFixedRemove(ctx, inv, ReferenceLocation.Anchor.HEAD));
        registerHandlers(matcher.getMethods("<java.util.Deque^: java.lang.Object removeLast()>"),
                (ctx, inv) -> handleFixedRemove(ctx, inv, ReferenceLocation.Anchor.TAIL));

        // =========================================================
        // 3. Container shape conversion & bulk operations
        // =========================================================
//        registerHandlersKeepPTA(matcher.getMethods("<java.util.Collection^: java.lang.Object[] toArray()>"),
//            this::handleToArray);
//        registerHandlersKeepPTA(matcher.getMethods("<java.util.Arrays: java.util.List asList(java.lang.Object[])>"),
//            this::handleArraysAsList);

        Set<JMethod> bulkOps = new HashSet<>();
        bulkOps.addAll(matcher.getMethods("<java.util.Collection^: boolean addAll(java.util.Collection)>"));
        bulkOps.addAll(matcher.getMethods("<java.util.Map^: void putAll(java.util.Map)>"));
//        registerHandlers(bulkOps, this::handleBulkAdd);

        if (ConfManager.v().getBoolean("container-precise")) {
            registerHandlersKeepPTA(iteratorProducers, this::handleIteratorSource);
            registerHandlersKeepPTA(iteratorNexts, this::handleIteratorNext);
            registerHandlersKeepPTA(matcher.getMethods("<java.util.Collection^: java.lang.Object[] toArray()>"),
                this::handleToArray);
            registerHandlersKeepPTA(matcher.getMethods("<java.util.Arrays: java.util.List asList(java.lang.Object[])>"),
                this::handleArraysAsList);
            registerHandlers(bulkOps, this::handleBulkAdd);
        }

        // =================================================================================
        // Register all methods to ignore
        // =================================================================================
        operationHandlers.keySet().stream()
            .filter(m -> !methodsKeepPTA.contains(m))
            .forEach(solver::addIgnoredMethod);
        System.out.println("[ContainerScanHandler] Registered " + operationHandlers.size() + " container operation methods.");
        System.out.println("[ContainerScanHandler] App Customized Container Operation: " + appCustomizedContainerOp.size());
    }

    private void registerHandlers(Set<JMethod> methods, BiConsumer<Context, Invoke> handler) {
        for (JMethod method : methods) {
//            if (method.getSignature().contains(ConfManager.v().getString("app.package-name")))
//                appCustomizedContainerOp.add(method);
//            else
                operationHandlers.put(method, handler);

        }
    }


    private void registerHandlersKeepPTA(Set<JMethod> methods, BiConsumer<Context, Invoke> handler) {
        for (JMethod method : methods) {
            operationHandlers.put(method, handler);
            methodsKeepPTA.add(method);
        }
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        JMethod method = csMethod.getMethod();
        if (method.getIR() == null) return;

        Context currentContext = csMethod.getContext();

        for (Stmt stmt : method.getIR()) {
            processStatement(stmt, currentContext);
        }
    }

    /**
     * Processes a statement to identify and dispatch container or array operations.
     */
    private void processStatement(Stmt stmt, Context context) {
        ContainerOperation operation = null;

        if (stmt instanceof Invoke invoke && !invoke.isDynamic()) {
            JMethod method = invoke.getMethodRef().resolveNullable();
            if (method != null) {
                // O(1) lookup to execute the corresponding processing logic
                BiConsumer<Context, Invoke> handler = operationHandlers.get(method);
                if (handler != null) {
                    GlobalState.countAppPackageMethodIfNeeded(method);
                    handler.accept(context, invoke);
                }
            }
        } else if (stmt instanceof StoreArray storeArray) {
            operation = apDeriver.deriveStoreArray(context, storeArray);
        } else if (stmt instanceof LoadArray loadArray) {
            operation = apDeriver.deriveLoadArray(context, loadArray);
        }

        if (operation != null) {
            collectedOperations.add(operation);
        }
    }


    private void handleMapPut(Context context, Invoke invoke) {
        ContainerOperation operation = apDeriver.deriveMapPut(context, invoke);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleMapGet(Context context, Invoke invoke) {
        ContainerOperation operation = apDeriver.deriveMapGet(context, invoke);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleMapRemove(Context context, Invoke invoke) {
        ContainerOperation operation = apDeriver.deriveMapRemove(context, invoke);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleIndexedPut(Context context, Invoke invoke, int indexArgIdx, int valueArgIdx) {
        ContainerOperation operation = apDeriver.deriveIndexedPut(context, invoke, indexArgIdx, valueArgIdx);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleFixedPut(Context context, Invoke invoke, ReferenceLocation.Anchor anchor) {
        ContainerOperation operation = apDeriver.deriveFixedPut(context, invoke, anchor);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleIndexedGet(Context context, Invoke invoke, int indexArgIdx) {
        ContainerOperation operation = apDeriver.deriveIndexedGet(context, invoke, indexArgIdx);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleFixedGet(Context context, Invoke invoke, ReferenceLocation.Anchor anchor) {
        ContainerOperation operation = apDeriver.deriveFixedGet(context, invoke, anchor);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleIndexedRemove(Context context, Invoke invoke, int indexArgIdx) {
        ContainerOperation operation = apDeriver.deriveIndexedRemove(context, invoke, indexArgIdx);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleFixedRemove(Context context, Invoke invoke, ReferenceLocation.Anchor anchor) {
        ContainerOperation operation = apDeriver.deriveFixedRemove(context, invoke, anchor);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleIteratorSource(Context context, Invoke invoke) {
        ContainerOperation operation = apDeriver.deriveIteratorSource(context, invoke);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleIteratorNext(Context context, Invoke invoke) {
        ContainerOperation operation = apDeriver.deriveIteratorNext(context, invoke);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleToArray(Context context, Invoke invoke) {
        ContainerOperation operation = apDeriver.deriveContainerTransferBaseToReturn(context, invoke);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleArraysAsList(Context context, Invoke invoke) {
        ContainerOperation operation = apDeriver.deriveContainerTransferArgToReturn(context, invoke, 0);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }

    private void handleBulkAdd(Context context, Invoke invoke) {
        ContainerOperation operation = apDeriver.deriveContainerTransferArgToBase(context, invoke, 0);
        if (operation != null) {
            collectedOperations.add(operation);
        }
    }
}
