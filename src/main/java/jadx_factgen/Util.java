package jadx_factgen;

import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.FieldInitInsnAttr;
import jadx.core.dex.info.ClassInfo;
import jadx.core.dex.info.FieldInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.BaseInvokeNode;
import jadx.core.dex.instructions.IfOp;
import jadx.core.dex.instructions.InvokeNode;
import jadx.core.dex.instructions.InvokeType;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.mods.ConstructorInsn;
import jadx.core.dex.nodes.*;
import jadx.core.dex.regions.conditions.IfCondition;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static jadx.core.dex.visitors.SaveCode.getFileExtension;
import static jadx_factgen.Souffle.UNDEFINED_SOURCE_LINE;

public class Util {
    public static final String DEFAULT_CONSTRUCTOR_SUFFIX = ".<init>():void";
    public static final String DEFAULT_STATIC_CONSTRUCTOR_SUFFIX = ".<clinit>():void";
    private static final Logger LOG = LoggerFactory.getLogger(Util.class);

    public static String escapeTabsAndNewlines(String str) {
        // prepend a backslash to any literal backslash + t to remove ambiguity
        str = str.replace("\\t", "\\\\t");
        // now escape tabs
        str = str.replace("\t", "\\t");

        str = str.replace("\\n", "\\\\n");
        str = str.replace("\n", "\\n");

        str = str.replace("\\r", "\\\\r");
        str = str.replace("\r", "\\r");
        return str;
    }

