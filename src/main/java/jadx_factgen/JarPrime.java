package jadx_factgen;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

public class JarPrime {
	private static final Logger LOG = LoggerFactory.getLogger(JarPrime.class);

	public static class ClassDebugTranslator extends ClassNode {
		// funcqualifier -> bytecodeOffset -> decompLine
		public Map<String, Map<Integer,Integer>> decompMap = new HashMap<>();

		public ClassDebugTranslator(final int api) {
			super(api);
		}

		public void parseMapping(File mapFile) {
			try {
				Scanner scan = new Scanner(mapFile);
				while (scan.hasNext()) {
					String methodName = scan.next();
					int bytecodeOffset = scan.nextInt();
					int decompLine = scan.nextInt();
					if (!decompMap.containsKey(methodName)) {
						decompMap.put(methodName, new HashMap<>());
					}
					Map<Integer, Integer> offsetMap = decompMap.get(methodName);
					// Current policy on multiple lines for the same bytecode: just pick the last one we see
					offsetMap.put(bytecodeOffset, decompLine);
				}
				scan.close();
			}
			catch (FileNotFoundException e) {
				LOG.error("Mapping file not found: " + mapFile);
			}
		}

		public int mapOffsetToLine(Map<Integer,Integer> offsetMap, int offset) {
			// Offset Map:
			//   10  --> line 15
			//   23  --> line 16
			//   30  --> line 17
			// The in-between instructions map to the NEXT line
			// So we find the smallest key >= our offset
			//   e.g. f(25) = line 17
			// The exception is for the last offset, there may be extra instructions after
			int closestKey = -1;
			int biggestKey = -1;
			for (int key : offsetMap.keySet()) {
				if (key > biggestKey) biggestKey = key;
				if (key < offset) continue;
				if (closestKey == -1) closestKey = key;
				if (key - offset < closestKey - offset) closestKey = key;
			}
			if (closestKey == -1) {
				// If there is no key above our offset, we must be at the end instructions
				closestKey = biggestKey;
			}
			return offsetMap.get(closestKey);
		}

		public void debugTranslate(File mapFile) {
			parseMapping(mapFile);
            for (MethodNode mn : methods) {
                InsnList insns = mn.instructions;
                String fqn = "L" + name + ";." + mn.name + ":" + mn.desc;
                Map<Integer, Integer> offsetMap = decompMap.get(fqn);
                if (offsetMap == null) {
                    LOG.debug("Failed to find method in facts: " + fqn);
                    continue;
                }
                for (AbstractInsnNode i : insns) {
                    if (i instanceof LineNumberNode) {
                        LineNumberNode lnn = (LineNumberNode) i;
                        lnn.line = mapOffsetToLine(offsetMap, lnn.start.getLabel().getOffset());
                    }
                }
            }
		}

		public void addLabels() {
            for (MethodNode mn : methods) {
                InsnList insns = mn.instructions;
                boolean lastDebug = false;
                for (AbstractInsnNode i : insns) {
                    if (i instanceof LineNumberNode) {
                        lastDebug = true;
                        continue;
                    }
                    if (i instanceof InsnNode) {
                        if (lastDebug) {
                            lastDebug = false;
                        } else {
                            LabelNode label = new LabelNode(new Label());
                            insns.insertBefore(i, label);
                            AbstractInsnNode line = new LineNumberNode(0, label);
                            insns.insertBefore(i, line);
                        }
                    }
                }
            }
		}
	}

	public static void translateJar(String output, String facts, String filename) throws IOException {
		File file = new File(filename);
		int extensionStart = file.toPath().getFileName().toString().lastIndexOf('.');
		File modFile = new File(output, file.toPath().getFileName().toString().substring(0, extensionStart) + "-modified.jar");
		JarInputStream jis = new JarInputStream(new FileInputStream(file));
		JarOutputStream jos = new JarOutputStream(new FileOutputStream(modFile));
		// read up to 64KB at a time
		int SIZE = 1024*64;
		byte[] data = new byte[SIZE];
		for (JarEntry je = jis.getNextJarEntry(); je != null; je = jis.getNextJarEntry()) {
			if (!je.getName().endsWith(".class")) {
				jos.putNextEntry(je);
				int bytes;
				while ((bytes = jis.read(data, 0, SIZE)) != -1) {
					jos.write(data, 0, bytes);
				}
				jos.closeEntry();
				continue;
			}
			byte[] ba = getBytes(facts, jis);
			jos.putNextEntry(new JarEntry(je.getRealName()));
			jos.write(ba, 0, ba.length);
			jos.closeEntry();
		}
		jis.close();
		jos.close();
	}

	private static byte[] getBytes(String facts, JarInputStream jis) throws IOException {
		ClassReader cr = new ClassReader(jis);
		// Why 2? because we need 1 to compute offsets, and another to output the modified class file
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		ClassWriter cw2 = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		ClassDebugTranslator w = new ClassDebugTranslator(Opcodes.ASM5);
		cr.accept(w, 0);
		w.addLabels();
		w.accept(cw);
		w.debugTranslate(new File(facts, "BytecodeDecompLine.facts"));
		w.accept(cw2);
        return cw2.toByteArray();
	}
}
