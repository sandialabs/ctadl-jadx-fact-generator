package jadx_factgen;

import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.instructions.*;
import jadx.core.dex.instructions.args.*;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.utils.InsnUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static jadx_factgen.Util.*;

class FactUtil {
    private static final Logger LOG = LoggerFactory.getLogger(FactUtil.class);
    private final Souffle souffle;

    public FactUtil(Souffle souffle) {
        this.souffle = souffle;
    }

    public static class ConstVar {
        public final String constStr;
        public final ArgType type;
        public final boolean isTypeConst;

        public ConstVar(String s, @Nullable ArgType t) {
            this(s, t, false);
        }

        /**
         * @param isTypeConst whether or not this type represents a type constant: its value is a type not a value of a type
         */
        public ConstVar(String s, @Nullable ArgType t, boolean isTypeConst) {
            constStr = escapeTabsAndNewlines(s);
            type = Objects.requireNonNullElse(t, ArgType.UNKNOWN);
            this.isTypeConst = isTypeConst;
        }

        public String getVarStr(Souffle souffle, InsnContext inCtx) {
            return getVarStr(souffle, methodToString(inCtx.mth), inCtx.stmtStr, inCtx.constVarCounter++);
        }

        public synchronized String getVarStr(Souffle souffle, String methodString, String stmtStr) {
            return getVarStr(souffle, methodString, stmtStr, 0);
        }

        public synchronized String getVarStr(Souffle souffle, String methodString, String stmtStr, int varCounter) {
            String internalName = "constvar/" + varCounter;
            String varStr = stmtStr + "/" + internalName;
            souffle.varHasInternalNameWriter.writeFact(varStr, internalName);
            souffle.varInMethodWriter.writeFact(varStr, methodString);
            souffle.varHasConstValueWriter.writeFact(varStr, constStr);
            souffle.varHasTypeWriter.writeFact(varStr, getArgTypeString());
            return varStr;
        }

        public String getArgTypeString() {
            String argTypeString = argTypeToString(type);
            if (isTypeConst) {
                return argTypeString + "{TYPE}";
            }
            return argTypeString;
        }
    }

    public static class InsnContext implements Comparable<InsnContext> {
        public final Set<String> seenLocalVars;
        public final BlockNode block;
        public final MethodNode mth;
        public final int insnIdx;
        public final String stmtQual;
        public final String stmtStr;
        public final InsnNode insn;
        public int constVarCounter = 0;

        public InsnContext(Set<String> seenLocalVars, BlockNode block, MethodNode mth, int blockIdx, int insnIdx, InsnNode insn) {
            this.seenLocalVars = seenLocalVars;
            this.block = block;
            this.mth = mth;
            this.insnIdx = insnIdx;
            this.insn = insn;
            this.stmtQual = stmtQualifier(block, blockIdx, insnIdx);
            this.stmtStr = stmtToString(mth, block, blockIdx, insnIdx);
        }

        public InsnContext(Set<String> seenLocalVars, FieldNode field, MethodNode mth, InsnNode insn, int subInsn) {
            this.seenLocalVars = seenLocalVars;
            // init to enter block, probably fine...
            this.block = mth.getEnterBlock();
            this.insnIdx = subInsn;
            this.mth = mth;
            this.insn = insn;
            this.stmtQual = stmtQualifier(field, subInsn);
            this.stmtStr = stmtToString(field, mth, subInsn);
        }

