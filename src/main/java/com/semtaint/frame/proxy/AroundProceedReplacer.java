package com.semtaint.frame.proxy;

import com.semtaint.utils.enhance.CodeEnhancer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.ir.DefaultIR;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.ArrayAccess;
import pascal.taie.ir.exp.CastExp;
import pascal.taie.ir.exp.ConditionExp;
import pascal.taie.ir.exp.FieldAccess;
import pascal.taie.ir.exp.InstanceFieldAccess;
import pascal.taie.ir.exp.InvokeDynamic;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.InvokeInterface;
import pascal.taie.ir.exp.InvokeSpecial;
import pascal.taie.ir.exp.InvokeStatic;
import pascal.taie.ir.exp.InvokeVirtual;
import pascal.taie.ir.exp.LValue;
import pascal.taie.ir.exp.RValue;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.AssignLiteral;
import pascal.taie.ir.stmt.Cast;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Goto;
import pascal.taie.ir.stmt.If;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.Nop;
import pascal.taie.ir.stmt.Return;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.StoreArray;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.ir.stmt.Throw;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.VoidType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * create around wrapper by cloning advice IR and replacing proceed() with target invocation.
 */
public class AroundProceedReplacer {

    private static final Logger logger = LogManager.getLogger(AroundProceedReplacer.class);

    private static final String PJP_CLASS = "org.aspectj.lang.ProceedingJoinPoint";
    private static final String PROCEED_NAME = "proceed";

    private final CodeEnhancer enhancer;

    public AroundProceedReplacer(CodeEnhancer enhancer) {
        this.enhancer = enhancer;
    }

    public JMethod createWrapper(JMethod adviceMethod, JMethod targetMethod) {
        IR adviceIR = adviceMethod.getIR();
        if (adviceIR == null) {
//            logger.warn("[A5] advice method {} has no IR, fallback to linear", adviceMethod.getSignature());
            return null;
        }

        List<Type> wrapperParamTypes = new ArrayList<>(adviceMethod.getParamTypes());
        wrapperParamTypes.addAll(targetMethod.getParamTypes());
        wrapperParamTypes.add(targetMethod.getDeclaringClass().getType());

        int adviceParamCount = adviceMethod.getParamCount();
        int targetParamCount = targetMethod.getParamCount();
        int targetRefIndex = adviceParamCount + targetParamCount;

        String wrapperName = adviceMethod.getName() + "_around$" + targetMethod.getName();
        JClass aspectClass = adviceMethod.getDeclaringClass();
        JMethod wrapper = enhancer.addMethod(
                aspectClass,
                wrapperName,
                wrapperParamTypes,
                adviceMethod.getReturnType());

        Var wrapperThis = new Var(wrapper, "this", aspectClass.getType(), 0);
        List<Var> wrapperParams = new ArrayList<>();
        for (int i = 0; i < wrapperParamTypes.size(); i++) {
            wrapperParams.add(new Var(wrapper, "wp" + i, wrapperParamTypes.get(i), i + 1));
        }

        Map<Var, Var> varMap = buildVarMapping(adviceIR, wrapper, wrapperThis, wrapperParams, adviceParamCount);

        List<Var> targetParamVars = new ArrayList<>();
        for (int i = 0; i < targetParamCount; i++) {
            targetParamVars.add(wrapperParams.get(adviceParamCount + i));
        }
        Var targetRefVar = wrapperParams.get(targetRefIndex);

        List<Stmt> wrapperStmts = new ArrayList<>();
        Map<Stmt, Stmt> stmtMap = new LinkedHashMap<>();
        Map<Stmt, Stmt> jumpNewToOldTarget = new HashMap<>();

        for (Stmt oldStmt : adviceIR.getStmts()) {
            List<Stmt> newStmts = cloneStmt(oldStmt, wrapper, varMap, targetMethod, targetRefVar, targetParamVars, jumpNewToOldTarget);
            if (!newStmts.isEmpty()) {
                stmtMap.put(oldStmt, newStmts.get(0));
            }
            wrapperStmts.addAll(newStmts);
        }

        fixupJumpTargets(jumpNewToOldTarget, stmtMap);

        Set<Var> returnVars = new LinkedHashSet<>();
        List<Var> locals = new ArrayList<>(new LinkedHashSet<>(varMap.values()));
        IR wrapperIR = new DefaultIR(wrapper, wrapperThis, wrapperParams,
                returnVars, locals, wrapperStmts, List.of());
        wrapper.setIR(wrapperIR);

//        logger.info("[A5] Created around wrapper: {}.{}", aspectClass.getSimpleName(), wrapperName);
        return wrapper;
    }

