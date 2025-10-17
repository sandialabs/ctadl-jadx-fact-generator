package jadx_factgen;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.LoggerFactory;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.googlecode.d2j.dex.writer.ApkPrime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jadx_factgen.Util.TempDirAutoDelete;

import jadx.api.DecompilationMode;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.AbstractVisitor;
import jadx.core.dex.visitors.ConstructorVisitor;
import jadx.core.dex.visitors.DepthTraversal;
import jadx.core.dex.visitors.IDexTreeVisitor;
import jadx.core.dex.visitors.InitCodeVariables;
import jadx.core.dex.visitors.MethodVisitor;
import jadx.core.dex.visitors.MoveInlineVisitor;
import jadx.core.dex.visitors.blocks.BlockProcessor;
import jadx.core.dex.visitors.blocks.BlockSplitter;
import jadx.core.dex.visitors.ssa.SSATransform;
import jadx.core.utils.ErrorsCounter;

import static jadx.core.dex.nodes.ProcessState.GENERATED_AND_UNLOADED;
import static jadx_factgen.Util.GetModifiedFile;

@SuppressWarnings("CanBeFinal")
public class App {
    protected final String version;
    // see jadx-cli/src/main/java/jadx/cli/JadxCLIArgs.java
    @Parameter(description = "<input file> (.apk, .dex, .jar, .class, .smali, .zip, .aar, .arsc, .aab)")
    protected String inputFile = "";
    @Parameter(names = { "-h", "--help" }, description = "print this help")
    protected boolean printHelp = false;
    @Parameter(names = { "-o", "--output" }, description = "Set output directory")
    protected String outDirArg = "output";
    @Parameter(names = { "-s", "--source" }, description = "Export source to 'sources' and 'resources' in output dir", arity = 1)
    protected boolean exportSource = true;
    @Parameter(
            names = { "-m", "--decompilation-mode" },
            description = "code output mode:"
                    + "\n 'auto' - trying best options"
                    + "\n 'restructure' - restore code structure (normal java code)"
                    + "\n 'simple' - simplified instructions (linear, with goto's, default)"
                    + "\n 'fallback' - raw instructions + ssa conversion",
            converter = DecompilationModeConverter.class
    )
    protected DecompilationMode decompilationMode = DecompilationMode.AUTO;
    @Parameter(names = { "-j", "--threads-count" }, description = "processing threads count")
    protected int threadsCount = JadxArgs.DEFAULT_THREADS_COUNT;
    @Parameter(names = { "--deobf" }, description = "activate deobfuscation")
    protected boolean deobfuscationOn = false;
    @Parameter(names = { "--cfg" }, description = "save methods control flow graph to dot file")
    protected boolean cfgOutput = false;
    @Parameter(names = { "--raw-cfg" }, description = "save methods control flow graph (use raw instructions)")
    protected boolean rawCfgOutput = false;
    @Parameter(names = { "--debug" }, description = "Print additional debug info (info of every visited instruction) (SLOW)")
    protected boolean debugInfo = false;
    @Parameter(
            names = { "--log-level" },
            description = "Set log level, values: quiet, progress, error, warn, info, debug",
            converter = LogLevelConverter.class
    )
    protected LogLevelEnum logLevel = LogLevelEnum.QUIET;
    @Parameter(names = { "--suppress-jadx-exceptions" }, description = "Don't log out jadx exceptions", arity = 1)
    protected boolean suppressJadxExceptions = true;
    @Parameter(names = { "--show-inconsistent-code" }, description = "Output inconsistent code when decompilation fails", arity = 1)
    protected boolean showInconsistentCode = true;
    @Parameter(names = { "--skip-factgen" }, description = "Just do the jar/apk rewriting (see --rewrite-debug-info) step for debugging (facts must already be present from a previous run).")
    protected boolean skipFactgen = false;
    @Parameter(names = { "--rewrite-debug-info" }, description = "Rewrites the jar/apk with debug info that points at decompiled source lines.")
    protected boolean rewriteDebugInfo = false;

    protected static RootNode root;

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(App.class);

    public App() {
      String foundVersion = null;
      try {
        String path = getClass().getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        File filePath = new File(new File(path).getParent(), "VERSION");
        foundVersion = readVersionFromFile(filePath);
      } catch (URISyntaxException e) {
        //e.printStackTrace();
      }
      version = foundVersion != null ? foundVersion : "dev-unknown";
    }

