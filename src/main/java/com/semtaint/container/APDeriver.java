package com.semtaint.container;

import com.semtaint.container.ap.AccessPattern;
import com.semtaint.container.ap.AccessPatternManager;
import com.semtaint.container.ap.FixedPattern;
import com.semtaint.container.ap.ReferenceLocation;
import com.semtaint.container.ap.WildCardPattern;
import com.semtaint.container.handler.ContainerOperation;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.analysis.pta.plugin.util.SolverHolder;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.Literal;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.StoreArray;

/**
 * @program: semtaint-newfront
 * @description: AccessPattern 推导器，负责从语句中提取容器操作信息
 * @author: springtime
 **/
public class APDeriver extends SolverHolder {

    private final AccessPatternManager apManager;

    public APDeriver(Solver solver, AccessPatternManager apManager) {
        super(solver);
        this.apManager = apManager;
    }

    // =========================================================================
    // 数组操作处理
    // =========================================================================

    /**
     * 处理数组存储操作: arr[index] = src
     * 语义: s[acc] = r
     */
    public ContainerOperation deriveStoreArray(Context context, StoreArray store) {
        CSVar baseVar = csManager.getCSVar(context, store.getArrayAccess().getBase());
        Var indexVar = store.getArrayAccess().getIndex();
        AccessPattern ap = createAccessPattern(indexVar, context);
        CSVar srcVar = csManager.getCSVar(context, store.getRValue());

        return ContainerOperation.put(baseVar, ap, srcVar);
    }

    /**
     * 处理数组加载操作: dst = arr[index]
     * 语义: l = s[acc]
     */
    public ContainerOperation deriveLoadArray(Context context, LoadArray load) {
        CSVar baseVar = csManager.getCSVar(context, load.getArrayAccess().getBase());
        Var indexVar = load.getArrayAccess().getIndex();
        AccessPattern ap = createAccessPattern(indexVar, context);
        CSVar dstVar = csManager.getCSVar(context, load.getLValue());

        return ContainerOperation.get(baseVar, ap, dstVar);
    }

    // =========================================================================
    // Map 操作处理
    // =========================================================================

    /**
     * 处理 Map Put: base[key] = value
     * 包括: Map.put, HttpSession.setAttribute, Set.add
     */
    public ContainerOperation deriveMapPut(Context context, Invoke invoke) {
//         Set.add 只有一个参数，视为 key
        boolean isSetAdd = invoke.getInvokeExp().getArgCount() == 1;

        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar keyVar = getCSVar(context, invoke, 0);
        CSVar valueVar = isSetAdd ? keyVar : getCSVar(context, invoke, 1);

        AccessPattern ap = createAccessPattern(keyVar.getVar(), context);
        return ContainerOperation.put(baseVar, ap, valueVar);
    }

    /**
     * 处理 Map Get: ret = base[key]
     */
    public ContainerOperation deriveMapGet(Context context, Invoke invoke) {
        CSVar retVar = getReturnCSVar(context, invoke);
        if (retVar == null) return null;

        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar keyVar = getCSVar(context, invoke, 0);

        AccessPattern ap = createAccessPattern(keyVar.getVar(), context);
        return ContainerOperation.get(baseVar, ap, retVar);
    }

    /**
     * 处理 Map Remove: remove base[key]
     */
    public ContainerOperation deriveMapRemove(Context context, Invoke invoke) {
        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar keyVar = getCSVar(context, invoke, 0);

        AccessPattern ap = createAccessPattern(keyVar.getVar(), context);
        return ContainerOperation.remove(baseVar, ap);
    }

    // =========================================================================
    // 有序容器操作处理
    // =========================================================================

    /**
     * 处理索引插入: base.add(index, value)
     */
    public ContainerOperation deriveIndexedPut(Context context, Invoke invoke, int indexArgIdx, int valueArgIdx) {
        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar indexVar = getCSVar(context, invoke, indexArgIdx);
        CSVar valueVar = getCSVar(context, invoke, valueArgIdx);

        AccessPattern ap = createAccessPattern(indexVar.getVar(), context);
        return ContainerOperation.sortedPut(baseVar, ap, valueVar);
    }

    /**
     * 处理固定位置插入: base.addLast(value) / base.push(value)
     */
    public ContainerOperation deriveFixedPut(Context context, Invoke invoke, ReferenceLocation.Anchor anchor) {
        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar valueVar = getCSVar(context, invoke, 0);

        FixedPattern ap = apManager.getFixedPattern(anchor, 0);

        return ContainerOperation.sortedPut(baseVar, ap, valueVar);
    }

    /**
     * 处理索引获取: ret = base.get(index)
     * 保持使用 LiteralPattern，让 SemanticModelHandler 进行动态转换
     */
    public ContainerOperation deriveIndexedGet(Context context, Invoke invoke, int indexArgIdx) {
        CSVar retVar = getReturnCSVar(context, invoke);
        if (retVar == null) return null;

        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar indexVar = getCSVar(context, invoke, indexArgIdx);

        // 使用 createAccessPattern 生成 LiteralPattern
        // 不要在这里转成 FixedPattern，让上层根据类型动态处理
        AccessPattern ap = createAccessPattern(indexVar.getVar(), context);
        return ContainerOperation.get(baseVar, ap, retVar);
    }

