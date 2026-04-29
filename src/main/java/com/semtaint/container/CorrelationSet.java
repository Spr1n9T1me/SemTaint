package com.semtaint.container;

import com.semtaint.config.ConfManager;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.semtaint.container.ap.AccessPattern;
import com.semtaint.container.ap.LiteralPattern;
import com.semtaint.container.mod.IContainerCSManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.util.SolverHolder;
import pascal.taie.analysis.pta.pts.PointsToSet;

/**
 * @program: semtaint-newfront
 * @description:
 * @author: springtime
 **/
public class CorrelationSet extends SolverHolder{

    private static final Logger logger = LogManager.getLogger(CorrelationSet.class);

    // CSContainer Object -> (AccessPattern -> Tainted PointsToSet)
    private final Map<CSObj, Map<AccessPattern, PointsToSet>> table = new HashMap<>();
    private final IContainerCSManager containerCSManager;
    private final boolean preciseStatsEnabled;

    private int createdMappingCount = 0;
    private int literalMappingCount = 0;
    private final Map<String, Integer> mappingCountByAccessPattern = new HashMap<>();

    public CorrelationSet(Solver solver) {
        super(solver);
        this.containerCSManager = (IContainerCSManager) solver.getCSManager();
        this.preciseStatsEnabled = ConfManager.v().getBoolean("pta.container-precise", false);
    }
    /**
     * MAPCONTAINERSTORE / ORDEREDCONTAINERINSERTTAINT
     *
     * @param container 上下文敏感的容器对象 (σ)
     * @param ap 访问模式
     * @param pointsToSet 污点指向集
     * @return 创建或获取的 ConPointer 对象
     */
    public ConPointer addMapping(CSObj container, AccessPattern ap, PointsToSet pointsToSet) {
        if (container == null || ap == null || pointsToSet == null || pointsToSet.isEmpty()) {
            return null;
        }

        Map<AccessPattern, PointsToSet> containerMap = table.computeIfAbsent(container, k -> new HashMap<>());
        boolean isNewMapping = !containerMap.containsKey(ap);
        if (ap instanceof LiteralPattern) {
            containerMap.put(ap, pointsToSet);
        } else {
            containerMap.merge(ap, pointsToSet, this::unionPointsToSets);
        }

        if (preciseStatsEnabled && isNewMapping) {
            createdMappingCount++;
            if (ap instanceof LiteralPattern) {
                literalMappingCount++;
            }
            String key = renderAccessPattern(ap);
            mappingCountByAccessPattern.put(key, mappingCountByAccessPattern.getOrDefault(key, 0) + 1);
        }

        ConPointer conPointer = containerCSManager.getConPointer(container, ap);

        // 返回 ConPointer 供调用者使用（例如添加工作列表、建立污点传播等）
        return conPointer;
    }

    /**
     * MAPCONTAINERLOAD / ORDEREDCONTAINERGET
     *
     * @param container 上下文敏感的容器对象 (σ)
     * @param ap 访问模式
     * @return 污点指向集，如果不存在则返回 null
     */
    public PointsToSet getMapping(CSObj container, AccessPattern ap) {
        Map<AccessPattern, PointsToSet> containerMap = table.get(container);
        if (containerMap == null) return null;
        return containerMap.get(ap);
    }

    /**
     *  ORDEREDCONTAINERREMOVE
     *
     * @param container 上下文敏感的容器对象 (σ)
     * @param ap 访问模式
     */
    public void removeMapping(CSObj container, AccessPattern ap) {
        Map<AccessPattern, PointsToSet> containerMap = table.get(container);
        if (containerMap != null) {
            containerMap.remove(ap);
            if (containerMap.isEmpty()) {
                table.remove(container);
            }
        }
    }

    /**
     *  ORDEREDCONTAINERINSERT / ORDEREDCONTAINERINSERTTAINT
     *
     * @param container 被操作的上下文敏感容器对象 (σ)
     * @param insertionAp 插入操作的访问模式 (ap_ins)，例如 Ref(HEAD, 0)
     */
    public void shift(CSObj container, AccessPattern insertionAp) {
        Map<AccessPattern, PointsToSet> currentMap = table.get(container);
        if (currentMap == null || currentMap.isEmpty()) return;

        // 构建新的映射表以避免并发修改异常和 Key 冲突
        Map<AccessPattern, PointsToSet> newMap = new HashMap<>();

        for (Map.Entry<AccessPattern, PointsToSet> entry : currentMap.entrySet()) {
            AccessPattern currentAp = entry.getKey();
            PointsToSet pts = entry.getValue();

            // 调用 AccessPattern.shift (对于 FixedPattern 会委托给 ReferenceLocation.shift)
            // 如果不可平移或不受影响，返回原对象；否则返回新对象
            AccessPattern shiftedAp = currentAp.shift(insertionAp);

            newMap.merge(shiftedAp, pts, this::unionPointsToSets);
        }

        table.put(container, newMap);
    }

