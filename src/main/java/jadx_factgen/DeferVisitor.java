package jadx_factgen;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.AbstractVisitor;
import jadx.core.dex.visitors.JadxVisitor;
import jadx.core.dex.visitors.PrepareForCodeGen;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@JadxVisitor(
        name = "Defer visitor",
        desc = "Visit all classes doing nothing except preventing them from unloading to process them at the end",
        runAfter = {
                PrepareForCodeGen.class
        }
)
public class DeferVisitor extends AbstractVisitor {
        public final Set<ClassNode> visitedClasses = Collections.synchronizedSet(new HashSet<>());

        public DeferVisitor() {
        }

        @Override
        public void init(RootNode root) {
            // since this won't be initted in the normal jadx flow, can just put init in the constructor instead
            // this way const variables can be made final
        }

        @Override
        public boolean visit(ClassNode cls) {
            // true means to keep processing inner classes and method, false means to stop
            // Seems like it visits some classes twice; the objects are completely identical as far as I can tell
            boolean newClass = visitedClasses.add(cls);
            if (newClass) {
                // this is necessary, because otherwise jadx will unload all the info from each class after all
                // visitors are done; this doesn't seem to increase memory usage much if at all from limited testing
                cls.add(AFlag.DONT_UNLOAD_CLASS);
            }
            return newClass;
        }

        @Override
        public void visit(MethodNode mth) {
            // pass
        }
}