    /**
     * 处理固定位置获取: ret = base.getFirst()
     */
    public ContainerOperation deriveFixedGet(Context context, Invoke invoke, ReferenceLocation.Anchor anchor) {
        CSVar retVar = getReturnCSVar(context, invoke);
        if (retVar == null) return null;

        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);

        FixedPattern ap = apManager.getFixedPattern(anchor, 0);

        return ContainerOperation.get(baseVar, ap, retVar);
    }

    /**
     * 处理索引删除: base.remove(index)
     */
    public ContainerOperation deriveIndexedRemove(Context context, Invoke invoke, int indexArgIdx) {
        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar indexVar = getCSVar(context, invoke, indexArgIdx);

        // 使用 createAccessPattern 生成 LiteralPattern
        AccessPattern ap = createAccessPattern(indexVar.getVar(), context);
        return ContainerOperation.sortedRemove(baseVar, ap);
    }

    /**
     * 处理固定位置删除: base.removeFirst() / base.removeLast()
     */
    public ContainerOperation deriveFixedRemove(Context context, Invoke invoke, ReferenceLocation.Anchor anchor) {
        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);

        FixedPattern ap = apManager.getFixedPattern(anchor, 0);

        return ContainerOperation.sortedRemove(baseVar, ap);
    }

    /**
     * 处理来源关联: ret = base.iterator()/entrySet()/keySet()/values()
     */
    public ContainerOperation deriveIteratorSource(Context context, Invoke invoke) {
        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar retVar = getReturnCSVar(context, invoke);
        if (baseVar == null || retVar == null) {
            return null;
        }
        return ContainerOperation.iteratorLink(baseVar, retVar);
    }

    /**
     * 处理迭代读取: ret = iter.next()
     */
    public ContainerOperation deriveIteratorNext(Context context, Invoke invoke) {
        CSVar iterVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar retVar = getReturnCSVar(context, invoke);
        if (iterVar == null || retVar == null) {
            return null;
        }
        return ContainerOperation.iteratorGet(iterVar, WildCardPattern.getInstance(), retVar);
    }

    /**
     * 处理容器迁移: ret = base.toArray()
     */
    public ContainerOperation deriveContainerTransferBaseToReturn(Context context, Invoke invoke) {
        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar retVar = getReturnCSVar(context, invoke);
        if (baseVar == null || retVar == null) {
            return null;
        }
        return ContainerOperation.containerTransfer(baseVar, retVar);
    }

    /**
     * 处理容器迁移: ret = asList(arg)
     */
    public ContainerOperation deriveContainerTransferArgToReturn(Context context, Invoke invoke, int argIdx) {
        CSVar srcVar = getCSVar(context, invoke, argIdx);
        CSVar retVar = getReturnCSVar(context, invoke);
        if (srcVar == null || retVar == null) {
            return null;
        }
        return ContainerOperation.containerTransfer(srcVar, retVar);
    }

    /**
     * 处理容器迁移: base.addAll(arg) / base.putAll(arg)
     */
    public ContainerOperation deriveContainerTransferArgToBase(Context context, Invoke invoke, int argIdx) {
        CSVar baseVar = getCSVar(context, invoke, InvokeUtils.BASE);
        CSVar srcVar = getCSVar(context, invoke, argIdx);
        if (baseVar == null || srcVar == null) {
            return null;
        }
        return ContainerOperation.containerTransfer(srcVar, baseVar);
    }

    /**
     * 创建访问模式（从变量中提取常量或创建符号访问模式）
     */
    private AccessPattern createAccessPattern(Var indexOrKeyVar, Context context) {
        if (indexOrKeyVar.isConst()) {
            Literal constant = indexOrKeyVar.getConstValue();
            if (constant instanceof IntLiteral intLit) {
                return apManager.getLiteralPattern(intLit);
            } else if (constant instanceof StringLiteral strLit) {
                return apManager.getLiteralPattern(strLit);
            }
        }

        return apManager.getVariablePattern(indexOrKeyVar);
    }

    /**
     * 强制创建一个基于 HEAD 的固定位置访问模式 (FixedPattern)。
     * 用于兼容 List 的 Append-only 模型，将索引访问视为距离头部的相对偏移。
     */
    private AccessPattern createHeadFixedPattern(Var indexVar, Context context) {
        // 尝试提取常量索引
        if (indexVar.isConst()) {
            Literal constant = indexVar.getConstValue();
            if (constant instanceof IntLiteral intLit) {
                // 生成 Ref(HEAD, value)
                return apManager.getFixedPattern(ReferenceLocation.Anchor.HEAD, intLit.getValue());
            }
        }

        // 如果是变量，且无法确定具体值，回退到 VariablePattern
        // 或者根据你的需求，这里也可以返回一个特殊的符号化 FixedPattern
        return apManager.getVariablePattern(indexVar);
    }

    /**
     * 获取参数或基变量的 CSVar
     */
    private CSVar getCSVar(Context context, Invoke invoke, int index) {
        Var var = InvokeUtils.getVar(invoke, index);
        if (var == null) return null;
        return csManager.getCSVar(context, var);
    }

    /**
     * 获取返回值的 CSVar
     */
    private CSVar getReturnCSVar(Context context, Invoke invoke) {
        Var var = invoke.getResult();
        if (var == null) return null;
        return csManager.getCSVar(context, var);
    }
}