    /**
     * 对应规则: ORDEREDCONTAINERREMOVE
     * 执行 unshift 操作：遍历容器内所有已跟踪的 AP，更新受删除影响的 AP 位置。
     * 注意：调用此方法前，通常应先调用 removeMapping 移除被删除那个元素本身的映射。
     *
     * @param container 被操作的上下文敏感容器对象 (σ)
     * @param deletionAp 删除操作的访问模式 (ap_del)
     */
    public void unshift(CSObj container, AccessPattern deletionAp) {
        Map<AccessPattern, PointsToSet> currentMap = table.get(container);
        if (currentMap == null || currentMap.isEmpty()) return;

        Map<AccessPattern, PointsToSet> newMap = new HashMap<>();

        for (Map.Entry<AccessPattern, PointsToSet> entry : currentMap.entrySet()) {
            AccessPattern currentAp = entry.getKey();
            PointsToSet pts = entry.getValue();

            // 调用 AccessPattern.unshift (对于 FixedPattern 会委托给 ReferenceLocation.unshift)
            AccessPattern unshiftedAp = currentAp.unshift(deletionAp);

            newMap.merge(unshiftedAp, pts, this::unionPointsToSets);
        }

        table.put(container, newMap);
    }

    /**
     * 对应规则: MAPCONTAINERSTORE 等
     * 重载方法：使用 ConPointer 作为键来建立映射。
     *
     * @param conPointer 复合容器指针对象 (s^c)
     * @param pointsToSet 污点指向集
     * @return 使用的 ConPointer 对象
     */
    public ConPointer addMapping(ConPointer conPointer, PointsToSet pointsToSet) {
        if (conPointer == null) return null;
        return addMapping(conPointer.getContainerObj(), conPointer.getAccessPattern(), pointsToSet);
    }

    /**
     * 对应规则: MAPCONTAINERLOAD 等
     * 重载方法：使用 ConPointer 作为键来获取映射。
     * * @param conPointer 复合容器指针对象 (s^c)
     * @return 污点指向集，如果不存在则返回 null
     */
    public PointsToSet getMapping(ConPointer conPointer) {
        if (conPointer == null) return null;
        return getMapping(conPointer.getContainerObj(), conPointer.getAccessPattern());
    }

    /**
     * 重载方法：移除 ConPointer 对应的映射。
     * * @param conPointer 复合容器指针对象
     */
    public void removeMapping(ConPointer conPointer) {
        if (conPointer == null) return;
        removeMapping(conPointer.getContainerObj(), conPointer.getAccessPattern());
    }

    /**
     * 获取指定容器内所有处于 "活跃状态" (即被跟踪/被污染) 的 ConPointer 集合。
     * * 在分析过程中，Solver 可能需要遍历某个容器所有被污染的位置，
     * 此方法将内部的 <AP> 实时包装回 ConPointer 对象。
     * * @param container 上下文敏感的容器对象
     * @return 该容器下所有活跃的 ConPointer 集合
     */
    public Set<ConPointer> getActiveConPointers(CSObj container) {
        Map<AccessPattern, PointsToSet> apMap = table.get(container);
        if (apMap == null || apMap.isEmpty()) {
            return Collections.emptySet();
        }

        // 惰性构建 ConPointer 集合
        return apMap.keySet().stream()
            .map(ap -> containerCSManager.getConPointer(container, ap))
            .collect(Collectors.toSet());
    }

    /**
     * 获取整个 Correlation Set 中所有活跃的 ConPointer。
     * 用于全局遍历或调试。
     * * @return 全局所有活跃的 ConPointer 集合
     */
    public Set<ConPointer> getAllActiveConPointers() {
        Set<ConPointer> allPointers = new HashSet<>();
        for (Map.Entry<CSObj, Map<AccessPattern, PointsToSet>> entry : table.entrySet()) {
            CSObj container = entry.getKey();
            for (AccessPattern ap : entry.getValue().keySet()) {
                allPointers.add(containerCSManager.getConPointer(container, ap));
            }
        }
        return allPointers;
    }

    /**
     * 检查容器是否有任何被跟踪的映射
     *
     * @param container 上下文敏感的容器对象
     * @return 如果容器有映射则返回 true
     */
    public boolean hasContainer(CSObj container) {
        return table.containsKey(container);
    }

    /**
     * 获取容器中所有被跟踪的访问模式
     *
     * @param container 上下文敏感的容器对象
     * @return 访问模式的映射表，如果不存在则返回 null
     */
    public Map<AccessPattern, PointsToSet> getContainerMappings(CSObj container) {
        return table.get(container);
    }

    /**
     * 清空整个 CorrelationSet
     */
    public void clear() {
        table.clear();
    }

    /**
     * 获取跟踪的容器数量
     *
     * @return 容器数量
     */
    public int size() {
        return table.size();
    }

