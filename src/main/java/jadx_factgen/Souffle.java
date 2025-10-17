package jadx_factgen;

import jadx.core.dex.instructions.args.ArgType;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

import static jadx_factgen.Util.argTypeToString;
import static jadx_factgen.Util.getFileWriter;

class Souffle {
    // there are a few other places the code would have to be changed if this is modified
    public static final String delimiter = "\t";
    public static final int THIS_ARG_INDEX = 0;
    public static final int RET_ARG_INDEX = -1;
    public static final int UNDEFINED_SOURCE_LINE = -1;
    private static final String PREDICATES_FILE = "jadx-import-predicates.dl";
    private static Souffle instance = null;
    private final List<SouffleFactFile> factsWriters = new ArrayList<>(20);

    public final SouffleFactFile langWriter;
    public final SouffleFactFile actualParamWriter;
    public final SouffleFactFile formalParamWriter;
    public final SouffleFactFile thisParamWriter;
    public final SouffleFactFile methodReturnWriter;
    public final SouffleFactFile methodWriter;
    public final SouffleFactFile invocationWriter;
    public final SouffleFactFile invocationReturnWriter;
    public final SouffleFactFile externalMethodWriter;
    public final SouffleFactFile moveWriter;
    public final SouffleFactFile stmtInMethodWriter;
    public final SouffleFactFile stmtSourceLineWriter;
    public final SouffleFactFile classFileNameWriter;
    public final SouffleFactFile varInMethodWriter;
    public final SouffleFactFile varHasTypeWriter;
    public final SouffleFactFile varHasInternalNameWriter;
    public final SouffleFactFile varHasNameWriter;
    public final SouffleFactFile instanceFieldWriter;
    public final SouffleFactFile iPutWriter;
    public final SouffleFactFile iGetWriter;
    public final SouffleFactFile staticPutWriter;
    public final SouffleFactFile staticExternalFieldWriter;
    public final SouffleFactFile staticGetWriter;
    public final SouffleFactFile basicBlockWriter;
    public final SouffleFactFile ifWriter;
    public final SouffleFactFile ternaryWriter;
    public final SouffleFactFile ternaryArgWriter;
    public final SouffleFactFile switchWriter;
    public final SouffleFactFile switchTargetWriter;
    public final SouffleFactFile typeInstanceWriter;
    public final SouffleFactFile aGetWriter;
    public final SouffleFactFile aPutWriter;
    public final SouffleFactFile phiAssignWriter;
    public final SouffleFactFile varHasConstValueWriter;
    public final SouffleFactFile fieldIsFinalWriter;
    public final SouffleFactFile fieldConstInitWriter;
    public final SouffleFactFile directSuperclassWriter;
    public final SouffleFactFile superInterfaceWriter;
    public final SouffleFactFile classHasNameWriter;
    public final SouffleFactFile classDefinedInWriter;
    public final SouffleFactFile interfaceTypeWriter;
    public final SouffleFactFile methodImplementedWriter;
    public final SouffleFactFile manifestRootWriter;
    public final SouffleFactFile manifestNodeWriter;
    public final SouffleFactFile manifestNodeChildWriter;
    public final SouffleFactFile manifestNodeAttrWriter;
    public final SouffleFactFile topParentClassWriter;
    public final SouffleFactFile bytecodeInsnWriter;

    public class SouffleFactFile {
        public PrintWriter writer;
        public String fileName;
        public SouffleAttr[] attrs;

        public SouffleFactFile(String baseName, SouffleAttr... attrs) throws IOException {
            this.writer = getFileWriter(baseName);
            this.fileName = baseName;
            this.attrs = attrs;
            factsWriters.add(this);
        }

        public synchronized void writeFact(String... facts) {
            // This MUST be thread-safe.
            // Visits can happen in parallel and if writes occur simultaneously the results can get messed up.
            if (facts.length != attrs.length) {
                throw new RuntimeException(String.format("Facts file %s facts length of %s does not match attrs %s",
                        fileName, Arrays.toString(facts), Arrays.toString(attrs)));
            }
            if (Arrays.asList(facts).contains(null)) {
                // sometimes the string conversion functions will return null to indicate not to write anything
                return;
            }

            for (int i = 0; i < facts.length; i++) {
                String fact = facts[i];
                SouffleAttr attr = attrs[i];
                attr.type.checkType(fact);
                if (fact.contains(delimiter)) {
                    throw new RuntimeException("Can't write fact tuple element containing delimiter");
                }
                writer.print(fact);
                // don't want to write the delimiter on the last element
                if (i != facts.length - 1) {
                    writer.print(delimiter);
                }
            }
            writer.println();
        }
    }

    @SuppressWarnings("unused")
    public enum SouffleType {
        SYMBOL, // string
        NUMBER, // signed 32-bit or 64-bit int
        UNSIGNED, // unsigned 32-bit or 64-bit int
        FLOAT; // IEEE 754 either 32-bit or 64-bit