    @SuppressWarnings("unused")
    public static boolean insnInMth(InsnNode insnToFind, MethodNode mth) {
        // mth.getInstructions() is usually (always?) null, so use this method to get all the instructions instead
        List<BlockNode> blocks = mth.getBasicBlocks();
        if (blocks != null) {
            AtomicBoolean found = new AtomicBoolean(false);
            for (BlockNode block : blocks) {
                List<InsnNode> insns = block.getInstructions();
                for (InsnNode insn : insns) {
                    insn.visitInsns(in -> {
                        if (in.equals(insnToFind)) {
                            found.set(true);
                        }
                    });
                    if (found.get()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isResolvedCall(BaseInvokeNode insn) {
        if (insn instanceof InvokeNode) {
            InvokeNode invokeNode = (InvokeNode)insn;
            InvokeType invokeType = invokeNode.getInvokeType();
            switch (invokeType) {
                case STATIC:
                case SUPER:
                case DIRECT:
                    return true;
                case VIRTUAL:
                case INTERFACE:
                case POLYMORPHIC:
                    return false;
                // TODO for lambdas (jadx.core.dex.instructions.invokedynamic.CustomLambdaCall.buildMethodCall); how to handle?
                case CUSTOM:
                    // InvokeCustomNode instance
                    LOG.error("TODO: need to handle InvokeCustomNode: " + insn);
                    return false;
                default:
                    LOG.error("Unhandled invoke type: " + invokeType);
                    return false;
            }
        }
        else if (insn instanceof ConstructorInsn) {
            // consctructors can't be virtual
            return true;
        }
        else {
            LOG.error("Unknown invoke instruction type: " + insn.getClass());
            return false;
        }
    }

    public static String methodToString(MethodNode mth) {
        return DescriptorFormatter.toDescriptor(mth.getParentClass()) +
                "." +
                methodToNameString(mth) +
                ":" +
                methodToParamListString(mth);
    }

    public static String methodToString(MethodInfo mth) {
        return DescriptorFormatter.toDescriptor(mth.getDeclClass()) +
                "." +
                methodToNameString(mth) +
                ":" +
                methodToParamListString(mth);
    }

    public static String methodToNameString(MethodInfo mth) {
        if (mth.hasAlias()) {
            return mth.getAlias();
        }
        return mth.getName();
    }

    public static String methodToNameString(MethodNode mth) {
        // this is fine to pass through for methodNode; the name MethodNode methods flow to MethodInfo anyway
        return methodToNameString(mth.getMethodInfo());
    }

    public static String methodToParamListString(MethodNode mth) {
        return DescriptorFormatter.toDescriptor(mth);
    }

    public static String methodToParamListString(MethodInfo mth) {
        return DescriptorFormatter.toDescriptor(mth);
    }

    public static String blockToString(BlockNode block, MethodNode mth) {
        // this seems to be the proper way to get a unique string to refer to a block
        // equals says they're equal if the offset and cid are equal, both of which are encoded in this string
        return methodToString(mth) + "/" + block.toString();
    }

    public static String classToString(ClassInfo cls) {
        return DescriptorFormatter.toDescriptor(cls);
    }

    public static String classToString(ClassNode cls) {
        return DescriptorFormatter.toDescriptor(cls);
    }

    public static String classToNameString(ClassNode cls) {
        return cls.getFullName();
    }

    public static String classFileName(ClassNode cls) {
        return cls.getInputFileName();
    }

    public static String classLineNo(ClassNode cls) {
        // if this hasn't been set by DebugInfoAttachVisitor yet, may be 0
        return String.valueOf(cls.getSourceLine());
    }

    public static String fieldToClassString(FieldInfo field) {
        return classToString(field.getDeclClass());
    }

    public static String fieldToClassString(FieldNode field) {
        return classToString(field.getParentClass());
    }

    public static String fieldToFieldString(FieldInfo field) {
        if (field.hasAlias()) {
            return field.getAlias();
        }
        return field.getName();
    }

    public static String fieldToFieldString(FieldNode field) {
        // this is fine to pass through for FieldNode; the name FieldNode methods flow to FieldInfo anyway
        return fieldToFieldString(field.getFieldInfo());
    }

    public static String ifOpToString(IfOp op) {
        return op.toString();
    }

    @SuppressWarnings("unused")
    public static String fieldToConstructorString(FieldNode field) {
        // if it's an actual instruction that initializes this, get the method it's in
        FieldInitInsnAttr initInsnAttr = field.get(AType.FIELD_INIT_INSN);
        if (initInsnAttr != null) {
            return methodToString(initInsnAttr.getInsnMth());
        }
        ClassNode cls = field.getParentClass();
        // otherwise check if it's static
        if (field.isStatic()) {
            MethodNode clinit = cls.getClassInitMth();
            if (clinit == null) {
                return classToString(cls) + DEFAULT_STATIC_CONSTRUCTOR_SUFFIX;
            }
            else {
                return methodToString(clinit);
            }
        }
        // if field is not static try to get the default init method
        MethodNode cons = cls.getDefaultConstructor();
        if (cons != null) {
            return methodToString(cons);
        }
        // sometimes there isn't a default constructor if there's a non-default constructor
        // if this value is initialized implicitly in the class body, there may not be a method for it, so bind one
        return classToString(cls) + DEFAULT_CONSTRUCTOR_SUFFIX;
    }

    public static String conditionToString(IfCondition cond) {
        switch (cond.getMode()) {
            case COMPARE:
                return ifOpToString(cond.getCompare().getOp());
            case TERNARY:
                return "TERNARY";
            case NOT:
                return "NOT";
            case AND:
                return "AND";
            case OR:
                return "OR";
            default:
                LOG.error("ERROR: Unknown condition type: " + cond);
                return "UNKNOWN";
        }
    }

    public static String sourceFileString(MethodNode mth) {
        return sourceFileString(mth.getParentClass());
    }

    public static String sourceFileString(ClassNode cls) {
        return cls.getInputFileName();
    }

    public static String stmtSourceLineString(InsnNode insn) {
        // this is sometimes unset and just returns 0; return -1 to make that more explicit
        int line = insn.getSourceLine();
        return String.valueOf(line == 0 ? UNDEFINED_SOURCE_LINE : line);
    }

    public static String argTypeToString(ArgType type) {
        return DescriptorFormatter.toDescriptor(type);
    }

    public static boolean fieldIsFinal(FieldNode field) {
        return field.getAccessFlags().isFinal();
    }

    public static boolean classIsInterface(ClassNode cls) {
        return cls.getAccessFlags().isInterface();
    }

    @SuppressWarnings("BlockingMethodInNonBlockingContext")
    protected static PrintWriter getFileWriter(String baseName) throws IOException {
        File outDir = App.root.getArgs().getOutDir();
        String outDirPath = (new File(outDir, "facts")).getCanonicalPath();
        return new PrintWriter(new BufferedWriter(new FileWriter(Paths.get(outDirPath, baseName).toFile())));
    }

    @Nullable
    protected static ClassNode getClassNode(ClassInfo cls) {
        return App.root.resolveClass(cls);
    }

    @SuppressWarnings("unused")
    protected static ClassNode getClassNode(ArgType clsType) {
        return App.root.resolveClass(clsType);
    }

    @Nullable
    protected static MethodNode getMethodNode(MethodInfo mth) {
        return App.root.resolveMethod(mth);
    }

    protected static boolean isExternalMethod(MethodInfo mth) {
        return getMethodNode(mth) == null;
    }

    protected static boolean isExternalClass(ClassInfo clsInfo) {
        return getClassNode(clsInfo) == null;
    }

    @SuppressWarnings("unused")
    protected static String classLineNo(ClassInfo cls) {
        ClassNode clsNode = getClassNode(cls);
        return clsNode == null ? "?" : classLineNo(clsNode);
    }

    @SuppressWarnings("unused")
    protected static String classFileName(ClassInfo cls) {
        ClassNode clsNode = getClassNode(cls);
        return clsNode == null ? "?" : classFileName(clsNode);
    }

    @SuppressWarnings("unused")
    protected static String methodLineNo(MethodInfo mth) {
        // if this hasn't been set by DebugInfoAttachVisitor yet, may be 0
        MethodNode mthNode = getMethodNode(mth);
        return mthNode == null ? "?" : String.valueOf(mthNode.getSourceLine());
    }

    @SuppressWarnings("unused")
    protected static String methodFileName(MethodInfo mth) {
        MethodNode mthNode = getMethodNode(mth);
        return mthNode == null ? "?" : mthNode.getInputFileName();
    }

    public static Map<Integer, Set<Integer>> getSrcToDecompMap(ClassNode classNode) {
        // want srcLine -> decompLine but we have decompLine -> srcLine
        Map<Integer, Integer> lineMapping = classNode.getCode().getCodeMetadata().getLineMapping();
        Map<Integer, Set<Integer>> invertedLineMapping = new HashMap<>();
        lineMapping.keySet().forEach(key -> {
            int val = lineMapping.get(key);
            invertedLineMapping.computeIfAbsent(val, _k -> new TreeSet<>()).add(key);
        });
        return invertedLineMapping;
    }

    public static String getClassFilePath(ClassNode cls) {
        // see jadx.core.dex.visitors.SaveCode.save
        return cls.getTopParentClass().getClassInfo().getAliasFullPath() + getFileExtension(cls.root());
    }

    /**
     * Recursively delete a directory and its contents (ignores symlinks). Can use the following to cleanup on exit:
     * Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteDir(dir)));
     * @param file the File object to delete
     */
    public static void deleteDir(File file) throws IOException {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                if (!Files.isSymbolicLink(f.toPath())) {
                    deleteDir(f);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("deleteDir failed to delete file " + file);
        }
    }

    /**
     * Construct a path name of /base/path/orgFile-suffix.orig_ext with the original's extension (if any)
     * @param pathBase the path this new file will fall under
     * @param origFile name of the file to base this one off of (path prefix will be discarded if any)
     * @param suffix the suffix to apply at the end but before the original .ext
     * @return a File representing the requested path
     */
    public static File GetModifiedFile(String pathBase, String origFile, String suffix) {
        // strip path if any
        String origFileName = new File(origFile).getName();
        // construct the filename w/ no ext and the ext
        int extensionStart = origFileName.lastIndexOf('.');
        String noExt = origFileName;
        String ext = "";
        if (extensionStart != -1) {
            noExt = origFileName.substring(0, extensionStart);
            ext = origFileName.substring(extensionStart);
        }
        return new File(pathBase, noExt + suffix + ext);
    }

    static class TempDirAutoDelete implements AutoCloseable {
        private final File tempDir;
        private boolean closed = false;

        /**
         * Create a temporary directory that will recursively delete its contents on try-with-resources exit
         * @param dirPrefix the path to create this dir under; null uses OS default (e.g. /tmp)
         * @throws IOException if an I/O error occurs or the temporary-file directory does not exist
         */
        TempDirAutoDelete(@Nullable String dirPrefix) throws IOException {
            if (dirPrefix == null) {
                tempDir = Files.createTempDirectory(null).toFile();
            }
            else {
                tempDir = Files.createTempDirectory(Paths.get(dirPrefix), null).toFile();
            }
        }

        public File getDir() {
            return tempDir;
        }

        @Override
        public void close() {
            if (!closed && tempDir.isDirectory()) {
                try {
                    deleteDir(tempDir);
                }
                catch (IOException e) {
                    LOG.error("Failed to delete temp dir", e);
                }
            }
            closed = true;
        }
    }
}