        @SuppressWarnings("unused")
        public InsnWrapArg getWrapArg(Map<InsnNode, FactUtil.InsnContext> inCtxs) {
            for (InsnContext innerCtx : inCtxs.values()) {
                for (InsnArg arg : innerCtx.insn.getArguments()) {
                    if (arg.isInsnWrap()) {
                        InsnWrapArg wrapArg = (InsnWrapArg) arg;
                        if (wrapArg.getWrapInsn() == this.insn) {
                            return wrapArg;
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public int compareTo(@NotNull InsnContext other) {
            // sort in insn idx order
            return this.insnIdx - other.insnIdx;
        }

        public static List<InsnContext> reverseSort(Collection<InsnContext> toSort) {
            // innermost first to outer
            return toSort.stream().sorted(Collections.reverseOrder()).collect(Collectors.toList());
        }
    }

    public static String stmtToString(FieldNode field, @Nullable MethodNode mth, int subInsn) {
        String methodString = mth != null ? methodToString(mth) : fieldToConstructorString(field);
        return methodString + stmtQualifier(field, subInsn);
    }

    public static String stmtToString(MethodNode mth, BlockNode block, int blockIdx, int subInsnIdx) {
        // can't use insn.getOffset(); sometimes returns -1, (some synthetic instructions don't set it)
        return blockToString(block, mth) + "/" + blockIdx + "/" + subInsnIdx;
    }

    public static String stmtQualifier(FieldNode field, int subInsn) {
        return "FIELD_INIT/" + fieldToFieldString(field) + "/" + subInsn;
    }

    // careful, this needs to be in sync with stmtToString
    public static String stmtQualifier(BlockNode block, int blockIdx, int subInsnIdx) {
        // can't use insn.getOffset(); sometimes returns -1, (some synthetic instructions don't set it)
        return block.toString() + "/" + blockIdx + "/" + subInsnIdx;
    }

    public String ssaVarToString(SSAVar ssavar, MethodNode mth, Set<String> seenLocalVars) {
        String internalName = "ssa/" + ssavar.toShortString();
        String argStr = methodToString(mth) + "/" + internalName;
        String codeName = ssavar.getName();
        if (updateVar(argStr, ssavar.getAssign().getType(), seenLocalVars, mth)) {
            souffle.varHasInternalNameWriter.writeFact(argStr, internalName);
            if (codeName != null) {
                // XXX: non-deterministically, in rare cases, jadx will not have a name for a var that should have one
                // I'm still unsure of the root cause, but after a lot of debugging I'm reasonably sure it's not on my end
                souffle.varHasNameWriter.writeFact(argStr, codeName);
            }
        }
        return argStr;
    }

    public String registerArgToString(RegisterArg arg, MethodNode mth, Set<String> seenLocalVars) {
        SSAVar ssavar = arg.getSVar();
        if (ssavar != null) {
            return ssaVarToString(ssavar, mth, seenLocalVars);
        }

        String codeName = arg.getName();
          // this will not be unique necessarily if this path is ever actually hit (type will just be first one seen)
        String internalName = "reg/" + (codeName != null ? codeName : arg.getRegNum());
        StringBuilder s = new StringBuilder();
        s.append(methodToString(mth));
        s.append("/");
        s.append(internalName);
        String argStr = s.toString();
        if (updateVar(argStr, arg.getType(), seenLocalVars, mth)) {
            souffle.varHasInternalNameWriter.writeFact(argStr, internalName);
            if (codeName != null) {
                souffle.varHasNameWriter.writeFact(argStr, codeName);
            }
        }
        return argStr;
    }

    public String insnArgToString(Map<InsnNode, InsnContext> inCtxs, InsnContext inCtx, InsnArg arg) {
        String argStr;
        ConstVar constVar;
        if (arg.isRegister()) {
            argStr = registerArgToString((RegisterArg) arg, inCtx.mth, inCtx.seenLocalVars);
        }
        else if ((constVar = insnArgConstVarElseNull(arg)) != null) { /* arg.isLiteral() implicitly handled here */
            return constVar.getVarStr(this.souffle, inCtx);
        }
        else if (arg.isNamed()) {
            // a named arg is a named (as in with a string) variable with a type but not a value basically
            NamedArg named = (NamedArg) arg;
            argStr = methodToString(inCtx.mth) + "/named/" + named.getName();
            souffle.varHasInternalNameWriter.writeFact(argStr, named.getName());
        }
        else if (arg.isInsnWrap()) {
            InsnWrapArg insnWrapArg = (InsnWrapArg) arg;
            InsnNode wrapInsn = insnWrapArg.getWrapInsn();
            argStr = getResultString(inCtxs.get(wrapInsn), insnWrapArg.getType());
        }
        else {
            throw new RuntimeException("Error: don't understand type for arg " + arg);
        }

        updateVar(argStr, arg.getType(), inCtx.seenLocalVars, inCtx.mth);
        return argStr;
    }

    public String phiStmtToString(MethodNode mth, PhiInsn phi, int insnIdx, Set<String> seenLocalVars) {
        return registerArgToString(phi.getResult(), mth, seenLocalVars) + "/" + "PhiAssign" + "/" + insnIdx;
    }

    public ConstVar constValueToConstVar(Object val) {
        // expect LiteralArg, String, ArgType or null
        if (val instanceof LiteralArg) {
            return insnArgConstVarElseNull((LiteralArg)val);
        }
        else if (val instanceof String) {
            return new ConstVar((String)val, ArgType.STRING);
        }
        else if (val instanceof ArgType) {
            ArgType typeVal = (ArgType)val;
            return new ConstVar(argTypeToString(typeVal), typeVal, true);
        }
        else if (val == null) {
            return null;
        }
        else {
            LOG.error("constValueToString unhandled type " + val);
            return new ConstVar(val.toString(), null);
        }
    }

    public ConstVar constVarFromConstNode(InsnNode insn) {
        return insnArgConstVarElseNull(insn.getArg(0));
    }

    public ConstVar constVarFromConstStringNode(ConstStringNode insn) {
        return new ConstVar(insn.getString(), ArgType.STRING);
    }

    public ConstVar constVarFromConstClassNode(ConstClassNode insn) {
        return new ConstVar(insn.getClsType() + ".class", insn.getClsType(), true);
    }

    public ConstVar insnArgConstVarElseNull(InsnArg arg) {
        // see InsnUtils.getConstValueByInsn
        if (arg.isLiteral()) {
            LiteralArg literalArg = (LiteralArg) arg;
            return new ConstVar(String.valueOf(literalArg.getLiteral()), literalArg.getType());
        }
        else if (arg.isInsnWrap()) {
            InsnNode wrapInsn = ((InsnWrapArg) arg).getWrapInsn();
            InsnType wrapInsnType = wrapInsn.getType();
            if (wrapInsn.isConstInsn()) {
                RegisterArg res = wrapInsn.getResult();
                if (wrapInsnType == InsnType.CONST) {
                    return constVarFromConstNode(wrapInsn);
                }
                if (wrapInsnType == InsnType.CONST_STR) {
                    return constVarFromConstStringNode((ConstStringNode)wrapInsn);
                }
                else if (wrapInsnType == InsnType.CONST_CLASS) {
                    return constVarFromConstClassNode((ConstClassNode)wrapInsn);
                }
                else if (res != null) {
                    return insnArgConstVarElseNull(res);
                }
                else if (wrapInsn.getArgsCount() > 0) {
                    // don't know if this case should ever get hit; getConstValueByInsn implies it might?
                    LOG.debug("null result but at least one arg for const wrap instr: " + wrapInsn);
                    return insnArgConstVarElseNull(wrapInsn.getArg(0));
                }
                // else fall through to return NULL
                LOG.warn("instr arg wrapped CONST fallthrough to null: " + wrapInsn);
            }
            else if (wrapInsnType == InsnType.SGET) {
                // want to check if external first; external classes will fail and produce noisy warnings
                if (!isExternalClass(((FieldInfo) ((IndexInsnNode) wrapInsn).getIndex()).getDeclClass())) {
                    // if the SGET goes to a constant static field, this will get its value; otherwise return null
                    // LiteralArg, String, ArgType or null
                    Object staticVal = InsnUtils.getConstValueByInsn(App.root, wrapInsn);
                    return constValueToConstVar(staticVal);
                }
            }
        }
        return null;
    }

    public String insnArgConstStringElseNull(InsnArg arg) {
        ConstVar constVar = insnArgConstVarElseNull(arg);
        if (constVar == null) {
            return null;
        }
        return constVar.constStr;
    }

    public boolean updateVar(String argStr, ArgType argType, Set<String> seenVars, @Nullable MethodNode mth) {
        boolean newVar = seenVars.add(argStr);
        if (newVar) {
            // null method indicates this is a global
            if (mth != null) {
                souffle.varInMethodWriter.writeFact(argStr, methodToString(mth));
            }
            if (argType != ArgType.UNKNOWN) {
                souffle.varHasTypeWriter.writeFact(argStr, argTypeToString(argType));
            }
        }
        return newVar;
    }

    public String getResultString(InsnContext inCtx, ArgType retType) {
        RegisterArg res = inCtx.insn.getResult();
        if (res == null) {
            String internalName = inCtx.stmtQual + "/bound_result";
            String resStr = inCtx.stmtStr + "/bound_result";
            if (updateVar(resStr, retType, inCtx.seenLocalVars, inCtx.mth)) {
                souffle.varHasInternalNameWriter.writeFact(resStr, internalName);
            }
            return resStr;
        }
        else {
            return registerArgToString(res, inCtx.mth, inCtx.seenLocalVars);
        }
    }
}
