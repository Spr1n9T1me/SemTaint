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

package pascal.taie.analysis.pta.core.solver;

import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.util.collection.Maps;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;

/**
 * Represents work list in pointer analysis.
 */
public final class WorkList {

    /**
     * Pointer entries to be processed.
     */
    private final Map<Pointer, PointsToSet> pointerEntries = Maps.newLinkedHashMap();

    /**
     * Call edges to be processed.
     */
    private final Queue<Edge<CSCallSite, CSMethod>> callEdges = new ArrayDeque<>();

    public void addEntry(Pointer pointer, PointsToSet pointsToSet) {
        PointsToSet set = pointerEntries.get(pointer);
        if (set != null) {
            set.addAll(pointsToSet);
        } else {
            pointerEntries.put(pointer, pointsToSet.copy());
        }
    }

    public void addEntry(Edge<CSCallSite, CSMethod> edge) {
        callEdges.add(edge);
    }

    public Entry pollEntry() {
        if (!callEdges.isEmpty()) {
            // for correctness, we need to ensure that any call edges in
            // the work list must be processed prior to the pointer entries
            return new CallEdgeEntry(callEdges.poll());
        } else if (!pointerEntries.isEmpty()) {
            var it = pointerEntries.entrySet().iterator();
            var e = it.next();
            it.remove();
            return new PointerEntry(e.getKey(), e.getValue());
        } else {
            throw new NoSuchElementException();
        }
    }

    public boolean isEmpty() {
        return pointerEntries.isEmpty() && callEdges.isEmpty();
    }

    public interface Entry {
    }

    public record PointerEntry(Pointer pointer, PointsToSet pointsToSet)
            implements Entry {
    }

    public record CallEdgeEntry(Edge<CSCallSite, CSMethod> edge)
            implements Entry {
    }
}
