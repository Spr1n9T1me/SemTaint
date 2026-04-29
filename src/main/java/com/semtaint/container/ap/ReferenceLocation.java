package com.semtaint.container.ap;

import java.util.Objects;

/**
 * @program: semtaint-newfront
 * @description: 用于表示基于锚点（HEAD/TAIL）的相对位置访问。
 * @author: springtime
 **/
public class ReferenceLocation {

    public enum Anchor {
        HEAD, //  HEAD，通常用于队列头部操作
        TAIL  //  TAIL，通常用于列表追加操作
    }

    private final Anchor anchor;
    private final int offset; // 对应论文中的 delta (整数)

    public ReferenceLocation(Anchor anchor, int offset) {
        this.anchor = anchor;
        this.offset = offset;
    }

    public Anchor getAnchor() {
        return anchor;
    }

    public int getOffset() {
        return offset;
    }

    /**
     *  shift 操作辅助函数
     * 当容器发生插入操作（insertionAp）时，计算当前 Ref 的新位置。
     *
     * @param insertionAp 插入操作产生的访问模式 (ap_ins)
     * @return 平移后的新 ReferenceLocation，如果不需要平移则返回 this
     */
    public ReferenceLocation shift(AccessPattern insertionAp) {
        // Case 1: 插入发生在 TAIL (ap_ins = Ref(TAIL, 0))
        // 逻辑来源: shift_ap(Ref(TAIL, delta), Ref(TAIL, 0)) = Ref(TAIL, delta - 1)
        if (insertionAp.getPattern() instanceof ReferenceLocation) {
            ReferenceLocation ins = (ReferenceLocation) insertionAp.getPattern();
            if (this.anchor == Anchor.TAIL && ins.anchor == Anchor.TAIL && ins.offset == 0) {
                // 论文指出 TAIL 模式下 delta 为非正数 [cite: 509]。
                // 例如：倒数第1个(0) -> 插入后变为倒数第2个(-1)
                return new ReferenceLocation(Anchor.TAIL, this.offset - 1);
            }
        }

        // Case 2: 插入发生在 HEAD 或具体索引 k (ap_ins = Lit(k) 或 Ref(HEAD, k))
        // 如果当前是 HEAD 类型且 delta >= k，则向后平移 (delta + 1)
        if (this.anchor == Anchor.HEAD) {
            int k = getLiteralIndex(insertionAp); // 辅助方法获取插入索引

            // 变量索引的保守处理：如果插入位置不确定，保守地假设可能影响
            // 这种情况下应该清除该容器的所有 HEAD 映射（由上层 CorrelationSet 处理）
            // 这里返回一个特殊标记，让调用者知道发生了不可确定的平移
            if (k == Integer.MIN_VALUE) {
                // TODO: 考虑向上抛出异常或返回 null，强制上层清除映射
                // 目前暂时返回 this，但应该在 CorrelationSet 中检测并处理此情况
                System.err.println("[WARNING] VariablePattern detected in shift operation. " +
                        "Precise analysis may be compromised. Consider clearing container mappings.");
                return this; // 保守策略：不平移，但精度受损
            }

            if (k != -1 && this.offset >= k) {
                return new ReferenceLocation(Anchor.HEAD, this.offset + 1);
            }
        }

        return this; // 不受影响
    }

    /**
     * 实现论文中的 unshift 操作辅助函数 [cite: 514-524]。
     * 当容器发生删除操作（deletionAp）时，计算当前 Ref 的新位置。
     *
     * @param deletionAp 删除操作产生的访问模式 (ap_del)
     * @return 平移后的新 ReferenceLocation
     */
    public ReferenceLocation unshift(AccessPattern deletionAp) {
        // Case 1: 删除发生在 TAIL (ap_del = Ref(TAIL, k))
        //  如果当前偏移 delta < k (即在删除点的左侧/更深处)，则 delta + 1
        if (deletionAp.getPattern() instanceof ReferenceLocation) {
            ReferenceLocation del = (ReferenceLocation) deletionAp.getPattern();
            if (this.anchor == Anchor.TAIL && del.anchor == Anchor.TAIL) {
                // 注意：对于 TAIL，更小的 delta 意味着更靠前的元素（因为 delta 是负数或0）
                if (this.offset < del.offset) {
                    return new ReferenceLocation(Anchor.TAIL, this.offset + 1);
                }
            }
        }

        // Case 2: 删除发生在 HEAD 或具体索引 k
        // 如果当前是 HEAD 类型且 delta > k，则向前平移 (delta - 1)
        if (this.anchor == Anchor.HEAD) {
            int k = getLiteralIndex(deletionAp);

            // 变量索引的保守处理：如果删除位置不确定，无法准确平移
            // 这会导致分析精度严重下降，应该清除该容器的所有 HEAD 映射
            if (k == Integer.MIN_VALUE) {
                // TODO: 考虑向上抛出异常或返回 null，强制上层清除映射
                System.err.println("[WARNING] VariablePattern detected in unshift operation. " +
                        "Precise analysis may be compromised. Consider clearing container mappings.");
                return this; // 保守策略：不平移，但精度受损
            }

            if (k != -1 && this.offset > k) {
                return new ReferenceLocation(Anchor.HEAD, this.offset - 1);
            }
        }

        return this;
    }

    /**
     * 辅助方法：尝试从 AP 中提取整型索引 k (处理 Lit(k) 或 Ref(HEAD, k))
     *
     * @param ap 访问模式
     * @return 整型索引；变量索引返回 Integer.MIN_VALUE（需保守处理）；无法提取返回 -1
     */
    private int getLiteralIndex(AccessPattern ap) {
        if (ap instanceof LiteralPattern) {
            Object literal = ap.getPattern();
            if (literal instanceof pascal.taie.ir.exp.IntLiteral) {
                return ((pascal.taie.ir.exp.IntLiteral) literal).getValue();
            }
        }
        if (ap.getPattern() instanceof ReferenceLocation) {
            ReferenceLocation ref = (ReferenceLocation) ap.getPattern();
            if (ref.anchor == Anchor.HEAD) {
                return ref.offset;
            }
        }
        // 关键修复：检测 VariablePattern
        // 这种情况意味着索引在编译期不可确定，必须保守处理
        // 返回 MIN_VALUE 作为特殊标记，通知调用者需要降级处理
        if (ap instanceof VariablePattern) {
            return Integer.MIN_VALUE;
        }
        return -1; // 表示无法提取数值索引
    }

    // 重写 equals 和 hashCode 是作为 CorrelationSet 键值的关键 [cite: 485]
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReferenceLocation that = (ReferenceLocation) o;
        return offset == that.offset && anchor == that.anchor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(anchor, offset);
    }

    @Override
    public String toString() {
        return "Ref(" + anchor + ", " + offset + ")";
    }
}