    private Map<Var, Var> buildVarMapping(IR adviceIR,
                                          JMethod wrapper,
                                          Var wrapperThis,
                                          List<Var> wrapperParams,
                                          int adviceParamCount) {
        Map<Var, Var> map = new HashMap<>();

        Var oldThis = adviceIR.getThis();
        if (oldThis != null) {
            map.put(oldThis, wrapperThis);
        }

        List<Var> oldParams = adviceIR.getParams();
        for (int i = 0; i < Math.min(oldParams.size(), adviceParamCount); i++) {
            map.put(oldParams.get(i), wrapperParams.get(i));
        }

        int counter = 0;
        for (Stmt stmt : adviceIR.getStmts()) {
            for (RValue use : stmt.getUses()) {
                if (use instanceof Var v && !map.containsKey(v)) {
                    map.put(v, new Var(wrapper, "loc" + counter++, v.getType(), -1));
                }
            }
            Optional<LValue> def = stmt.getDef();
            if (def.isPresent() && def.get() instanceof Var v && !map.containsKey(v)) {
                map.put(v, new Var(wrapper, "loc" + counter++, v.getType(), -1));
            }
        }

        return map;
    }

    private Var mapVar(Var old, JMethod wrapper, Map<Var, Var> varMap) {
        if (old == null) {
            return null;
        }
        return varMap.computeIfAbsent(old, v -> new Var(wrapper, v.getName() + "$m", v.getType(), -1));
    }

    private List<Var> mapArgs(List<Var> oldArgs, JMethod wrapper, Map<Var, Var> varMap) {
        List<Var> result = new ArrayList<>(oldArgs.size());
        for (Var v : oldArgs) {
            result.add(mapVar(v, wrapper, varMap));
        }
        return result;
    }

    private boolean isProceedCall(Invoke invoke) {
        MethodRef ref = invoke.getMethodRef();
        String declClass = ref.getDeclaringClass().getName();
        return PJP_CLASS.equals(declClass) && PROCEED_NAME.equals(ref.getName());
    }

