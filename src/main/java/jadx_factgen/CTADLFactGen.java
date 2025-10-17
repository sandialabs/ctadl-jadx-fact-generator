package jadx_factgen;

import jadx.api.JadxArgs;
import jadx.api.ICodeWriter;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.annotations.InsnCodeOffset;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.utils.CodeUtils;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.FieldInitInsnAttr;
import jadx.core.dex.attributes.nodes.PhiListAttr;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.*;
import jadx.core.dex.instructions.args.*;
import jadx.core.dex.instructions.mods.ConstructorInsn;
import jadx.core.dex.instructions.mods.TernaryInsn;
import jadx.core.dex.nodes.*;
import jadx.core.dex.regions.conditions.IfCondition;
import jadx.core.dex.visitors.AbstractVisitor;
import jadx.core.dex.visitors.JadxVisitor;
import jadx.core.utils.BlockUtils;
import jadx.core.utils.EncodedValueUtils;
import jadx.core.xmlgen.XmlSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;

import static jadx_factgen.FactUtil.*;
import static jadx_factgen.Souffle.RET_ARG_INDEX;
import static jadx_factgen.Util.*;

@JadxVisitor(
        name = "CTADL Fact Generation",
        desc = "Generates datalog facts for use in CTADL"
)

public class CTADLFactGen extends AbstractVisitor {
    private final Souffle souffle;
    private final FactUtil factUtil;
    private final Set<ClassNode> visitedClasses = Collections.synchronizedSet(new HashSet<>());
    private final Set<MethodInfo> seenExternalMethods = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> seenExternalInterfaces = Collections.synchronizedSet(new HashSet<>());
    private final Set<FieldInfo> seenExternalFields = Collections.synchronizedSet(new HashSet<>());
    private final Set<FieldInfo> seenFields = Collections.synchronizedSet(new HashSet<>());
    private final DebugLogWriter insnLogWriter;
    private final DebugLogWriter skippedInsnLogWriter;
    private static final Logger LOG = LoggerFactory.getLogger(CTADLFactGen.class);

