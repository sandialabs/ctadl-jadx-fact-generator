package com.googlecode.d2j.dex.writer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Scanner;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.slf4j.LoggerFactory;

import com.googlecode.d2j.Method;
import com.googlecode.d2j.dex.writer.CodeWriter.OP35c;
import com.googlecode.d2j.dex.writer.insn.Insn;
import com.googlecode.d2j.dex.writer.insn.Label;
import com.googlecode.d2j.dex.writer.insn.OpInsn;
import com.googlecode.d2j.dex.writer.insn.PreBuildInsn;
import com.googlecode.d2j.dex.writer.item.ClassDataItem;
import com.googlecode.d2j.dex.writer.item.ClassDataItem.EncodedMethod;
import com.googlecode.d2j.dex.writer.item.ClassDefItem;
import com.googlecode.d2j.dex.writer.item.DebugInfoItem;
import com.googlecode.d2j.reader.DexFileReader;
import com.googlecode.d2j.reader.Op;
import com.googlecode.d2j.visitors.DexClassVisitor;
import com.googlecode.d2j.visitors.DexCodeVisitor;
import com.googlecode.d2j.visitors.DexFileVisitor;
import com.googlecode.d2j.visitors.DexMethodVisitor;

import brut.androlib.ApkBuilder;
import brut.androlib.ApkDecoder;
import brut.androlib.Config;
import brut.androlib.exceptions.AndrolibException;
import brut.common.BrutException;
import brut.directory.DirectoryException;
import brut.directory.ExtFile;