    /**
     * 基于当前保存的 AccessPattern 推断容器的逻辑大小
     * 仅计算 TAIL 类型的模式，用于 List 的动态索引转换。
     *
     * @param container 容器对象
     * @return 容器的逻辑大小（基于 TAIL 偏移计算）
     */
    public int deriveContainerSize(CSObj container) {
        Map<AccessPattern, PointsToSet> map = table.get(container);
        if (map == null || map.isEmpty()) {
            return 0;
        }

        int minOffset = 0;
        boolean hasTail = false;

        for (AccessPattern ap : map.keySet()) {
            if (ap.getPattern() instanceof com.semtaint.container.ap.ReferenceLocation) {
                com.semtaint.container.ap.ReferenceLocation ref =
                    (com.semtaint.container.ap.ReferenceLocation) ap.getPattern();
                if (ref.getAnchor() == com.semtaint.container.ap.ReferenceLocation.Anchor.TAIL) {
                    hasTail = true;
                    // TAIL 的 offset 是 <= 0 的 (0, -1, -2...)
                    // 最小的 offset 对应最早插入的元素
                    if (ref.getOffset() < minOffset) {
                        minOffset = ref.getOffset();
                    }
                }
            }
        }

        if (!hasTail) return 0;

        // Size = 1 - minOffset
        // 例: 只有 TAIL(0) -> Size = 1
        // 例: TAIL(0), TAIL(-1) -> min=-1 -> Size = 2
        return 1 - minOffset;
    }

    /**
     * 当 pta.container-precise 开启时，输出容器映射统计信息。
     */
    public void logPreciseStatistics() {
        if (!preciseStatsEnabled) {
            return;
        }

        double literalRatio = createdMappingCount == 0
                ? 0D
                : (literalMappingCount * 100.0) / createdMappingCount;
        String literalRatioText = String.format(Locale.ROOT, "%.2f", literalRatio);

        logger.info("[ContainerPrecise] Created container mappings: {}", createdMappingCount);
        logger.info("[ContainerPrecise] Literal mappings: {} ({}%)",
            literalMappingCount, literalRatioText);

        if (mappingCountByAccessPattern.isEmpty()) {
            logger.info("[ContainerPrecise] AccessPattern mapping counts: <empty>");
            return;
        }
        /*
        * precise mapping breakdown
        * */
//        Map<String, Integer> sorted = mappingCountByAccessPattern.entrySet().stream()
//                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
//                        .thenComparing(Map.Entry.comparingByKey()))
//                .collect(Collectors.toMap(
//                        Map.Entry::getKey,
//                        Map.Entry::getValue,
//                        (left, right) -> left,
//                        LinkedHashMap::new
//                ));
//
//        logger.info("[ContainerPrecise] AccessPattern mapping counts (created):");
//        for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
//            logger.info("[ContainerPrecise]   {} -> {}", entry.getKey(), entry.getValue());
//        }

        Map<String, Integer> activeMappingCountByType = new HashMap<>();
        Map<String, Set<CSObj>> uniqueHeapObjsByType = new HashMap<>();

        for (Map<AccessPattern, PointsToSet> containerMap : table.values()) {
            for (Map.Entry<AccessPattern, PointsToSet> mapping : containerMap.entrySet()) {
                AccessPattern ap = mapping.getKey();
                PointsToSet pts = mapping.getValue();

                String apType = renderAccessPatternType(ap);
                activeMappingCountByType.put(
                        apType,
                        activeMappingCountByType.getOrDefault(apType, 0) + 1
                );

                if (pts != null && !pts.isEmpty()) {
                    Set<CSObj> heapObjs = uniqueHeapObjsByType.computeIfAbsent(apType, k -> new HashSet<>());
                    for (CSObj obj : pts) {
                        heapObjs.add(obj);
                    }
                }
            }
        }

        if (activeMappingCountByType.isEmpty()) {
            logger.info("[ContainerPrecise] AccessPattern type statistics: <empty>");
            return;
        }

        Map<String, Integer> sortedTypeMappings = activeMappingCountByType.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        logger.info("[ContainerPrecise] AccessPattern type statistics (active mappings / unique heap objects):");
        for (Map.Entry<String, Integer> entry : sortedTypeMappings.entrySet()) {
            String type = entry.getKey();
            int mappingCount = entry.getValue();
            int heapObjCount = uniqueHeapObjsByType.getOrDefault(type, Collections.emptySet()).size();
            logger.info("[ContainerPrecise]   {} -> mappings={}, heapObjects={}",
                    type, mappingCount, heapObjCount);
        }
    }

    private String renderAccessPattern(AccessPattern ap) {
        if (ap == null) {
            return "<null>";
        }
        Object pattern = ap.getPattern();
        String patternText = pattern == null ? "null" : pattern.toString();
        return ap.getClass().getSimpleName() + "(" + patternText + ")";
    }

    private String renderAccessPatternType(AccessPattern ap) {
        return ap == null ? "<null>" : ap.getClass().getSimpleName();
    }

    private PointsToSet unionPointsToSets(PointsToSet left, PointsToSet right) {
        if (left == null || left.isEmpty()) {
            return right;
        }
        if (right == null || right.isEmpty()) {
            return left;
        }
        if (left == right) {
            return left;
        }
        PointsToSet merged = solver.makePointsToSet();
        merged.addAll(left);
        merged.addAll(right);
        return merged;
    }

    // 调试辅助
    @Override
    public String toString() {
        return table.toString();
    }
}