    public CTADLFactGen(App app) {
        JadxArgs args = App.root.getArgs();
        File outDir = args.getOutDir();
        outDir = new File(outDir, "facts");
        // need to ensure directory exists; jadx won't have created it yet, and won't at all if not saving files
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new RuntimeException("Error creating output directory " + outDir);
        }
        try {
            souffle = Souffle.getSouffle();
            factUtil = new FactUtil(souffle);

            insnLogWriter = new DebugLogWriter("Instructions.txt", app.debugInfo);
            skippedInsnLogWriter = new DebugLogWriter("SkippedInstructions.txt", app.debugInfo);
        } catch (IOException e) {
            throw new RuntimeException("CTADL fact gen failed to create facts files, got: " + e);
        }
    }

    @Override
    public void init(RootNode root) {
        // since this won't be initted in the normal jadx flow, can just put init in the constructor instead
        // this way const variables can be made final
    }

    public void init(RootNode root, List<ResourceFile> resources) {
        init(root);
        for (ResourceFile f : resources) {
            if (f.getType() == ResourceType.MANIFEST) {
                LongFunction<String> getIdString = (long i) -> "<ManifestXmlNode>-" + Long.toUnsignedString(i);
                String xmlContent = f.loadContent().getText().getCodeStr();
                try {
                    DocumentBuilder builder = XmlSecurity.getSecureDbf().newDocumentBuilder();
                    //noinspection BlockingMethodInNonBlockingContext
                    Document androidManifest = builder.parse(new InputSource(new StringReader(xmlContent)));
                    androidManifest.getDocumentElement().normalize();
                    Node curNode = androidManifest.getDocumentElement();
                    final ArrayList<Node> toVisit = new ArrayList<>();
                    final Map<Node, Long> idMap = new HashMap<>();
                    long nodeId = 0;
                    while (curNode != null) {
                        if (idMap.containsKey(curNode)) {
                            LOG.error("Unexpected duplicate key in manifest XML: " + curNode);
                        }
                        idMap.put(curNode, nodeId);
                        String idString = getIdString.apply(nodeId);
                        souffle.manifestNodeWriter.writeFact(idString, curNode.getNodeName());
                        if (curNode != androidManifest.getDocumentElement()) {
                            Node parent = curNode.getParentNode();
                            Long parentId = idMap.get(parent);
                            if (parentId == null) {
                                LOG.error("Could not find parent id for node" + curNode);
                            }
                            else {
                                souffle.manifestNodeChildWriter.writeFact(getIdString.apply(parentId), idString);
                            }
                        }
                        else {
                            souffle.manifestRootWriter.writeFact(idString);
                        }
                        NamedNodeMap attrs = curNode.getAttributes();
                        if (attrs != null) {
                            for (int i = 0; i < attrs.getLength(); i++) {
                                Node attr = attrs.item(i);
                                String key = attr.getNodeName();
                                String value = attr.getNodeValue();
                                souffle.manifestNodeAttrWriter.writeFact(idString, key, escapeTabsAndNewlines(value));
                            }
                        }
                        NodeList childNodes = curNode.getChildNodes();
                        for (int i = 0; i < childNodes.getLength(); i++) {
                            Node node = childNodes.item(i);
                            if (node.getNodeType() == Node.ELEMENT_NODE) {
                                toVisit.add(node);
                            }
                        }
                        if (toVisit.isEmpty()) {
                            curNode = null;
                        }
                        else {
                            curNode = toVisit.remove(toVisit.size() - 1);
                        }
                        nodeId++;
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Could not parse Android Manifest", e);
                }
            }
        }
    }

    public void cleanup() {
        for (Souffle.SouffleFactFile factFile : souffle.getFactsWriters()) {
            factFile.writer.flush();
        }
        for (DebugLogWriter writer : DebugLogWriter.debugLogWriters) {
            writer.flush();
        }
        LOG.debug("Fact gen visited count: " + visitedClasses.size());
    }

    public MethodNode findMethod(Map<Integer, MethodNode> decompPosToMethod, int pos) {
        int closestPos = 0;
        MethodNode ret = null;
        for(Map.Entry<Integer, MethodNode> entry : decompPosToMethod.entrySet()) {
            if(entry.getKey() < pos && entry.getKey() > closestPos) {
                closestPos = entry.getKey();
                ret = entry.getValue();
            }
        }
        return ret;
    }

    // The code utils verion of getLineNumForPos has a bug where it runs forever sometimes
    // I think it happens when pos is at the start or end of the file
    // We need a safer version for our purposes
    public int getLineNumForPosSafe(String code, int pos) {
        String newLine = ICodeWriter.NL;
        int newLineLen = newLine.length();
        int line = 1;
        int prev = 0;
        while (true) {
                int next = code.indexOf(newLine, prev);
		if (next >= pos) {
		    return line;
		}
		if(next == -1) {
		    return line;
		}
		prev = next + newLineLen;
		line++;
	}
    }
    
    
    @Override
    public boolean visit(ClassNode cls) {
        // true means to keep processing inner classes and method, false means to stop
        // Seems like it visits some classes twice; the objects are completely identical as far as I can tell
        boolean newClass = visitedClasses.add(cls);
        if (newClass) {
            LOG.debug("Visiting class " + cls);

            String clsTypeString = classToString(cls);
            ArgType superClassType = cls.getSuperClass();
            souffle.classFileNameWriter.writeFact(clsTypeString, getClassFilePath(cls));
            // the top parent class will be itself if this is the top level
            souffle.topParentClassWriter.writeFact(clsTypeString, classToString(cls.getTopParentClass()));
            if (superClassType != null) {
                souffle.directSuperclassWriter.writeFact(argTypeToString(superClassType), clsTypeString);
            }
            for (ArgType superInterfaceType : cls.getInterfaces()) {
                if (seenExternalInterfaces.add(argTypeToString(superInterfaceType))) {
                    souffle.interfaceTypeWriter.writeFact(argTypeToString(superInterfaceType));
                }
                souffle.superInterfaceWriter.writeFact(argTypeToString(superInterfaceType), clsTypeString);
            }
            souffle.classHasNameWriter.writeFact(clsTypeString, classToNameString(cls));
            souffle.classDefinedInWriter.writeFact(clsTypeString, classFileName(cls), classLineNo(cls));
            if (classIsInterface(cls)) {
                souffle.interfaceTypeWriter.writeFact(clsTypeString);
            }
            for (FieldNode field : cls.getFields()) {
                // need to handle assignments for field init, will not be processed in the normal method visit code
                // these instructions will not appear in the method they claim to be in if done in the class body
                // see addField in jadx.core.codegen.ClassGen.java
                handleClassFieldInit(field);
                if (fieldIsFinal(field)) {
                    souffle.fieldIsFinalWriter.writeFact(fieldToClassString(field), fieldToFieldString(field));
                }
            }
        }
        return newClass;
    }

    @Override
    public void visit(MethodNode mth) {
        long start = System.nanoTime();
        LOG.debug("Visiting method: " + methodToString(mth));
        final Set<String> seenLocalVars = Collections.synchronizedSet(new HashSet<>());
        if (!processMethod(mth, seenLocalVars)) {
            return; // no code
        }

        List<BlockNode> blocks = mth.getBasicBlocks();
        if (blocks == null) {
            LOG.warn("Blocks in " + methodToNameString(mth) + " were null");
            return;
        }
        ArrayList<BlockNode> sortedBlocks = new ArrayList<>(blocks.size());
        BlockUtils.dfsVisit(mth, sortedBlocks::add);

        // DONT_GENERATE: process as usual, but don't output to generated code
        // seems like jadx visitors generally mark a node with DONT_GENERATE rather than removing them in some cases
        // comments refer to this as "hiding" an instruction
        // REMOVE are instructions that can be removed from the instruction stream but haven't been
        // still process REMOVE, seems these always have DONT_GENERATE anyway (far as I've seen)
        // WRAPPED indicates its a wrapped instruction (e.g. arg to an instr is itself an instruction); see wrapInsnIntoArg
        // SYNTHETIC seems to be instructions inserted by analysis passes
        AtomicInteger insnIndex = new AtomicInteger();
        for (BlockNode block : sortedBlocks) {
            // first, preemptively iterate over all the phi nodes in the block from the phi list attr
            // the phi nodes are removed from the normal instruction stream in the ssa pass
            // (see jadx.core.dex.visitors.ssa.SSATransform.process() which calls hidePhiInsns() at the end)
            // phi nodes handle ssa assignments that can have multiple sources due to ssa renaming
            // e.g. r0, renamed r0v1 and r0v2, is assigned values in two different branches, then later r1, renamed r1v0,
            // is assigned r0, then we have r1v0 = phi(r0v1, r0v2) to handle this
            // TODO sometimes (but not always) phi node moves are duplicated in other instructions
            if (!block.contains(AFlag.DONT_GENERATE)) {
                PhiListAttr phiList = block.get(AType.PHI_LIST);
                if (phiList != null) {
                    for (PhiInsn phi : phiList.getList()) {
                        int insnIdx = insnIndex.getAndIncrement();
                        String to = factUtil.registerArgToString(phi.getResult(), mth, seenLocalVars);
                        for (int i = 0; i < phi.getArgsCount(); i++) {
                            String phiStmt = factUtil.phiStmtToString(mth, phi, insnIdx, seenLocalVars);
                            addNewStmt(phiStmt, insnIdx, mth, block, phi);
                            RegisterArg arg = phi.getArg(i);
                            BlockNode fromBlock = phi.getBlockByArgIndex(i);
                            String from = factUtil.registerArgToString(arg, mth, seenLocalVars);
                            // sometimes we'll have the same ssavar (src) from multiple different blocks
                            // this is probably a case where multiple immediate predecessor blocks don't modify a
                            // register and it takes on its previous value, which thus all share the same ssa name
                            souffle.moveWriter.writeFact(phiStmt, to, from);
                            souffle.phiAssignWriter.writeFact(phiStmt, from, blockToString(fromBlock, mth));
                        }
                    }
                }
            }
            int blockIdx = 0;
            for (InsnNode insn : block.getInstructions()) {
                // base instr should be index 0, then increment for each wrapped instruction
                final int finalBlockIdx = blockIdx;
                final Map<InsnNode, InsnContext> inCtxs = new HashMap<>();
                final ArrayList<InsnNode> insnList = new ArrayList<>();
                // this will iterate the parent then, recursively, all the wrapped instructions in its args
                // visit to build up the info on all the instructions then process each one
                insn.visitInsns(in -> { insnList.add(in); });
                Collections.reverse(insnList);
                for (InsnNode subinsn : insnList) {
                    int insnIdx = insnIndex.getAndIncrement();
                    inCtxs.put(subinsn, new InsnContext(seenLocalVars, block, mth, finalBlockIdx, insnIdx, subinsn));
                }

                for (InsnNode subinsn : insnList) {
                    InsnContext inCtx = inCtxs.get(subinsn);
                    // order instruction index from innermost to outermost
                    DebugLogWriter insnWriter;
                    // can't trust DONT_GENERATE flag, not sure why
                    //noinspection PointlessBooleanExpression
                    if (false && (block.contains(AFlag.DONT_GENERATE) || inCtx.insn.contains(AFlag.DONT_GENERATE))) {
                        insnWriter = skippedInsnLogWriter;
                    } else {
                        insnWriter = insnLogWriter;
                        processStmt(inCtx, inCtxs);
                    }
                    String insnString = insnWriter.isDebugEnabled() ? inCtx.insn.toString() : "";
                    synchronized (insnWriter) {
                        insnWriter.println(insnString);
                        insnWriter.println("Source line: " + inCtx.insn.getSourceLine());
                        // always 0
                        insnWriter.println("Def pos: " + inCtx.insn.getDefPosition());
                        insnWriter.println("Attr string: " + inCtx.insn.getAttributesString());
                        insnWriter.println("Index: " + inCtx.insnIdx);
                        // sometimes -1 (not given a value); usually in child instrs, but sometimes in parent also
                        insnWriter.println("Offset: " + inCtx.insn.getOffset());
                        insnWriter.println("Fact Str: " + inCtx.stmtStr);
                        insnWriter.println();
                    }
                }
                blockIdx++;
            }
        }
        LOG.debug("Done vising method: " + methodToString(mth));
        LOG.debug("Took " + ((double)(System.nanoTime() - start) / (1000.0 * 1000.0)) + " ms");
    }

    private void addNewStmt(String stmtStr, int index, MethodNode mth, BlockNode block, InsnNode insn) {
        souffle.stmtInMethodWriter.writeFact(stmtStr, String.valueOf(index), methodToString(mth));
        souffle.stmtSourceLineWriter.writeFact(stmtStr, sourceFileString(mth), stmtSourceLineString(insn), "0", String.valueOf(insn.getOffset()));
        souffle.basicBlockWriter.writeFact(stmtStr, blockToString(block, mth));
    }

    private void handleClassFieldInit(FieldNode field) {
        FieldInitInsnAttr initInsnAttr = field.get(AType.FIELD_INIT_INSN);
        if (initInsnAttr != null) {
            // this means it's assigned the result of an instruction (usually a method call)
            // assigned value seems to always be of type ONE_ARG with one arg (may be wrapped)
            // the intruction is a "hidden" instruction for this method (it's not actually in the method's blocks)
            InsnNode fieldInitInsn = initInsnAttr.getInsn();
            MethodNode mth = initInsnAttr.getInsnMth();
            // build insn context map
            final Map<InsnNode, InsnContext> inCtxs = new HashMap<>();
            final Set<String> seenLocalVars = new HashSet<>();
            AtomicInteger insnIdx = new AtomicInteger();
            fieldInitInsn.visitInsns(in -> {
                inCtxs.put(in, new InsnContext(seenLocalVars, field, mth, in, insnIdx.getAndIncrement()));
            });
            // process main and inner instructions
            for (InsnContext inCtx : InsnContext.reverseSort(inCtxs.values())) {
                if (inCtx.insn == fieldInitInsn) {
                    // special handling for main field init insn
                    // could add this to ONE_ARG handling in processStmt in theory assuming this is always a field init,
                    // but seems like it may cause issues in future jadx versions if it is used for something else
                    InsnArg assignedVal = fieldInitInsn.getArg(0);
                    RegisterArg thisArg = inCtx.mth.getThisArg();
                    if (thisArg == null) {
                        // static field initialization method (<clinit>())
                        souffle.staticPutWriter.writeFact(inCtx.stmtStr, fieldToClassString(field),
                                fieldToFieldString(field), argTypeToString(field.getFieldInfo().getType()), factUtil.insnArgToString(inCtxs, inCtx, assignedVal));
                    }
                    else {
                        // normal field initialization in <init>()
                        String fieldWriteTarget = factUtil.registerArgToString(thisArg, inCtx.mth, inCtx.seenLocalVars);
                        souffle.iPutWriter.writeFact(inCtx.stmtStr, fieldToClassString(field), fieldToFieldString(field), fieldWriteTarget, factUtil.insnArgToString(inCtxs, inCtx, assignedVal));
                        handleInstanceField(field.getFieldInfo());
                    }
                    // unfortunately source line seems to always be undefined... (-1)
                    addNewStmt(inCtx.stmtStr, inCtx.insnIdx, inCtx.mth, inCtx.block, inCtx.insn);
                }
                else {
                    processStmt(inCtx, inCtxs);
                }
            }
        }
        else {
            // field with no init instruction
            EncodedValue constVal = field.get(JadxAttrType.CONSTANT_VALUE);
            ConstVar constVar;
            if (constVal != null && (constVar = factUtil.constValueToConstVar(EncodedValueUtils.convertToConstValue(constVal))) != null) {
                // When an init instruction is missing, we generate one anyway, and put it into the <clinit> method.
                // Because there's no original instruction, the <clinit> method can also be omitted, so we make sure to gin one up
                String constructorStr = fieldToConstructorString(field);
                String classStr = fieldToClassString(field);
                souffle.methodWriter.writeFact(constructorStr, "<clinit>", classStr, "V", "()V", "0");
                // field with no init instruction
                // this path only seems to hit if the field is static (not necessarily final) AND is assigned a constant value
                souffle.fieldConstInitWriter.writeFact(classStr, fieldToFieldString(field),
                        constVar.getVarStr(souffle, constructorStr, stmtToString(field, null, 0)));
            }
            else {
                // if we get here, the field is implicitly assigned its default value (declared with no assignment)
                LOG.debug("Field implicitly initialized to null " + field);
            }
        }
    }

    // returns true if there is code, false otherwise
    private boolean processMethod(MethodNode mth, Set<String> seenLocalVars) {
        // returns are handled in RETURN instructions
        String methodString = methodToString(mth);
        RegisterArg ths = mth.getThisArg();
        String parentClassString = classToString(mth.getMethodInfo().getDeclClass());
        String descriptor = methodToParamListString(mth);
        String mthShortName = methodToNameString(mth);
        if (ths != null) {
            souffle.thisParamWriter.writeFact(methodString, factUtil.registerArgToString(ths, mth, seenLocalVars));
        }
        int i = 0;
        // this is not included in the arg reg list by default, use getAllArgRegs to prepend it (if it exists)
        for (RegisterArg param : mth.getAllArgRegs()) {
            souffle.formalParamWriter.writeFact(methodString, String.valueOf(i), factUtil.registerArgToString(param, mth, seenLocalVars));
            i++;
        }
        ArgType retType = mth.getReturnType();
        if (!retType.isVoid()) {
            String internalName = "@retparameter";
            String paramVar = methodString + "/" + internalName;
            souffle.formalParamWriter.writeFact(methodString, String.valueOf(RET_ARG_INDEX), paramVar);
            souffle.varHasInternalNameWriter.writeFact(paramVar, internalName);
            souffle.varHasTypeWriter.writeFact(paramVar, argTypeToString(retType));
            souffle.varInMethodWriter.writeFact(paramVar, methodString);
        }
        souffle.methodWriter.writeFact(
                methodString,
                mthShortName, parentClassString, argTypeToString(mth.getReturnType()), descriptor, String.valueOf(i));
        // souffle.methodArityWriter.writeFact(methodString, String.valueOf(i));
        if (mth.isNoCode()) {
            return false;
        }
        souffle.methodImplementedWriter.writeFact(parentClassString, mthShortName, descriptor, methodString);
        return true;
    }

    private void handleExternalStaticField(FieldInfo field) {
        // it is on the caller to ensure that this field is used in a static context, otherwise we can't tell
        if (isExternalClass(field.getDeclClass()) && seenExternalFields.add(field)) {
            souffle.staticExternalFieldWriter.writeFact(fieldToClassString(field), fieldToFieldString(field),
                     argTypeToString(field.getType()));
        }
    }

    private void handleInstanceField(FieldInfo field) {
        // it is on the caller to ensure that this field is used in a static context, otherwise we can't tell
        if (seenFields.add(field)) {
            souffle.instanceFieldWriter.writeFact(fieldToClassString(field), fieldToFieldString(field),
                     argTypeToString(field.getType()));
        }
    }

    private void processStmt(InsnContext inCtx, Map<InsnNode, InsnContext> inCtxs) {
        addNewStmt(inCtx.stmtStr, inCtx.insnIdx, inCtx.mth, inCtx.block, inCtx.insn);
        jadx.core.dex.attributes.nodes.BytecodeInfoAttr bc;
        if ((bc = inCtx.insn.get(AType.BYTECODE_INFO)) != null) {
          souffle.bytecodeInsnWriter.writeFact(
            inCtx.stmtStr, bc.getFile(), "BINROOT", String.valueOf(bc.getOffset()),
            String.valueOf(bc.getLength()));
        }

        InsnType insnType = inCtx.insn.getType();
        // see jadx.core.dex.instructions.InsnDecoder.decode
        switch (insnType) {
            case ARITH: {
                // ArithNode
                // ADD,SUB,MUL,DIV,REM("%"),AND("&"),OR("|"),XOR,SHL,SHR,USHR(">>>")
                ArithNode arithNode = (ArithNode) inCtx.insn;
                InsnArg arg1 = arithNode.getArg(0);
                InsnArg arg2 = arithNode.getArg(1);
                String arg1Str = factUtil.insnArgToString(inCtxs, inCtx, arg1);
                String arg2Str =  factUtil.insnArgToString(inCtxs, inCtx, arg2);

                RegisterArg res = arithNode.getResult();
                if (res != null) {
                    // this is a normal x = y OP z instruction
                    String resultString = factUtil.getResultString(inCtx, arg1.getType());
                    souffle.moveWriter.writeFact(inCtx.stmtStr, resultString, arg1Str);
                    souffle.moveWriter.writeFact(inCtx.stmtStr, resultString, arg2Str);
                } else {
                    // if an ArithNode result is null, it means it's a += increment
                    // op on arg 1. This is talked about in ArithOp.oneArgOp. :|
                    if (arithNode.contains(AFlag.ARITH_ONEARG)) {
                        boolean written = false;
                        String resultString = arg1Str;
                        if (arg1.isInsnWrap()) {
                            // this is for the ARITH_ONEARG case where it wraps its
                            // first argument (like why)
                            InsnWrapArg insnWrapArg = (InsnWrapArg) arg1;
                            InsnNode wrapInsn = insnWrapArg.getWrapInsn();
                            // if this is an IGET, generate a move to the field. sigh.
                            if (wrapInsn instanceof IndexInsnNode) {
                                IndexInsnNode indexNode = (IndexInsnNode) wrapInsn;
                                FieldInfo field = (FieldInfo)indexNode.getIndex();
                                if (wrapInsn.getArgsCount() > 0) {
                                  InsnArg to = indexNode.getArg(0);
                                  resultString = factUtil.insnArgToString(inCtxs, inCtx, to);
                                  souffle.iPutWriter.writeFact(inCtx.stmtStr, fieldToClassString(field), fieldToFieldString(field), resultString, arg2Str);
                                  handleInstanceField(field);
                                  written = true;
                                } else {
                                  resultString = factUtil.getResultString(inCtx, field.getType());
                                  souffle.staticGetWriter.writeFact(inCtx.stmtStr, fieldToClassString(field),
                                          fieldToFieldString(field), argTypeToString(field.getType()), resultString);
                                  handleExternalStaticField(field);
                                  written = true;
                                }
                            } else {
                                LOG.error(inCtx.stmtStr + ": ARITH_ONEARG ArithNode unhandled wrapped instruction arg 1: " + wrapInsn.getClass());
                            }
                        }
                        if (!written) {
                          souffle.moveWriter.writeFact(inCtx.stmtStr, resultString, arg2Str);
                        }
                    } else if (!arithNode.contains(AFlag.WRAPPED)) {
                        LOG.error(inCtx.stmtStr + ": Unhandled ARITH");
                    }
                }
                break;
            }

            case NOT:
                // not-int;not-long vA, vB; A=dst, B=src
                // unary NOT (i.e. one's complement: ~a)
                // InsnNode
            case NEG:
                // neg-int;neg-long;neg-float;neg-double vA, vB; A=dst, B=src
                // unary negation (i.e. two's complement: -a)

            case MOVE: {
                // InsnNode move from arg(0) into result
                // InsnNode
                InsnArg from = inCtx.insn.getArg(0);
                souffle.moveWriter.writeFact(inCtx.stmtStr, factUtil.getResultString(inCtx, from.getType()), factUtil.insnArgToString(inCtxs, inCtx, from));
                break;
            }

            case MOVE_MULTI: {
                // fallback only instruction; move many args; iterate over args
                // this is a weird one: see BlockSplitter.expandMoveMulti; each pair of args is a dst, src pair
                // comes from java (do dalvik ops will produce this): dup_x1, dup_x2, dup2, dup2_x1, swap
                // InsnNode
                int mvCount = inCtx.insn.getArgsCount() / 2;
                for (int i = 0; i < mvCount; i++) {
                    int startArg = i * 2;
                    RegisterArg to = (RegisterArg) inCtx.insn.getArg(startArg);
                    InsnArg from = inCtx.insn.getArg(startArg + 1);
                    souffle.moveWriter.writeFact(inCtx.stmtStr, factUtil.registerArgToString(to, inCtx.mth, inCtx.seenLocalVars), factUtil.insnArgToString(inCtxs, inCtx, from));
                }
                break;
            }

            case CHECK_CAST:
                // check-cast vAA, type@BBBB; A=reference reg, B=type index
                // check to see if A can be cast to B, if not, throw ClassCastException
                // IndexInsnNode
            case CAST: {
                // X-to-Y vA, vB; A=dst, B=src
                // cast X to Y e.g. int-to-long: int32 a; long result = (long) a;
                // IndexInsnNode
                // The arg is what is being casted, the type comes from
                IndexInsnNode indexNode = (IndexInsnNode) inCtx.insn;
                InsnArg from = indexNode.getArg(0);
                ArgType toType = (ArgType)indexNode.getIndex();
                souffle.moveWriter.writeFact(inCtx.stmtStr, factUtil.getResultString(inCtx, toType),
                        factUtil.insnArgToString(inCtxs, inCtx, from));
                break;
            }

            case AGET: {
                // aget vAA, vBB, vCC; A=dst reg, B=array reg, C=index reg
                // res = arg0[arg1]
                // InsnNode
                // generate a MOVE from the array to the dst
                InsnArg from = inCtx.insn.getArg(0);
                // arg 1 is the index with type ArgType.NARROW_INTEGRAL
                InsnArg index = inCtx.insn.getArg(1);
                String realIndexString = factUtil.insnArgToString(inCtxs, inCtx, index);
                String toVar = factUtil.getResultString(inCtx, from.getType().getArrayElement());
                String fromVar = factUtil.insnArgToString(inCtxs, inCtx, from);

                String indexString = factUtil.insnArgConstStringElseNull(index);
                if (indexString != null) {
                  realIndexString = indexString;
                }
                souffle.aGetWriter.writeFact(inCtx.stmtStr, realIndexString, toVar, fromVar);
                break;
            }

            case APUT: {
                // aput vAA, vBB, vCC; A=src reg, B=array reg, C=index reg
                // result is Null ?
                // arg0[arg1] = arg2
                // InsnNode
                // generate a MOVE from the src to the array
                InsnArg to = inCtx.insn.getArg(0);
                // arg 1 is the index with type ArgType.NARROW_INTEGRAL
                InsnArg index = inCtx.insn.getArg(1);
                InsnArg from = inCtx.insn.getArg(2);
                String realIndexString = factUtil.insnArgToString(inCtxs, inCtx, index);
                String toVar = factUtil.insnArgToString(inCtxs, inCtx, to);
                String fromVar = factUtil.insnArgToString(inCtxs, inCtx, from);

                String indexString = factUtil.insnArgConstStringElseNull(index);
                if (indexString != null) {
                  realIndexString = indexString;
                }
                souffle.aPutWriter.writeFact(inCtx.stmtStr, realIndexString, toVar, fromVar);
                break;
            }

            case FILLED_NEW_ARRAY: {
                // FilledNewArrayNode (combined NEW_ARRAY and FILL_ARRAY)
                // from codegen, this corresponds to: new <arr_type>{...};
                FilledNewArrayNode filledArr = (FilledNewArrayNode) inCtx.insn;
                // the result is the array allocated in the new instruction
                // and then each arg are all the registers
                // new-array + one or more aput
                // new-array vA, vB, type@CCCC; A=dst reg, B=size reg, C=type index
                // aput vAA, vBB, vCC; A=src reg, B=array reg, C=index reg
                // move from each argument to the array
                String resultStr = factUtil.getResultString(inCtx, filledArr.getArrayType());
                int i = 0;
                for (InsnArg from : filledArr.getArguments()) {
                    String fromStr = factUtil.insnArgToString(inCtxs, inCtx, from);
                    souffle.aPutWriter.writeFact(inCtx.stmtStr, String.valueOf(i), resultStr, fromStr);
                    i++;
                }
                break;
            }

            // IndexInsnNode; FieldInfo field = (FieldInfo)((IndexInsnNode)insn).getIndex();
            // get variants have a result register
            case SGET: {
                // sget vAA, field@BBBB; A=dst reg, B=static field reference index
                IndexInsnNode indexNode = (IndexInsnNode) inCtx.insn;
                FieldInfo from = (FieldInfo)indexNode.getIndex();
                String resultString = factUtil.getResultString(inCtx, from.getType());
                souffle.staticGetWriter.writeFact(inCtx.stmtStr, fieldToClassString(from),
                        fieldToFieldString(from), argTypeToString(from.getType()), resultString);
                handleExternalStaticField(from);
                break;
            }
            case SPUT: {
                // sput vAA, field@BBBB; A=src value reg, B=static field reference index
                IndexInsnNode indexNode = (IndexInsnNode) inCtx.insn;
                FieldInfo to = (FieldInfo)(indexNode).getIndex();
                InsnArg from = indexNode.getArg(0);
                souffle.staticPutWriter.writeFact(inCtx.stmtStr, fieldToClassString(to), fieldToFieldString(to), argTypeToString(to.getType()), factUtil.insnArgToString(inCtxs, inCtx, from));
                handleExternalStaticField(to);
                break;
            }
            case IGET: {
                // iget vA, vB, field@CCCC; A=dst reg, B=object register, C=field reference index
                IndexInsnNode indexNode = (IndexInsnNode) inCtx.insn;
                FieldInfo field = (FieldInfo)indexNode.getIndex();
                InsnArg from = indexNode.getArg(0);
                String resultString = factUtil.getResultString(inCtx, field.getType());
                String fromStr = factUtil.insnArgToString(inCtxs, inCtx, from);
                souffle.iGetWriter.writeFact(inCtx.stmtStr, fieldToClassString(field), fieldToFieldString(field), resultString, fromStr);
                handleInstanceField(field);
                break;
            }
            case IPUT: {
                // iput vA, vB, field@CCCC; A=src value reg, B=object register, C=field reference index
                IndexInsnNode indexNode = (IndexInsnNode) inCtx.insn;
                FieldInfo field = (FieldInfo)indexNode.getIndex();
                InsnArg from = indexNode.getArg(0);
                InsnArg to = indexNode.getArg(1);
                String toStr = factUtil.insnArgToString(inCtxs, inCtx, to);
                souffle.iPutWriter.writeFact(inCtx.stmtStr, fieldToClassString(field), fieldToFieldString(field), factUtil.insnArgToString(inCtxs, inCtx, to), factUtil.insnArgToString(inCtxs, inCtx, from));
                handleInstanceField(field);
                break;
            }

            case STR_CONCAT: {
                // InsnNode
                // generated from e.g. a string build chain to concat a series of strings to one result
                String resultStr = factUtil.getResultString(inCtx, ArgType.STRING);
                // move all the strings to the result (these are not always constant strings)
                Set<String> addedArgs = new HashSet<>();
                for (InsnArg from : inCtx.insn.getArguments()) {
                    String fromStr = factUtil.insnArgToString(inCtxs, inCtx, from);
                    if (addedArgs.add(fromStr)) {
                        souffle.moveWriter.writeFact(inCtx.stmtStr, resultStr, fromStr);
                    }
                }
                break;
            }

            case RETURN: {
                // formal param return in meth
                if (inCtx.insn.getArgsCount() > 0 && !inCtx.mth.isVoidReturn()) {
                    InsnArg retval = inCtx.insn.getArg(0);
                    souffle.methodReturnWriter.writeFact(inCtx.stmtStr,
                            factUtil.insnArgToString(inCtxs, inCtx, retval));
                }
                break;
            }

            case TERNARY: {
                // TernaryInsn
                // Ternary: res = cond ? t : f
                // write both sides to result
                TernaryInsn tisn = (TernaryInsn) inCtx.insn;
                InsnArg then = tisn.getArg(0);
                InsnArg els = tisn.getArg(1);
                IfCondition tCond = tisn.getCondition();
                String resultString;
                // this can be false if e.g. the two sides are two different class types
                if (then.getType().equals(els.getType())) {
                    resultString = factUtil.getResultString(inCtx, then.getType());
                }
                else {
                    // try to coerce to constants
                    ConstVar thenConst = factUtil.insnArgConstVarElseNull(then);
                    ConstVar elsConst = factUtil.insnArgConstVarElseNull(els);
                    if (thenConst != null && elsConst != null && thenConst.type.equals(elsConst.type)) {
                        resultString = factUtil.getResultString(inCtx, thenConst.type);
                    }
                    // otherwise, if they're at least objects, indicate that much
                    else if (then.getType().isObject() && then.getType().isObject()) {
                        resultString = factUtil.getResultString(inCtx, ArgType.OBJECT);
                    }
                    else {
                        // give up and just say unknown
                        resultString = factUtil.getResultString(inCtx, ArgType.UNKNOWN);
                    }
                }

                String ifStr = factUtil.insnArgToString(inCtxs, inCtx, then);
                String elsStr = factUtil.insnArgToString(inCtxs, inCtx, els);
                souffle.moveWriter.writeFact(inCtx.stmtStr, resultString, ifStr);
                if (ifStr != null && !ifStr.equals(elsStr)) {
                    souffle.moveWriter.writeFact(inCtx.stmtStr, resultString, elsStr);
                }
                souffle.ternaryWriter.writeFact(inCtx.stmtStr, conditionToString(tCond),
                        factUtil.insnArgToString(inCtxs, inCtx, then), factUtil.insnArgToString(inCtxs, inCtx, els));

                for (RegisterArg arg : tCond.getRegisterArgs()) {
                    souffle.ternaryArgWriter.writeFact(inCtx.stmtStr, factUtil.registerArgToString(arg, inCtx.mth, inCtx.seenLocalVars));
                }

                break;
            }

            case IF: {
                // IfNode
                // if-test vA=arg1, vB=arg2, +CCCC=targetoffset; branch to dest if compare is true
                // if-testz vAA=arg1, +BBBB=targetoffset; branch to dest if compare is true, taking 2nd arg to be 0
                IfNode ifinsn = (IfNode) inCtx.insn;
                BlockNode thenBlock = ifinsn.getThenBlock();
                BlockNode elseBlock = ifinsn.getElseBlock();
                if (thenBlock != null && elseBlock != null) {
                    souffle.ifWriter.writeFact(inCtx.stmtStr, ifOpToString(ifinsn.getOp()),
                            factUtil.insnArgToString(inCtxs, inCtx, ifinsn.getArg(0)),
                            factUtil.insnArgToString(inCtxs, inCtx, ifinsn.getArg(1)),
                            blockToString(thenBlock, inCtx.mth), blockToString(elseBlock, inCtx.mth));
                }
                // sometimes the targets are null as in e.g. the conditions in ternary instructions
                // in these cases, ignoring writing this out should be fine
                break;
            }

            case SWITCH: {
                // SwitchInsn
                SwitchInsn switchinsn = (SwitchInsn) inCtx.insn;
                souffle.switchWriter.writeFact(inCtx.stmtStr, factUtil.insnArgToString(inCtxs, inCtx, switchinsn.getArg(0)));

                BlockNode[] blocks = switchinsn.getTargetBlocks();
                int[] intKeys = switchinsn.getKeys();
                for (int i = 0; i < blocks.length; i++) {
                    BlockNode switch_block = blocks[i];
                    // jadx determines if the key is a class field const, if so, switchinsn.getKey(i) has type FieldNode
                    // for now just write out the int though
                    souffle.switchTargetWriter.writeFact(inCtx.stmtStr, String.valueOf(intKeys[i]),
                            blockToString(switch_block, inCtx.mth));
                }
                break;
            }

            case ARRAY_LENGTH: {
                // InsnNode
                // array-length vA, vB; A=dst, B=array reference reg; store number of entries in array in dst
                InsnArg from = inCtx.insn.getArg(0);

                souffle.moveWriter.writeFact(inCtx.stmtStr, factUtil.getResultString(inCtx, ArgType.INT), factUtil.insnArgToString(inCtxs, inCtx, from));
                break;
            }

            case NEW_ARRAY: {
                // NewArrayNode; new-array opcodes (new-array vA, vB, type@CCCC; A=dst, B=size, C=array type index)
                NewArrayNode newArrayNode = (NewArrayNode) inCtx.insn;
                ArgType arrType = newArrayNode.getArrayType();

                souffle.typeInstanceWriter.writeFact(inCtx.stmtStr, factUtil.getResultString(inCtx, arrType),
                        argTypeToString(arrType));
                break;
            }
            case NEW_INSTANCE: {
                // IndexInsnNode: only used in fallback mode; comes from CONSTRUCTOR with CallType CONSTRUCTOR
                IndexInsnNode indexInsnNode = (IndexInsnNode) inCtx.insn;
                ArgType clsType = (ArgType)indexInsnNode.getIndex();

                souffle.typeInstanceWriter.writeFact(inCtx.stmtStr, factUtil.getResultString(inCtx, clsType),
                        argTypeToString(clsType));
                break;
            }
            case CONSTRUCTOR: {
                // constructor call, subtype of BaseInvokeNode (ConstructorInsn); add special handling here if desired
                // note this one will ALSO be handled by processInvoke
                ConstructorInsn constructorNode = (ConstructorInsn) inCtx.insn;
                ArgType clsType = constructorNode.getClassType().getType();

                souffle.typeInstanceWriter.writeFact(inCtx.stmtStr, factUtil.getResultString(inCtx, clsType),
                        argTypeToString(clsType));
                break;
            }

            case PHI: {
                // PhiInsn
                // contains a list of args (registers) that each share an index with a block in blockBinds
                // if pred block is that block, the reg from that block gets assigned to the result by the phi node
                // this should only ever be hit in fallback mode; these nodes get deleted in the ssa visitor
                // in fallback mode, can just ignore anyway since these are handled in the method visitor
                break;
            }

            case ONE_ARG: {
                // an argument wrapped as an instruction with one arg; used as field initializtion attr to contain val
                // the arg does not need to be a constant and can be a wrapped instruction (method invocation?)
                // notably the result does not actually refer to the assigned field
                LOG.error("Unhandled ONE_ARG insn");
                break;
            }

            // I believe both of these are just loaded with literals (constants)
            // See FILL_ARRAY in jadx.core.codegen.InsnGen which gives "arr = {...}" i.e. an array initializer
            case FILL_ARRAY: {
                // filled-new-array - construct array and fill with contents (passed regs)
                // FillArrayInsn
                FillArrayInsn arrayNode = (FillArrayInsn)inCtx.insn;
                InsnArg arrArg = arrayNode.getArg(0);
                ArgType arrayType = arrArg.getType();
                String toVar = factUtil.insnArgToString(inCtxs, inCtx, arrArg);
                ArgType elemType;
                if (arrayType.isTypeKnown() && arrayType.isArray()) {
                    elemType = arrayType.getArrayElement();
                } else {
                    ArgType elementType = arrayNode.getElementType(); // unknown type
                    elemType = elementType.selectFirst();
                }
                int i = 0;
                for (LiteralArg arg : arrayNode.getLiteralArgs(elemType)) {
                    // move each arg into the array; also emit an APUT
                    String fromVar = factUtil.insnArgToString(inCtxs, inCtx, arg);
                    souffle.aPutWriter.writeFact(inCtx.stmtStr, String.valueOf(i), toVar, fromVar);
                    i++;
                }
                break;
            }
            case FILL_ARRAY_DATA: {
                // fill-array-data - fill array with given data (array of primitives in table data pseudo-instr offset)
                // fill-array-data-payload contains data
                // FillArrayData
                // can call getResult()(?) and getLiteralArgs() ?
                // This is fallback, so ignore for now
                LOG.error("FILL_ARRAY_DATA unhandled!");
                break;
            }

            case CONST: {
                // const/const-wide vAA, #+BBBBBBBB; A: dst, B: literal
                // InsnNode
                ConstVar constVar = factUtil.constVarFromConstNode(inCtx.insn);
                // don't want to add a move if it doesn't write to an actual result (e.g. wrap arg)
                if (inCtx.insn.getResult() != null) {
                    souffle.moveWriter.writeFact(inCtx.stmtStr, factUtil.getResultString(inCtx, constVar.type),
                            constVar.getVarStr(souffle, inCtx));
                }
                break;
            }
            case CONST_STR: {
                // I believe these are for immutable string constants that appear in the code
                // const-string vAA, string@BBBB; A: dst, B: string index
                // ConstStringNode
                ConstVar constVar = factUtil.constVarFromConstStringNode((ConstStringNode)inCtx.insn);
                // don't want to add a move if we already handled it as a const insn wrap arg
                if (inCtx.insn.getResult() != null) {
                    souffle.moveWriter.writeFact(inCtx.stmtStr, factUtil.getResultString(inCtx, constVar.type),
                            constVar.getVarStr(souffle, inCtx));
                }
                break;
            }
            case CONST_CLASS: {
                // Seems to be for e.g. "Object.class / object.getClass()"
                // const-class vAA, type@BBBB; A: dst, B: type pool index
                // ConstClassNode
                ConstVar constVar = factUtil.constVarFromConstClassNode((ConstClassNode)inCtx.insn);
                // don't want to add a move if we already handled it as a const insn wrap arg
                if (inCtx.insn.getResult() != null) {
                    souffle.moveWriter.writeFact(inCtx.stmtStr, factUtil.getResultString(inCtx, constVar.type),
                            constVar.getVarStr(souffle, inCtx));
                }
                break;
            }

            case MOVE_RESULT:
                // move return value into a result register after a call (these are merged into invoke instrs)
                // see jadx.core.dex.visitors.ProcessInstructionsVisitor.mergeMoveResult
                LOG.error("Unexpected MOVE_RESULT: " + inCtx.insn);
                break;

            // ignored instructions
            case CMP_L:
                // cmp-long;cmpl-float;cmpl-double vAA, vBB, vCC; A=dst, B=first src, C=second src
                // 0 if B == C; 1 if B > C; -1 if B < C
                // for float and double, if either is NaN, return -1
                // InsnNode
            case CMP_G:
                // cmpg-float;cmpg-double vAA, vBB, vCC; A=dst, B=first src, C=second src
                // 0 if B == C; 1 if B > C; -1 if B < C
                // for float and double, if either is NaN, return 1
                // InsnNode
            case INSTANCE_OF:
                // instance-of vA, vB, type@CCCC; A=dst, B=reference register, C=type index
                // 1 (true) if B is an instance of C or 0 (false) if not
                // IndexInsnNode
            case THROW:
                // throw vAA; A=exception to throw
                // InsnNode
            case MOVE_EXCEPTION:
                // move-exception vAA; A=dst
                // save just caught exception (must be first instruction of handler) to register A
            case GOTO:
                // GotoNode
            case SWITCH_DATA:
                // SwitchData
                // data attached to SwitchInsn node
            case MONITOR_ENTER:
                // InsnNode, monitor-enter vAA; A=reference-bearing register
                // get monitor (mutex?) for object;
            case MONITOR_EXIT:
                // InsnNode, monitor-exit vAA; A=reference-bearing register
                // release monitor (mutex?) for object;
            case INVOKE:
                // of type BaseInvokeNode; handled in processInvoke
            case BREAK:
            case CONTINUE:
            case REGION_ARG:
                // fake insn to keep arguments which will be used in regions codegen
            case NOP:
                break;
            default:
                // may get here if they add new instruction types, so don't want this to be a error
                LOG.error("Unhandled instruction type" + insnType);
                break;
        }

        if (inCtx.insn instanceof BaseInvokeNode) {
            processInvoke(inCtxs, inCtx);
        }
    }

    private void processInvoke(Map<InsnNode, InsnContext> inCtxs, InsnContext inCtx) {
        // The offset is an instruction offset into function
        BaseInvokeNode invokeInsn = (BaseInvokeNode) inCtx.insn;
        MethodInfo callMth = invokeInsn.getCallMth();
        String callMthString = methodToString(callMth);
        String callMthName = methodToNameString(callMth);
        String callMthParamString = methodToParamListString(callMth);
        // for externals, record arity, formals, types, signature
        if (isExternalMethod(callMth) && seenExternalMethods.add(callMth)) {
            int i = 0;
            souffle.externalMethodWriter.writeFact(callMthString);
            InsnArg ths = invokeInsn.getInstanceArg();
            if (ths != null) {
                // getArguments param 0 is this
                String internalName = "@this";
                String thsVar = callMthString + "/" + internalName;
                souffle.thisParamWriter.writeFact(callMthString, thsVar);
                souffle.formalParamWriter.writeFact(callMthString, String.valueOf(i), thsVar);
                souffle.varHasInternalNameWriter.writeFact(thsVar, internalName);
                souffle.varHasTypeWriter.writeFact(thsVar, argTypeToString(callMth.getDeclClass().getType()));
                souffle.varInMethodWriter.writeFact(thsVar, callMthString);
                i++;
            }
            // want to make sure the types are consistent, so get types from call mth not from call site
            for (ArgType argType : callMth.getArgumentsTypes()) {
                String internalName = "@parameter" + i;
                String paramVar = callMthString + "/" + internalName;
                souffle.formalParamWriter.writeFact(callMthString, String.valueOf(i), paramVar);
                souffle.varHasInternalNameWriter.writeFact(paramVar, internalName);
                souffle.varHasTypeWriter.writeFact(paramVar, argTypeToString(argType));
                souffle.varInMethodWriter.writeFact(paramVar, callMthString);
                i++;
            }
            ArgType retType = callMth.getReturnType();
            if (invokeInsn.getResult() != null || !retType.isVoid()) {
                String internalName = "@retparameter";
                String paramVar = callMthString + "/" + internalName;
                souffle.formalParamWriter.writeFact(callMthString, String.valueOf(RET_ARG_INDEX), paramVar);
                souffle.varHasInternalNameWriter.writeFact(paramVar, internalName);
                souffle.varHasTypeWriter.writeFact(paramVar, argTypeToString(retType));
                souffle.varInMethodWriter.writeFact(paramVar, callMthString);
            }
            souffle.methodWriter.writeFact(
                    callMthString,
                    callMthName,
                    classToString(callMth.getDeclClass()),
                    argTypeToString(callMth.getReturnType()),
                    callMthParamString,
                    String.valueOf(i));
        }

        String invokeType = "unknown";
        if (invokeInsn instanceof InvokeNode) {
          invokeType = ((InvokeNode) invokeInsn).getInvokeType().toString();
        } else if (invokeInsn instanceof ConstructorInsn) {
          invokeType = ((ConstructorInsn) invokeInsn).getCallType().toString();
        }

          souffle.invocationWriter.writeFact(
                  inCtx.stmtStr, callMthString, String.valueOf(isResolvedCall(invokeInsn)),
                  classToString(callMth.getDeclClass()), callMthName, callMthParamString, invokeType);

        if (callMth.isConstructor() && !isExternalMethod(callMth)) {
            int i = 0;
            if (invokeInsn.getResult() != null) {
                // constructor with result, use result as arg 0
                RegisterArg arg0 = invokeInsn.getResult();
                    souffle.actualParamWriter.writeFact(inCtx.stmtStr, String.valueOf(0),
                            factUtil.registerArgToString(arg0, inCtx.mth, inCtx.seenLocalVars));
                i = 1;
            } else if (inCtx.mth.isConstructor()) {
                // call like this(...) or super(...) from one constructor to another
                ConstructorInsn constructor = (ConstructorInsn) invokeInsn;
                if (constructor.isSuper() || constructor.isSelf() || constructor.isThis()) {
                    RegisterArg ths = inCtx.mth.getThisArg();
                    souffle.actualParamWriter.writeFact(inCtx.stmtStr, String.valueOf(0),
                            factUtil.registerArgToString(ths, inCtx.mth, inCtx.seenLocalVars));
                    i = 1;
                }
            }
            for (InsnArg arg : invokeInsn.getArguments()) {
                souffle.actualParamWriter.writeFact(inCtx.stmtStr, String.valueOf(i),
                        factUtil.insnArgToString(inCtxs, inCtx, arg));
                i++;
            }
        } else {
            int argsCount = invokeInsn.getArgsCount();
            for (int i = 0; i < argsCount; i++) {
                souffle.actualParamWriter.writeFact(inCtx.stmtStr, String.valueOf(i),
                        factUtil.insnArgToString(inCtxs, inCtx, invokeInsn.getArg(i)));
            }
            // now write out return if there is one
            RegisterArg res = invokeInsn.getResult();
            if (res != null) {
                souffle.invocationReturnWriter.writeFact(inCtx.stmtStr,
                        factUtil.registerArgToString(res, inCtx.mth, inCtx.seenLocalVars));
            } else if (!callMth.getReturnType().isVoid() && invokeInsn.contains(AFlag.WRAPPED)) {
                // sometimes this happens. don't know why.
                // so the call needs a invocationReturn to bind the return value to the call
                souffle.invocationReturnWriter.writeFact(inCtx.stmtStr,
                        factUtil.getResultString(inCtx, callMth.getReturnType()));
            }
        }
    }
}