    private List<Stmt> cloneStmt(Stmt oldStmt,
                                 JMethod wrapper,
                                 Map<Var, Var> varMap,
                                 JMethod targetMethod,
                                 Var targetRefVar,
                                 List<Var> targetParamVars,
                                 Map<Stmt, Stmt> jumpNewToOldTarget) {
        List<Stmt> result = new ArrayList<>();

        if (oldStmt instanceof Invoke oldInvoke && !oldInvoke.isDynamic()) {
            if (isProceedCall(oldInvoke)) {
                result.addAll(createTargetCall(oldInvoke, wrapper, varMap, targetMethod, targetRefVar, targetParamVars));
            } else {
                result.add(remapInvoke(oldInvoke, wrapper, varMap));
            }
            return result;
        }

        if (oldStmt instanceof Copy oldCopy) {
            Var lv = mapVar(oldCopy.getLValue(), wrapper, varMap);
            Var rv = mapVar(oldCopy.getRValue(), wrapper, varMap);
            result.add(new Copy(lv, rv));
            return result;
        }

        if (oldStmt instanceof New oldNew) {
            Var lv = mapVar(oldNew.getLValue(), wrapper, varMap);
            result.add(new New(wrapper, lv, oldNew.getRValue()));
            return result;
        }

        if (oldStmt instanceof AssignLiteral oldLit) {
            Var lv = mapVar(oldLit.getLValue(), wrapper, varMap);
            result.add(new AssignLiteral(lv, oldLit.getRValue()));
            return result;
        }

        if (oldStmt instanceof Cast oldCast) {
            Var lv = mapVar(oldCast.getLValue(), wrapper, varMap);
            CastExp oldExp = oldCast.getRValue();
            Var castVal = mapVar(oldExp.getValue(), wrapper, varMap);
            result.add(new Cast(lv, new CastExp(castVal, oldExp.getCastType())));
            return result;
        }

        if (oldStmt instanceof LoadField oldLF) {
            Var lv = mapVar(oldLF.getLValue(), wrapper, varMap);
            FieldAccess oldAccess = oldLF.getFieldAccess();
            FieldAccess newAccess;
            if (oldAccess instanceof InstanceFieldAccess ifa) {
                Var base = mapVar(ifa.getBase(), wrapper, varMap);
                newAccess = new InstanceFieldAccess(ifa.getFieldRef(), base);
            } else {
                newAccess = oldAccess;
            }
            result.add(new LoadField(lv, newAccess));
            return result;
        }

        if (oldStmt instanceof StoreField oldSF) {
            Var rv = mapVar(oldSF.getRValue(), wrapper, varMap);
            FieldAccess oldAccess = oldSF.getFieldAccess();
            FieldAccess newAccess;
            if (oldAccess instanceof InstanceFieldAccess ifa) {
                Var base = mapVar(ifa.getBase(), wrapper, varMap);
                newAccess = new InstanceFieldAccess(ifa.getFieldRef(), base);
            } else {
                newAccess = oldAccess;
            }
            result.add(new StoreField(newAccess, rv));
            return result;
        }

        if (oldStmt instanceof LoadArray oldLA) {
            Var lv = mapVar(oldLA.getLValue(), wrapper, varMap);
            ArrayAccess oldAcc = oldLA.getArrayAccess();
            Var base = mapVar(oldAcc.getBase(), wrapper, varMap);
            Var index = mapVar(oldAcc.getIndex(), wrapper, varMap);
            result.add(new LoadArray(lv, new ArrayAccess(base, index)));
            return result;
        }

        if (oldStmt instanceof StoreArray oldSA) {
            Var rv = mapVar(oldSA.getRValue(), wrapper, varMap);
            ArrayAccess oldAcc = oldSA.getArrayAccess();
            Var base = mapVar(oldAcc.getBase(), wrapper, varMap);
            Var index = mapVar(oldAcc.getIndex(), wrapper, varMap);
            result.add(new StoreArray(new ArrayAccess(base, index), rv));
            return result;
        }

        if (oldStmt instanceof Return oldRet) {
            Var rv = oldRet.getValue();
            result.add(rv != null ? new Return(mapVar(rv, wrapper, varMap)) : new Return());
            return result;
        }

        if (oldStmt instanceof Throw oldThrow) {
            result.add(new Throw(mapVar(oldThrow.getExceptionRef(), wrapper, varMap)));
            return result;
        }

        if (oldStmt instanceof If oldIf) {
            ConditionExp oldCond = oldIf.getCondition();
            Var op1 = mapVar(oldCond.getOperand1(), wrapper, varMap);
            Var op2 = mapVar(oldCond.getOperand2(), wrapper, varMap);
            If newIf = new If(new ConditionExp(oldCond.getOperator(), op1, op2));
            newIf.setTarget(oldIf.getTarget());
            jumpNewToOldTarget.put(newIf, oldIf.getTarget());
            result.add(newIf);
            return result;
        }

        if (oldStmt instanceof Goto oldGoto) {
            Goto newGoto = new Goto();
            newGoto.setTarget(oldGoto.getTarget());
            jumpNewToOldTarget.put(newGoto, oldGoto.getTarget());
            result.add(newGoto);
            return result;
        }

        result.add(new Nop());
        return result;
    }