public class ApkPrime {
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ApkPrime.class);

  public static class Pair<T, U> {
    private final T first;
    private final U second;

    public Pair(T first, U second) {
      this.first = first;
      this.second = second;
    }

    public T getFirst() {
      return first;
    }

    public U getSecond() {
      return second;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o)
        return true;
      if (o == null || getClass() != o.getClass())
        return false;
      Pair<?, ?> pair = (Pair<?, ?>) o; // Cast to Pair
      return Objects.equals(first, pair.first) && Objects.equals(second, pair.second); // Compare values
    }

    @Override
    public int hashCode() {
      return Objects.hash(first, second); // Generate hash code based on first and second
    }

    @Override
    public String toString() {
      return "Pair{" +
          "first=" + first +
          ", second=" + second +
          '}';
    }
  }

  public static class RootedFile {
    String filename = new String();
    String uriBaseId = new String();

    public boolean equals(Object o) {
      if (this == o)
        return true;
      if (!(o instanceof RootedFile))
        return false;
      RootedFile loc = (RootedFile) o;
      return this.filename.equals(loc.filename) &&
          this.uriBaseId.equals(loc.uriBaseId);
    }

    public int hashCode() {
      return Objects.hash(filename, uriBaseId);
    }
  };

  public static class BinaryLoc {
    String filename = new String();
    String uriBaseId = new String();
    Long byteOffset;
    Long byteLength; // not part of equals or hashCode

    public boolean equals(Object o) {
      if (this == o)
        return true;
      if (!(o instanceof BinaryLoc))
        return false;
      BinaryLoc loc = (BinaryLoc) o;
      boolean res = this.filename.equals(loc.filename) &&
          this.uriBaseId.equals(loc.uriBaseId) &&
          this.byteOffset.equals(loc.byteOffset);
      return res;
    }

    public int hashCode() {
      int res = Objects.hash(filename, uriBaseId, byteOffset);
      return res;
    }

    public String toString() {
      return "BinaryLoc{filename = " + filename + "," +
          "uriBaseId = " + uriBaseId + "," +
          "byteOffset = " + byteOffset + "}";
    }
  }

  public static class SourceLoc {
    String filename = new String();
    String uriBaseId = new String();
    int startLine = -1;

    public boolean equals(Object o) {
      if (this == o)
        return true;
      if (!(o instanceof SourceLoc))
        return false;
      SourceLoc loc = (SourceLoc) o;
      return this.filename.equals(loc.filename) &&
          this.uriBaseId.equals(loc.uriBaseId) &&
          this.startLine == loc.startLine;
    }

    public int hashCode() {
      return Objects.hash(filename, uriBaseId, startLine);
    }

    public String toString() {
      return "SourceLoc{filename = " + filename + "," +
          "uriBaseId = " + uriBaseId + "," +
          "startLine = " + startLine + "}";
    }
  }

  public static class SourceMap {
    private Map<BinaryLoc, SourceLoc> offsetMap = new HashMap<BinaryLoc, SourceLoc>();

    public void add(Object o) {
      if (!(o instanceof JSONObject)) {
        throw new RuntimeException("Bad source map: not an object");
      }
      JSONObject root = (JSONObject) o;
      parse(root);
    }

    private void parse(JSONObject root) {
      Long version = (Long) root.get("version");
      if (version < 1) {
        throw new RuntimeException("Bad version: " + version);
      }
      String toolString = (String) root.get("tool");
      if (!toolString.equals("jadx")) {
        throw new RuntimeException("Bad tool: " + toolString);
      }
      JSONArray mappings = (JSONArray) root.get("mappings");
      mappings.stream().forEach(elt -> parseMapping((JSONObject) elt));
    }

    private void parseMapping(JSONObject mapping) {
      JSONArray binary = (JSONArray) mapping.get("binary");
      JSONObject binaryLoc = (JSONObject) binary.get(0);
      BinaryLoc bloc = new BinaryLoc();
      bloc.filename = (String) ((JSONObject) ((JSONObject) binaryLoc.get("physicalLocation")).get("artifactLocation"))
          .get("uri");
      bloc.uriBaseId = (String) ((JSONObject) ((JSONObject) binaryLoc.get("physicalLocation")).get("artifactLocation"))
          .get("uriBaseId");
      bloc.byteOffset = (Long) ((JSONObject) ((JSONObject) binaryLoc.get("physicalLocation")).get("region"))
          .get("byteOffset");
      bloc.byteLength = (Long) ((JSONObject) ((JSONObject) binaryLoc.get("physicalLocation")).get("region"))
          .get("byteLength");

      JSONArray source = (JSONArray) mapping.get("source");
      JSONObject sourceLoc = (JSONObject) source.get(0);
      SourceLoc sloc = new SourceLoc();
      sloc.filename = (String) ((JSONObject) ((JSONObject) sourceLoc.get("physicalLocation")).get("artifactLocation"))
          .get("uri");
      sloc.uriBaseId = (String) ((JSONObject) ((JSONObject) sourceLoc.get("physicalLocation")).get("artifactLocation"))
          .get("uriBaseId");
      sloc.startLine = ((Long) ((JSONObject) ((JSONObject) sourceLoc.get("physicalLocation")).get("region"))
          .get("startLine")).intValue();

      offsetMap.put(bloc, sloc);
    }

    public SourceLoc getLocsForInstructionOffset(String filename, int offset) {
      Long longoff = Long.valueOf(offset);
      BinaryLoc key = new BinaryLoc();
      key.filename = filename;
      key.uriBaseId = "BINROOT";
      key.byteOffset = longoff;
      return offsetMap.getOrDefault(key, null);
    }

    public int size() {
      return offsetMap.size();
    }
  }

  public static class MethodOffsetMap {
    Map<Pair<String, Integer>, SourceLoc> reloff = new HashMap<Pair<String, Integer>, SourceLoc>();

    public void addOffset(Method method, int relOffset, SourceLoc sloc) {
      String key = method.getOwner() + "." + method.getName() + ":" + method.getProto().getDesc();
      LOG.debug("MethodOffsetMap.addOffset {}, {} -> {}", key, relOffset, sloc);
      reloff.put(new Pair<String, Integer>(key, relOffset), sloc);
    }

    public SourceLoc getSourceLoc(EncodedMethod method, int relOffset) {
      String methodName = method.method.clazz.descriptor.stringData.string + "." +
          method.method.name.stringData.string + ":(" +
          (method.method.proto.parameters.items.stream()
              .map(item -> item.descriptor.stringData.string)
              .collect(Collectors.joining("")))
          + ")" +
          method.method.proto.ret.descriptor.stringData.string;
      SourceLoc res = reloff.get(new Pair<String, Integer>(methodName, relOffset));
      LOG.debug("MethodOffsetMap.getSourceLoc {}, {} -> {}", methodName, relOffset, res);
      return res;
    }
  }

  public static class MappingInfo {
    RootedFile file;
    SourceMap smap;
    MethodOffsetMap momap = new MethodOffsetMap();

    public MappingInfo(RootedFile file, SourceMap smap) {
      this.file = file;
      this.smap = smap;
    }
  }

  public static class MyCodeVisitor extends DexCodeVisitor {
    MappingInfo info;
    Method method;
    int code_off;

    public MyCodeVisitor(MappingInfo info, Method method) {
      this.info = info;
      this.method = method;
    }

    @Override
    public void visitStartCodeOffset(int code_off) {
      this.code_off = code_off;
    }

    @Override
    public void visitStmtOffset(int stmt_off) {
      // if (!method.toString().startsWith("Lca/ji/no/method10/BaiduUtils"))
      //   return;
      // We don't know why we subtract 8
      int key = code_off + 24 - 8 + stmt_off;
      // try (BufferedWriter writer = new BufferedWriter(new FileWriter("output/keys.txt", true))) {
      //   writer.write(Integer.toString(key) + " " + Integer.toString(opcode) + "\n");
      // } catch (IOException e) {
      //   e.printStackTrace();
      // }
      SourceLoc sloc = info.smap.getLocsForInstructionOffset(info.file.filename, key);
      // LOG.debug("Filename {} Method {} has code offset {}: match? {}", info.file.filename, method.toString(), key,
      //     sloc);
      if (sloc != null) {
        info.momap.addOffset(method, stmt_off, sloc);
      }
    }
  }

  public static class MyMethodVisitor extends DexMethodVisitor {
    MappingInfo info;
    Method method;

    public MyMethodVisitor(MappingInfo info, Method method) {
      this.info = info;
      this.method = method;
    }

    @Override
    public DexCodeVisitor visitCode() {
      return new MyCodeVisitor(info, method);
    }
  }

  public static class MyClassVisitor extends DexClassVisitor {
    MappingInfo info;

    public MyClassVisitor(MappingInfo info) {
      this.info = info;
    }

    @Override
    public DexMethodVisitor visitMethod(int accessFlags, Method method) {
      return new MyMethodVisitor(info, method);
    }
  }

  public static class MyFileVisitor extends DexFileVisitor {
    MappingInfo info;

    public MyFileVisitor(MappingInfo info) {
      this.info = info;
    }

    @Override
    public DexClassVisitor visit(int access_flags, String className, String superClass, String[] interfaceNames) {
      return new MyClassVisitor(info);
    }
  }

  public static class DexDebugTranslator extends DexFileWriter {
    private final List<ClassWriter> cw_list = new ArrayList<>(500);
    public MappingInfo info;
    // classname -> funcname -> bytecodeOffset -> decompLine
    public Map<String, Map<String, Map<Integer, Integer>>> decompMap = new HashMap<>();
    public String filename;

    public String methodIndexToString(int index) {
      if (headItem.methodIdSection.items.get(index) != null) {
        return headItem.methodIdSection.items.get(index).name.stringData.string;
      } else {
        return "?" + index + "?";
      }
    }

    public String insnToString(Insn insn) {
      if (insn instanceof OP35c) {
        OP35c op35c = (OP35c) insn;
        return op35c.op.toString() + " " + methodIndexToString(op35c.item.index) + "() " + op35c.A + " args";
        // op35c.item.index is index into file.method_ids; name_idx into string_ids
      } else if (insn instanceof OpInsn) {
        return ((OpInsn) insn).op.toString() + " ???";
      } else if (insn instanceof Label) {
        return "L:";
      } else if (insn instanceof PreBuildInsn) {
        int opc = ((PreBuildInsn) (insn)).data[0];
        return Op.ops[opc].toString();
      } // for PreBuild, pull out 16 bits, take bottom 8 bits, use as opcode
      else {
        return insn.toString();
      }
    }

    public void printMethod(String classDesc, String method) {
      for (ClassWriter cw : cw_list) {
        for (int x = 0; x < cw.dataItem.directMethods.size(); x++) {
          EncodedMethod em = cw.dataItem.directMethods.get(x);
          String methodName = em.method.name.stringData.string;
          String className = cw.defItem.clazz.descriptor.stringData.string;
          if (methodName.startsWith(method) &&
              className.startsWith(classDesc)) {
            for (int i = 0; i < em.code.insns.size(); i++) {
              Insn insn = em.code.insns.get(i);
              LOG.debug(insn.offset + " " + insnToString(insn));
            }
          }
        }
        for (int x = 0; x < cw.dataItem.virtualMethods.size(); x++) {
          EncodedMethod em = cw.dataItem.virtualMethods.get(x);
          String methodName = em.method.name.stringData.string;
          String className = cw.defItem.clazz.descriptor.stringData.string;
          if (methodName.startsWith(method) &&
              className.startsWith(classDesc)) {
            for (int i = 0; i < em.code.insns.size(); i++) {
              Insn insn = em.code.insns.get(i);
              LOG.debug(insn.offset + " " + insnToString(insn));
            }
          }
        }
      }
    }

    public void parseMapping(String factsPath) {
      Map<String, String> mthToClassMap = new HashMap<>();
      File mapFile = new File(factsPath, "BytecodeDecompLine.facts");
      File mthFile = new File(factsPath, "Method.facts");
      try {
        Scanner scan = new Scanner(mthFile);
        while (scan.hasNext()) {
          String mth = scan.next();
          scan.next();
          String cls = scan.next();
          scan.next();
          scan.next();
          scan.next();
          mthToClassMap.put(mth, cls);
        }
      } catch (FileNotFoundException e) {
        LOG.error("Method file not found: " + mthFile);
      }
      try {
        Scanner scan = new Scanner(mapFile);
        while (scan.hasNext()) {
          String methodName = scan.next();
          int bytecodeOffset = scan.nextInt();
          int decompLine = scan.nextInt();
          String className = mthToClassMap.get(methodName);
          if (!decompMap.containsKey(className)) {
            decompMap.put(className, new HashMap<>());
          }
          Map<String, Map<Integer, Integer>> innerMap = decompMap.get(className);
          if (!innerMap.containsKey(methodName)) {
            innerMap.put(methodName, new HashMap<>());
          }
          Map<Integer, Integer> offsetMap = innerMap.get(methodName);
          // Current policy on multiple lines for the same bytecode: just pick the last one we see
          offsetMap.put(bytecodeOffset, decompLine);
        }
        scan.close();
      } catch (FileNotFoundException e) {
        LOG.error("Mapping file not found: " + mapFile);
      }
    }

    public void debugTranslate(String factsPath) {
      int constClassMissing = 0, factsClassMissing = 0;
      int codeMissing = 0, debugInfoMissing = 0, insnsMissing = 0;
      //parseMapping(factsPath);
      for (ClassWriter cw : cw_list) {
        String className = cw.defItem.clazz.descriptor.stringData.string;
        //ClassDataItem classData = null;
        // for (int z = 0; z < cp.classDataItems.size(); z++) {
        //   ClassDataItem classDataCandidate = cp.classDataItems.get(z);
        //   if (classDataCandidate == cw.dataItem) {
        //     classData = classDataCandidate;
        //     break;
        //   }
        // }
        // if (classData == null) {
        //   LOG.debug("** Class not found in ConstPool: " + className);
        //   constClassMissing++;
        //   continue;
        // }
        // Map<String, Map<Integer, Integer>> innerMap = decompMap.get(className);
        // if (innerMap == null) {
        //   LOG.debug("** Class not found in facts: " + className);
        //   factsClassMissing++;
        // } else {
        List<EncodedMethod> allMethods = new ArrayList<>();
        allMethods.addAll(cw.dataItem.directMethods);
        allMethods.addAll(cw.dataItem.virtualMethods);
        for (EncodedMethod em : allMethods) {
          LOG.debug("Looking at method: {}", em);
          // Fully qualified method name in our JDK-based format
          String methodName = className + "." +
              em.method.name.stringData.string + ":(" +
              (em.method.proto.parameters.items.stream()
                  .map(item -> item.descriptor.stringData.string)
                  .collect(Collectors.joining("")))
              + ")" +
              em.method.proto.ret.descriptor.stringData.string;
          // Map<Integer, Integer> offsetMap = innerMap.get(methodName);
          // if (offsetMap == null) {
          //   LOG.debug("** Method not found in facts: " + className + " " + methodName);
          // } else {
          if (em.code != null) {
            if (em.code.debugInfo == null) {
              em.code.debugInfo = new DebugInfoItem();
              //em.code.debugInfo.firstLine = 100;
              //em.code.debugInfo.fileName = "k.java";
              // need to set em.code.debugInfo.firstLine (int)
              // need to set em.code.debugInfo.fileName (StringIdItem)
              // maybe add it to ConstPool?
              cw.cp.addDebugInfoItem(em.code.debugInfo);
            }
            em.code.place(0);
          }
          if (em.code == null) {
            LOG.debug("** Code missing: " + className + " " + methodName);
            codeMissing++;
          } else if (em.code.debugInfo == null) {
            LOG.debug("** Debug Info missing: " + className + " " + methodName);
            debugInfoMissing++;
          } else if (em.code.insns == null) {
            LOG.debug("** Insns missing: " + className + " " + methodName);
            insnsMissing++;
          }

          if (em.code != null && em.code.debugInfo != null && em.code.insns != null) {
            // throw away old debug info
            // run through every insn in the method
            // use offsetMap to decide what decomp line it's associated with
            // add that to the debuginfo
            em.code.debugInfo.debugNodes.clear();
            int curOffset = 0;
            int prevOffset = 0;
            for (int i = 0; i < em.code.insns.size(); i++) {
              Insn insn = em.code.insns.get(i);
              SourceLoc loc = info.momap.getSourceLoc(em, curOffset);
              if (loc != null) {
                int decompLine = loc.startLine;
                Label l = new Label();
                l.offset = insn.offset;
                if (prevOffset > insn.offset) {
                  LOG.warn("skipping instruction due to bonkers offset");
                  LOG.debug("*** curOffset: {} insn.offset: {} insn.getCodeUnitSize(): {} decompLine: {}", curOffset, insn.offset, insn.getCodeUnitSize(), decompLine);
                } else {
                  em.code.debugInfo.debugNodes.add(DebugInfoItem.DNode.line(decompLine, l));
                  prevOffset = insn.offset;
                }
              }
              curOffset += insn.getCodeUnitSize() * 2;
            }
            LOG.debug("end of debug nodes");
          }
          // }
        }
        // }
      }
      if (constClassMissing > 0) {
        LOG.warn("const class missing: " + constClassMissing);
      }
      if (factsClassMissing > 0) {
        LOG.warn("facts class missing: " + factsClassMissing);
      }
      if (codeMissing > 0) {
        LOG.warn("code missing: " + codeMissing);
      }
      if (debugInfoMissing > 0) {
        LOG.warn("debug info missing: " + debugInfoMissing);
      }
      if (insnsMissing > 0) {
        LOG.warn("insns missing: " + insnsMissing);
      }
    }

    @Override
    public DexClassVisitor visit(int accessFlag, String name, String superClass, String[] itfClass) {
      ClassDefItem defItem = cp.putClassDefItem(accessFlag, name, superClass, itfClass);
      ClassWriter cw = new ClassWriter(defItem, cp);
      cw_list.add(cw);
      return cw;
    }
  }

  /**
   * @param inputDex  path to the input dex file
   * @param outputDex path to the output dex file
   * @param factsPath path to facts dir to modify the dex file with
   * @throws IOException on file errors
   */
  public static void translateDex(String inputDex, String outputDex, String factsPath, String sourcesPath)
      throws IOException {
    DexDebugTranslator w = new DexDebugTranslator();
    SourceMap smap = loadSourceMap(sourcesPath);
    LOG.debug("Found {} source maps", smap.size());
    RootedFile fvFile = new RootedFile();
    fvFile.filename = new File(inputDex).getName(); // just classes.dex
    w.info = new MappingInfo(fvFile, smap);
    MyFileVisitor fv = new MyFileVisitor(w.info);
    w.filename = inputDex;
    DexFileReader dexFileReader = new DexFileReader(new File(inputDex));
    dexFileReader.accept(fv);
    dexFileReader.accept(w);
    w.debugTranslate(factsPath);
    try (FileOutputStream fos = new FileOutputStream(outputDex)) {
      fos.write(w.toByteArray());
    }
  }

  public static SourceMap loadSourceMap(String sourcesPath) throws IOException {
    SourceMap res = new SourceMap();
    Files.walkFileTree(Paths.get(sourcesPath), new SimpleFileVisitor<Path>() {
      @Override
      public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (file.toString().endsWith(".json")) {
          res.add(JSONValue.parse(Files.readString(file)));
        }
        return java.nio.file.FileVisitResult.CONTINUE;
      }
    });
    // for (Entry<BinaryLoc, SourceLoc> entry : res.offsetMap.entrySet()) {
    //   LOG.debug("offsetMap entry: {} -> {}", entry.getKey(), entry.getValue());
    // }
    return res;
  }

  @SuppressWarnings("unused")
  public static void printMethodDex(String inputDex, String methodDesc) throws IOException {
    DexDebugTranslator w = new DexDebugTranslator();
    DexFileReader dexFileReader = new DexFileReader(new File(inputDex));
    dexFileReader.accept(w);
    w.buildMapListItem();
    w.printMethod(methodDesc, "m");
  }

  private static void setupApktoolLogging() {
    // currently disables all logging; see setupLogging in ApkTool Main.java
    Logger logger = Logger.getLogger("brut");
    for (Handler handler : logger.getHandlers()) {
      logger.removeHandler(handler);
    }
    LogManager.getLogManager().reset();
  }

  /**
   * @param targetApk the path to the apk you want to extract
   * @param outputDir the dir to extract the apk to
   */
  public static void extractApk(String targetApk, File outputDir) {
    setupApktoolLogging();
    Config config = Config.getDefaultConfig();
    // need to pass this flag so apktool doesn't complain about an existing directory
    config.forceDelete = true;
    // output raw dex files instead of smali
    config.decodeSources = Config.DECODE_SOURCES_NONE;
    config.decodeResources = Config.DECODE_RESOURCES_NONE;
    try {
      // sometimes, this holds an fd AndroidManifest.xml in the temp dir, making it undeletable in Windows...
      new ApkDecoder(config, new ExtFile(targetApk)).decode(outputDir);
    } catch (AndrolibException | IOException | DirectoryException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @param inputDir  the input directory containing the previously extracted apk
   * @param outputApk the string of the apk to write
   */
  public static void buildApk(File inputDir, File outputApk) {
    setupApktoolLogging();
    Config config = Config.getDefaultConfig();
    // we need this because aapt2 sometimes errors with a "could not exec" error when building resources
    // aapt2 became the default asset packaging tool starting with apktool 2.9.0
    // see this issue https://github.com/iBotPeaches/Apktool/issues/3183 (and other similar ones)
    config.useAapt2 = false;
    try {
      new ApkBuilder(config, new ExtFile(inputDir)).build(outputApk);
    } catch (BrutException e) {
      throw new RuntimeException(e);
    }
  }
}
