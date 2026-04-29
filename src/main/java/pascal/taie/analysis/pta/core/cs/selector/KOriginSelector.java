/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.core.cs.selector;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.context.TrieContext;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.heap.NewObj;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;

import java.util.function.Predicate;

/**
 * Origin-sensitive context selector.
 * <p>
 * The context invariant: for any non-empty context produced by this selector,
 * {@code getElementAt(0)} is always the {@link JMethod} of the origin entry,
 * and the remaining {@code k-1} slots form a sliding window over one tail kind
 * (call-site, receiver object, or receiver type).
 * <p>
 * Configuration format examples: {@code "2-origin"}, {@code "2-origin-call"},
 * {@code "2-origin-obj"}, {@code "2-origin-type"}, {@code "3-origin-call-2h"}.
 */
class KOriginSelector extends AbstractContextSelector<Object> {

    enum TailKind {
        CALL,
        OBJ,
        TYPE
    }

    /** Total context length limit (>= 2). */
    private final int k;

    /** Heap context length limit. */
    private final int hk;

    /** Predicate that identifies origin entry methods. */
    private final Predicate<JMethod> isOriginMethod;

    /** Kind of non-origin tail elements in the sliding window. */
    private final TailKind tailKind;

    public KOriginSelector(int k, int hk, Predicate<JMethod> isOriginMethod) {
        this(k, hk, isOriginMethod, TailKind.CALL);
    }

    public KOriginSelector(int k, int hk,
                           Predicate<JMethod> isOriginMethod,
                           TailKind tailKind) {
        if (k < 2) {
            throw new IllegalArgumentException("Origin-sensitive requires k >= 2");
        }
        this.k = k;
        this.hk = hk;
        this.isOriginMethod = isOriginMethod;
        this.tailKind = tailKind;
    }

    @Override
    public Context selectContext(CSCallSite callSite, JMethod callee) {
        Context callerCtx = callSite.getContext();
        JMethod caller = callSite.getCallSite().getContainer();

        if (callerCtx.getLength() == 0) {
            if (isOriginMethod.test(caller)) {
                // caller is an origin entry point (injected with empty context)
                // establish the origin invariant: [origin, ...]
                Context originCtx = factory.make(caller);
                return appendStaticTail(originCtx, callSite.getCallSite());
            }
            return getEmptyContext();
        }

        return appendStaticTail(callerCtx, callSite.getCallSite());
    }

    @Override
    public Context selectContext(CSCallSite callSite, CSObj recv, JMethod callee) {
        Context callerCtx = callSite.getContext();
        JMethod caller = callSite.getCallSite().getContainer();

        if (callerCtx.getLength() == 0) {
            if (isOriginMethod.test(caller)) {
                Context originCtx = factory.make(caller);
                return originAppend(originCtx, getInstanceTailElement(callSite, recv));
            }
            return getEmptyContext();
        }

        return originAppend(callerCtx, getInstanceTailElement(callSite, recv));
    }

    private Context appendStaticTail(Context callerCtx, Invoke callSite) {
        if (tailKind == TailKind.CALL) {
            return originAppend(callerCtx, callSite);
        }
        // For origin-obj/type, static calls do not contribute a new tail element.
        return callerCtx;
    }

    private Object getInstanceTailElement(CSCallSite callSite, CSObj recv) {
        return switch (tailKind) {
            case CALL -> callSite.getCallSite();
            case OBJ -> recv.getObject();
            case TYPE -> recv.getObject().getContainerType();
        };
    }

    /**
     * Appends {@code tailElem} to {@code callerCtx} while preserving the
     * origin invariant (element 0 is always the origin {@link JMethod}).
     *
     * <p>If {@code callerCtx.length < k}, the new element is appended directly.
     * Otherwise the window slides: origin is kept, the oldest non-origin element
     * is dropped, and {@code tailElem} is appended at the tail.
     */
    private Context originAppend(Context callerCtx, Object tailElem) {
        if (callerCtx.getLength() < k) {
            return ((TrieContext) callerCtx).getChild(tailElem);
        }
        // Collect tail (k-2) elements (exclude the origin at position 0).
        Object[] tail = new Object[k - 2];
        TrieContext node = (TrieContext) callerCtx;
        for (int i = k - 3; i >= 0; i--) {
            tail[i] = node.getElem();
            node = node.getParent();
        }
        // Rebuild: root -> [origin] -> tail... -> tailElem
        TrieContext root = (TrieContext) factory.getEmptyContext();
        TrieContext current = root.getChild(((TrieContext) callerCtx).getOriginElement());
        for (Object e : tail) {
            current = current.getChild(e);
        }
        return current.getChild(tailElem);
    }

    @Override
    protected Context selectNewObjContext(CSMethod method, NewObj obj) {
        Context ctx = method.getContext();
        if (ctx.getLength() == 0 || hk == 0) {
            return getEmptyContext();
        }
        if (ctx.getLength() <= hk) {
            return ctx;
        }
        // Keep origin + tail (hk-1) elements.
        Object[] tail = new Object[hk - 1];
        TrieContext node = (TrieContext) ctx;
        for (int i = hk - 2; i >= 0; i--) {
            tail[i] = node.getElem();
            node = node.getParent();
        }
        TrieContext root = (TrieContext) factory.getEmptyContext();
        TrieContext current = root.getChild(((TrieContext) ctx).getOriginElement());
        for (Object e : tail) {
            current = current.getChild(e);
        }
        return current;
    }
}