    public static String readVersionFromFile(File filePath) {
        String version = null;
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            version = br.readLine(); // Read the first line, which is expected to contain the version number
        } catch (IOException e) {
            System.err.println("Error reading the version file: " + e.getMessage());
        }
        return version;
    }

    public static void main(String[] args) {
        // parse command line args
        App app = new App();
        JCommander jcommander = JCommander.newBuilder().addObject(app).build();
        jcommander.parse(args);
        if (app.printHelp) {
            jcommander.usage();
            return;
        }

        app.applyLogLevel();

        System.err.println("generating facts...");

        // init jadx args
        if (app.threadsCount <= 0) {
            throw new RuntimeException("Threads count must be positive, got: " + app.threadsCount);
        }
        JadxArgs jadxArgs = new JadxArgs();
        jadxArgs.setInputFile(new File(app.inputFile));
        File outDir = new File(app.outDirArg);
        jadxArgs.setDebugInfo(app.debugInfo);
        jadxArgs.setOutDir(outDir);
        jadxArgs.setDecompilationMode(app.decompilationMode);
        jadxArgs.setThreadsCount(app.threadsCount);
        jadxArgs.setDeobfuscationOn(app.deobfuscationOn);
        jadxArgs.setCfgOutput(app.cfgOutput);
        jadxArgs.setRawCFGOutput(app.rawCfgOutput);
        jadxArgs.setSkipFilesSave(!app.exportSource);
        jadxArgs.setShowInconsistentCode(app.showInconsistentCode);

        try (JadxDecompiler jadx = new JadxDecompiler(jadxArgs)) {
            if (!app.skipFactgen) {
                // init decompiler
                jadx.load();
                // get root class node
                root = jadx.getRoot();
                // get analysis passes
                List<IDexTreeVisitor> passes = root.getPasses();
                if (app.decompilationMode == DecompilationMode.FALLBACK) {
                    // removes FallbackModeVisitor, which breaks after we do the
                    // passes below.
                    passes.remove(passes.size()-1);
                    // fallback mode doesn't transform the code in any way. the
                    // code below creates basic blocks and ssa-transforms the code.
                    // I modeled the passes below after what
                    // Jadx.getSimpleModePasses() does
                    passes.add(new BlockSplitter());
                    passes.add(new MethodVisitor(mth -> mth.add(AFlag.DISABLE_BLOCKS_LOCK)));
                    passes.add(new BlockProcessor());
                    passes.add(new SSATransform());
                    passes.add(new MoveInlineVisitor());
                    // ConstructorVisitor is necessary because it changes
                    // constructor <init> methods to have a return value (instead
                    // of passing 'this' as 0'th arg).
                    passes.add(new ConstructorVisitor());
                    passes.add(new InitCodeVariables());
                    passes.add(new MethodVisitor(mth -> mth.remove(AFlag.DONT_GENERATE)));
                }

                // add custom pass to infer more source line info
                SourceLineExtrapolationVisitor sourceVisitor = new SourceLineExtrapolationVisitor();
                passes.add(sourceVisitor);

                // add custom pass to disable class unloading
                DeferVisitor deferVisitor = new DeferVisitor();
                passes.add(deferVisitor);

                LOG.debug("Analysis passes (in order):");
                for (IDexTreeVisitor visitor : passes) {
                    LOG.debug("\t" + visitor.getClass().getName());
                }

                // run analysis passes
                if (app.suppressJadxExceptions) {
                    app.applyLogLevel(ErrorsCounter.class, LogLevelEnum.QUIET);
                }
                jadx.save();
                LOG.debug("Deferred class count: " + deferVisitor.visitedClasses.size());

                // run fact gen analysis pass then do clean up
                CTADLFactGen ctadlFactGen = new CTADLFactGen(app);
                ctadlFactGen.init(root, jadx.getResources());
                callVisitor(deferVisitor.visitedClasses, ctadlFactGen, app.threadsCount);
                // ensure files are written to disk
                ctadlFactGen.cleanup();
                unloadClasses(deferVisitor.visitedClasses);

                // we can get at the classes / methods in the decompiler object now, but for whatever reason a lot of their
                // methods have null basic blocks, which is not true of iterating over them as an analysis pass
            }

            System.out.println("-".repeat(80));
            //noinspection BlockingMethodInNonBlockingContext
            System.out.println("Output is in " + outDir.getCanonicalPath());
            System.out.println("-".repeat(80));
            File factsPath = new File(outDir.getCanonicalPath(), "facts");
            File sourcesPath = new File(outDir.getCanonicalPath(), "sources");
            if (app.rewriteDebugInfo) {
              System.err.println("rewriting debug info...");
            }
            if (app.rewriteDebugInfo && app.inputFile.endsWith(".jar")) {
                JarPrime.translateJar(outDir.getCanonicalPath(), factsPath.getCanonicalPath(), app.inputFile);
            }
            else if (app.rewriteDebugInfo && app.inputFile.endsWith(".apk")) {
                try (TempDirAutoDelete tmp = new TempDirAutoDelete(outDir.getCanonicalPath())) {
                    File tmpDir = tmp.getDir();
                    ApkPrime.extractApk(app.inputFile, tmpDir);
                    int i = 1;
                    while (true) {
                        // classes.dex, classes2.dex ...
                        String suffix = i == 1 ? "" : String.valueOf(i);
                        File dexFile = new File(tmpDir, "classes" + suffix + ".dex");
                        if (!dexFile.isFile()) {
                            break;
                        }
                        ApkPrime.translateDex(dexFile.toString(), dexFile.toString(), factsPath.getCanonicalPath(), sourcesPath.getCanonicalPath());
                        i++;
                    }
                    ApkPrime.buildApk(tmpDir, GetModifiedFile(outDir.getCanonicalPath(), app.inputFile, "-modified"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void callVisitor(Collection<ClassNode> visitedClasses, AbstractVisitor visitor, int threadCount) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (ClassNode toVisit : visitedClasses) {
            if (!toVisit.isInner()) {
                executor.submit(() -> DepthTraversal.visit(visitor, toVisit));
            }
        }
        executor.shutdown();
        while (true) {
            try {
                //noinspection BlockingMethodInNonBlockingContext
                if (executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)) break;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void unloadClasses(Collection<ClassNode> visitedClasses) {
        for (ClassNode visited : visitedClasses) {
            visited.unload();
            visited.setState(GENERATED_AND_UNLOADED);
        }
    }

    /**
     * Get the list of classes we expect Jadx to process. This seems like it works, but I'm not sure if this will
     * remain stable for later jadx versions.
     * @param jadx decompiler object
     * @return List of class nodes
     */
    @SuppressWarnings("unused")
    private static List<ClassNode> getClassList(JadxDecompiler jadx) {
        Predicate<String> classFilter = jadx.getArgs().getClassFilter();
        // also see JadxDecompiler::getClasses()
        List<ClassNode> classes = root.getClasses();
        List<ClassNode> processQueue = new ArrayList<>(classes.size());
        Set<ClassNode> innerClasses = new HashSet<>();
        for (ClassNode outerClsNode : classes) {
            innerClasses.clear();
            innerClasses.add(outerClsNode);
            outerClsNode.getInnerAndInlinedClassesRecursive(innerClasses);
            for (ClassNode clsNode : innerClasses) {
                if (clsNode.isSynthetic()) {
                    continue;
                }
                if (classFilter != null && !classFilter.test(clsNode.getClassInfo().getFullName())) {
                    continue;
                }
                processQueue.add(clsNode);
            }
        }
        return processQueue;
    }

    private static String enumValuesString(Enum<?>[] values) {
        return Stream.of(values)
                .map(v -> v.name().replace('_', '-').toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }

    private static class DecompilationModeConverter implements IStringConverter<DecompilationMode> {
        @Override
        public DecompilationMode convert(String value) {
            try {
                return DecompilationMode.valueOf(value.toUpperCase());
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        '\'' + value + "' is unknown, possible values are: "
                                + App.enumValuesString(DecompilationMode.values()));
            }
        }
    }

    private enum LogLevelEnum {
        QUIET(Level.OFF),
        PROGRESS(Level.OFF),
        ERROR(Level.ERROR),
        WARN(Level.WARN),
        INFO(Level.INFO),
        DEBUG(Level.DEBUG);

        private final Level level;

        LogLevelEnum(Level level) {
            this.level = level;
        }

        @SuppressWarnings("unused")
        public Level getLevel() {
            return level;
        }
    }

    private static class LogLevelConverter implements IStringConverter<LogLevelEnum> {
        public static String enumValuesString(Enum<?>[] values) {
            return Stream.of(values)
                    .map(v -> v.name().replace('_', '-').toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));
        }

        @Override
        public LogLevelEnum convert(String value) {
            try {
                return LogLevelEnum.valueOf(value.toUpperCase());
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        '\'' + value + "' is unknown log level, possible values are "
                                + enumValuesString(LogLevelEnum.values()));
            }
        }
    }

    private void applyLogLevel() {
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.setLevel(logLevel.getLevel());
    }

    @SuppressWarnings("SameParameterValue")
    private void applyLogLevel(Class<?> target, LogLevelEnum level) {
        Logger logger = (Logger) LoggerFactory.getLogger(target);
        logger.setLevel(level.getLevel());
    }
}
