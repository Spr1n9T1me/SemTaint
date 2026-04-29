package com.semtaint.container.handler;

import com.semtaint.container.ap.AccessPattern;
import com.semtaint.container.ap.WildCardPattern;

import pascal.taie.analysis.pta.core.cs.element.CSVar;

/**
 * @program: semtaint-newfront
 * @description: 封装容器操作的所有信息（基对象、访问模式、值变量、操作类型）
 * @author: springtime
 **/
public record ContainerOperation(
        CSVar baseVar,
        AccessPattern accessPattern,
        CSVar valueVar,  // 对于 Get 操作，这是目标变量；对于 Put 操作，这是源变量；对Remove 操作，为 null
        OperationKind kind
) {
    /**
     * 容器操作类型
     */
    public enum OperationKind {
        CONTAINER_PUT,           // Map put, Set add, Array store
        CONTAINER_GET,           // Map get, Array load, List get
        CONTAINER_REMOVE,        // Map remove
        SORTED_CONTAINER_PUT,    // Ordered container insertions (List.add with index)
        SORTED_CONTAINER_REMOVE, // Ordered container removals (List.remove with index)
        ITERATOR_LINK,           // Link iterator/view object to source container object
        ITERATOR_GET,            // iter.next() like read from linked container
        CONTAINER_TRANSFER       // Whole-container transfer (e.g., toArray/asList/addAll/putAll)
    }

    /**
     * 创建 Put 操作
     */
    public static ContainerOperation put(CSVar baseVar, AccessPattern accessPattern, CSVar valueVar) {
        return new ContainerOperation(baseVar, accessPattern, valueVar, OperationKind.CONTAINER_PUT);
    }

    /**
     * 创建 Get 操作
     */
    public static ContainerOperation get(CSVar baseVar, AccessPattern accessPattern, CSVar targetVar) {
        return new ContainerOperation(baseVar, accessPattern, targetVar, OperationKind.CONTAINER_GET);
    }

    /**
     * 创建 Remove 操作
     */
    public static ContainerOperation remove(CSVar baseVar, AccessPattern accessPattern) {
        return new ContainerOperation(baseVar, accessPattern, null, OperationKind.CONTAINER_REMOVE);
    }

    /**
     * 创建有序容器Put 操作
     */
    public static ContainerOperation sortedPut(CSVar baseVar, AccessPattern accessPattern, CSVar valueVar) {
        return new ContainerOperation(baseVar, accessPattern, valueVar, OperationKind.SORTED_CONTAINER_PUT);
    }

    /**
     * 创建有序容器Remove 操作
     */
    public static ContainerOperation sortedRemove(CSVar baseVar, AccessPattern accessPattern) {
        return new ContainerOperation(baseVar, accessPattern, null, OperationKind.SORTED_CONTAINER_REMOVE);
    }

    /**
     * 创建 Iterator 关联操作
     */
    public static ContainerOperation iteratorLink(CSVar containerVar, CSVar iteratorOrViewVar) {
        return new ContainerOperation(containerVar, WildCardPattern.getInstance(), iteratorOrViewVar, OperationKind.ITERATOR_LINK);
    }

    /**
     * 创建 Iterator 读取操作
     */
    public static ContainerOperation iteratorGet(CSVar iterVar, AccessPattern accessPattern, CSVar retVar) {
        return new ContainerOperation(iterVar, accessPattern, retVar, OperationKind.ITERATOR_GET);
    }

    /**
     * 创建容器间迁移操作
     */
    public static ContainerOperation containerTransfer(CSVar srcContainerVar, CSVar dstContainerVar) {
        return new ContainerOperation(srcContainerVar, WildCardPattern.getInstance(), dstContainerVar, OperationKind.CONTAINER_TRANSFER);
    }
}
