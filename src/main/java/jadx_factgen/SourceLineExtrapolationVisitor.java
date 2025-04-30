package jadx_factgen;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.dex.visitors.AbstractVisitor;
import jadx.core.dex.visitors.JadxVisitor;
import jadx.core.dex.visitors.PrepareForCodeGen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@JadxVisitor(
        name = "Source line extrapolation visitor",
        desc = "Visit all instructions, adding missing source line information by guessing it is the same as the last seen real source line annotation",
        runAfter = {
                PrepareForCodeGen.class
        }
)

public class SourceLineExtrapolationVisitor extends AbstractVisitor {
    public int lastSourceLine;
    private static final Logger LOG = LoggerFactory.getLogger(CTADLFactGen.class);
    
    public SourceLineExtrapolationVisitor() {
	lastSourceLine = -1;
    }
    
    @Override
    public void init(RootNode root) {
    }

    // Check the predecessor of this node, if there is only one, remember it's last source line
    public void checkPredecessor(BlockNode node) {
	if(node.getPredecessors().size() == 1) {
	    BlockNode pred = node.getPredecessors().get(0);
	    int size = pred.getInstructions().size();
	    for (int i = 0; i < size; i++) {
		InsnNode insn = pred.getInstructions().get(i);
		if(insn.getSourceLine() > 0) {
		    lastSourceLine = insn.getSourceLine();
		}
	    }
	    
	}
    }
    
    public void visitInsn(InsnNode insn, MethodNode mth) {
	if(insn.getSourceLine() > 0) {
	    lastSourceLine = insn.getSourceLine();
	    //if(mth.getName().startsWith("getFilename")){
	    //LOG.debug("Already Has Source Line: " + insn.toString());			
	    //}
	} else {
	    if(lastSourceLine > 0) {
		//if(mth.getName().startsWith("getFilename")){
		//    LOG.debug("Propagated Source Line: " + insn.toString());
		//}
		insn.setSourceLine(lastSourceLine);
		//if(lastSourceLine == 84) {
		//}
	    } else {
		//if(mth.getName().startsWith("getFilename")){
		//    LOG.debug("No Propagation: " + insn.toString());
		//}
	    }
	}			
    }
    
    @Override
    public void visit(MethodNode mth) {
	if (mth.isNoCode()) {
	    return;
	}
	//if(mth.getName().startsWith("getFilename")) {
	//    LOG.debug("Method: " + mth.getName());
	//}
	for (BlockNode block : mth.getBasicBlocks()) {
	    // For now, we will only extrapolate lines within a basic block
	    // Once we reach a new basic block, we reset
	    lastSourceLine = -1;
	    checkPredecessor(block);
	    
	    int size = block.getInstructions().size();
	    for (int i = 0; i < size; i++) {
		InsnNode insn = block.getInstructions().get(i);
		insn.visitInsns(in -> { visitInsn(in, mth); });
	    }
	}
    }

}