        @Override
        public String toString() {
            return name().toLowerCase();
        }

        @SuppressWarnings("ResultOfMethodCallIgnored")
        public void checkType(String attr) throws NumberFormatException {
            switch (this) {
                case SYMBOL:
                    // always valid
                    break;
                case NUMBER:
                    Long.parseLong(attr);
                    break;
                case UNSIGNED:
                    Long.parseUnsignedLong(attr);
                    break;
                case FLOAT:
                    Double.parseDouble(attr);
                    break;
                default:
                    throw new RuntimeException("SouffleType: Unhandled type");
            }
        }
    }

    public List<SouffleFactFile> getFactsWriters() {
        return factsWriters;
    }

    public static synchronized Souffle getSouffle() throws IOException {
        if (instance == null) {
            instance = new Souffle();
        }
        return instance;
    }

    public static class SouffleAttr {
        public final SouffleType type;
        public final String name;
        public SouffleAttr(SouffleType type, String name) {
            this.type = type;
            this.name = name;
            if (isInvalidSouffleName(name)) {
                throw new RuntimeException("Invalid souffle attr " + name);
            }
        }
    }

    private Souffle() throws IOException {
        langWriter = new SouffleFactFile("CTADLLanguage.facts",
                new SouffleAttr(SouffleType.SYMBOL, "language"));
        actualParamWriter = new SouffleFactFile("ActualParam.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.NUMBER, "argN"),
                new SouffleAttr(SouffleType.SYMBOL, "arg"));
        formalParamWriter = new SouffleFactFile("FormalParam.facts",
                new SouffleAttr(SouffleType.SYMBOL, "method"),
                new SouffleAttr(SouffleType.NUMBER, "argN"),
                new SouffleAttr(SouffleType.SYMBOL, "formal"));
        thisParamWriter = new SouffleFactFile("ThisParam.facts",
                new SouffleAttr(SouffleType.SYMBOL, "method"),
                new SouffleAttr(SouffleType.SYMBOL, "this"));
        methodReturnWriter = new SouffleFactFile("ReturnStmt.facts",
                new SouffleAttr(SouffleType.SYMBOL, "retStmt"),
                new SouffleAttr(SouffleType.SYMBOL, "retVar"));
        methodWriter = new SouffleFactFile("Method.facts",
                new SouffleAttr(SouffleType.SYMBOL, "method"),
                new SouffleAttr(SouffleType.SYMBOL, "simpleName"),
                new SouffleAttr(SouffleType.SYMBOL, "declClass"),
                new SouffleAttr(SouffleType.SYMBOL, "returnType"),
                new SouffleAttr(SouffleType.SYMBOL, "descriptor"),
                new SouffleAttr(SouffleType.NUMBER, "arity"));
        invocationWriter = new SouffleFactFile("MethodInvocation.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "callee"),
                new SouffleAttr(SouffleType.SYMBOL, "isResolved"),
                new SouffleAttr(SouffleType.SYMBOL, "declClass"),
                new SouffleAttr(SouffleType.SYMBOL, "simpleName"),
                new SouffleAttr(SouffleType.SYMBOL, "descriptor"),
                new SouffleAttr(SouffleType.SYMBOL, "invokeType"));
        invocationReturnWriter = new SouffleFactFile("MethodInvocationReturn.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "retVar"));
        externalMethodWriter = new SouffleFactFile("ExternalMethod.facts",
                new SouffleAttr(SouffleType.SYMBOL, "externalMethod"));
        moveWriter = new SouffleFactFile("Move.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "to"),
                new SouffleAttr(SouffleType.SYMBOL, "from"));
        stmtInMethodWriter = new SouffleFactFile("StmtInMethod.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.NUMBER, "index"),
                new SouffleAttr(SouffleType.SYMBOL, "method"));
        stmtSourceLineWriter = new SouffleFactFile("StmtSourceLine.facts",
						   new SouffleAttr(SouffleType.SYMBOL, "stmt"),
						   new SouffleAttr(SouffleType.SYMBOL, "file"),
						   new SouffleAttr(SouffleType.NUMBER, "line"),
						   new SouffleAttr(SouffleType.NUMBER, "column"),
						   new SouffleAttr(SouffleType.NUMBER, "offset"));
        classFileNameWriter = new SouffleFactFile("ClassFileName.facts",
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "fileName"));
        varInMethodWriter = new SouffleFactFile("VarInMethod.facts",
                new SouffleAttr(SouffleType.SYMBOL, "localVar"),
                new SouffleAttr(SouffleType.SYMBOL, "method"));
        // TODO this output is based on stmt processing order, making this non-deterministic (different ways at
        //  getting at the same var from different stmts seems to give slightly different types in some cases)
        varHasTypeWriter = new SouffleFactFile("VarHasType.facts",
                new SouffleAttr(SouffleType.SYMBOL, "var"),
                new SouffleAttr(SouffleType.SYMBOL, "type"));
        varHasInternalNameWriter = new SouffleFactFile("VarHasInternalName.facts",
                new SouffleAttr(SouffleType.SYMBOL, "var"),
                new SouffleAttr(SouffleType.SYMBOL, "name"));
        varHasNameWriter = new SouffleFactFile("VarHasName.facts",
                new SouffleAttr(SouffleType.SYMBOL, "var"),
                new SouffleAttr(SouffleType.SYMBOL, "name"));
        staticGetWriter = new SouffleFactFile("StaticGet.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "field"),
                new SouffleAttr(SouffleType.SYMBOL, "fieldType"),
                new SouffleAttr(SouffleType.SYMBOL, "toVar"));
        staticPutWriter = new SouffleFactFile("StaticPut.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "field"),
                new SouffleAttr(SouffleType.SYMBOL, "fieldType"),
                new SouffleAttr(SouffleType.SYMBOL, "fromVar"));
        staticExternalFieldWriter = new SouffleFactFile("StaticExternalField.facts",
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "field"),
                new SouffleAttr(SouffleType.SYMBOL, "type"));
        instanceFieldWriter = new SouffleFactFile("InstanceField.facts",
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "field"),
                new SouffleAttr(SouffleType.SYMBOL, "type"));
        iPutWriter = new SouffleFactFile("IPut.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "field"),
                new SouffleAttr(SouffleType.SYMBOL, "toVar"),
                new SouffleAttr(SouffleType.SYMBOL, "fromVar"));
        iGetWriter = new SouffleFactFile("IGet.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "field"),
                new SouffleAttr(SouffleType.SYMBOL, "toVar"),
                new SouffleAttr(SouffleType.SYMBOL, "fromVar"));
        basicBlockWriter = new SouffleFactFile("StmtInBasicblock.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "basic_block"));
        ifWriter = new SouffleFactFile("IfStmt.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "ifop"),
                new SouffleAttr(SouffleType.SYMBOL, "arg1"),
                new SouffleAttr(SouffleType.SYMBOL, "arg2"),
                new SouffleAttr(SouffleType.SYMBOL, "if_block"),
                new SouffleAttr(SouffleType.SYMBOL, "else_block"));
        ternaryWriter = new SouffleFactFile("TernaryStmt.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "ternary_cond"),
                new SouffleAttr(SouffleType.SYMBOL, "if_var"),
                new SouffleAttr(SouffleType.SYMBOL, "else_var"));
        ternaryArgWriter = new SouffleFactFile("TernaryArg.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "ternary_arg"));
        switchWriter = new SouffleFactFile("SwitchStmt.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "arg"));
        switchTargetWriter = new SouffleFactFile("SwitchTarget.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.NUMBER, "key"),
                new SouffleAttr(SouffleType.SYMBOL, "target_block"));
        typeInstanceWriter = new SouffleFactFile("TypeInstance.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "var"),
                new SouffleAttr(SouffleType.SYMBOL, "type"));
        aGetWriter = new SouffleFactFile("AGet.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "index"),
                new SouffleAttr(SouffleType.SYMBOL, "toVar"),
                new SouffleAttr(SouffleType.SYMBOL, "fromVar"));
        aPutWriter = new SouffleFactFile("APut.facts",
                new SouffleAttr(SouffleType.SYMBOL, "stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "index"),
                new SouffleAttr(SouffleType.SYMBOL, "toVar"),
                new SouffleAttr(SouffleType.SYMBOL, "fromVar"));
        phiAssignWriter = new SouffleFactFile("PhiAssign.facts",
                new SouffleAttr(SouffleType.SYMBOL, "phi_stmt"),
                new SouffleAttr(SouffleType.SYMBOL, "ssa_from_var"),
                new SouffleAttr(SouffleType.SYMBOL, "src_block"));
        varHasConstValueWriter = new SouffleFactFile("VarIsConst.facts",
                new SouffleAttr(SouffleType.SYMBOL, "constVar"),
                new SouffleAttr(SouffleType.SYMBOL, "constValue"));
        fieldIsFinalWriter = new SouffleFactFile("FieldIsFinal.facts",
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "field"));
        fieldConstInitWriter = new SouffleFactFile("FieldConstInit.facts",
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "field"),
                new SouffleAttr(SouffleType.SYMBOL, "constVar"));
        directSuperclassWriter = new SouffleFactFile("DirectSuperclass.facts",
                new SouffleAttr(SouffleType.SYMBOL, "superclassType"),
                new SouffleAttr(SouffleType.SYMBOL, "subclassType"));
        superInterfaceWriter = new SouffleFactFile("SuperInterface.facts",
                new SouffleAttr(SouffleType.SYMBOL, "superinterfaceType"),
                new SouffleAttr(SouffleType.SYMBOL, "subclassType"));
        classHasNameWriter = new SouffleFactFile("ClassHasName.facts",
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "className"));
        classDefinedInWriter = new SouffleFactFile("ClassDefinedIn.facts",
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "fileName"),
                new SouffleAttr(SouffleType.NUMBER, "fileLine"));
        interfaceTypeWriter = new SouffleFactFile("InterfaceType.facts",
                new SouffleAttr(SouffleType.SYMBOL, "interfaceType"));
        methodImplementedWriter = new SouffleFactFile("MethodImplemented.facts",
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "mthShortName"),
                new SouffleAttr(SouffleType.SYMBOL, "paramList"),
                new SouffleAttr(SouffleType.SYMBOL, "mth"));
        manifestRootWriter = new SouffleFactFile("ManifestRoot.facts",
                new SouffleAttr(SouffleType.SYMBOL, "nodeId"));
        manifestNodeWriter = new SouffleFactFile("ManifestNode.facts",
                new SouffleAttr(SouffleType.SYMBOL, "nodeId"),
                new SouffleAttr(SouffleType.SYMBOL, "nodeName"));
        manifestNodeChildWriter = new SouffleFactFile("ManifestNodeChild.facts",
                new SouffleAttr(SouffleType.SYMBOL, "nodeId"),
                new SouffleAttr(SouffleType.SYMBOL, "childId"));
        manifestNodeAttrWriter = new SouffleFactFile("ManifestNodeAttr.facts",
                new SouffleAttr(SouffleType.SYMBOL, "nodeId"),
                new SouffleAttr(SouffleType.SYMBOL, "key"),
                new SouffleAttr(SouffleType.SYMBOL, "value"));
        topParentClassWriter = new SouffleFactFile("TopParentClass.facts",
                new SouffleAttr(SouffleType.SYMBOL, "class"),
                new SouffleAttr(SouffleType.SYMBOL, "topParentClass"));
        bytecodeInsnWriter = new SouffleFactFile("InsnBytecodeLocation.facts",
 	        new SouffleAttr(SouffleType.SYMBOL, "insn"),
 	        new SouffleAttr(SouffleType.SYMBOL, "artifactUri"),
 	        new SouffleAttr(SouffleType.SYMBOL, "artifactUriBaseId"),
 	        new SouffleAttr(SouffleType.NUMBER, "byteOffset"),
 	        new SouffleAttr(SouffleType.NUMBER, "byteLength"));

        writePredicatesFile();
    }

    private static boolean isInvalidSouffleName(String ident) {
        // lower and upercase characters, numbers, ?, _, begins with a letter, underscore, or question mark
        return ident == null || ident.isEmpty() || !ident.substring(0, 1).matches("[A-Za-z_?]") || !ident.matches("[A-Za-z0-9_?]+");
    }

    private void writePredicatesFile() throws IOException {
        langWriter.writeFact("JADX");
        PrintWriter predicatesWriter = getFileWriter(PREDICATES_FILE);
        // add some #defines for constants first
        predicatesWriter.println("#define JADX_THIS_ARG_INDEX (" + THIS_ARG_INDEX + ")");
        predicatesWriter.println("#define JADX_RET_ARG_INDEX (" + RET_ARG_INDEX + ")");
        predicatesWriter.println("#define JADX_UNDEFINED_SOURCE_LINE (" + UNDEFINED_SOURCE_LINE + ")");
        predicatesWriter.println("#define JADX_STRING_TYPE " + "\"" + argTypeToString(ArgType.STRING) + "\"");
        predicatesWriter.println("#define JADX_TRUE \"" + true + "\"");
        predicatesWriter.println("#define JADX_FALSE \"" + false + "\"");
        predicatesWriter.println();
        for (SouffleFactFile factFile : factsWriters) {
            String fileName = factFile.fileName;
            // currently assumes name starts with non-number and doesn't contain whitespace or other invalid characters
            // strip file extension
            String factName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            if (isInvalidSouffleName(factName)) {
                throw new RuntimeException("Invalid souffle relation " + factName);
            }
            StringJoiner types = new StringJoiner(", ");
            for (SouffleAttr attr : factFile.attrs) {
                types.add(String.format("%s:%s", attr.name, attr.type));
            }
            predicatesWriter.println(String.format(".decl _%s(%s)", factName, types));
        }
        for (SouffleFactFile factFile : factsWriters) {
            String fileName = factFile.fileName;
            String factName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            predicatesWriter.println(String.format(".input _%s(IO=\"file\", delimiter=\"%s\", filename=\"%s\")",
                    factName, delimiter.replace("\t", "\\t"), fileName));
        }
        predicatesWriter.close();
    }
}
