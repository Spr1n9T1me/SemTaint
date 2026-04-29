package com.semtaint.container.ap;

/**
 * @program: semtaint-newfront
 * @description:  AP_fixed ::= Ref(rho, delta)
 * @author: springtime
 **/
public class FixedPattern extends AccessPattern {
    
    // 静态引用，由 Solver 初始化时设置
    private static AccessPatternManager manager;
    
    public static void setManager(AccessPatternManager apManager) {
        manager = apManager;
    }
    
    public FixedPattern(ReferenceLocation loc) {
        super(loc);
    }

    /**
     * 平移访问模式，用于容器的插入/删除操作后更新访问位置
     *
     * @param direction  1: 插入操作，向右平移；-1: 删除操作，向左平移
     * @param operationAp 操作产生的访问模式（插入或删除的位置）
     */
    public void shiftPattern(int direction, AccessPattern operationAp) {
        if (this.pattern instanceof ReferenceLocation) {
            ReferenceLocation currentLoc = (ReferenceLocation) this.pattern;
            ReferenceLocation newLoc;

            if (direction == 1) {
                // 插入操作，使用 shift
                newLoc = currentLoc.shift(operationAp);
            } else if (direction == -1) {
                // 删除操作，使用 unshift
                newLoc = currentLoc.unshift(operationAp);
            } else {
                throw new IllegalArgumentException("Direction must be 1 (insert) or -1 (remove)");
            }

            this.pattern = newLoc;
        } else {
            throw new UnsupportedOperationException("Shifting operation is only supported for Fixed Patterns with ReferenceLocation.");
        }
    }

    /**
     * 重写 shift 方法以支持 CorrelationSet 的统一调用。
     * 对于 FixedPattern，委托给内部的 ReferenceLocation.shift()。
     *
     * @param insertionAp 插入操作产生的访问模式 (ap_ins)
     * @return 平移后的新 FixedPattern，如果不需要平移则返回 this
     */
    @Override
    public AccessPattern shift(AccessPattern insertionAp) {
        if (this.pattern instanceof ReferenceLocation) {
            ReferenceLocation currentLoc = (ReferenceLocation) this.pattern;
            ReferenceLocation newLoc = currentLoc.shift(insertionAp);

            // 如果位置没有变化，返回当前对象以避免不必要的对象创建
            if (newLoc == currentLoc) {
                return this;
            }

            // 使用 manager 获取或创建 FixedPattern
            if (manager != null) {
                return manager.getFixedPattern(newLoc);
            }
            return new FixedPattern(newLoc);
        }
        return this;
    }

    /**
     * 重写 unshift 方法以支持 CorrelationSet 的统一调用。
     * 对于 FixedPattern，委托给内部的 ReferenceLocation.unshift()。
     *
     * @param deletionAp 删除操作产生的访问模式 (ap_del)
     * @return 平移后的新 FixedPattern
     */
    @Override
    public AccessPattern unshift(AccessPattern deletionAp) {
        if (this.pattern instanceof ReferenceLocation) {
            ReferenceLocation currentLoc = (ReferenceLocation) this.pattern;
            ReferenceLocation newLoc = currentLoc.unshift(deletionAp);

            // 如果位置没有变化，返回当前对象
            if (newLoc == currentLoc) {
                return this;
            }

            // 使用 manager 获取或创建 FixedPattern
            if (manager != null) {
                return manager.getFixedPattern(newLoc);
            }
            return new FixedPattern(newLoc);
        }
        return this;
    }

}