    private List<Stmt> createTargetCall(Invoke proceedInvoke,
                                        JMethod wrapper,
                                        Map<Var, Var> varMap,
                                        JMethod targetMethod,
                                        Var targetRefVar,
                                        List<Var> targetParamVars) {
        List<Stmt> stmts = new ArrayList<>();

        Var oldResult = proceedInvoke.getResult();
        Var newResult = oldResult != null ? mapVar(oldResult, wrapper, varMap) : null;

        MethodRef targetRef = targetMethod.getRef();

        if (targetMethod.isStatic()) {
            InvokeStatic invokeExp = new InvokeStatic(targetRef, targetParamVars);
            Var lvalue = !targetMethod.getReturnType().equals(VoidType.VOID) ? newResult : null;
            stmts.add(new Invoke(wrapper, invokeExp, lvalue));
        } else {
            InvokeVirtual invokeExp = new InvokeVirtual(targetRef, targetRefVar, targetParamVars);
            Var lvalue = !targetMethod.getReturnType().equals(VoidType.VOID) ? newResult : null;
            stmts.add(new Invoke(wrapper, invokeExp, lvalue));
        }

        if (targetMethod.getReturnType().equals(VoidType.VOID) && newResult != null) {
            stmts.add(new AssignLiteral(newResult, pascal.taie.ir.exp.NullLiteral.get()));
        }

        return stmts;
    }

    private Invoke remapInvoke(Invoke oldInvoke, JMethod wrapper, Map<Var, Var> varMap) {
        InvokeExp oldExp = oldInvoke.getInvokeExp();
        Var oldResult = oldInvoke.getResult();
        Var newResult = oldResult != null ? mapVar(oldResult, wrapper, varMap) : null;

        InvokeExp newExp;

        if (oldExp instanceof InvokeVirtual iv) {
            Var newBase = mapVar(iv.getBase(), wrapper, varMap);
            newExp = new InvokeVirtual(iv.getMethodRef(), newBase, mapArgs(iv.getArgs(), wrapper, varMap));
        } else if (oldExp instanceof InvokeInterface ii) {
            Var newBase = mapVar(ii.getBase(), wrapper, varMap);
            newExp = new InvokeInterface(ii.getMethodRef(), newBase, mapArgs(ii.getArgs(), wrapper, varMap));
        } else if (oldExp instanceof InvokeSpecial is) {
            Var newBase = mapVar(is.getBase(), wrapper, varMap);
            newExp = new InvokeSpecial(is.getMethodRef(), newBase, mapArgs(is.getArgs(), wrapper, varMap));
        } else if (oldExp instanceof InvokeStatic ist) {
            newExp = new InvokeStatic(ist.getMethodRef(), mapArgs(ist.getArgs(), wrapper, varMap));
        } else if (oldExp instanceof InvokeDynamic) {
            newExp = oldExp;
        } else {
            newExp = oldExp;
        }

        return new Invoke(wrapper, newExp, newResult);
    }

    private void fixupJumpTargets(Map<Stmt, Stmt> jumpNewToOldTarget,
                                  Map<Stmt, Stmt> stmtMap) {
        for (Map.Entry<Stmt, Stmt> entry : jumpNewToOldTarget.entrySet()) {
            Stmt jump = entry.getKey();
            Stmt oldTarget = entry.getValue();
            if (oldTarget == null) {
                continue;
            }
            Stmt newTarget = stmtMap.get(oldTarget);
            if (newTarget == null) {
                continue;
            }
            if (jump instanceof If ifStmt) {
                ifStmt.setTarget(newTarget);
            } else if (jump instanceof Goto gotoStmt) {
                gotoStmt.setTarget(newTarget);
            }
        }
    }
}
